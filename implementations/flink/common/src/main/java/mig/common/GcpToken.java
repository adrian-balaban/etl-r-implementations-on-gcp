package mig.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OAuth access tokens for GCP, off the metadata server. Ported from {@code
 * apps/common/src/main/java/ro/mig/common/GcpToken.java} in
 * beam-dataflow — the one piece
 * this repo's local-only common module never needed until now.
 *
 * <p>A GKE pod under Workload Identity resolves the metadata server to its own token; off
 * GCP (a laptop, this repo's ITs) the metadata server does not exist, {@link #supplier()}'s
 * fetch fails, and the caller gets {@code null} — which is what {@link
 * GcpTokenOauthCallbackHandler} treats as "not running on GCP, use PLAINTEXT".
 */
public final class GcpToken {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String METADATA_URL =
      "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";

  /** Refresh early: a token that expires mid-request is the same as no token. */
  private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private String cached;
  private Instant expiresAt = Instant.EPOCH;

  private GcpToken() {}

  /** An access token and the instant Google says it stops working. */
  public record Token(String value, Instant expiry) {}

  /** A supplier returning a metadata-server access token, or {@code null} off GCP. */
  public static Supplier<String> supplier() {
    GcpToken instance = new GcpToken();
    return instance::get;
  }

  /**
   * Like {@link #supplier()}, but with the token's real expiry. The metadata server hands out
   * a cached token until 5 minutes before it expires, so a fresh fetch may live 5 to 60 minutes.
   */
  public static Supplier<Token> tokenSupplier() {
    GcpToken instance = new GcpToken();
    return () -> {
      String value = instance.get();
      return value == null ? null : new Token(value, instance.expiresAt.plus(EXPIRY_MARGIN));
    };
  }

  private synchronized String get() {
    if (cached != null && Instant.now().isBefore(expiresAt)) {
      return cached;
    }
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(METADATA_URL))
              .header("Metadata-Flavor", "Google")
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        return null;
      }
      JsonNode body = MAPPER.readTree(res.body());
      String token = body.path("access_token").asText(null);
      long expiresIn = body.path("expires_in").asLong(0);
      if (token == null || token.isBlank()) {
        return null;
      }
      cached = token;
      expiresAt = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
      return cached;
    } catch (Exception e) {
      // Off GCP there is no metadata server, and that is not an error — the caller
      // falls back to PLAINTEXT (redpanda locally, this repo's ITs).
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
