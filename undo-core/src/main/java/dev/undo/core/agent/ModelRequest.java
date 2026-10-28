package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.ToolSpec;
import java.util.List;

/**
 * One model call. {@code messages} are Messages API message objects ({role, content}) rebuilt
 * verbatim from the stored transcript.
 */
public record ModelRequest(
    String model,
    String system,
    List<ToolSpec> tools,
    List<JsonNode> messages,
    int maxTokens,
    String effort,
    /** Hosted task context the scripted model needs (task input, task seq). */
    JsonNode taskInput,
    int taskSeq) {}
