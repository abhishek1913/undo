package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;

public record Remediation(String tool, JsonNode args, String description) {}
