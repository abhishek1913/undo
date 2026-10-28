package dev.undo.server.agent;

import dev.undo.core.agent.ModelException;
import dev.undo.core.agent.ScriptedModelClient;
import dev.undo.core.model.Run;
import dev.undo.core.port.ModelClient;
import java.util.function.Function;

/** Picks the model of a run from its model_mode; Claude only exists when a key is configured. */
public final class ModelClients implements Function<Run, ModelClient> {
  private final ScriptedModelClient scripted;
  private final ModelClient claude; // null without ANTHROPIC_API_KEY

  public ModelClients(ScriptedModelClient scripted, ModelClient claude) {
    this.scripted = scripted;
    this.claude = claude;
  }

  public boolean claudeAvailable() {
    return claude != null;
  }

  @Override
  public ModelClient apply(Run run) {
    if ("claude".equals(run.modelMode)) {
      return claude != null
          ? claude
          : req -> {
            throw new ModelException(
                ModelException.Kind.BAD_REQUEST, "no Anthropic API key configured", null);
          };
    }
    return scripted;
  }
}
