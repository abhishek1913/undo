package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.PolicyVersion;
import dev.undo.core.port.repo.PolicyRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgPolicyRepo implements PolicyRepo {
  private final JdbcClient db;

  PgPolicyRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void insert(PolicyVersion p) {
    db.sql(
            """
            insert into policy_versions(id, name, version, source_yaml, compiled, sha256, active, created_by, created_at)
            values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)
        .params(
            p.id,
            p.name,
            p.version,
            p.sourceYaml,
            json(p.compiled),
            p.sha256,
            p.active,
            p.createdBy,
            ts(p.createdAt))
        .update();
  }

  @Override
  public Optional<PolicyVersion> get(UUID id) {
    return db.sql("select * from policy_versions where id = ?").param(id).query(POLICY).optional();
  }

  @Override
  public Optional<PolicyVersion> active(String name) {
    return db.sql("select * from policy_versions where name = ? and active")
        .param(name)
        .query(POLICY)
        .optional();
  }

  @Override
  public Optional<PolicyVersion> version(String name, int version) {
    return db.sql("select * from policy_versions where name = ? and version = ?")
        .params(name, version)
        .query(POLICY)
        .optional();
  }

  @Override
  public List<PolicyVersion> all() {
    return db.sql("select * from policy_versions order by name, version").query(POLICY).list();
  }

  @Override
  public int maxVersion(String name) {
    return db.sql("select coalesce(max(version), 0) from policy_versions where name = ?")
        .param(name)
        .query(Integer.class)
        .single();
  }

  @Override
  public void activate(UUID id) {
    db.sql(
            "update policy_versions set active = false where name = (select name from policy_versions where id = ?) and active and id <> ?")
        .params(id, id)
        .update();
    db.sql("update policy_versions set active = true where id = ?").param(id).update();
  }
}
