package dev.undo.core.port.repo;

import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.Severity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconRepo {
  ReconRun insertRun(ReconRun run);

  void updateRun(ReconRun run);

  Optional<ReconRun> lastCompleted(String connector);

  List<ReconRun> runs(int limit);

  /** OPEN or ACKNOWLEDGED finding with this fingerprint. */
  Optional<ReconFinding> openByFingerprint(String fingerprint);

  /** recon_open_fingerprint is enforced. */
  void insertFinding(ReconFinding finding);

  void updateFinding(ReconFinding finding);

  Optional<ReconFinding> finding(UUID id);

  /** Findings of one action, newest first. */
  List<ReconFinding> findingsOfAction(UUID actionId);

  /** OPEN and ACKNOWLEDGED findings counted by type and severity (metric, sidebar badge). */
  List<OpenCount> openCounts();

  record OpenCount(FindingType type, Severity severity, long count) {}

  /** Newest first. Null filters are ignored. */
  List<ReconFinding> findings(FindingStatus status, FindingType type, int limit);
}
