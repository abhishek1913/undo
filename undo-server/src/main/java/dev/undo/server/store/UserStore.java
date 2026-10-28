package dev.undo.server.store;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Console users (table users): bcrypt hashes and roles. */
@Component
public class UserStore {
  public record UserRow(
      UUID id, String email, String passwordHash, List<String> roles, Instant createdAt) {}

  private final JdbcClient db;

  public UserStore(JdbcClient db) {
    this.db = db;
  }

  private static final RowMapper<UserRow> ROW =
      (rs, i) -> {
        Array a = rs.getArray("roles");
        return new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            Arrays.asList((String[]) a.getArray()),
            rs.getTimestamp("created_at").toInstant());
      };

  public Optional<UserRow> byEmail(String email) {
    return db.sql("select * from users where email = ?").param(email).query(ROW).optional();
  }

  public Optional<UserRow> byId(UUID id) {
    return db.sql("select * from users where id = ?").param(id).query(ROW).optional();
  }

  public long count() {
    return db.sql("select count(*) from users").query(Long.class).single();
  }

  public void insert(UUID id, String email, String passwordHash, List<String> roles, Instant now) {
    db.sql(
            "insert into users(id, email, password_hash, roles, created_at) values (?, ?, ?, ?, ?) on conflict (email) do nothing")
        .params(id, email, passwordHash, roles.toArray(new String[0]), Timestamp.from(now))
        .update();
  }
}
