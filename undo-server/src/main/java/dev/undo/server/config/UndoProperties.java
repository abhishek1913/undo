package dev.undo.server.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** undo.* settings (Section 26). */
@ConfigurationProperties("undo")
public record UndoProperties(
    @DefaultValue("api,worker") List<String> roles,
    String nodeId,
    @DefaultValue Execution execution,
    @DefaultValue Resolve resolve,
    @DefaultValue Runs runs,
    @DefaultValue Approvals approvals,
    @DefaultValue Revert revert,
    @DefaultValue Recon recon,
    @DefaultValue Webhooks webhooks,
    @DefaultValue Connectors connectors,
    @DefaultValue Agent agent,
    @DefaultValue Auth auth,
    @DefaultValue Demo demo,
    @DefaultValue Chaos chaos,
    @DefaultValue Ratelimit ratelimit,
    @DefaultValue Loops loops) {

  public boolean api() {
    return roles.contains("api");
  }

  public boolean worker() {
    return roles.contains("worker");
  }

  public record Execution(
      @DefaultValue("true") boolean inline,
      @DefaultValue("PT30S") Duration lease,
      @DefaultValue("PT10S") Duration providerTimeout,
      @DefaultValue("8") int maxAttempts,
      @DefaultValue("PT0.5S") Duration backoffBase,
      @DefaultValue("PT30S") Duration backoffCap) {}

  public record Resolve(
      @DefaultValue("PT60S") Duration lookupGrace, @DefaultValue("PT1H") Duration maxUnknownAge) {}

  public record Runs(
      @DefaultValue("PT30M") Duration idleTimeout,
      @DefaultValue("PT60S") Duration hostedLease,
      @DefaultValue("4") int maxHostedPerWorker) {}

  public record Approvals(@DefaultValue("PT24H") Duration defaultExpiry) {}

  public record Revert(@DefaultValue("PT15M") Duration previewTtl) {}

  public record Recon(
      @DefaultValue("true") boolean schedulerEnabled,
      @DefaultValue("PT5M") Duration interval,
      @DefaultValue("PT24H") Duration window,
      @DefaultValue("PT10M") Duration overlap) {}

  public record Webhooks(@DefaultValue Shopfront shopfront) {
    public record Shopfront(@DefaultValue("") String secret) {}
  }

  public record Connectors(@DefaultValue ShopfrontConn shopfront) {
    public record ShopfrontConn(
        @DefaultValue("http://shopfront:8090") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String adminToken,
        @DefaultValue("PT15M") Duration exportLag) {}
  }

  public record Agent(
      @DefaultValue("scripted") String mode,
      @DefaultValue("claude-opus-5-5") String model,
      @DefaultValue("medium") String effort,
      @DefaultValue("16000") int maxTokens,
      @DefaultValue("12") int maxTurnsPerTask,
      @DefaultValue("default") String fallbacks,
      /** Tests only: a stub Messages API. Empty = api.anthropic.com. */
      @DefaultValue("") String baseUrl) {}

  public record Auth(
      @DefaultValue("") String jwtSecret,
      String bootstrapAdminEmail,
      String bootstrapAdminPassword) {}

  public record Demo(
      @DefaultValue("false") boolean enabled,
      String agentKey,
      @DefaultValue("http://undo-worker:8081/internal/halt") String workerHaltUrl) {}

  public record Chaos(@DefaultValue("0.0") double haltProbability) {}

  public record Ratelimit(@DefaultValue("50") double perKeyRps, @DefaultValue("100") long burst) {}

  /** Worker loop intervals (Section 7.6). */
  public record Loops(
      @DefaultValue("PT0.2S") Duration executor,
      @DefaultValue("PT1S") Duration sweeper,
      @DefaultValue("PT0.5S") Duration resolver,
      @DefaultValue("PT10S") Duration approvalExpiry,
      @DefaultValue("PT0.3S") Duration revert,
      @DefaultValue("PT1S") Duration hostedRunner,
      @DefaultValue("PT5S") Duration webhookRetry,
      @DefaultValue("PT60S") Duration idleCloser) {}
}
