package dev.undo.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import dev.undo.core.model.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates the compiled form of a policy (Section 14). Deny by default; precedence deny >
 * require_approval > allow rule > tool default; any evaluation error fails closed as DENY
 * policy_error.
 */
public final class PolicyEvaluator {
  /** Inputs, one JSON object per root: args, facts, run, agent, ctx. */
  public record Context(
      JsonNode args, JsonNode facts, JsonNode run, JsonNode agent, JsonNode ctx) {}

  public record MatchedRule(String id, Decision decision, String reason) {}

  /**
   * @param reasonCode machine reason: "allow", "require_approval", "tool_not_in_policy",
   *     "policy_error" or the deciding rule id
   * @param reasonText plain English shown to agent and approver
   */
  public record Result(
      Decision decision,
      List<MatchedRule> matched,
      String reasonCode,
      String reasonText,
      String ruleId,
      String error) {}

  static final class EvalError extends Exception {
    EvalError(String m) {
      super(m);
    }
  }

  private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

  private PolicyEvaluator() {}

  public static Result evaluate(JsonNode compiled, String tool, Context ctx) {
    JsonNode t = compiled.path("tools").get(tool);
    if (t == null) {
      return new Result(
          Decision.DENY,
          List.of(),
          "tool_not_in_policy",
          "This tool is not allowed by the policy.",
          null,
          null);
    }
    List<MatchedRule> matched = new ArrayList<>();
    for (JsonNode r : t.path("rules")) {
      String id = r.path("id").asText();
      try {
        if (test(r.path("when"), ctx)) {
          matched.add(
              new MatchedRule(
                  id, decision(r.path("decision").asText()), r.path("reason").asText()));
        }
      } catch (EvalError e) {
        String msg = "rule " + id + ": " + e.getMessage();
        return new Result(
            Decision.DENY,
            matched,
            "policy_error",
            "The policy could not be evaluated: " + msg,
            id,
            msg);
      }
    }
    for (Decision d : List.of(Decision.DENY, Decision.REQUIRE_APPROVAL, Decision.ALLOW)) {
      for (MatchedRule m : matched) {
        if (m.decision() == d) {
          return new Result(d, matched, m.id(), m.reason(), m.id(), null);
        }
      }
    }
    Decision def = decision(t.path("default").asText("deny"));
    String text =
        switch (def) {
          case ALLOW -> "Allowed by the policy.";
          case REQUIRE_APPROVAL -> "This tool always needs a human.";
          case DENY -> "This tool is not allowed by the policy.";
        };
    return new Result(def, matched, "default", text, null, null);
  }

  static Decision decision(String s) {
    return switch (s) {
      case "allow" -> Decision.ALLOW;
      case "require_approval" -> Decision.REQUIRE_APPROVAL;
      default -> Decision.DENY;
    };
  }

  static boolean test(JsonNode c, Context ctx) throws EvalError {
    if (c.has("all")) {
      for (JsonNode child : c.get("all")) {
        if (!test(child, ctx)) {
          return false; // short-circuit: later children are not evaluated
        }
      }
      return true;
    }
    if (c.has("any")) {
      for (JsonNode child : c.get("any")) {
        if (test(child, ctx)) {
          return true;
        }
      }
      return false;
    }
    if (c.has("not")) {
      return !test(c.get("not"), ctx);
    }
    String field = c.path("field").asText();
    String op = c.path("op").asText();
    JsonNode v = resolve(field, ctx);
    boolean missing = v == null;
    JsonNode other;
    if (c.has("ref")) {
      other = resolve(c.get("ref").asText(), ctx);
      if (other == null) {
        throw new EvalError("ref " + c.get("ref").asText() + " is missing");
      }
    } else {
      other = c.get("value");
    }
    switch (op) {
      case "exists":
        return !missing;
      case "not_exists":
        return missing;
      case "eq":
        return !missing && equal(v, other);
      case "neq":
        return missing || !equal(v, other);
      case "in":
        return !missing && contains(other, v, field);
      case "not_in":
        return missing || !contains(other, v, field);
      case "gt", "gte", "lt", "lte":
        {
          if (missing) {
            throw new EvalError(field + " is missing (used with " + op + ")");
          }
          if (!v.isNumber() || other == null || !other.isNumber()) {
            throw new EvalError(op + " needs two numbers (" + field + ")");
          }
          int cmp = v.decimalValue().compareTo(other.decimalValue());
          return switch (op) {
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            default -> cmp <= 0;
          };
        }
      case "matches":
        {
          if (missing) {
            throw new EvalError(field + " is missing (used with matches)");
          }
          if (!v.isTextual() || other == null || !other.isTextual()) {
            throw new EvalError("matches needs a string field and a pattern (" + field + ")");
          }
          Pattern p = PATTERNS.computeIfAbsent(other.asText(), Pattern::compile);
          return p.matches(v.asText());
        }
      default:
        throw new EvalError("unknown operator " + op);
    }
  }

  private static boolean contains(JsonNode list, JsonNode v, String field) throws EvalError {
    if (list == null || !list.isArray()) {
      throw new EvalError("in/not_in needs a list (" + field + ")");
    }
    for (JsonNode e : list) {
      if (equal(e, v)) {
        return true;
      }
    }
    return false;
  }

  private static boolean equal(JsonNode a, JsonNode b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    return a.equals(b);
  }

  /** Returns the value at the dotted path, or null when missing (absent or JSON null). */
  static JsonNode resolve(String path, Context ctx) {
    String[] seg = path.split("\\.");
    JsonNode cur =
        switch (seg[0]) {
          case "args" -> ctx.args();
          case "facts" -> ctx.facts();
          case "run" -> ctx.run();
          case "agent" -> ctx.agent();
          case "ctx" -> ctx.ctx();
          default -> null;
        };
    for (int i = 1; i < seg.length && cur != null; i++) {
      cur = cur.isObject() ? cur.get(seg[i]) : null;
    }
    return cur == null || cur.isNull() || cur.isMissingNode() ? null : cur;
  }
}
