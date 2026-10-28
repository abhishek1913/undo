package dev.undo.core.port.repo;

import dev.undo.core.model.Agent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepo {
  void insert(Agent agent);

  Optional<Agent> get(UUID id);

  Optional<Agent> byName(String name);

  List<Agent> all();
}
