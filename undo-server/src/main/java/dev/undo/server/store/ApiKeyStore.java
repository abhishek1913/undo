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

/** Agent API keys (table api_keys). Only the SHA-256 of a key is stored (FR-SEC-1). */
@Component
public class ApiKeyStore {
  public record KeyRow(
      UUID id,
      UUID agentId,
      String agentName,
      String prefix,
      List<String> allowedTools,
      String defaultPolicy,
      Instant createdAt,
      Instant revokedAt) {}

  private final JdbcClient db;

  public ApiKeyStore(JdbcClient db) {
    this.db = db;
  }

  private static final RowMapper<KeyRow> ROW =
      (rs, i) -> {
        Array a = rs.getArray("allowed_tools");
        Timestamp rev = rs.getTimestamp("revoked_at");
        return new KeyRow(
            rs.getObject("id", UUID.class),
            rs.getObject("agent_id", UUID.class),
            rs.getString("agent_name"),
            rs.getString("prefix"),
            Arrays.asList((String[]) a.getArray()),
            rs.getString("default_policy"),
            rs.getTimestamp("created_at").toInstant(),
            rev == null ? null : rev.toInstant());
      };

  public void insert(
      UUID id,
      UUID agentId,
      String prefix,
      byte[] hash,
      List<String> allowedTools,
      String defaultPolicy,
      Instant now) {
    db.sql(
            "insert into api_keys(id, agent_id, prefix, key_hash, allowed_tools, default_policy, created_at) values (?, ?, ?, ?, ?, ?, ?)")
        .params(
            id,
            agentId,
            prefix,
            hash,
            allowedTools.toArray(new String[0]),
            defaultPolicy,
            Timestamp.from(now))
        .update();
  }

  public Optional<KeyRow> byHash(byte[] hash) {
    return db.sql(
            """
            select k.*, a.name agent_name from api_keys k join agents a on a.id = k.agent_id
            where k.key_hash = ? and k.revoked_at is null
            """)
        .param(hash)
        .query(ROW)
        .optional();
  }

  public List<KeyRow> byAgent(UUID agentId) {
    return db.sql(
            "select k.*, a.name agent_name from api_keys k join agents a on a.id = k.agent_id where k.agent_id = ? order by k.created_at")
        .param(agentId)
        .query(ROW)
        .list();
  }

  public boolean revoke(UUID keyId, Instant now) {
    return db.sql("update api_keys set revoked_at = ? where id = ? and revoked_at is null")
            .params(Timestamp.from(now), keyId)
            .update()
        == 1;
  }

  public long countForAgent(UUID agentId) {
    return db.sql("select count(*) from api_keys where agent_id = ?")
        .param(agentId)
        .query(Long.class)
        .single();
  }
}
