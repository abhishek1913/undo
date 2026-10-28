package dev.undo.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import dev.undo.core.connector.Effect;
import dev.undo.core.json.Json;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a policy document (Section 14.3) and produces the normalized compiled form stored in
 * policy_versions.compiled. The evaluator uses only the compiled form.
 */
public final class PolicyCompiler {
  static final Set<String> ROOTS = Set.of("args", "facts", "run", "agent", "ctx");
  static final Set<String> OPS =
      Set.of(
          "eq", "neq", "gt", "gte", "lt", "lte", "in", "not_in", "matches", "exists", "not_exists");
  static final Set<String> DECISIONS = Set.of("allow", "deny", "require_approval");

  public record Result(List<PolicyError> errors, ObjectNode compiled, String name) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private final Map<String, Effect> tools;

  /**
   * @param tools every registered tool name and its effect (agent and internal tools).
   */
  public PolicyCompiler(Map<String, Effect> tools) {
    this.tools = tools;
  }

  public Result compile(String yaml) {
    PolicyYaml.Parsed p = PolicyYaml.parse(yaml);
    List<PolicyError> errors = new ArrayList<>(p.errors());
    if (!errors.isEmpty()) {
      return new Result(errors, null, null);
    }
    Ctx c = new Ctx(p, errors);
    JsonNode root = p.root();
    if (!root.isObject()) {
      c.err("", "The document must be a mapping.");
      return new Result(errors, null, null);
    }
    c.keys(
        root,
        "",
        Set.of("apiVersion", "kind", "metadata", "spec"),
        Set.of("apiVersion", "kind", "metadata", "spec"));
    if (!"undo.dev/v1".equals(root.path("apiVersion").asText(null))) {
      c.err("/apiVersion", "apiVersion must be undo.dev/v1.");
    }
    if (!"Policy".equals(root.path("kind").asText(null))) {
      c.err("/kind", "kind must be Policy.");
    }
    ObjectNode out = Json.obj();
    JsonNode md = root.path("metadata");
    String name = null;
    if (md.isObject()) {
      c.keys(md, "/metadata", Set.of("name", "description"), Set.of("name"));
      name = md.path("name").asText(null);
      if (name == null || !name.matches("[a-z0-9-]+")) {
        c.err("/metadata/name", "metadata.name must match [a-z0-9-]+.");
      }
      out.put("name", name);
      out.put("description", md.path("description").asText(""));
    } else {
      c.err("/metadata", "metadata must be a mapping.");
    }
    JsonNode spec = root.path("spec");
    if (!spec.isObject()) {
      c.err("/spec", "spec must be a mapping.");
      return new Result(errors, null, name);
    }
    c.keys(
        spec,
        "/spec",
        Set.of("defaultDecision", "budgets", "approvals", "tools"),
        Set.of("defaultDecision", "budgets", "tools"));
    if (!"deny".equals(spec.path("defaultDecision").asText(null))) {
      c.err("/spec/defaultDecision", "defaultDecision must be deny (deny by default).");
    }
    JsonNode budgets = spec.path("budgets");
    ObjectNode b = out.putObject("budgets");
    if (budgets.isObject()) {
      c.keys(
          budgets,
          "/spec/budgets",
          Set.of("moneyOutMinor", "actions", "llmTokens"),
          Set.of("moneyOutMinor", "actions", "llmTokens"));
      b.put("money_out_minor", c.positive(budgets, "/spec/budgets", "moneyOutMinor"));
      b.put("actions", c.positive(budgets, "/spec/budgets", "actions"));
      b.put("llm_tokens", c.positive(budgets, "/spec/budgets", "llmTokens"));
    } else {
      c.err("/spec/budgets", "budgets must be a mapping.");
    }
    String expires = "PT24H";
    JsonNode approvals = spec.path("approvals");
    if (!approvals.isMissingNode()) {
      if (approvals.isObject()) {
        c.keys(approvals, "/spec/approvals", Set.of("expiresAfter"), Set.of());
        if (approvals.has("expiresAfter")) {
          try {
            Duration d = Duration.parse(approvals.get("expiresAfter").asText());
            if (d.isNegative() || d.isZero()) {
              c.err("/spec/approvals/expiresAfter", "expiresAfter must be positive.");
            }
            expires = d.toString();
          } catch (DateTimeParseException e) {
            c.err(
                "/spec/approvals/expiresAfter",
                "expiresAfter must be an ISO-8601 duration such as PT24H.");
          }
        }
      } else {
        c.err("/spec/approvals", "approvals must be a mapping.");
      }
    }
    out.put("approvals_expires_after", expires);
    ObjectNode toolsOut = out.putObject("tools");
    JsonNode toolsIn = spec.path("tools");
    Set<String> ruleIds = new HashSet<>();
    if (toolsIn.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = toolsIn.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String tool = e.getKey();
        String tp = "/spec/tools/" + tool;
        Effect effect = tools.get(tool);
        if (effect == null) {
          c.err(tp, "Unknown tool '" + tool + "'.");
        }
        toolsOut.set(tool, compileTool(c, tp, tool, effect, e.getValue(), ruleIds));
      }
    } else {
      c.err("/spec/tools", "tools must be a mapping.");
    }
    return new Result(errors, errors.isEmpty() ? out : null, name);
  }

  private ObjectNode compileTool(
      Ctx c, String tp, String tool, Effect effect, JsonNode t, Set<String> ruleIds) {
    ObjectNode out = Json.obj();
    if (!t.isObject()) {
      c.err(tp, "Tool entry must be a mapping.");
      return out;
    }
    c.keys(t, tp, Set.of("default", "rules"), Set.of("default"));
    String def = t.path("default").asText(null);
    if (!DECISIONS.contains(def)) {
      c.err(tp + "/default", "default must be allow, deny or require_approval.");
    } else if (def.equals("require_approval") && effect == Effect.READ) {
      c.err(tp + "/default", "READ tools cannot require approval.");
    }
    out.put("default", def);
    ArrayNode rules = out.putArray("rules");
    JsonNode rin = t.path("rules");
    if (rin.isMissingNode()) {
      return out;
    }
    if (!rin.isArray()) {
      c.err(tp + "/rules", "rules must be a list.");
      return out;
    }
    for (int i = 0; i < rin.size(); i++) {
      String rp = tp + "/rules/" + i;
      JsonNode r = rin.get(i);
      if (!r.isObject()) {
        c.err(rp, "A rule must be a mapping.");
        continue;
      }
      c.keys(
          r,
          rp,
          Set.of("id", "decision", "reason", "when"),
          Set.of("id", "decision", "reason", "when"));
      String id = r.path("id").asText(null);
      if (id != null) {
        if (!id.matches("[a-z0-9_]+\\.[a-z0-9-]+")) {
          c.err(rp + "/id", "Rule id must look like <tool-prefix>.<slug>.");
        }
        if (!ruleIds.add(id)) {
          c.err(rp + "/id", "Duplicate rule id '" + id + "'.");
        }
      }
      String decision = r.path("decision").asText(null);
      if (!DECISIONS.contains(decision)) {
        c.err(rp + "/decision", "decision must be allow, deny or require_approval.");
      } else if (decision.equals("require_approval") && effect == Effect.READ) {
        c.err(rp + "/decision", "READ tools cannot have require_approval rules.");
      }
      if (!r.path("reason").isTextual()) {
        c.err(rp + "/reason", "reason must be text.");
      }
      ObjectNode ro = rules.addObject();
      ro.put("id", id);
      ro.put("decision", decision);
      ro.put("reason", r.path("reason").asText(""));
      ro.set("when", condition(c, rp + "/when", r.path("when")));
    }
    return out;
  }

  private JsonNode condition(Ctx c, String p, JsonNode n) {
    ObjectNode out = Json.obj();
    if (!n.isObject()) {
      c.err(p, "A condition must be a mapping.");
      return out;
    }
    if (n.has("all") || n.has("any")) {
      String k = n.has("all") ? "all" : "any";
      c.keys(n, p, Set.of(k), Set.of(k));
      ArrayNode list = out.putArray(k);
      JsonNode in = n.get(k);
      if (!in.isArray()) {
        c.err(p + "/" + k, k + " must be a list of conditions.");
        return out;
      }
      for (int i = 0; i < in.size(); i++) {
        list.add(condition(c, p + "/" + k + "/" + i, in.get(i)));
      }
      return out;
    }
    if (n.has("not")) {
      c.keys(n, p, Set.of("not"), Set.of("not"));
      out.set("not", condition(c, p + "/not", n.get("not")));
      return out;
    }
    c.keys(n, p, Set.of("field", "op", "value", "ref"), Set.of("field", "op"));
    String field = n.path("field").asText(null);
    String op = n.path("op").asText(null);
    validPath(c, p + "/field", field);
    out.put("field", field);
    out.put("op", op);
    if (!OPS.contains(op)) {
      c.err(p + "/op", "Unknown operator '" + op + "'.");
      return out;
    }
    boolean hasValue = n.has("value");
    boolean hasRef = n.has("ref");
    if (op.equals("exists") || op.equals("not_exists")) {
      if (hasValue || hasRef) {
        c.err(p, op + " takes no value or ref.");
      }
      return out;
    }
    if (hasValue == hasRef) {
      c.err(p, "Give exactly one of value or ref.");
      return out;
    }
    if (hasRef) {
      validPath(c, p + "/ref", n.path("ref").asText(null));
      out.put("ref", n.path("ref").asText());
      return out;
    }
    JsonNode v = n.get("value");
    switch (op) {
      case "in", "not_in" -> {
        if (!v.isArray()) {
          c.err(p + "/value", op + " needs a list value.");
        }
      }
      case "gt", "gte", "lt", "lte" -> {
        if (!v.isNumber()) {
          c.err(p + "/value", op + " needs a number value.");
        }
      }
      case "matches" -> {
        if (!v.isTextual()) {
          c.err(p + "/value", "matches needs a pattern string.");
        } else {
          try {
            Pattern.compile(v.asText());
          } catch (PatternSyntaxException e) {
            c.err(p + "/value", "Invalid RE2 pattern: " + e.getMessage());
          }
        }
      }
      default -> {}
    }
    out.set("value", v);
    return out;
  }

  private static void validPath(Ctx c, String p, String path) {
    if (path == null || path.isBlank()) {
      c.err(p, "A path is required.");
      return;
    }
    String root = path.split("\\.", 2)[0];
    if (!ROOTS.contains(root)) {
      c.err(p, "Path must start with args, facts, run, agent or ctx (got '" + path + "').");
    }
    for (String seg : path.split("\\.", -1)) {
      if (seg.isEmpty()) {
        c.err(p, "Path has an empty segment: '" + path + "'.");
        return;
      }
    }
  }

  private record Ctx(PolicyYaml.Parsed parsed, List<PolicyError> errors) {
    void err(String pointer, String message) {
      errors.add(new PolicyError(parsed.line(pointer), message));
    }

    void keys(JsonNode n, String p, Set<String> allowed, Set<String> required) {
      for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
        String k = it.next();
        if (!allowed.contains(k)) {
          err(p + "/" + k, "Unknown key '" + k + "'.");
        }
      }
      for (String r : new java.util.TreeSet<>(required)) { // stable error order
        if (!n.has(r)) {
          err(p, "Missing key '" + r + "'.");
        }
      }
    }

    long positive(JsonNode n, String p, String field) {
      JsonNode v = n.get(field);
      if (v == null || !v.isIntegralNumber() || v.asLong() <= 0) {
        err(p + "/" + field, field + " must be a positive integer.");
        return 0;
      }
      return v.asLong();
    }
  }
}
