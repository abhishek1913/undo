package dev.undo.core.port;

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

/** Repositories available inside a transaction. */
public interface TxContext {
  RunRepo runs();

  TaskRepo tasks();

  ActionRepo actions();

  AttemptRepo attempts();

  ApprovalRepo approvals();

  TurnRepo turns();

  LedgerRepo ledger();

  RevertRepo reverts();

  WebhookRepo webhooks();

  ReconRepo recon();

  EventRepo events();

  PolicyRepo policies();

  /** Not in the PRD list; agents are needed for the policy root {@code agent.name}. ADR-001. */
  AgentRepo agents();
}
