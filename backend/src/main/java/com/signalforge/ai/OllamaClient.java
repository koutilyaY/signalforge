package com.signalforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.platform.config.SignalForgeProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal client for a locally-running Ollama.
 *
 * <p>Hand-rolled on {@code java.net.http.HttpClient} rather than pulling in an AI SDK. The
 * integration is one POST to {@code /api/generate}; a framework would add a dependency, a
 * dependency's transitive tree, and a version to keep current, in exchange for nothing.
 *
 * <p><b>Every failure returns empty rather than throwing.</b> The AI assistant is optional by
 * design, and the calling code must never have a reason to catch. If Ollama is not running, not
 * installed, slow, or returns nonsense, the incident page simply has no AI panel.
 */
@Component
public class OllamaClient {

  private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final SignalForgeProperties.Ai config;

  public OllamaClient(ObjectMapper objectMapper, SignalForgeProperties properties) {
    this.objectMapper = objectMapper;
    this.config = properties.ai();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  public boolean isEnabled() {
    return config.enabled();
  }

  public String model() {
    return config.model();
  }

  /**
   * Cheap liveness probe. Used to decide whether to offer the feature at all, so it must be fast
   * and must never block a page render for long.
   */
  public boolean isReachable() {
    if (!config.enabled()) {
      return false;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/tags"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (Exception e) {
      log.debug("Ollama not reachable at {}: {}", config.baseUrl(), e.toString());
      return false;
    }
  }

  /**
   * Sends a prompt and returns the raw completion.
   *
   * @return the model's text, or empty on any failure whatsoever
   */
  public Optional<String> generate(String systemPrompt, String userPrompt) {
    if (!config.enabled()) {
      return Optional.empty();
    }

    try {
      Map<String, Object> payload =
          Map.of(
              "model",
              config.model(),
              "prompt",
              userPrompt,
              "system",
              systemPrompt,
              "stream",
              false,
              // Low temperature: this task is extraction and summarisation of
              // supplied facts, not creative writing. Anything higher invites
              // exactly the embellishment the whole design is trying to prevent.
              "options",
              Map.of("temperature", config.temperature(), "num_predict", 800));

      HttpRequest request =
          HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/generate"))
              .timeout(config.timeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Ollama returned HTTP {} - skipping AI summary", response.statusCode());
        return Optional.empty();
      }

      JsonNode body = objectMapper.readTree(response.body());
      String text = body.path("response").asText(null);
      return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      // Deliberately broad. There is no failure here worth propagating: the
      // caller's only sensible response to any of them is "no AI summary".
      log.warn("AI summary generation failed ({}). Incident handling is unaffected.", e.toString());
      return Optional.empty();
    }
  }
}
