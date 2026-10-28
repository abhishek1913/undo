package dev.undo.sim.world;

import java.time.Instant;
import java.util.PriorityQueue;

/**
 * Virtual time. Events run in (time, seq) order; seq is assigned in scheduling order, so the same
 * seed always produces the same order (Section 21.2).
 */
public final class EventLoop {
  private record Ev(long timeMicros, long seq, String what, Runnable action) {}

  private final PriorityQueue<Ev> queue =
      new PriorityQueue<>(
          (a, b) ->
              a.timeMicros != b.timeMicros
                  ? Long.compare(a.timeMicros, b.timeMicros)
                  : Long.compare(a.seq, b.seq));
  private final Instant epoch;
  private long nowMicros;
  private long seq;
  private long executed;
  private Trace trace;

  public EventLoop(Instant epoch) {
    this.epoch = epoch;
  }

  public void trace(Trace t) {
    this.trace = t;
  }

  public Instant now() {
    return epoch.plusNanos(nowMicros * 1000);
  }

  public long nowMicros() {
    return nowMicros;
  }

  public long elapsedSeconds() {
    return nowMicros / 1_000_000;
  }

  public void at(long timeMicros, String what, Runnable action) {
    queue.add(new Ev(Math.max(timeMicros, nowMicros), seq++, what, action));
  }

  public void after(long delayMicros, String what, Runnable action) {
    at(nowMicros + Math.max(0, delayMicros), what, action);
  }

  /** Runs one event; false when the queue is empty. */
  public boolean step() {
    Ev e = queue.poll();
    if (e == null) {
      return false;
    }
    nowMicros = e.timeMicros;
    executed++;
    if (trace != null) {
      trace.line(nowMicros, e.seq, e.what);
    }
    e.action.run();
    return true;
  }

  public long executed() {
    return executed;
  }

  public int pending() {
    return queue.size();
  }
}
