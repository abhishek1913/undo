package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.RunStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** MCP over Streamable HTTP (Section 18) with a real agent key. */
class McpIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  private Resp rpc(String key, String session, String method, String params, Integer id)
      throws Exception {
    String body =
        "{\"jsonrpc\":\"2.0\","
            + (id == null ? "" : "\"id\":" + id + ",")
            + "\"method\":\""
            + method
            + "\""
            + (params == null ? "" : ",\"params\":" + params)
            + "}";
    List<String> h =
        new ArrayList<>(
            List.of(
                "Authorization", "Bearer " + key, "Accept", "application/json, text/event-stream"));
    if (session != null) {
      h.add("Mcp-Session-Id");
      h.add(session);
    }
    return undo("POST", "/mcp", body, h.toArray(String[]::new));
  }

  private String initialize(String key) throws Exception {
    Resp init =
        rpc(
            key,
            null,
            "initialize",
            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it-client\",\"version\":\"1\"}}",
            1);
    assertThat(init.status()).isEqualTo(200);
    assertThat(init.body().at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
    assertThat(init.body().at("/result/serverInfo/name").asText()).isEqualTo("undo");
    String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
    assertThat(rpc(key, session, "notifications/initialized", null, null).status()).isEqualTo(202);
    return session;
  }

  private static List<String> names(Resp list) {
    List<String> out = new ArrayList<>();
    list.body().at("/result/tools").forEach(t -> out.add(t.get("name").asText()));
    return out;
  }

  @Test
  void initializeListAndCallShopGetTicket() throws Exception {
    String session = initialize(AGENT_KEY);
    Resp list = rpc(AGENT_KEY, session, "tools/list", "{}", 2);
    assertThat(names(list))
        .contains("shop_get_ticket", "shop_issue_refund")
        .doesNotContain("shop_cancel_refund");
    JsonNode schema =
        java.util.stream.StreamSupport.stream(list.body().at("/result/tools").spliterator(), false)
            .filter(t -> t.get("name").asText().equals("shop_get_ticket"))
            .findFirst()
            .orElseThrow()
            .get("inputSchema");
    assertThat(schema.at("/properties/ticket_id/type").asText()).isEqualTo("string");

    Resp call =
        rpc(
            AGENT_KEY,
            session,
            "tools/call",
            "{\"name\":\"shop_get_ticket\",\"arguments\":{\"ticket_id\":\"TKT-0001\"}}",
            3);
    assertThat(call.body().at("/result/isError").asBoolean()).isFalse();
    JsonNode text = Json.parse(call.body().at("/result/content/0/text").asText());
    assertThat(text.get("status").asText()).isEqualTo("succeeded");
    assertThat(text.at("/result/id").asText()).isEqualTo("TKT-0001");

    // The session is one run; DELETE completes it.
    var run = store.tx(tx -> tx.runs().byMcpSession(session)).orElseThrow();
    assertThat(run.title).isEqualTo("MCP: it-client");
    assertThat(actionsOf(run.id)).hasSize(1);
    assertThat(
            undo(
                    "DELETE",
                    "/mcp",
                    null,
                    "Authorization",
                    "Bearer " + AGENT_KEY,
                    "Mcp-Session-Id",
                    session)
                .status())
        .isEqualTo(204);
    assertThat(store.tx(tx -> tx.runs().get(run.id)).orElseThrow().status)
        .isEqualTo(RunStatus.COMPLETED);
    assertThat(
            rpc(
                    AGENT_KEY,
                    session,
                    "tools/call",
                    "{\"name\":\"shop_get_ticket\",\"arguments\":{\"ticket_id\":\"TKT-0001\"}}",
                    4)
                .status())
        .isEqualTo(200); // the run is COMPLETED: reads may still answer, writes are denied
  }

  @Test
  void mcpToolsListFiltersByKeyScopes() throws Exception {
    ConsoleClient admin = ConsoleClient.as("admin@undo.local", "undo-demo-admin");
    String agent =
        admin
            .post("/api/agents", "{\"name\":\"mcp-readonly-" + System.nanoTime() + "\"}")
            .body()
            .get("id")
            .asText();
    String key =
        admin
            .post(
                "/api/agents/" + agent + "/keys",
                "{\"allowed_tools\":[\"shop_get_*\"],\"default_policy\":\"support-default\"}")
            .body()
            .get("key")
            .asText();
    String session = initialize(key);
    assertThat(names(rpc(key, session, "tools/list", "{}", 2)))
        .containsExactlyInAnyOrder("shop_get_ticket", "shop_get_order", "shop_get_customer");
    Resp refused =
        rpc(key, session, "tools/call", "{\"name\":\"shop_issue_refund\",\"arguments\":{}}", 3);
    assertThat(refused.body().at("/error/code").asInt()).isEqualTo(-32602);
    // Another agent's session is not visible to this key.
    String other = initialize(AGENT_KEY);
    assertThat(rpc(key, other, "tools/list", "{}", 4).status()).isEqualTo(404);
  }

  @Test
  void transportRules() throws Exception {
    assertThat(undo("GET", "/mcp", null, "Authorization", "Bearer " + AGENT_KEY).status())
        .isEqualTo(405);
    assertThat(
            undo(
                    "POST",
                    "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                    "Authorization",
                    "Bearer undo_sk_wrong")
                .status())
        .isEqualTo(401);
    assertThat(rpc(AGENT_KEY, null, "tools/list", "{}", 1).status()).isEqualTo(400);
    assertThat(rpc(AGENT_KEY, null, "ping", null, 9).body().get("result").isObject()).isTrue();
  }
}
