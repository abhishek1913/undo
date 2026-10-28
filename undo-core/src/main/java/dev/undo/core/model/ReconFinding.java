package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class ReconFinding extends Row<ReconFinding> {
  public UUID id;
  public UUID reconRunId;
  public FindingType type;
  public Severity severity;
  public UUID actionId;
  public String providerObjectRef;
  public JsonNode expected;
  public JsonNode actual;
  public FindingStatus status;
  public String fingerprint;
  public String resolutionNote;
  public UUID resolvedBy;
  public Instant resolvedAt;
  public Instant createdAt;
}
