package mig.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mig.common.KafkaClients;

/**
 * Stand-in for Target System's loader API — the oracle for
 * the settle loop. Vendored from {@code apps/target-system-mock} in
 * beam-dataflow, trimmed to the Kafka-only intake this lane uses:
 * there is no {@code loader-app} here, so the original's HTTP {@code POST /v1/accounts} path — and the injected-failure /
 * retry logic that existed to exercise a Loader's retry code — is not carried over. What is
 * kept exactly: idempotency on the dedup key (= {@code account_key}), one confirmation event
 * per accepted document with an {@code outcome} of {@code created} or {@code duplicate}
 * (the settle loop's oracle), one rejection event per refused document, and
 * the {@code /__admin/suppress-next-confirmation} hook that manufactures "sent but not
 * confirmed" deterministically.
 */
public final class TargetSystemMock {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OUTCOME_CREATED = "created";
  private static final String OUTCOME_DUPLICATE = "duplicate";

  // Only the ids, never the parsed documents: this used to be Map<String, JsonNode> and was read
  // for nothing but its size. On real GCP at 200,000 accounts it exhausted the heap at ~57k
  // documents (OutOfMemoryError in the consumer thread) while the HTTP side kept answering.
  private final java.util.Set<String> accounts = ConcurrentHashMap.newKeySet();
  private final Map<String, String> idempotency = new ConcurrentHashMap<>();
  private final AtomicLong received = new AtomicLong();
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong duplicates = new AtomicLong();
  private final AtomicLong confirmationsPublished = new AtomicLong();
  private final AtomicLong rejectionsPublished = new AtomicLong();
  private final AtomicLong consumed = new AtomicLong();
  private final AtomicBoolean suppressNextConfirmation = new AtomicBoolean(false);

  // Identity and Kafka position, so the counters above can be CHECKED instead of trusted
  //. Every counter is in memory and per process. The consumer group commits
  // offsets, so a restarted — or second — instance resumes where the last one stopped and its
  // counters cover only its own lifetime: after six full runs one process read 252 of 300 messages
  // and nothing in its own output said whether that was a bug, a restart or a second instance.
  // `instanceId` + `startedAt` say WHICH process answered; `positionSinceStart` is how far this
  // process's consumer actually advanced on the topic, which must equal `consumedFromTopic`.
  private final String instanceId = java.util.UUID.randomUUID().toString();
  private final java.time.Instant startedAt = java.time.Instant.now();
  private volatile int assignedPartitions;
  private volatile long startPositionSum = -1;
  private volatile long positionSum;
  private volatile long endOffsetSum;
  private volatile String consumerGroup = "";

  private final KafkaProducer<String, String> producer;
  private final String confirmationTopic;
  private final String rejectionTopic;

  public TargetSystemMock(String bootstrap, String confirmationTopic, String rejectionTopic) {
    this.confirmationTopic = confirmationTopic;
    this.rejectionTopic = rejectionTopic;
    this.producer = new KafkaProducer<>(KafkaClients.producerProps(bootstrap));
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("TARGET_SYSTEM_PORT", "8080"));
    String bootstrap = env("KAFKA_BOOTSTRAP", "localhost:9092");
    String targetTopic = env("TARGET_SYSTEM_TARGET_TOPIC", "target-system-target");
    String confirmationTopic = env("TARGET_SYSTEM_CONFIRMATION_TOPIC", "target-system-confirmations");
    String rejectionTopic = env("TARGET_SYSTEM_REJECTION_TOPIC", "target-system-rejections");

    TargetSystemMock mock = new TargetSystemMock(bootstrap, confirmationTopic, rejectionTopic);

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    mock.registerAdminEndpoints(server);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();

