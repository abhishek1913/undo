package dev.undo.server.config;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.undo.connector.shopfront.ShopfrontApi;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.engine.ApprovalService;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RecoveryService;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoConnector;
import dev.undo.core.engine.WebhookService;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.BugFlags;
import dev.undo.core.port.Clock;
import dev.undo.core.port.CrashPoints;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import dev.undo.core.recon.ReconService;
import dev.undo.core.revert.RevertService;
import dev.undo.server.http.HttpShopfrontApi;
import dev.undo.server.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds the core ports to real implementations and builds the engine services. */
@Configuration
public class EngineBeans {
  /** The name this process uses in leases, attempts and logs. */
  public record NodeId(String value) {}

  @Bean
  NodeId nodeId(UndoProperties p) throws Exception {
    String id = p.nodeId();
    if (id == null || id.isBlank()) {
      id = InetAddress.getLocalHost().getHostName();
    }
    return new NodeId(id);
  }

  /** Postgres keeps microseconds; the clock does too, so values round-trip exactly. */
  @Bean
  Clock clock() {
    return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @Bean
  Ids ids() {
    return UuidCreator::getTimeOrderedEpoch;
  }

  @Bean
  CrashPoints crashPoints(UndoProperties p) {
    return p.chaos().haltProbability() > 0
        ? new ChaosCrashPoints(p.chaos().haltProbability())
        : CrashPoints.NONE;
  }

  @Bean
  ShopfrontApi shopfrontApi(UndoProperties p, Metrics metrics) {
    return new HttpShopfrontApi(
        p.connectors().shopfront().baseUrl(), p.execution().providerTimeout(), metrics);
  }

  @Bean
  ShopfrontConnector shopfrontConnector(ShopfrontApi api, UndoProperties p, Clock clock) {
    EngineConfig cfg = engineConfig(p);
    return new ShopfrontConnector(
        api,
        new ShopfrontConnector.Config(
            p.connectors().shopfront().apiKey(),
            p.webhooks().shopfront().secret(),
            p.connectors().shopfront().exportLag(),
            cfg.factsTimeout()),
        clock);
  }

  @Bean
  Registry registry(ShopfrontConnector shopfront, Store store) {
    return new Registry(List.of(shopfront, new UndoConnector(store)));
  }

  @Bean
  LocalNotifier localNotifier(Clock clock) {
    return new LocalNotifier(clock);
  }

  @Bean
  Metrics metrics(
      MeterRegistry registry, org.springframework.beans.factory.ObjectProvider<Tracer> tracer) {
    return new MicrometerMetrics(registry, tracer.getIfAvailable());
  }

  @Bean
  Ports ports(
      Clock clock,
      Ids ids,
      CrashPoints crash,
      Store store,
      Registry registry,
      LocalNotifier notifier,
      Metrics metrics) {
    return new Ports(
        clock,
        ids,
        crash,
        store,
        registry,
        notifier,
        BugFlags.NONE,
        () -> ThreadLocalRandom.current().nextDouble(),
        notifier,
        metrics);
  }

  @Bean
  EngineConfig engineConfig(UndoProperties p) {
    EngineConfig d = EngineConfig.defaults();
    return new EngineConfig(
        p.execution().lease(),
        p.execution().providerTimeout(),
        p.execution().maxAttempts(),
        p.execution().backoffBase(),
        p.execution().backoffCap(),
        p.resolve().lookupGrace(),
        p.resolve().maxUnknownAge(),
        d.nativeKeySafetyMargin(),
        d.factsTimeout(),
        p.approvals().defaultExpiry(),
        p.revert().previewTtl(),
        p.runs().hostedLease(),
        p.runs().idleTimeout(),
        p.execution().inline(),
        d.defaultWaitMs(),
        d.maxWaitMs());
  }

  @Bean
  PolicyService policyService(Store store, Ids ids, Clock clock, Registry registry) {
    return new PolicyService(store, ids, clock, registry);
  }

  @Bean
  ExecutionService executionService(Ports p, EngineConfig cfg) {
    return new ExecutionService(p, cfg);
  }

  @Bean
  ProposalService proposalService(
      Ports p, PolicyService policies, ExecutionService ex, EngineConfig cfg) {
    return new ProposalService(p, policies, ex, cfg);
  }

  @Bean
  RecoveryService recoveryService(Ports p, ExecutionService ex, EngineConfig cfg) {
    return new RecoveryService(p, ex, cfg);
  }

  @Bean
  ApprovalService approvalService(Ports p) {
    return new ApprovalService(p);
  }

  @Bean
  RunService runService(Ports p, PolicyService policies, EngineConfig cfg) {
    return new RunService(p, policies, cfg);
  }

  @Bean
  WebhookService webhookService(Ports p) {
    return new WebhookService(p);
  }

  @Bean
  RevertService revertService(
      Ports p,
      RunService runs,
      ProposalService proposals,
      WebhookService webhooks,
      EngineConfig cfg) {
    return new RevertService(p, runs, proposals, webhooks, cfg);
  }

  @Bean
  ReconService reconService(
      Ports p,
      WebhookService webhooks,
      ExecutionService ex,
      dev.undo.core.port.Locks locks,
      UndoProperties props) {
    ReconService.Config d = ReconService.Config.defaults();
    UndoProperties.Recon r = props.recon();
    return new ReconService(
        p,
        webhooks,
        ex,
        locks,
        new ReconService.Config(
            r.interval(),
            r.window(),
            r.overlap(),
            d.settle(),
            d.leaseTtl(),
            d.unmatchedWebhookAfter()));
  }

  @Bean
  dev.undo.core.agent.ScriptedModelClient scriptedModelClient(
      dev.undo.server.agent.HttpGroundTruth truth) {
    return new dev.undo.core.agent.ScriptedModelClient(truth);
  }

  /** Claude only when ANTHROPIC_API_KEY is set (the app runs fully without it). */
  @Bean
  dev.undo.server.agent.ModelClients modelClients(
      dev.undo.core.agent.ScriptedModelClient scripted, UndoProperties p) {
    String key = System.getenv("ANTHROPIC_API_KEY");
    dev.undo.core.port.ModelClient claude = null;
    if (key != null && !key.isBlank()) {
      claude =
          dev.undo.server.llm.ClaudeModelClient.create(
              key, p.agent().baseUrl(), "default".equals(p.agent().fallbacks()));
    }
    return new dev.undo.server.agent.ModelClients(scripted, claude);
  }

  @Bean
  dev.undo.core.agent.AgentRunner agentRunner(
      Ports p,
      ProposalService proposals,
      RunService runs,
      dev.undo.server.agent.ModelClients models,
      UndoProperties props) {
    dev.undo.core.agent.AgentRunner.Config d = dev.undo.core.agent.AgentRunner.Config.defaults();
    UndoProperties.Agent a = props.agent();
    return new dev.undo.core.agent.AgentRunner(
        p,
        proposals,
        runs,
        models,
        new dev.undo.core.agent.AgentRunner.Config(
            a.model(),
            a.effort(),
            a.maxTokens(),
            a.maxTurnsPerTask(),
            d.lease(),
            d.heartbeat(),
            d.toolWaitMs(),
            d.modelBackoff()));
  }
}
