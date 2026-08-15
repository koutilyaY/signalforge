package com.signalforge.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for everything SignalForge-specific, bound from {@code signalforge.*}.
 *
 * <p>Validated at startup so a misconfigured deployment fails immediately and loudly rather than at
 * the first request.
 */
@ConfigurationProperties(prefix = "signalforge")
@Validated
public record SignalForgeProperties(
    @Valid @NotNull Security security,
    @Valid @NotNull Ingestion ingestion,
    @Valid @NotNull Detection detection,
    @Valid @NotNull Correlation correlation,
    @Valid @NotNull Ai ai,
    @Valid @NotNull Cache cache) {

  public record Security(
      /**
       * HS256 signing key, base64 or raw. Must be at least 32 bytes of real entropy - {@link
       * com.signalforge.iam.auth.JwtService} refuses to start otherwise.
       */
      @NotBlank String jwtSecret,
      @NotNull Duration accessTokenTtl,
      @NotNull Duration refreshTokenTtl,
      @NotBlank String jwtIssuer,
      /** Origins allowed to call the API from a browser. */
      List<String> allowedOrigins,
      @Min(4) int bcryptStrength,
      /** Failed logins allowed per email per window before lockout. */
      @Min(1) int loginMaxAttempts,
      @NotNull Duration loginLockoutWindow) {}

  public record Ingestion(
      /** Default per-organization ceiling; an org row may override it downward or upward. */
      @Min(1) int defaultRateLimitPerMinute,
      /** Max events in one batch request. */
      @Min(1) int maxBatchSize,
      /**
       * Events older than this or further in the future than {@code maxClockSkew} are rejected - a
       * producer with a broken clock otherwise poisons every time-window computation.
       */
      @NotNull Duration maxEventAge,
      @NotNull Duration maxClockSkew) {}

  public record Detection(
      boolean enabled,
      /** How often the rule evaluator sweeps. Detection latency is bounded below by this. */
      @NotNull Duration evaluationInterval,
      /** Rules are skipped for services with no telemetry in this window - nothing to judge. */
      @NotNull Duration idleServiceGrace,
      @Min(1) int maxRulesPerEvaluation) {}

  public record Correlation(
      /** How far back to look for deployments that could explain an incident. */
      @NotNull Duration deploymentLookback,
      /** Window either side of incident start used to gather correlated telemetry. */
      @NotNull Duration evidenceWindow,
      @Min(1) int maxRelatedServices,
      @Min(1) int maxTraceSamples,
      @Min(1) int maxErrorSignatures) {}

  public record Ai(
      /**
       * When false (the default), no LLM is contacted and the platform behaves exactly as it does
       * with Ollama unreachable. Detection, correlation and incident management never depend on
       * this flag.
       */
      boolean enabled,
      @NotBlank String baseUrl,
      @NotBlank String model,
      @NotNull Duration timeout,
      @Min(1) int maxEvidenceItems,
      double temperature) {}

  public record Cache(
      @NotNull Duration serviceHealthTtl,
      @NotNull Duration organizationSettingsTtl,
      @NotNull Duration dashboardAggregateTtl,
      @NotNull Duration incidentDedupeLockTtl) {}
}
