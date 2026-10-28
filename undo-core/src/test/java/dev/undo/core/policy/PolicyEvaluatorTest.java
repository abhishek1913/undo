package dev.undo.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Decision;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyEvaluatorTest {
  static PolicyEvaluator.Context ctx(String args, String facts) {
    return new PolicyEvaluator.Context(
        Json.parse(args),
        Json.parse(facts),
        Json.parse("{\"number\":7,\"labels\":{\"env\":\"demo\"}}"),
        Json.parse("{\"name\":\"bot\"}"),
        Json.parse("{\"kind\":\"PRIMARY\",\"is_compensation\":false}"));
  }

  static boolean test(String cond, String args, String facts) throws PolicyEvaluator.EvalError {
    return PolicyEvaluator.test(Json.parse(cond), ctx(args, facts));
  }

  static JsonNode policy(String toolJson) {
    return Json.parse("{\"tools\":{\"t\":" + toolJson + "}}");
  }

  @Nested
  class Operators {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":5}|true",
          "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":6}|false",
          "{\"field\":\"args.n\",\"op\":\"neq\",\"value\":6}|true",
          "{\"field\":\"args.n\",\"op\":\"gt\",\"value\":4}|true",
          "{\"field\":\"args.n\",\"op\":\"gt\",\"value\":5}|false",
          "{\"field\":\"args.n\",\"op\":\"gte\",\"value\":5}|true",
          "{\"field\":\"args.n\",\"op\":\"lt\",\"value\":6}|true",
          "{\"field\":\"args.n\",\"op\":\"lte\",\"value\":4}|false",
          "{\"field\":\"args.s\",\"op\":\"in\",\"value\":[\"a\",\"b\"]}|true",
          "{\"field\":\"args.s\",\"op\":\"not_in\",\"value\":[\"a\",\"b\"]}|false",
          "{\"field\":\"args.s\",\"op\":\"matches\",\"value\":\"[ab]\"}|true",
          "{\"field\":\"args.s\",\"op\":\"matches\",\"value\":\"b\"}|false",
          "{\"field\":\"args.s\",\"op\":\"exists\"}|true",
          "{\"field\":\"args.s\",\"op\":\"not_exists\"}|false",
          "{\"field\":\"args.n\",\"op\":\"eq\",\"ref\":\"facts.m\"}|true",
          "{\"field\":\"args.n\",\"op\":\"lt\",\"ref\":\"facts.big\"}|true",
          "{\"field\":\"run.labels.env\",\"op\":\"eq\",\"value\":\"demo\"}|true",
          "{\"field\":\"agent.name\",\"op\":\"eq\",\"value\":\"bot\"}|true",
          "{\"field\":\"ctx.is_compensation\",\"op\":\"eq\",\"value\":false}|true"
        })
    void evaluates(String cond, boolean expected) throws Exception {
      assertThat(test(cond, "{\"n\":5,\"s\":\"a\"}", "{\"m\":5,\"big\":10}")).isEqualTo(expected);
    }

    @Test
    void matchesIsAFullMatch() throws Exception {
      assertThat(
              test(
                  "{\"field\":\"args.s\",\"op\":\"matches\",\"value\":\"ab\"}",
                  "{\"s\":\"xaby\"}",
                  "{}"))
          .isFalse();
      assertThat(
              test(
                  "{\"field\":\"args.s\",\"op\":\"matches\",\"value\":\".*ab.*\"}",
                  "{\"s\":\"xaby\"}",
                  "{}"))
          .isTrue();
    }

    @Test
    void combinators() throws Exception {
      String t = "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":5}";
      String f = "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":6}";
      assertThat(test("{\"all\":[" + t + "," + t + "]}", "{\"n\":5}", "{}")).isTrue();
      assertThat(test("{\"all\":[" + t + "," + f + "]}", "{\"n\":5}", "{}")).isFalse();
      assertThat(test("{\"all\":[]}", "{}", "{}")).isTrue();
      assertThat(test("{\"any\":[" + f + "," + t + "]}", "{\"n\":5}", "{}")).isTrue();
      assertThat(test("{\"any\":[]}", "{}", "{}")).isFalse();
      assertThat(test("{\"not\":" + f + "}", "{\"n\":5}", "{}")).isTrue();
    }
  }

  @Nested
  class MissingPaths {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "exists|false",
          "not_exists|true",
          "eq|false",
          "neq|true",
          "in|false",
          "not_in|true"
        })
    void missingPathSemantics(String op, boolean expected) throws Exception {
      String value =
          op.contains("in") ? ",\"value\":[1]" : op.contains("exists") ? "" : ",\"value\":1";
      assertThat(
              test(
                  "{\"field\":\"facts.nope.deeper\",\"op\":\"" + op + "\"" + value + "}",
                  "{}",
                  "{}"))
          .isEqualTo(expected);
    }

    @Test
    void jsonNullCountsAsMissing() throws Exception {
      assertThat(test("{\"field\":\"facts.ticket\",\"op\":\"exists\"}", "{}", "{\"ticket\":null}"))
          .isFalse();
    }

    @Test
    void allShortCircuitsSoGuardedRulesDoNotError() throws Exception {
      String c =
          "{\"all\":[{\"field\":\"facts.ticket\",\"op\":\"exists\"},{\"field\":\"facts.ticket.customer_id\",\"op\":\"neq\",\"ref\":\"facts.order.customer_id\"}]}";
      assertThat(test(c, "{}", "{\"ticket\":null}")).isFalse();
    }
  }

  @Nested
  class FailClosed {
    @Test
    void policyMissingFieldInOrderingOperatorFailsClosed() {
      JsonNode p =
          policy(
              "{\"default\":\"allow\",\"rules\":[{\"id\":\"t.big\",\"decision\":\"require_approval\",\"reason\":\"r\",\"when\":{\"field\":\"args.amount\",\"op\":\"gt\",\"value\":1}}]}");
      PolicyEvaluator.Result r = PolicyEvaluator.evaluate(p, "t", ctx("{}", "{}"));
      assertThat(r.decision()).isEqualTo(Decision.DENY);
      assertThat(r.reasonCode()).isEqualTo("policy_error");
      assertThat(r.error()).contains("args.amount is missing");
    }

    @Test
    void typeMismatchFailsClosed() {
      JsonNode p =
          policy(
              "{\"default\":\"allow\",\"rules\":[{\"id\":\"t.x\",\"decision\":\"deny\",\"reason\":\"r\",\"when\":{\"field\":\"args.s\",\"op\":\"gt\",\"value\":1}}]}");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{\"s\":\"a\"}", "{}")).reasonCode())
          .isEqualTo("policy_error");
    }

    @Test
    void missingRefFailsClosed() {
      JsonNode p =
          policy(
              "{\"default\":\"allow\",\"rules\":[{\"id\":\"t.x\",\"decision\":\"deny\",\"reason\":\"r\",\"when\":{\"field\":\"args.n\",\"op\":\"gt\",\"ref\":\"facts.order.refundable_minor\"}}]}");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{\"n\":1}", "{}")).reasonCode())
          .isEqualTo("policy_error");
    }

    @Test
    void matchesOnMissingOrNonStringFailsClosed() throws Exception {
      JsonNode p =
          policy(
              "{\"default\":\"allow\",\"rules\":[{\"id\":\"t.x\",\"decision\":\"deny\",\"reason\":\"r\",\"when\":{\"field\":\"args.s\",\"op\":\"matches\",\"value\":\"a\"}}]}");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{}", "{}")).reasonCode())
          .isEqualTo("policy_error");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{\"s\":5}", "{}")).reasonCode())
          .isEqualTo("policy_error");
    }

    @Test
    void toolNotInPolicyIsDenied() {
      PolicyEvaluator.Result r =
          PolicyEvaluator.evaluate(policy("{\"default\":\"allow\"}"), "other", ctx("{}", "{}"));
      assertThat(r.decision()).isEqualTo(Decision.DENY);
      assertThat(r.reasonCode()).isEqualTo("tool_not_in_policy");
    }
  }

  @Nested
  class Precedence {
    String rule(String id, String decision, boolean matches) {
      String cond =
          matches
              ? "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":1}"
              : "{\"field\":\"args.n\",\"op\":\"eq\",\"value\":2}";
      return "{\"id\":\""
          + id
          + "\",\"decision\":\""
          + decision
          + "\",\"reason\":\""
          + id
          + "\",\"when\":"
          + cond
          + "}";
    }

    @Test
    void denyBeatsRequireApprovalBeatsAllowBeatsDefault() {
      JsonNode p =
          policy(
              "{\"default\":\"deny\",\"rules\":["
                  + rule("t.a", "allow", true)
                  + ","
                  + rule("t.r", "require_approval", true)
                  + ","
                  + rule("t.d", "deny", true)
                  + "]}");
      PolicyEvaluator.Result r = PolicyEvaluator.evaluate(p, "t", ctx("{\"n\":1}", "{}"));
      assertThat(r.decision()).isEqualTo(Decision.DENY);
      assertThat(r.ruleId()).isEqualTo("t.d");
      assertThat(r.matched()).hasSize(3);
      p =
          policy(
              "{\"default\":\"deny\",\"rules\":["
                  + rule("t.a", "allow", true)
                  + ","
                  + rule("t.r", "require_approval", true)
                  + "]}");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{\"n\":1}", "{}")).decision())
          .isEqualTo(Decision.REQUIRE_APPROVAL);
      p = policy("{\"default\":\"deny\",\"rules\":[" + rule("t.a", "allow", true) + "]}");
      assertThat(PolicyEvaluator.evaluate(p, "t", ctx("{\"n\":1}", "{}")).decision())
          .isEqualTo(Decision.ALLOW);
      p =
          policy(
              "{\"default\":\"require_approval\",\"rules\":[" + rule("t.d", "deny", false) + "]}");
      PolicyEvaluator.Result d = PolicyEvaluator.evaluate(p, "t", ctx("{\"n\":1}", "{}"));
      assertThat(d.decision()).isEqualTo(Decision.REQUIRE_APPROVAL);
      assertThat(d.matched()).isEmpty();
    }
  }
}