    AtomicBoolean running = new AtomicBoolean(true);
    Thread consumer =
        new Thread(
            () -> {
              try {
                mock.consumeLoop(bootstrap, targetTopic, running);
              } catch (Throwable t) {
                // A dead consumer behind a live HTTP server looks healthy and confirms nothing —
                // exactly what the OutOfMemoryError above did. Die loudly so Cloud Run restarts us.
                System.err.println("[mock] consumer thread died: " + t);
                Runtime.getRuntime().halt(1);
              }
            },
            "target-topic-consumer");
    consumer.setDaemon(true);
    consumer.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  running.set(false);
                  mock.producer.flush();
                  mock.producer.close(Duration.ofSeconds(5));
                }));

    System.out.printf(
        "target-system-mock listening on :%d, consuming %s -> confirmations=%s rejections=%s%n",
        port, targetTopic, confirmationTopic, rejectionTopic);
  }

  /**
   * Extracted from {@code main()} so {@code TargetSystemMockTest} can drive the admin HTTP
   * surface against a real (ephemeral-port) {@link HttpServer} with real HTTP requests,
   * rather than reflecting into the {@code private handleXxx} methods — pure wiring, no
   * behaviour change from what {@code main()} did inline before.
   */
  void registerAdminEndpoints(HttpServer server) {
    server.createContext("/__admin/stats", this::handleStats);
    server.createContext("/__admin/reset", this::handleReset);
    server.createContext("/__admin/suppress-next-confirmation", this::handleSuppressNextConfirmation);
    server.createContext("/__admin/health", this::handleHealth);
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  // ── kafka intake ──────────────────────────────────────────────────────────────

  private void consumeLoop(String bootstrap, String topic, AtomicBoolean running) {
    consumerGroup = "target-system-mock";
    Properties props = KafkaClients.consumerProps(bootstrap, consumerGroup);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long lastSnapshot = 0;
      while (running.get()) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          consumed.incrementAndGet();
          applyDocument(record.key(), record.value());
        }
        if (!records.isEmpty()) {
          producer.flush(); // every confirmation of this batch is on the broker before its offsets are committed
          consumer.commitSync();
        }
        // The consumer is not thread-safe, so the HTTP handler cannot ask it anything; it reads
        // this snapshot instead. Once a second is plenty for a check made by a human or a script.
        long now = System.currentTimeMillis();
        if (now - lastSnapshot >= 1000) {
          lastSnapshot = now;
          snapshotPositions(consumer);
        }
      }
    } catch (Exception e) {
      System.err.printf("[mock] consumer loop stopped: %s%n", e.getMessage());
    }
  }

  private void snapshotPositions(KafkaConsumer<String, String> consumer) {
    var assignment = consumer.assignment();
    assignedPartitions = assignment.size();
    if (assignment.isEmpty()) {
      return;
    }
    long position = 0;
    for (var tp : assignment) {
      position += consumer.position(tp);
    }
    long end = 0;
    for (long o : consumer.endOffsets(assignment).values()) {
      end += o;
    }
    if (startPositionSum < 0) {
      // First snapshot AFTER the first poll: the position before any record of this run was applied
      // is this position minus what was consumed since, which for a fresh start is what we want.
      startPositionSum = position - consumed.get();
    }
    positionSum = position;
    endOffsetSum = end;
  }

  private void applyDocument(String dedupKey, String body) {
    received.incrementAndGet();

    JsonNode doc;
    try {
      doc = MAPPER.readTree(body);
    } catch (IOException e) {
      publishRejection(null, dedupKey, "<unparseable>", "invalid_json");
      return;
    }

    String accountId = doc.path("accountId").asText(null);
    if (accountId == null || accountId.isBlank()) {
      publishRejection(doc, dedupKey, "<unidentified>", "missing_accountId");
      return;
    }
    if (dedupKey == null || dedupKey.isBlank()) {
      publishRejection(doc, dedupKey, accountId, "blank_idempotency_key");
      return;
    }

    if (idempotency.putIfAbsent(dedupKey, accountId) != null) {
      // A replayed batch: no second account, but still a verdict.
      duplicates.incrementAndGet();
      publishConfirmation(doc, accountId, dedupKey, OUTCOME_DUPLICATE);
      return;
    }

    accounts.add(accountId);
    accepted.incrementAndGet();
    publishConfirmation(doc, accountId, dedupKey, OUTCOME_CREATED);
  }

  private void publishConfirmation(JsonNode doc, String accountId, String accountKey, String outcome) {
    // RC9 hook: consume the one-shot suppress flag before sending — the account is still
    // stored; only the publish is skipped, manufacturing "sent but not confirmed".
    if (suppressNextConfirmation.getAndSet(false)) {
      System.out.printf("[mock] suppress-next-confirmation: skipping publish for %s%n", accountKey);
      return;
    }
    String runId = doc.path("migration").path("runId").asText("");
    String json =
        MAPPER
            .createObjectNode()
            .put("runId", runId)
            .put("accountId", accountId)
            .put("accountKey", accountKey)
            .put("outcome", outcome)
            .put("confirmedAt", Instant.now().toString())
            .toString();
    sendAsync(new ProducerRecord<>(confirmationTopic, accountKey, json), confirmationsPublished, "confirmation", accountKey);
  }

  private void publishRejection(JsonNode doc, String dedupKey, String accountId, String reason) {
    System.err.printf("[mock] rejecting %s (%s): %s%n", accountId, dedupKey, reason);
    String runId = doc == null ? "" : doc.path("migration").path("runId").asText("");
    String json =
        MAPPER
            .createObjectNode()
            .put("runId", runId)
            .put("accountId", accountId)
            .put("accountKey", dedupKey == null ? "" : dedupKey)
            .put("reason", reason)
            .put("rejectedAt", Instant.now().toString())
            .toString();
    sendAsync(new ProducerRecord<>(rejectionTopic, dedupKey, json), rejectionsPublished, "rejection", dedupKey);
  }

  /**
   * Fire-and-count. This used to be {@code producer.send(...).get()}: one blocking round trip to
   * Managed Kafka PER MESSAGE (~65 ms), which capped the mock at ~15 messages/s and — found on
   * real GCP at 200,000 accounts — left recon waiting on a target system that would have needed
   * hours. A failed publish is still logged and still not retried (unchanged); the difference is
   * only that a batch is sent together and {@link #consumeLoop} flushes it before committing.
   */
  private void sendAsync(
      ProducerRecord<String, String> record, java.util.concurrent.atomic.AtomicLong counter, String what, String key) {
    producer.send(
        record,
        (metadata, error) -> {
          if (error == null) {
            counter.incrementAndGet();
          } else {
            System.err.printf("[mock] %s publish FAILED for %s: %s%n", what, key, error.getMessage());
          }
        });
  }

  // ── admin HTTP ────────────────────────────────────────────────────────────────

  private void handleStats(HttpExchange exchange) throws IOException {
    ObjectNode stats = MAPPER.createObjectNode();
    stats.put("received", received.get());
    stats.put("accepted", accepted.get());
    stats.put("duplicatesIgnored", duplicates.get());
    stats.put("confirmationsPublished", confirmationsPublished.get());
    stats.put("rejectionsPublished", rejectionsPublished.get());
    stats.put("consumedFromTopic", consumed.get());
    stats.put("distinctAccounts", accounts.size());
    // Which process answered, and whether its counters can be believed (see the field comment).
    stats.put("instanceId", instanceId);
    stats.put("startedAt", startedAt.toString());
    stats.put("uptimeSeconds", java.time.Duration.between(startedAt, java.time.Instant.now()).toSeconds());
    stats.put("consumerGroup", consumerGroup);
    stats.put("assignedPartitions", assignedPartitions);
    stats.put("positionSinceStart", startPositionSum < 0 ? 0 : positionSum - startPositionSum);
    stats.put("lag", Math.max(0, endOffsetSum - positionSum));
    stats.put("topicEndOffset", endOffsetSum);
    respond(exchange, 200, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stats));
  }

  private void handleReset(HttpExchange exchange) throws IOException {
    accounts.clear();
    idempotency.clear();
    received.set(0);
    accepted.set(0);
    duplicates.set(0);
    confirmationsPublished.set(0);
    rejectionsPublished.set(0);
    consumed.set(0);
    suppressNextConfirmation.set(false);
    respond(exchange, 200, "{\"status\":\"reset\"}");
  }

  private void handleSuppressNextConfirmation(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    suppressNextConfirmation.set(true);
    respond(exchange, 200, "{\"status\":\"suppress-next-confirmation-armed\"}");
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    respond(exchange, 200, "{\"status\":\"ok\"}");
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }
}
