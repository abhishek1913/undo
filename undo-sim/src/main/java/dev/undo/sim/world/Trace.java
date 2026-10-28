package dev.undo.sim.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/** Optional event log for one seed: one line per event and per notable engine step. */
public final class Trace {
  private final Writer out;

  public Trace(Writer out) {
    this.out = out;
  }

  public void line(long micros, long seq, String what) {
    try {
      out.write(String.format("%012d %08d %s%n", micros, seq, what));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void note(long micros, String what) {
    line(micros, -1, "  " + what);
  }

  public void flush() {
    try {
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
