package dev.undo.core.port;

import dev.undo.core.agent.ModelException;
import dev.undo.core.agent.ModelRequest;
import dev.undo.core.agent.ModelResponse;

/** Hosted runner model: Claude adapter or scripted. */
public interface ModelClient {
  ModelResponse create(ModelRequest request) throws ModelException;
}
