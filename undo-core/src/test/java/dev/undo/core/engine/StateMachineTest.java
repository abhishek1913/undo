package dev.undo.core.engine;

import static dev.undo.core.model.ActionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.RunStatus;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateMachineTest {
  /** Section 8.2, edge by edge. */
  static final Set<String> LEGAL =
      Set.of(
          "PROPOSED>DENIED",
          "PROPOSED>AWAITING_APPROVAL",
          "PROPOSED>APPROVED",
          "AWAITING_APPROVAL>APPROVED",
          "AWAITING_APPROVAL>REJECTED",
          "AWAITING_APPROVAL>EXPIRED",
          "AWAITING_APPROVAL>CANCELLED",
          "APPROVED>EXECUTING",
          "APPROVED>CANCELLED",
          "EXECUTING>SUCCEEDED",
          "EXECUTING>FAILED",
          "EXECUTING>NEEDS_REVIEW",
          "EXECUTING>RETRY_WAIT",
          "EXECUTING>UNKNOWN",
          "RETRY_WAIT>EXECUTING",
          "RETRY_WAIT>CANCELLED",
          "UNKNOWN>EXECUTING",
          "UNKNOWN>APPROVED",
          "UNKNOWN>SUCCEEDED",
          "UNKNOWN>FAILED",
          "UNKNOWN>NEEDS_REVIEW",
          "NEEDS_REVIEW>SUCCEEDED",
          "NEEDS_REVIEW>FAILED");

  @Test
  void actionTransitionsAreExactlyTheLegalSet() {
    int legal = 0;
    for (ActionStatus from : ActionStatus.values()) {
      for (ActionStatus to : ActionStatus.values()) {
        boolean expected = LEGAL.contains(from + ">" + to);
        assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected);
        if (expected) {
          legal++;
        }
      }
    }
    assertThat(legal).isEqualTo(23);
  }

  @Test
  void terminalStatusesHaveNoWayOut() {
    for (ActionStatus s : EnumSet.of(DENIED, REJECTED, EXPIRED, SUCCEEDED, FAILED, CANCELLED)) {
      assertThat(ActionStatus.legalTargets(s)).as(s.name()).isEmpty();
    }
  }

  @Test
  void runTransitions() {
    assertThat(RunStatus.ACTIVE.canMoveTo(RunStatus.COMPLETED)).isTrue();
    assertThat(RunStatus.ACTIVE.canMoveTo(RunStatus.FROZEN)).isTrue();
    assertThat(RunStatus.ACTIVE.canMoveTo(RunStatus.FAILED)).isTrue();
    assertThat(RunStatus.ACTIVE.canMoveTo(RunStatus.REVERTING)).isFalse();
    for (RunStatus s : EnumSet.of(RunStatus.COMPLETED, RunStatus.FROZEN, RunStatus.FAILED)) {
      assertThat(s.canMoveTo(RunStatus.REVERTING)).isTrue();
      assertThat(s.canMoveTo(RunStatus.ACTIVE)).isFalse();
    }
    assertThat(RunStatus.REVERTING.canMoveTo(RunStatus.REVERTED)).isTrue();
    assertThat(RunStatus.REVERTING.canMoveTo(RunStatus.PARTIALLY_REVERTED)).isTrue();
    assertThat(RunStatus.REVERTED.canMoveTo(RunStatus.REVERTING)).isFalse();
    assertThat(RunStatus.PARTIALLY_REVERTED.canMoveTo(RunStatus.REVERTING)).isFalse();
  }

  @Test
  void primaryExecutionGuard() {
    assertThat(RunStatus.ACTIVE.allowsPrimaryExecution()).isTrue();
    assertThat(RunStatus.COMPLETED.allowsPrimaryExecution()).isTrue();
    assertThat(RunStatus.FROZEN.allowsPrimaryExecution()).isFalse();
    assertThat(RunStatus.REVERTING.allowsPrimaryExecution()).isFalse();
  }

  @Test
  void dedupeKeyActivity() {
    for (ActionStatus s : EnumSet.of(DENIED, REJECTED, EXPIRED, FAILED, CANCELLED)) {
      assertThat(s.endedWithoutEffect()).isTrue();
    }
    assertThat(SUCCEEDED.endedWithoutEffect()).isFalse();
    assertThat(NEEDS_REVIEW.endedWithoutEffect()).isFalse();
  }
}
