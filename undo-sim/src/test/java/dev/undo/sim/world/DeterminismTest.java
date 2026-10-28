package dev.undo.sim.world;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.port.Bug;
import java.io.StringWriter;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DeterminismTest {
  static String trace(long seed, EnumSet<Bug> bugs) {
    StringWriter w = new StringWriter();
    World.Result r = new World(seed, bugs, true, new Trace(w)).run();
    return w + "|" + r.violations();
  }

  @Test
  void sameSeedGivesTheSameTraceTwice() {
    for (long seed : new long[] {1, 5164, 7}) {
      assertThat(trace(seed, EnumSet.noneOf(Bug.class)))
          .isEqualTo(trace(seed, EnumSet.noneOf(Bug.class)));
    }
  }

  @Test
  void aFailureReproducesExactly() {
    EnumSet<Bug> bug = EnumSet.of(Bug.RETRY_UNKNOWN_WITHOUT_LOOKUP);
    long seed = 1;
    while (new World(seed, bug, true, null).run().violations().isEmpty()) {
      seed++;
      assertThat(seed).as("the bug flag must be caught within 200 seeds").isLessThan(200);
    }
    String a = trace(seed, bug);
    assertThat(a).contains("INV-1");
    assertThat(trace(seed, bug)).isEqualTo(a);
  }

  @Test
  void cleanSeedsHaveNoViolations() {
    for (long seed = 1; seed <= 30; seed++) {
      assertThat(new World(seed, EnumSet.noneOf(Bug.class), true, null).run().violations())
          .as("seed " + seed)
          .isEmpty();
    }
  }
}
