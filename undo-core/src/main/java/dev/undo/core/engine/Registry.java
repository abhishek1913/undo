package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.port.ConnectorRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tools registered by connectors at startup (FR-TOOL-1, FR-TOOL-4). */
public final class Registry implements ConnectorRegistry {
  private final Map<String, Connector> connectors = new LinkedHashMap<>();
  private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

  public Registry(List<Connector> list) {
    for (Connector c : list) {
      connectors.put(c.id(), c);
      for (ToolSpec t : c.tools()) {
        if (tools.containsKey(t.name())) {
          throw new IllegalStateException("Duplicate tool name across connectors: " + t.name());
        }
        checkSchema(t);
        tools.put(t.name(), t);
      }
    }
  }

  /** additionalProperties false, and every property listed in required. */
  static void checkSchema(ToolSpec t) {
    JsonNode s = t.inputSchema();
    if (!"object".equals(s.path("type").asText())
        || !s.path("additionalProperties").isBoolean()
        || s.get("additionalProperties").asBoolean()) {
      throw new IllegalStateException(
          t.name() + ": schema must be an object with additionalProperties false");
    }
    Set<String> required = new HashSet<>();
    s.path("required").forEach(n -> required.add(n.asText()));
    for (Iterator<String> it = s.path("properties").fieldNames(); it.hasNext(); ) {
      String prop = it.next();
      if (!required.contains(prop)) {
        throw new IllegalStateException(
            t.name() + ": property " + prop + " must be listed in required");
      }
    }
  }

  @Override
  public Connector connector(String id) {
    Connector c = connectors.get(id);
    if (c == null) {
      throw new IllegalArgumentException("no connector " + id);
    }
    return c;
  }

  @Override
  public ToolSpec tool(String name) {
    return tools.get(name);
  }

  @Override
  public List<ToolSpec> all() {
    return new ArrayList<>(tools.values());
  }
}
