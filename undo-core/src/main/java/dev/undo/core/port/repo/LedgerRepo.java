package dev.undo.core.port.repo;

import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.Posting;
import java.util.List;
import java.util.UUID;

public interface LedgerRepo {
  boolean entryExists(UUID actionId, String kind);

  /**
   * Inserts the entry and its postings. Unique (action_id, kind) is enforced
   * (journal_entries_action_id_kind_key); balance is checked at commit (postings_balanced).
   */
  void insert(JournalEntry entry, List<Posting> postings);

  List<JournalEntry> entriesByRun(UUID runId);

  List<Posting> postingsByRun(UUID runId);

  List<Posting> postingsByAction(UUID actionId);
}
