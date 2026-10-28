package dev.undo.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.connector.Effect;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyCompilerTest {
  static final Map<String, Effect> TOOLS = new HashMap<>();

  static {
    for (String t :
        new String[] {
          "shop_list_open_tickets",
          "shop_get_ticket",
          "shop_get_order",
          "shop_get_customer",
          "undo_run_status"
        }) {
      TOOLS.put(t, Effect.READ);
    }
    for (String t :
        new String[] {
          "shop_issue_refund",
          "shop_grant_store_credit",
          "shop_send_email",
          "shop_close_ticket",
          "shop_set_order_note",
          "shop_cancel_refund",
          "shop_revoke_store_credit",
          "shop_reopen_ticket",
          "shop_restore_order_note"
        }) {
      TOOLS.put(t, Effect.WRITE);
    }
  }

  final PolicyCompiler compiler = new PolicyCompiler(TOOLS);

  static String doc(String tools) {
    return """
        apiVersion: undo.dev/v1
        kind: Policy
        metadata:
          name: test-policy
        spec:
          defaultDecision: deny
          budgets: { moneyOutMinor: 1000, actions: 10, llmTokens: 100 }
          tools:
        """
        + tools;
  }

  @Test
  void shippedSupportDefaultLoads() {
    PolicyCompiler.Result r = compiler.compile(BuiltinPolicies.supportDefaultYaml());
    assertThat(r.errors()).isEmpty();
    assertThat(r.name()).isEqualTo("support-default");
    assertThat(r.compiled().at("/budgets/money_out_minor").asLong()).isEqualTo(100_000_000);
    assertThat(r.compiled().at("/tools/shop_issue_refund/rules")).hasSize(5);
    assertThat(r.compiled().at("/approvals_expires_after").asText()).isEqualTo("PT24H");
  }

  @Test
  void defaultDecisionMustBeDeny() {
    PolicyCompiler.Result r =
        compiler.compile(
            doc("    shop_get_order: { default: allow }\n")
                .replace("defaultDecision: deny", "defaultDecision: allow"));
    assertThat(r.errors())
        .anySatisfy(
            e -> {
              assertThat(e.message()).contains("defaultDecision must be deny");
              assertThat(e.line()).isEqualTo(6);
            });
  }

  @Test
  void unknownToolAndUnknownKeyAreErrorsWithLines() {
    PolicyCompiler.Result r =
        compiler.compile(
            doc(
                "    shop_nope: { default: allow }\n    shop_get_order: { default: allow, colour: red }\n"));
    assertThat(r.errors())
        .anySatisfy(
            e -> {
              assertThat(e.message()).contains("Unknown tool 'shop_nope'");
              assertThat(e.line()).isEqualTo(9);
            });
    assertThat(r.errors())
        .anySatisfy(
            e -> {
              assertThat(e.message()).contains("Unknown key 'colour'");
              assertThat(e.line()).isEqualTo(10);
            });
  }

  @Test
  void readToolsCannotRequireApproval() {
    assertThat(
            compiler.compile(doc("    shop_get_order: { default: require_approval }\n")).errors())
        .anyMatch(e -> e.message().contains("READ tools cannot require approval"));
    String rules =
        """
            shop_get_order:
              default: allow
              rules:
                - id: order.x
                  decision: require_approval
                  reason: no
                  when: { field: args.order_id, op: exists }
        """;
    assertThat(compiler.compile(doc(rules)).errors())
        .anyMatch(e -> e.message().contains("READ tools cannot have require_approval"));
  }

  @Test
  void ruleValidation() {
    String rules =
        """
            shop_issue_refund:
              default: allow
              rules:
                - id: refund.a
                  decision: deny
                  reason: a
                  when: { field: bogus.x, op: gt, value: 1 }
                - id: refund.a
                  decision: deny
                  reason: b
                  when: { field: args.x, op: matches, value: "(" }
                - id: refund.c
                  decision: maybe
                  reason: c
                  when: { field: args.x, op: between, value: 1 }
                - id: refund.d
                  decision: deny
                  reason: d
                  when: { field: args.x, op: in, value: 3 }
        """;
    var errors = compiler.compile(doc(rules)).errors();
    assertThat(errors).anyMatch(e -> e.message().contains("Path must start with"));
    assertThat(errors).anyMatch(e -> e.message().contains("Duplicate rule id 'refund.a'"));
    assertThat(errors).anyMatch(e -> e.message().contains("Invalid RE2 pattern"));
    assertThat(errors).anyMatch(e -> e.message().contains("decision must be"));
    assertThat(errors).anyMatch(e -> e.message().contains("Unknown operator 'between'"));
    assertThat(errors).anyMatch(e -> e.message().contains("in needs a list value"));
  }

  @Test
  void budgetsMustBePositiveAndYamlErrorsHaveLines() {
    assertThat(
            compiler
                .compile(
                    doc("    shop_get_order: { default: allow }\n")
                        .replace("actions: 10", "actions: 0"))
                .errors())
        .anyMatch(e -> e.message().contains("actions must be a positive integer"));
    PolicyCompiler.Result bad = compiler.compile("apiVersion: undo.dev/v1\nkind: [unclosed\n");
    assertThat(bad.errors()).hasSize(1);
    assertThat(bad.errors().get(0).message()).startsWith("YAML syntax error");
  }
}
