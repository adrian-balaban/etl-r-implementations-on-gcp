package mig.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the admin HTTP surface.
 *
 * <p>Drives {@code /__admin/*} with real HTTP requests against a real {@link HttpServer} on
 * an ephemeral local port — no reflection into private handler methods, no mocked
 * {@code HttpExchange}. The Kafka producer {@link TargetSystemMock}'s constructor creates is
 * never used here (construction alone does not contact a broker), so {@code "localhost:1"}
 * as the bootstrap string is safe: nothing in these tests calls {@code produce()}.
 */
class TargetSystemMockTest {

  private HttpServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void start() throws IOException {
    TargetSystemMock mock = new TargetSystemMock("localhost:1", "confirmations", "rejections");
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    mock.registerAdminEndpoints(server);
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void healthReportsOk() throws Exception {
    HttpResponse<String> resp = get("/__admin/health");
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).isEqualTo("{\"status\":\"ok\"}");
  }

  @Test
  void statsStartAtZero() throws Exception {
    HttpResponse<String> resp = get("/__admin/stats");
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(resp.body())
        .contains("\"received\" : 0")
        .contains("\"accepted\" : 0")
        .contains("\"duplicatesIgnored\" : 0")
        .contains("\"confirmationsPublished\" : 0")
        .contains("\"rejectionsPublished\" : 0")
        .contains("\"consumedFromTopic\" : 0")
        .contains("\"distinctAccounts\" : 0");
  }

  @Test
  void suppressNextConfirmationArmsTheOneShotFlag() throws Exception {
    HttpResponse<String> resp = post("/__admin/suppress-next-confirmation", "");
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).isEqualTo("{\"status\":\"suppress-next-confirmation-armed\"}");
  }

  @Test
  void resetRespondsOkAndStatsStayAtZero() throws Exception {
    HttpResponse<String> resp = post("/__admin/reset", "");
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).isEqualTo("{\"status\":\"reset\"}");

    // reset() after a fresh start is a no-op observably, but exercises every counter's
    // .set(0) and the map .clear() calls, not just handleStats' .get() reads.
    assertThat(get("/__admin/stats").body()).contains("\"received\" : 0");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
    return client.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The stats say WHICH process answered. Two reads of one process agree on
   * its identity; that is what lets a reader tell "this counter is small because the instance
   * restarted" from "this counter is wrong".
   */
  @Test
  void statsIdentifyTheProcessThatAnsweredAndItsConsumerPosition() throws Exception {
    com.fasterxml.jackson.databind.JsonNode first =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(get("/__admin/stats").body());
    com.fasterxml.jackson.databind.JsonNode second =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(get("/__admin/stats").body());

    assertThat(first.path("instanceId").asText()).isNotBlank();
    assertThat(second.path("instanceId").asText()).as("same process, same id").isEqualTo(first.path("instanceId").asText());
    assertThat(first.path("startedAt").asText()).isNotBlank();
    assertThat(first.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
    // no consumer loop is running in this unit test, so no partitions and nothing consumed
    assertThat(first.path("assignedPartitions").asInt()).isZero();
    assertThat(first.path("positionSinceStart").asLong()).isZero();
    assertThat(first.path("lag").asLong()).isZero();
  }
}
