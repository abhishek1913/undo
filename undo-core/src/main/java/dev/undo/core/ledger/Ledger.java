package dev.undo.core.ledger;

import dev.undo.core.connector.JournalSpec;
import dev.undo.core.connector.PostingLine;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.Posting;
import dev.undo.core.port.Bug;
import dev.undo.core.port.BugFlags;
import dev.undo.core.port.Ids;
import dev.undo.core.port.TxContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes double-entry journal entries (FR-LED-1, FR-LED-2). Each (action_id, kind) is written at
 * most once, so duplicate webhooks, retries and a webhook racing a compensation post once in total.
 */
public final class Ledger {
  private Ledger() {}

  /**
   * @param actionId the PRIMARY action the money belongs to
   * @param source overrides the spec's source (execution, webhook, recon, compensation, review)
   */
  public static int write(
      TxContext tx,
      Ids ids,
      BugFlags bugs,
      Instant now,
      UUID runId,
      UUID actionId,
      List<JournalSpec> specs,
      String source,
      String sourceRef) {
    int written = 0;
    for (JournalSpec spec : specs) {
      if (spec.lines().isEmpty()) {
        continue;
      }
      if (!bugs.on(Bug.JOURNAL_SKIPS_EXISTENCE_CHECK)
          && tx.ledger().entryExists(actionId, spec.kind())) {
        continue;
      }
      JournalEntry e = new JournalEntry();
      e.id = ids.next();
      e.runId = runId;
      e.actionId = actionId;
      e.kind = spec.kind();
      e.source = source != null ? source : spec.source();
      e.sourceRef = sourceRef;
      e.createdAt = now;
      List<Posting> postings = new ArrayList<>();
      for (PostingLine l : spec.lines()) {
        Posting p = new Posting();
        p.entryId = e.id;
        p.account = l.account();
        p.amountMinor = l.amountMinor();
        postings.add(p);
      }
      tx.ledger().insert(e, postings);
      written++;
    }
    return written;
  }

  /** Net per account over postings. */
  public static java.util.TreeMap<String, Long> net(List<Posting> postings) {
    java.util.TreeMap<String, Long> out = new java.util.TreeMap<>();
    for (Posting p : postings) {
      out.merge(p.account, p.amountMinor, Long::sum);
    }
    return out;
  }

  /** Net run exposure = sum of postings on expense:* accounts (Section 33.3). */
  public static long expenseExposure(List<Posting> postings) {
    long s = 0;
    for (Posting p : postings) {
      if (p.account.startsWith("expense:")) {
        s += p.amountMinor;
      }
    }
    return s;
  }
}
