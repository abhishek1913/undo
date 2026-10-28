package dev.undo.server.security;

import java.util.List;
import java.util.UUID;

/** The caller behind an agent API key. Agent keys can never approve or revert (FR-APR-2). */
public record AgentPrincipal(
    UUID keyId, UUID agentId, String agentName, List<String> allowedTools, String defaultPolicy) {
  public boolean mayUse(String tool) {
    return ApiKeys.allowed(allowedTools, tool);
  }
}
