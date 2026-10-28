package dev.undo.server.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Event;
import dev.undo.core.model.Run;
import dev.undo.core.port.Clock;
import dev.undo.core.port.Store;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GET /api/stream?run=&after_seq= — SSE in seq order with Last-Event-ID resume (FR-EVT-2). */
@RestController
public class StreamController {
  private final Store store;
  private final Clock clock;

  public StreamController(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestParam(value = "run", required = false) Long runNumber,
      @RequestParam(value = "after_seq", required = false) Long afterSeq,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    long start =
        lastEventId != null && lastEventId.matches("\\d+")
            ? Long.parseLong(lastEventId)
            : afterSeq == null ? latest() : afterSeq;
    UUID runId =
        runNumber == null
            ? null
            : store
                .tx(tx -> tx.runs().byNumber(runNumber))
                .map((Run r) -> r.id)
                .orElse(new UUID(0, 0));
    SseEmitter emitter = new SseEmitter(0L);
    Thread.startVirtualThread(() -> pump(emitter, start, runId));
    return emitter;
  }

  /** Without a cursor, start with the newest 200 events. */
  private long latest() {
    return Math.max(0, store.tx(tx -> tx.events().maxSeq()) - 200);
  }

  private void pump(SseEmitter emitter, long start, UUID runId) {
    GapTracker gaps = new GapTracker(start, Duration.ofSeconds(2));
    long lastBeat = System.currentTimeMillis();
    try {
      while (true) {
        long cursor = gaps.cursor();
        List<Event> batch = store.tx(tx -> tx.events().after(cursor, null, 500));
        List<Event> deliver = gaps.accept(batch, clock.now());
        for (Event e : deliver) {
          if (runId != null && !runId.equals(e.runId)) {
            continue;
          }
          emitter.send(
              SseEmitter.event()
                  .id(Long.toString(e.seq))
                  .name(e.type)
                  .data(Json.write(payload(e)), MediaType.APPLICATION_JSON));
        }
        if (System.currentTimeMillis() - lastBeat > 15_000) {
          emitter.send(SseEmitter.event().comment("keep-alive"));
          lastBeat = System.currentTimeMillis();
        }
        if (deliver.isEmpty()) {
          Thread.sleep(200);
        }
      }
    } catch (IOException | IllegalStateException e) {
      emitter.completeWithError(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      emitter.complete();
    }
  }

  static JsonNode payload(Event e) {
    ObjectNode o = Json.obj();
    o.put("seq", e.seq);
    o.put("type", e.type);
    o.put("run_id", e.runId == null ? null : e.runId.toString());
    o.put("action_id", e.actionId == null ? null : e.actionId.toString());
    o.put("at", e.at.toString());
    o.set("data", e.data);
    return o;
  }
}
