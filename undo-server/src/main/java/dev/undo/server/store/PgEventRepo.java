package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.Event;
import dev.undo.core.port.repo.EventRepo;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgEventRepo implements EventRepo {
  private final JdbcClient db;

  PgEventRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public Event append(Event e) {
    return db.sql(
            "insert into events(run_id, action_id, type, data, at, prev_hash, hash) values (?, ?, ?, ?::jsonb, ?, ?, ?) returning *")
        .params(e.runId, e.actionId, e.type, json(e.data), ts(e.at), e.prevHash, e.hash)
        .query(EVENT)
        .single();
  }

  @Override
  public long maxSeq() {
    return db.sql("select coalesce(max(seq), 0) from events").query(Long.class).single();
  }

  @Override
  public List<Event> after(long afterSeq, UUID runId, int limit) {
    return db.sql(
            "select * from events where seq > ? and (cast(? as uuid) is null or run_id = ?) order by seq limit ?")
        .params(afterSeq, runId, runId, limit)
        .query(EVENT)
        .list();
  }
}
