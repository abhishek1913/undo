package dev.undo.core.engine;

import dev.undo.core.model.Action;
import dev.undo.core.model.BudgetState;
import dev.undo.core.port.TxContext;

/**
 * Budget state changes of one action (FR-BUD-3). Both methods do nothing unless the state is
 * RESERVED, so they are idempotent and never touch reads, compensations or remediations.
 */
public final class Budget {
  private Budget() {}

  /** SUCCEEDED: RESERVED -> SPENT (reserved -= money; spent += money). */
  public static void settleIfReserved(TxContext tx, Action a) {
    if (a.budgetState == BudgetState.RESERVED) {
      tx.runs().settle(a.runId, a.moneyOutMinor);
      a.budgetState = BudgetState.SPENT;
    }
  }

  /** REJECTED, EXPIRED, FAILED, CANCELLED: RESERVED -> RELEASED. */
  public static void releaseIfReserved(TxContext tx, Action a) {
    if (a.budgetState == BudgetState.RESERVED) {
      tx.runs().release(a.runId, a.moneyOutMinor, 1);
      a.budgetState = BudgetState.RELEASED;
    }
  }
}
