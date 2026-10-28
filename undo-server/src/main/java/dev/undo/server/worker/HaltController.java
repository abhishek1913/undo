package dev.undo.server.worker;

import dev.undo.server.config.UndoProperties;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo only: POST /internal/halt makes this worker call Runtime.halt(137), like kill -9; compose
 * restarts it (Section 19.3, kill-worker). Disabled unless undo.demo.enabled.
 */
@RestController
public class HaltController {
  private final UndoProperties props;

  public HaltController(UndoProperties props) {
    this.props = props;
  }

  @PostMapping("/internal/halt")
  public ResponseEntity<?> halt() {
    if (!props.demo().enabled() || !props.worker()) {
      return ResponseEntity.notFound().build();
    }
    Thread.startVirtualThread(
        () -> {
          try {
            Thread.sleep(50);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          Runtime.getRuntime().halt(137);
        });
    return ResponseEntity.accepted().body(Map.of("halting", true));
  }
}
