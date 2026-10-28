package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class RevertPlan extends Row<RevertPlan> {
  public UUID id;
  public long number;
  public UUID runId;
  public PlanScope scope;
  public UUID requestedBy;
  public PlanStatus status;
  public JsonNode summary;
  public String leaseOwner;
  public Instant leaseExpiresAt;
  public long leaseFence;
  public Instant createdAt;
  public Instant startedAt;
  public Instant completedAt;
}
