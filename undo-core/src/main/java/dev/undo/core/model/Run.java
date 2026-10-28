package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class Run extends Row<Run> {
  public UUID id;
  public long number;
  public UUID agentId;
  public RunKind kind;
  public RunStatus status;
  public String title;
  public String scenario;
  public String modelMode;
  public JsonNode labels;
  public UUID policyVersionId;
  public String currency = "INR";
  // Budget columns change only through RunRepo.reserve/release/settle/addTokens.
  public long moneyCapMinor;
  public long moneyReservedMinor;
  public long moneySpentMinor;
  public int actionCap;
  public int actionCount;
  public long llmTokenCap;
  public long llmTokensUsed;
  public String mcpSessionId;
  public String leaseOwner;
  public Instant leaseExpiresAt;
  public long leaseFence;
  public String auditHeadHash;
  public String failureReason;
  public Instant lastActivityAt;
  public Instant createdAt;
  public Instant completedAt;
  public Instant frozenAt;

  public String display() {
    return "Run #" + number;
  }
}
