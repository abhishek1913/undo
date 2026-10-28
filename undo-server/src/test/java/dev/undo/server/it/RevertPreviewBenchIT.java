package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * B7 (Section 23): wall time of a revert preview of a run with 500 SUCCEEDED actions, on Postgres
 * with a real HTTP Shopfront. The preview reads the provider's current state once per action.
 * Writes the measurement to {@code target/bench-b7.txt}.
 */
class RevertPreviewBenchIT extends StackTestSupport {
  private static final int ACTIONS = 500;

  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  @Test
  void revertPreviewOf500SucceededActions() throws Exception {
    UUID run = newRun(null);
    for (int i = 0; i < ACTIONS; i++) {
      Resp r =
          propose(
              run,
              "shop_set_order_note",
              "{\"order_id\":\"" + freshOrder() + "\",\"note\":\"bench " + i + "\"}",
              null);
      assertThat(r.body().get("status").asText()).isEqualTo("succeeded");
    }
    long number = store.tx(tx -> tx.runs().get(run)).orElseThrow().number;
    ConsoleClient c = ConsoleClient.as("approver@undo.local", "undo-demo-approver");
    long t0 = System.nanoTime();
    Resp preview = c.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}");
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(preview.status()).isEqualTo(201);
    JsonNode plan = preview.body();
    assertThat(plan.get("items")).hasSize(ACTIONS);
    String line = "B7 revert preview, " + ACTIONS + " SUCCEEDED actions: " + ms + " ms";
    System.out.println(line);
    Files.writeString(Path.of("target", "bench-b7.txt"), line + "\n");
  }
}
