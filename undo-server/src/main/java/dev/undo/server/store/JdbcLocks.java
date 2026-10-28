package dev.undo.server.store;

import dev.undo.core.port.Clock;
import dev.undo.core.port.Locks;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fenced job leases in table job_leases (e.g. recon:shopfront). */
public final class JdbcLocks implements Locks {
  private final JdbcClient db;
  private final Clock clock;

  public JdbcLocks(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Override
  public Optional<Long> tryAcquire(String name, String owner, Duration ttl) {
    Timestamp now = Timestamp.from(clock.now());
    Timestamp until = Timestamp.from(clock.now().plus(ttl));
    db.sql(
            "insert into job_leases(name, owner, expires_at, fence) values (?, null, null, 0) on conflict (name) do nothing")
        .param(name)
        .update();
    return db.sql(
            """
            update job_leases set owner = ?, expires_at = ?, fence = fence + 1
            where name = ? and (expires_at is null or expires_at < ?) returning fence
            """)
        .params(owner, until, name, now)
        .query(Long.class)
        .optional();
  }

  @Override
  public boolean renew(String name, long fence, Duration ttl) {
    return db.sql("update job_leases set expires_at = ? where name = ? and fence = ?")
            .params(Timestamp.from(clock.now().plus(ttl)), name, fence)
            .update()
        == 1;
  }

  @Override
  public void release(String name, long fence) {
    db.sql("update job_leases set owner = null, expires_at = null where name = ? and fence = ?")
        .params(name, fence)
        .update();
  }
}
