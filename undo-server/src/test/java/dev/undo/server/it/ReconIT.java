package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reconciliation end to end (Section 16) on Postgres with a real HTTP Shopfront. */
class ReconIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  private JsonNode reconNow() throws Exception {
    ConsoleClient admin = ConsoleClient.as("admin@undo.local", "undo-demo-admin");
    Thread.sleep(5); // recon checks only actions that did not change while it listed
    Resp r = admin.post("/api/recon/runs", "{}");
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().get("status").asText()).isEqualTo("COMPLETED");
    return r.body();
  }

  private List<JsonNode> findingsOf(String actionId, String type) throws Exception {
    ConsoleClient viewer = ConsoleClient.as("viewer@undo.local", "undo-demo-viewer");
    JsonNode all = viewer.get("/api/recon/findings?type=" + type).body();
    List<JsonNode> out = new ArrayList<>();
    all.get("data")
        .forEach(
            f -> {
              if (actionId.equals(f.path("action_id").asText())) {
                out.add(f);
              }
            });
    return out;
  }

  private String succeededRefund() throws Exception {
    UUID run = newRun(null);
    propose(run, "shop_issue_refund", refund(freshOrder(), 1200), null);
    String id = actionsOf(run).get(0).id.toString();
    await(
        "refund succeeds",
        Duration.ofSeconds(10),
        () -> action(id).status == ActionStatus.SUCCEEDED);
    return id;
  }

  @Test
  void reconAutoHealsMissedWebhook() throws Exception {
    shop("POST", "/admin/faults", "{\"webhook_drop_rate\":1}");
    String id = succeededRefund();
    UUID runId = action(id).runId;
    shop("POST", "/admin/settlement/run?limit=1&undo_run_id=" + runId, null);
    Thread.sleep(1500); // a delivered webhook would have arrived by now
    assertThat(action(id).providerStatus).isEqualTo("pending");

    JsonNode run = reconNow();
    assertThat(run.at("/stats/auto_healed").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(action(id).providerStatus).isEqualTo("settled");
    List<JsonNode> fs = findingsOf(id, "STATUS_MISMATCH");
    assertThat(fs).hasSize(1);
    assertThat(fs.get(0).get("status").asText()).isEqualTo("AUTO_HEALED");
    assertThat(fs.get(0).get("severity").asText()).isEqualTo("INFO");
    assertThat(fs.get(0).at("/actual/provider_status").asText()).isEqualTo("settled");
    boolean posted = store.tx(tx -> tx.ledger().entryExists(UUID.fromString(id), "REFUND_SETTLED"));
    assertThat(posted).isTrue();
    reconNow();
    assertThat(findingsOf(id, "STATUS_MISMATCH")).hasSize(1);
  }

  @Test
  void reconAutoHealsGhostEffectForUnknownAction() throws Exception {
    String id = succeededRefund();
    String ref = action(id).providerRef;
    // The state of an action whose response was lost and whose lookup is not due yet: UNKNOWN,
    // no provider reference, resolve_after in an hour (the resolver leaves it alone).
    store.tx(
        tx -> {
          Action a = tx.actions().lock(UUID.fromString(id));
          ActionStatus prev = a.status;
          long fence = a.leaseFence;
          a.status = ActionStatus.UNKNOWN;
          a.providerRef = null;
          a.providerStatus = null;
          a.completedAt = null;
          a.unknownSince = Instant.now();
          a.resolveAfter = Instant.now().plus(Duration.ofHours(1));
          a.updatedAt = Instant.now();
          return tx.actions().update(a, prev, fence);
        });

    reconNow();
    Action healed = action(id);
    assertThat(healed.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(healed.providerRef).isEqualTo(ref);
    assertThat(healed.resolveAfter).isNull();
    List<JsonNode> fs = findingsOf(id, "GHOST_EFFECT");
    assertThat(fs).hasSize(1);
    assertThat(fs.get(0).get("status").asText()).isEqualTo("AUTO_HEALED");
    assertThat(fs.get(0).get("severity").asText()).isEqualTo("WARNING");
    assertThat(fs.get(0).get("provider_object_ref").asText()).isEqualTo(ref);
    assertThat(refundsFor(id)).isEqualTo(1);
  }

  @Test
  void reconFlagsDuplicateEffectAsCritical() throws Exception {
    String id = succeededRefund();
    Action a = action(id);
    // Someone creates a second refund directly at Shopfront with the real undo_action_id (on
    // another
    // order, so Shopfront's own invariant orders_with_multiple_undo_refunds stays 0 for other ITs).
    String body =
        "{\"order_id\":\""
            + freshOrder()
            + "\",\"amount_minor\":100,\"reason\":\"other\",\"metadata\":{\"undo_action_id\":\""
            + id
            + "\"}}";
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + SHOP_PORT + "/v1/refunds"))
                .header("Authorization", "Bearer " + SHOP_KEY)
                .header("Idempotency-Key", "by-hand-" + id)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(201);
    assertThat(refundsFor(id)).isEqualTo(2);

    reconNow();
    List<JsonNode> fs = findingsOf(id, "DUPLICATE_EFFECT");
    assertThat(fs).hasSize(1);
    JsonNode f = fs.get(0);
    assertThat(f.get("severity").asText()).isEqualTo("CRITICAL");
    assertThat(f.get("status").asText()).isEqualTo("OPEN");
    assertThat(f.at("/actual/provider_objects").asInt()).isEqualTo(2);
    assertThat(f.get("action_number").asLong()).isEqualTo(a.number);
    reconNow();
    assertThat(findingsOf(id, "DUPLICATE_EFFECT")).hasSize(1);

    ConsoleClient viewer = ConsoleClient.as("viewer@undo.local", "undo-demo-viewer");
    assertThat(viewer.get("/api/overview").body().at("/tiles/open_critical_findings").asLong())
        .isGreaterThanOrEqualTo(1);
    String fid = f.get("id").asText();
    assertThat(viewer.post("/api/recon/findings/" + fid + "/acknowledge", "{}").status())
        .isEqualTo(403);
    ConsoleClient approver = ConsoleClient.as("approver@undo.local", "undo-demo-approver");
    assertThat(approver.post("/api/recon/findings/" + fid + "/acknowledge", "{}").status())
        .isEqualTo(200);
    Resp noNote = approver.post("/api/recon/findings/" + fid + "/resolve", "{}");
    assertThat(noNote.status()).isEqualTo(422);
    Resp resolved =
        approver.post(
            "/api/recon/findings/" + fid + "/resolve", "{\"note\":\"second refund was by hand\"}");
    assertThat(resolved.status()).isEqualTo(200);
    assertThat(resolved.body().get("status").asText()).isEqualTo("RESOLVED");
    assertThat(resolved.body().get("resolution_note").asText())
        .isEqualTo("second refund was by hand");
    // Only ADMIN triggers runs.
    assertThat(approver.post("/api/recon/runs", "{}").status()).isEqualTo(403);
  }
}
