package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.Agent;
import dev.undo.core.port.repo.AgentRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgAgentRepo implements AgentRepo {
  private final JdbcClient db;

  PgAgentRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void insert(Agent a) {
    db.sql("insert into agents(id, name, created_at) values (?, ?, ?)")
        .params(a.id, a.name, ts(a.createdAt))
        .update();
  }

  @Override
  public Optional<Agent> get(UUID id) {
    return db.sql("select * from agents where id = ?").param(id).query(AGENT).optional();
  }

  @Override
  public Optional<Agent> byName(String name) {
    return db.sql("select * from agents where name = ?").param(name).query(AGENT).optional();
  }

  @Override
  public List<Agent> all() {
    return db.sql("select * from agents order by name").query(AGENT).list();
  }
}
