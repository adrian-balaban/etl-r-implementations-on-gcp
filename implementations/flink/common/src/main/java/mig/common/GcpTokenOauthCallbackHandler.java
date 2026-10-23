package mig.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bridges Kafka's OAUTHBEARER SASL mechanism to a Google access token, so {@code recon},
 * {@code target-system-mock} and the Flink jobs' Kafka sink can authenticate to GCP Managed
 * Service for Apache Kafka over SASL_SSL. Ported from {@code
 * apps/common/src/main/java/ro/mig/common/GcpTokenOauthCallbackHandler.java} — that branch was cut when the lane was first ported and is restored here now that the lane targets GCP.
 *
 * <p>Kafka instantiates this reflectively from {@code sasl.login.callback.handler.class}, so
 * it needs a public no-arg constructor. The JAAS {@code OAuthBearerLoginModule} is still
 * required in {@code sasl.jaas.config} even with a custom handler — without it: "No login
 * module found for OAUTHBEARER".
 */
public final class GcpTokenOauthCallbackHandler implements AuthenticateCallbackHandler {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String METADATA_EMAIL_URL =
      "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email";

  private Supplier<GcpToken.Token> tokenSupplier;

  public GcpTokenOauthCallbackHandler() {}

  @Override
  public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
    this.tokenSupplier = GcpToken.tokenSupplier();
  }

  @Override
  public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
    for (Callback callback : callbacks) {
      if (!(callback instanceof OAuthBearerTokenCallback cb)) {
        throw new UnsupportedCallbackException(callback, "Only OAuthBearerTokenCallback is supported.");
      }
      GcpToken.Token access = tokenSupplier == null ? null : tokenSupplier.get();
      String token = access == null ? null : access.value();
      if (token == null || token.isBlank()) {
        throw new UnsupportedCallbackException(
            cb, "No Google access token available for OAUTHBEARER — is this running on GCP?");
      }
      String principal = principal();
      if (principal == null || principal.isBlank()) {
        throw new UnsupportedCallbackException(
            cb,
            "No Kafka principal available — set GOOGLE_MANAGED_KAFKA_AUTH_PRINCIPAL to the "
                + "service-account email, or run where the metadata server can name it.");
      }
      // The token's real expiry, not "an hour from now": the metadata server may hand back a token
      // with 5 minutes left. Claiming an hour makes Kafka refresh after the token has died.
      Instant expiry = access.expiry();
      cb.token(new GoogleOAuthBearerToken(kafkaToken(token, principal, expiry), principal, expiry));
    }
  }

  @Override
  public void close() {
    // Nothing to release — the supplier is a plain function over the metadata server.
  }

  /**
   * The token Managed Kafka actually validates — <em>not</em> the raw access token
   *. Google's broker expects a three-part, dot-joined, base64url value
   * shaped like a JWT: {@code b64(header).b64(claims).b64(accessToken)}, with {@code
   * alg=GOOG_OAUTH2_TOKEN}, {@code scope=kafka} and {@code sub} set to the authenticating
   * service account's email. Handing it the bare access token authenticates nothing — the
   * broker answers "invalid credentials with SASL mechanism OAUTHBEARER", an error that
   * names the mechanism, not the encoding. Mirrors {@code
   * com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler}, reimplemented here rather
   * than pulled in as a dependency.
   */
  static String kafkaToken(String accessToken, String principal, Instant expiry) {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("typ", "JWT");
    header.put("alg", "GOOG_OAUTH2_TOKEN");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("exp", expiry.getEpochSecond());
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("scope", "kafka");
    claims.put("sub", principal);
    return String.join(".", b64(json(header)), b64(json(claims)), b64(accessToken));
  }

  private static String json(Map<String, Object> value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("could not serialise the Kafka token claims", e);
    }
  }

  private static String b64(String data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String principal() {
    String fromEnv = System.getenv("GOOGLE_MANAGED_KAFKA_AUTH_PRINCIPAL");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(METADATA_EMAIL_URL))
              .header("Metadata-Flavor", "Google")
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      return res.statusCode() == 200 ? res.body().trim() : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static final class GoogleOAuthBearerToken implements OAuthBearerToken {
    private final String value;
    private final String principal;
    private final Instant expiry;

    GoogleOAuthBearerToken(String value, String principal, Instant expiry) {
      this.value = value;
      this.principal = principal;
      this.expiry = expiry;
    }

    @Override
    public String value() {
      return value;
    }

    @Override
    public Long startTimeMs() {
      return null;
    }

    @Override
    public long lifetimeMs() {
      return expiry.toEpochMilli();
    }

    @Override
    public Set<String> scope() {
      return Set.of("kafka");
    }

    @Override
    public String principalName() {
      return principal;
    }
  }
}
