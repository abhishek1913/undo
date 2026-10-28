package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class PolicyVersion extends Row<PolicyVersion> {
  public UUID id;
  public String name;
  public int version;
  public String sourceYaml;
  public JsonNode compiled;
  public String sha256;
  public boolean active;
  public String createdBy;
  public Instant createdAt;
}
