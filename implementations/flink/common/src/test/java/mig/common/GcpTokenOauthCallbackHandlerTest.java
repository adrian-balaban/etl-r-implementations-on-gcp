package mig.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Managed Kafka rejects a bare access token — it wants a JWT-shaped,
 * dot-joined, base64url value. Verifies {@link GcpTokenOauthCallbackHandler#kafkaToken} builds
 * exactly that shape, without needing a real GCP token or a live broker.
 */
class GcpTokenOauthCallbackHandlerTest {

  @Test
  void kafkaTokenIsThreePartJwtShapedBase64Url() throws Exception {
    Instant expiry = Instant.now().plusSeconds(3600);
    String token = GcpTokenOauthCallbackHandler.kafkaToken("fake-access-token", "sa@project.iam.gserviceaccount.com", expiry);

    String[] parts = token.split("\\.");
    assertThat(parts).hasSize(3);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode header = mapper.readTree(decode(parts[0]));
    JsonNode claims = mapper.readTree(decode(parts[1]));

    assertThat(header.get("typ").asText()).isEqualTo("JWT");
    assertThat(header.get("alg").asText()).isEqualTo("GOOG_OAUTH2_TOKEN");
    assertThat(claims.get("scope").asText()).isEqualTo("kafka");
    assertThat(claims.get("sub").asText()).isEqualTo("sa@project.iam.gserviceaccount.com");
    assertThat(claims.get("exp").asLong()).isEqualTo(expiry.getEpochSecond());

    // The third segment is the raw access token itself, base64url-encoded (not re-JSON'd).
    assertThat(decode(parts[2])).isEqualTo("fake-access-token");
  }

  private static String decode(String base64url) {
    return new String(Base64.getUrlDecoder().decode(base64url), StandardCharsets.UTF_8);
  }
}
