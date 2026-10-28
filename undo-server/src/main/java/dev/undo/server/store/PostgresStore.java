package dev.undo.server.store;

import dev.undo.core.port.ConstraintViolationException;
import dev.undo.core.port.Store;
import dev.undo.core.port.TxContext;
import dev.undo.core.port.repo.ActionRepo;
import dev.undo.core.port.repo.AgentRepo;
import dev.undo.core.port.repo.ApprovalRepo;
import dev.undo.core.port.repo.AttemptRepo;
import dev.undo.core.port.repo.EventRepo;
import dev.undo.core.port.repo.LedgerRepo;
import dev.undo.core.port.repo.PolicyRepo;
import dev.undo.core.port.repo.ReconRepo;
import dev.undo.core.port.repo.RevertRepo;
import dev.undo.core.port.repo.RunRepo;
import dev.undo.core.port.repo.TaskRepo;
import dev.undo.core.port.repo.TurnRepo;
import dev.undo.core.port.repo.WebhookRepo;
import java.sql.SQLException;
import java.util.function.Function;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store on Postgres 17: one tx() = one READ COMMITTED transaction (Spring TransactionTemplate)
 * around JdbcClient repositories. Unique, check and trigger violations become {@link
 * ConstraintViolationException} with the DDL constraint name, the same as InMemoryStore.
 */
public final class PostgresStore implements Store {
  private final TransactionTemplate tt;
  private final TxContext ctx;

  public PostgresStore(JdbcClient db, PlatformTransactionManager tm) {
    this.tt = new TransactionTemplate(tm);
    tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    PgTaskRepo tasks = new PgTaskRepo(db);
    RunRepo runs = new PgRunRepo(db);
    ActionRepo actions = new PgActionRepo(db);
    AttemptRepo attempts = new PgAttemptRepo(db);
    ApprovalRepo approvals = new PgApprovalRepo(db);
    LedgerRepo ledger = new PgLedgerRepo(db);
    RevertRepo reverts = new PgRevertRepo(db);
    WebhookRepo webhooks = new PgWebhookRepo(db);
    ReconRepo recon = new PgReconRepo(db);
    EventRepo events = new PgEventRepo(db);
    PolicyRepo policies = new PgPolicyRepo(db);
    AgentRepo agents = new PgAgentRepo(db);
    this.ctx =
        new TxContext() {
          public RunRepo runs() {
            return runs;
          }

          public TaskRepo tasks() {
            return tasks;
          }

          public ActionRepo actions() {
            return actions;
          }

          public AttemptRepo attempts() {
            return attempts;
          }

          public ApprovalRepo approvals() {
            return approvals;
          }

          public TurnRepo turns() {
            return tasks;
          }

          public LedgerRepo ledger() {
            return ledger;
          }

          public RevertRepo reverts() {
            return reverts;
          }

          public WebhookRepo webhooks() {
            return webhooks;
          }

          public ReconRepo recon() {
            return recon;
          }

          public EventRepo events() {
            return events;
          }

          public PolicyRepo policies() {
            return policies;
          }

          public AgentRepo agents() {
            return agents;
          }
        };
  }

  @Override
  public <T> T tx(Function<TxContext, T> work) {
    try {
      return tt.execute(status -> work.apply(ctx));
    } catch (DataAccessException | TransactionException e) {
      ConstraintViolationException c = translate(e);
      if (c != null) {
        throw c;
      }
      throw e;
    }
  }

  /**
   * Maps Postgres integrity errors (23xxx, and the balance trigger's P0001) to constraint names.
   */
  static ConstraintViolationException translate(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof PSQLException p && p.getSQLState() != null) {
        String state = p.getSQLState();
        String constraint =
            p.getServerErrorMessage() == null ? null : p.getServerErrorMessage().getConstraint();
        String msg =
            p.getServerErrorMessage() == null
                ? p.getMessage()
                : p.getServerErrorMessage().getMessage();
        if (state.startsWith("23")) {
          return new ConstraintViolationException(constraint == null ? state : constraint, msg);
        }
        if (state.equals("P0001") && msg != null && msg.contains("is not balanced")) {
          return new ConstraintViolationException("postings_balanced", msg);
        }
        if (state.equals("P0001") && msg != null && msg.contains("append-only")) {
          return new ConstraintViolationException("append_only", msg);
        }
      }
      if (t instanceof SQLException s && t.getCause() == null && s.getNextException() != null) {
        return translate(s.getNextException());
      }
    }
    return null;
  }
}
