package mig.common;

import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * One place this project decides how to talk to a broker. Ported from {@code
 * apps/common/src/main/java/ro/mig/common/KafkaClients.java}. Local redpanda has no auth
 * (PLAINTEXT, the default here); GCP Managed Kafka needs SASL_SSL/OAUTHBEARER — that branch
 * was cut when the lane was first ported, and
 * is restored here via {@link #applySecurity}, selected by the
 * {@code KAFKA_SECURITY_PROTOCOL} env var so an unset environment (every local test in this
 * repo) is unaffected.
 */
public final class KafkaClients {

  private KafkaClients() {}

  /**
   * Producer properties shared by every producer in this repo. {@code enable.idempotence} +
   * broker retries: a retried send never writes a duplicate, so at-least-once delivery from
   * batch (no checkpoints) stays safe without hand-rolled backoff.
   */
  public static Properties producerProps(String bootstrap) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, 5);
    props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
    applySecurity(props);
    return props;
  }

  /**
   * Consumer properties for a bounded, one-shot read of a small topic. A fresh group per run
   * ({@code groupId}, conventionally {@code <role>-<runId>}) means a re-run never skips
   * records a previous group committed past — bounded by end offsets, not a committed
   * position, so auto-commit is off and the reset is {@code earliest}.
   */
  public static Properties consumerProps(String bootstrap, String groupId) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
    applySecurity(props);
    return props;
  }

  /**
   * Adds the SASL_SSL/OAUTHBEARER block when {@code KAFKA_SECURITY_PROTOCOL} names anything
   * other than PLAINTEXT. Left alone otherwise, which is what keeps every local test in this
   * repo (redpanda, PLAINTEXT) unaffected.
   */
  public static void applySecurity(Properties props) {
    if ("PLAINTEXT".equals(securityProtocol())) {
      return;
    }
    props.put("security.protocol", "SASL_SSL");
    props.put("sasl.mechanism", "OAUTHBEARER");
    props.put("sasl.login.callback.handler.class", "mig.common.GcpTokenOauthCallbackHandler");
    props.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");
  }

  /** The configured protocol, defaulting to PLAINTEXT so an unset environment is local. */
  public static String securityProtocol() {
    String value = System.getenv("KAFKA_SECURITY_PROTOCOL");
    return value == null || value.isBlank() ? "PLAINTEXT" : value;
  }
}
