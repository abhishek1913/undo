package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Console API (Section 19.2): sessions, CSRF, roles, approvals, keys, overview, SSE. */
class ConsoleApiIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  @Test
  void loginSetsAStrictHttpOnlySessionCookie() throws Exception {
    ConsoleClient c = new ConsoleClient();
    assertThat(c.login("admin@undo.local", "wrong").status()).isEqualTo(401);
    Resp ok = c.login("admin@undo.local", "undo-demo-admin");
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body().at("/user/roles/0").asText()).isEqualTo("ADMIN");
    String setCookie = String.join("; ", ok.headers().allValues("Set-Cookie"));
    assertThat(setCookie)
        .contains("undo_session=")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("undo_csrf=");
    assertThat(c.get("/api/auth/me").body().at("/user/email").asText())
        .isEqualTo("admin@undo.local");
    HttpCookie session =
        c.jar.getCookieStore().getCookies().stream()
            .filter(x -> x.getName().equals("undo_session"))
            .findFirst()
            .orElseThrow();
    assertThat(session.isHttpOnly()).isTrue();
  }

  @Test
  void consoleApiNeedsASessionAndCsrf() throws Exception {
    ConsoleClient anon = new ConsoleClient();
    assertThat(anon.get("/api/runs").status()).isEqualTo(401);
    ConsoleClient c = new ConsoleClient();
    c.login("approver@undo.local", "undo-demo-approver");
    assertThat(c.call("POST", "/api/runs/1/freeze", null, false).status())
        .isEqualTo(403); // no CSRF header
    // a console session cannot call the agent API
    assertThat(c.get("/v1/tools").status()).isEqualTo(401);
  }

  @Test
  void rolesAreEnforced() throws Exception {
    ConsoleClient viewer = new ConsoleClient();
    viewer.login("viewer@undo.local", "undo-demo-viewer");
    assertThat(viewer.get("/api/overview").status()).isEqualTo(200);
    Resp denied =
        viewer.post("/api/approvals/" + UUID.randomUUID() + "/approve", "{\"args_hash\":\"x\"}");
    assertThat(denied.status()).isEqualTo(403);
    assertThat(denied.body().get("code").asText()).isEqualTo("FORBIDDEN");
    assertThat(viewer.get("/api/agents").status()).isEqualTo(403);
  }

  @Test
  void approvingACardExecutesTheActionWithinOneSecond() throws Exception {
    UUID run = newRun(null);
    Resp p = propose(run, "shop_issue_refund", refund(freshBigOrder(), 1_050_000), null);
    assertThat(p.body().get("status").asText()).isEqualTo("pending_approval");
    ConsoleClient c = new ConsoleClient();
    c.login("approver@undo.local", "undo-demo-approver");
    JsonNode card = null;
    for (JsonNode x : c.get("/api/approvals?status=PENDING").body().get("data")) {
      if (x.get("action").asText().equals(p.body().get("action").asText())) {
        card = x;
      }
    }
    assertThat(card).isNotNull();
    assertThat(card.at("/reasons/0/id").asText()).isEqualTo("refund.large-needs-human");
    assertThat(card.get("args_hash").asText()).hasSize(64);
    Resp mismatch =
        c.post(
            "/api/approvals/" + card.get("id").asText() + "/approve",
            "{\"args_hash\":\"" + "0".repeat(64) + "\"}");
    assertThat(mismatch.status()).isEqualTo(409);
    assertThat(mismatch.body().get("code").asText()).isEqualTo("APPROVAL_HASH_MISMATCH");
    long t0 = System.currentTimeMillis();
    Resp ok =
        c.post(
            "/api/approvals/" + card.get("id").asText() + "/approve",
            "{\"args_hash\":\"" + card.get("args_hash").asText() + "\"}");
    assertThat(ok.status()).isEqualTo(200);
    String actionNumber = card.get("action_number").asText();
    await(
        "executed",
        Duration.ofSeconds(1),
        () -> {
          try {
            return c.get("/api/actions/" + actionNumber)
                .body()
                .get("status")
                .asText()
                .equals("SUCCEEDED");
          } catch (Exception e) {
            return false;
          }
        });
    assertThat(System.currentTimeMillis() - t0).isLessThan(1000);
    JsonNode detail = c.get("/api/actions/" + actionNumber).body();
    assertThat(detail.at("/approval/approved_args_hash").asText())
        .isEqualTo(card.get("args_hash").asText());
    assertThat(detail.get("transitions").size()).isGreaterThanOrEqualTo(5);
    assertThat(detail.get("attempts").size()).isEqualTo(1);
  }

  @Test
  void rejectNeedsANoteAndBulkChecksEachItem() throws Exception {
    UUID run = newRun(null);
    Resp a = propose(run, "shop_issue_refund", refund(freshBigOrder(), 1_050_000), null);
    Resp b = propose(run, "shop_issue_refund", refund(freshBigOrder(), 1_050_000), null);
    ConsoleClient c = new ConsoleClient();
    c.login("approver@undo.local", "undo-demo-approver");
    JsonNode cards =
        c.get("/api/approvals?run=" + store.tx(tx -> tx.runs().get(run)).orElseThrow().number)
            .body()
            .get("data");
    assertThat(cards.size()).isEqualTo(2);
    String id0 = cards.get(0).get("id").asText();
    assertThat(c.post("/api/approvals/" + id0 + "/reject", "{\"note\":\"\"}").status())
        .isEqualTo(422);
    String bulk =
        "{\"decision\":\"reject\",\"note\":\"bulk\",\"items\":[{\"approval_id\":\""
            + id0
            + "\",\"args_hash\":\""
            + cards.get(0).get("args_hash").asText()
            + "\"},{\"approval_id\":\""
            + cards.get(1).get("id").asText()
            + "\",\"args_hash\":\"stale\"}]}";
    JsonNode results = c.post("/api/approvals/bulk", bulk).body().get("results");
    assertThat(results.get(0).get("ok").asBoolean()).isTrue();
    assertThat(results.get(1).get("code").asText()).isEqualTo("APPROVAL_HASH_MISMATCH");
    assertThat(a.status()).isEqualTo(200);
    assertThat(b.status()).isEqualTo(200);
  }

  @Test
  void runDetailTimelineActionsAndLedger() throws Exception {
    UUID run = newRun(null);
    propose(run, "shop_issue_refund", refund(freshOrder(), 2500), null);
    long number = store.tx(tx -> tx.runs().get(run)).orElseThrow().number;
    ConsoleClient c = new ConsoleClient();
    c.login("viewer@undo.local", "undo-demo-viewer");
    JsonNode detail = c.get("/api/runs/" + number).body();
    assertThat(detail.get("policy").asText()).isEqualTo("support-default v1");
    assertThat(detail.get("net_exposure_minor").asLong()).isEqualTo(2500);
    assertThat(c.get("/api/runs/" + number + "/timeline").body().get("data").size())
        .isGreaterThanOrEqualTo(4);
    assertThat(c.get("/api/runs/" + number + "/actions?status=SUCCEEDED").body().get("data").size())
        .isEqualTo(1);
    JsonNode ledger = c.get("/api/runs/" + number + "/ledger").body();
    assertThat(ledger.get("balanced").asBoolean()).isTrue();
    assertThat(ledger.at("/net_by_account/expense:refunds").asLong()).isEqualTo(2500);
    assertThat(c.get("/api/runs?status=ACTIVE").body().get("data").size()).isPositive();
    assertThat(c.get("/api/overview").body().at("/tiles/active_runs").asInt()).isPositive();
    assertThat(c.get("/api/tools").body().get("tools").toString()).contains("\"internal\":true");
  }

  @Test
  void adminCreatesAgentAndKeyThatWorksOnceShownAndCanBeRevoked() throws Exception {
    ConsoleClient c = new ConsoleClient();
    c.login("admin@undo.local", "undo-demo-admin");
    String name = "it-agent-" + System.nanoTime() % 100000;
    String agentId = c.post("/api/agents", "{\"name\":\"" + name + "\"}").body().get("id").asText();
    JsonNode k =
        c.post(
                "/api/agents/" + agentId + "/keys",
                "{\"allowed_tools\":[\"shop_get_*\"],\"default_policy\":\"support-default\"}")
            .body();
    String key = k.get("key").asText();
    assertThat(key).startsWith("undo_sk_");
    assertThat(k.get("mcp_command").asText())
        .contains("claude mcp add --transport http undo http://localhost:8080/mcp");
    Resp tools = undo("GET", "/v1/tools", null, "Authorization", "Bearer " + key);
    assertThat(tools.body().get("tools").size())
        .isEqualTo(3); // shop_get_ticket, shop_get_order, shop_get_customer
    assertThat(c.get("/api/agents").body().toString()).doesNotContain(key);
    c.post("/api/keys/" + k.get("id").asText() + "/revoke", null);
    assertThat(undo("GET", "/v1/tools", null, "Authorization", "Bearer " + key).status())
        .isEqualTo(401);
  }

  @Test
  void sseStreamsEventsInSeqOrderAndResumes() throws Exception {
    ConsoleClient c = new ConsoleClient();
    c.login("viewer@undo.local", "undo-demo-viewer");
    UUID run = newRun(null);
    long number = store.tx(tx -> tx.runs().get(run)).orElseThrow().number;
    propose(run, "shop_get_ticket", "{\"ticket_id\":\"TKT-0002\"}", null);
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + UNDO_PORT + "/api/stream?run=" + number + "&after_seq=0"))
            .GET()
            .build();
    HttpResponse<InputStream> r = c.client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(r.statusCode()).isEqualTo(200);
    long last = 0;
    int events = 0;
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
      String line;
      long deadline = System.currentTimeMillis() + 5000;
      while (events < 4
          && System.currentTimeMillis() < deadline
          && (line = in.readLine()) != null) {
        if (line.startsWith("id:")) {
          long seq = Long.parseLong(line.substring(3).trim());
          assertThat(seq).isGreaterThan(last);
          last = seq;
          events++;
        }
      }
    }
    assertThat(events).isGreaterThanOrEqualTo(4);
  }
}
