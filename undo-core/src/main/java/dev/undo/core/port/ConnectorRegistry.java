package dev.undo.core.port;

import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ToolSpec;
import java.util.List;

public interface ConnectorRegistry {
  Connector connector(String id);

  /** Returns the tool or null when it is not registered. */
  ToolSpec tool(String name);

  List<ToolSpec> all();
}
