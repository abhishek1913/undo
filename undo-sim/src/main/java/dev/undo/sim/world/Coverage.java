package dev.undo.sim.world;

import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.port.CrashPoint;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/** What a set of seeds exercised (printed every run, Section 21.6). */
public final class Coverage {
  public final EnumMap<CrashPoint, Long> reached = new EnumMap<>(CrashPoint.class);
  public final EnumMap<CrashPoint, Long> crashed = new EnumMap<>(CrashPoint.class);
  public final TreeMap<String, Long> transitions = new TreeMap<>();
  public final EnumMap<AttemptOutcome, Long> outcomes = new EnumMap<>(AttemptOutcome.class);
  public final TreeMap<String, Long> revertKinds = new TreeMap<>();
  public final TreeMap<String, Long> findingTypes = new TreeMap<>();
  public final TreeMap<String, Long> providerRequests = new TreeMap<>();

  void reached(CrashPoint p) {
    reached.merge(p, 1L, Long::sum);
  }

  void crashed(CrashPoint p) {
    crashed.merge(p, 1L, Long::sum);
  }

  void transition(ActionStatus from, ActionStatus to) {
    if (from != null) {
      transitions.merge(from + ">" + to, 1L, Long::sum);
    }
  }

  void outcome(AttemptOutcome o) {
    if (o != null) {
      outcomes.merge(o, 1L, Long::sum);
    }
  }

  void providerRequest(String label, int status) {
    providerRequests.merge(
        label.replaceAll("/[A-Z]{2,3}-[A-Z0-9]+", "/{id}") + " " + status, 1L, Long::sum);
  }

  public synchronized void merge(Coverage o) {
    o.reached.forEach((k, v) -> reached.merge(k, v, Long::sum));
    o.crashed.forEach((k, v) -> crashed.merge(k, v, Long::sum));
    o.transitions.forEach((k, v) -> transitions.merge(k, v, Long::sum));
    o.outcomes.forEach((k, v) -> outcomes.merge(k, v, Long::sum));
    o.revertKinds.forEach((k, v) -> revertKinds.merge(k, v, Long::sum));
    o.findingTypes.forEach((k, v) -> findingTypes.merge(k, v, Long::sum));
    o.providerRequests.forEach((k, v) -> providerRequests.merge(k, v, Long::sum));
  }

  public static long min(Map<?, Long> m) {
    return m.values().stream().mapToLong(Long::longValue).min().orElse(0);
  }
}
