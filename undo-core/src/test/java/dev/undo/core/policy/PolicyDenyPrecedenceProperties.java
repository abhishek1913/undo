package dev.undo.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Decision;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Any matching deny rule always wins, whatever the other rules and the default say. */
class PolicyDenyPrecedenceProperties {
  record Rule(String decision, boolean matches) {}

  @Provide
  Arbitrary<List<Rule>> rules() {
    return Combinators.combine(
            Arbitraries.of("allow", "deny", "require_approval"), Arbitraries.of(true, false))
        .as(Rule::new)
        .list()
        .ofMaxSize(8);
  }

  @Property
  void matchingDenyAlwaysWins(@ForAll("rules") List<Rule> rules, @ForAll("defaults") String def) {
    ObjectNode tool = Json.obj();
    tool.put("default", def);
    ArrayNode arr = tool.putArray("rules");
    int i = 0;
    for (Rule r : rules) {
      ObjectNode o = arr.addObject();
      o.put("id", "t.r" + i++);
      o.put("decision", r.decision());
      o.put("reason", "x");
      o.set(
          "when",
          Json.parse(
              "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":" + (r.matches() ? 1 : 2) + "}"));
    }
    ObjectNode policy = Json.obj();
    policy.putObject("tools").set("t", tool);
    JsonNode args = Json.parse("{\"n\":1}");
    Decision d =
        PolicyEvaluator.evaluate(
                policy,
                "t",
                new PolicyEvaluator.Context(args, Json.obj(), Json.obj(), Json.obj(), Json.obj()))
            .decision();
    boolean anyDeny = rules.stream().anyMatch(r -> r.matches() && r.decision().equals("deny"));
    boolean anyApproval =
        rules.stream().anyMatch(r -> r.matches() && r.decision().equals("require_approval"));
    if (anyDeny) {
      assertThat(d).isEqualTo(Decision.DENY);
    } else if (anyApproval) {
      assertThat(d).isEqualTo(Decision.REQUIRE_APPROVAL);
    } else if (rules.stream().noneMatch(Rule::matches)) {
      assertThat(d.name().toLowerCase()).isEqualTo(def);
    }
  }

  @Provide
  Arbitrary<String> defaults() {
    return Arbitraries.of("allow", "deny", "require_approval");
  }
}
