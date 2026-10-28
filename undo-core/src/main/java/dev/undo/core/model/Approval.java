package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class Approval extends Row<Approval> {
  public UUID id;
  public UUID actionId;
  public UUID runId;
  public String argsHash;
  public String approvedArgsHash;
  public UUID policyVersionId;
  public JsonNode reasons;

  /** Never used for decisions (ArchUnit rule 5). */
  public JsonNode advisoryNote;

  public ApprovalStatus status;
  public Instant requestedAt;
  public Instant expiresAt;
  public UUID decidedBy;
  public Instant decidedAt;
  public String decisionNote;
}
