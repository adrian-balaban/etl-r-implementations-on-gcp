package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SPIKE: is {@code upsert-kafka} usable from a BATCH Flink
 * job on Flink 2.2.1, or does it refuse and force the documented fallback ({@code kafka} +
 * {@code sink.delivery-guarantee = at-least-once})? Answer goes in  either
 * way — with the error message if it is a refusal.
 */
class UpsertKafkaSpikeIT {

  private static final String RP_CONTAINER = "mig-upsert-spike-redpanda";
  private static final int RP_PORT = 19099;
  private static final String BOOTSTRAP = "localhost:" + RP_PORT;
  private static final String TOPIC = "spike-upsert";
  private static boolean podmanAvailable;

  @BeforeAll
  static void startRedpanda() throws Exception {
    podmanAvailable = ProcessBuilder.class != null && commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH");
    run("podman", "rm", "-f", RP_CONTAINER);
    run(
        "podman", "run", "--rm", "-d", "--name", RP_CONTAINER,
        "-p", RP_PORT + ":9092",
        "docker.io/redpandadata/redpanda:latest", "redpanda", "start",
        "--smp=1", "--memory=512M", "--overprovisioned", "--node-id=0", "--check=false",
        "--kafka-addr=internal://0.0.0.0:29092,external://0.0.0.0:9092",
        "--advertise-kafka-addr=internal://127.0.0.1:29092,external://localhost:" + RP_PORT);
    boolean ready = false;
    for (int i = 0; i < 40 && !ready; i++) {
      ready = run("podman", "exec", RP_CONTAINER, "rpk", "cluster", "health") == 0
          && runCapture("podman", "exec", RP_CONTAINER, "rpk", "cluster", "health")
              .replaceAll("\\s+", " ").contains("Healthy: true");
      if (!ready) {
        Thread.sleep(1000);
      }
    }
    assertThat(ready).as("redpanda healthy").isTrue();
    run("podman", "exec", RP_CONTAINER, "rpk", "topic", "create", TOPIC);
  }

  @AfterAll
  static void stopRedpanda() throws Exception {
    if (podmanAvailable) {
      run("podman", "rm", "-f", RP_CONTAINER);
    }
  }

  @Test
  void upsertKafkaInBatchMode() throws Exception {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setRuntimeMode(RuntimeExecutionMode.BATCH);
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

    tEnv.executeSql(
        "CREATE TABLE target_kafka ("
            + " account_key STRING, doc_json STRING,"
            + " PRIMARY KEY (account_key) NOT ENFORCED"
            + ") WITH ("
            + " 'connector'='upsert-kafka',"
            + " 'topic'='" + TOPIC + "',"
            + " 'properties.bootstrap.servers'='" + BOOTSTRAP + "',"
            + " 'key.format'='raw',"
            + " 'value.format'='raw',"
            + " 'value.fields-include'='EXCEPT_KEY'"
            + ")");

    try {
      tEnv.executeSql(
              "INSERT INTO target_kafka SELECT * FROM (VALUES ('k1','{\"a\":1}'), ('k2','{\"a\":2}')) AS t(k, v)")
          .await();
      System.out.println("SPIKE-RESULT: upsert-kafka ACCEPTED in batch mode");
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      System.out.println("SPIKE-RESULT: upsert-kafka REFUSED in batch mode: "
          + root.getClass().getName() + ": " + root.getMessage());
      throw e;
    }

    Properties props = new Properties();
    props.put("bootstrap.servers", BOOTSTRAP);
    props.put("group.id", "spike-reader");
    props.put("auto.offset.reset", "earliest");
    int count = 0;
    try (var consumer = new KafkaConsumer<String, String>(props, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(TOPIC));
      for (int i = 0; i < 10 && count < 2; i++) {
        ConsumerRecords<String, String> recs = consumer.poll(Duration.ofSeconds(2));
        for (var r : recs) {
          System.out.println("SPIKE-RECORD: key=" + r.key() + " value=" + r.value());
          count++;
        }
      }
    }
    assertThat(count).as("records landed on the topic").isEqualTo(2);
  }

  private static boolean commandExists(String cmd) throws Exception {
    return new ProcessBuilder("sh", "-c", "command -v " + cmd)
        .redirectErrorStream(true)
        .start()
        .waitFor()
        == 0;
  }

  private static int run(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  private static String runCapture(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
  }
}
