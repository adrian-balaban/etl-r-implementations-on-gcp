package ro.mig.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import ro.mig.common.KafkaClients;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settle phase: read both return topics until every published key has a verdict.
 *
 * <p>
 * <b>Why this is not a bounded end-offset read.</b> The obvious implementation —
 * snapshot {@code endOffsets}, poll until every partition reaches it, done — is what
 * {@code ReconService.readConfirmations} does, and it is correct there because recon runs
 * long after the load. Here it is actively wrong: the loader publishes and then
 * immediately reads, so at the moment the snapshot is taken Target System has consumed
 * almost nothing and the end offsets are near-empty. The read hits "all partitions at
 * end" on the first pass and returns having seen a handful of confirmations, and the
 * timeout — the thing that was supposed to bound the wait — never comes into play at all.
 * Measured locally: 96 of 500 documents settled, the other 404 reported as lost when in
 * fact they were confirmed moments later.
 *
 * <p>
 * So the terminating condition is the <em>question being answered</em>, not the topic's
 * length: poll until every key in {@code awaiting} has been confirmed or rejected, and
 * stop early only on the deadline. That deadline is now doing real work — it is the
 * answer to the plan's Q5, "how long do we wait for confirmations before failing the
 * run", rather than a backstop that never fires.
 *
 * <p>
 * Both topics are read by one loop rather than two sequential ones, so a slow
 * confirmation stream cannot eat the whole budget and leave nothing for rejections.
 * Events are filtered on {@code runId}, so another run's traffic on the same topic is
 * ignored, and a fresh consumer group per run means a re-run reads the topic from the
 * beginning rather than resuming past its own previous attempt.
 */
final class ReturnStream {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReturnStream() {
    }

    /**
     * Confirmed keys, duplicate keys, and rejected key → reason, for one run.
     *
     * <p>
     * {@code confirmed} and {@code duplicates} are disjoint and both count as settled —
     * the split exists only so the loader can keep reporting {@code duplicatesIgnored}
     * with the meaning it had when a replay returned HTTP 200 (plan Q2).
     */
    record Verdicts(Set<String> confirmed, Set<String> duplicates,
            Map<String, String> rejected) {
    }

    /**
     * When to stop waiting for verdicts.
     *
     * <p>
     * The first version used one absolute deadline, {@code timeoutSeconds} after the loader
     * started reading. That bounds the wait for a small run and fails every large one by
     * construction: found on 2026-10-17 on real GCP, where 200,000 documents were published and
     * the mock confirmed about 320 a second, so a healthy run reported 161,620 unsettled when
     * the 120 s window closed with verdicts still arriving.
     *
     * <p>
     * The wait is now an <em>idle</em> window: the clock restarts whenever any new verdict
     * arrives, and the loader gives up only after {@code idleSeconds} with none, so a stalled
     * or dead Target System is still caught at the same speed as before. An absolute cap
     * ({@link #HARD_CAP_SECONDS}) stops a trickle from holding the run forever.
     */
    static final class Patience {
        /** No run waits longer than this, however steadily verdicts arrive. */
        static final long HARD_CAP_SECONDS = 3600;

        private final long idleNanos;
        private final long hardDeadlineNanos;
        private long lastProgressNanos;

        Patience(long idleSeconds, long startNanos) {
            this.idleNanos = Duration.ofSeconds(idleSeconds).toNanos();
            this.hardDeadlineNanos = startNanos
                    + Duration.ofSeconds(Math.max(idleSeconds, HARD_CAP_SECONDS)).toNanos();
            this.lastProgressNanos = startNanos;
        }

        /** A new verdict arrived at {@code nowNanos}. */
        void progressed(long nowNanos) {
            this.lastProgressNanos = nowNanos;
        }

        boolean exhausted(long nowNanos) {
            return nowNanos - lastProgressNanos >= idleNanos || nowNanos >= hardDeadlineNanos;
        }
    }

    /**
     * Polls both return topics until {@code awaiting} is fully accounted for, or the
     * stream goes quiet. {@code timeoutSeconds} is an <em>idle</em> window: the wait
     * continues for as long as verdicts keep arriving and ends only after that many
     * seconds with none (see {@link Patience}). Keys still unaccounted for when this
     * returns are what the caller reports as {@code unsettled}.
     */
    static Verdicts settle(String bootstrap, String confirmationTopic, String rejectionTopic,
            String runId, String groupPrefix, Set<String> awaiting, int timeoutSeconds) {

        Set<String> confirmed = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        Map<String, String> rejected = new LinkedHashMap<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                KafkaClients.consumerProps(bootstrap, groupPrefix + "-" + runId))) {

            List<TopicPartition> partitions = new ArrayList<>();
            partitions.addAll(partitionsOf(consumer, confirmationTopic));
            partitions.addAll(partitionsOf(consumer, rejectionTopic));
            if (partitions.isEmpty()) {
                // Not fatal here, deliberately: with no return topics every published
                // document goes unsettled and the caller fails the run with a message that
                // names how many that cost. Throwing would report a Kafka error instead and
                // hide the number.
                System.err.printf(
                        "loader: neither %s nor %s has any partitions; every published "
                                + "document will be counted as unsettled.%n",
                        confirmationTopic, rejectionTopic);
                return new Verdicts(confirmed, duplicates, rejected);
            }

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            Patience patience = new Patience(timeoutSeconds, System.nanoTime());
            while (confirmed.size() + duplicates.size() + rejected.size() < awaiting.size()
                    && !patience.exhausted(System.nanoTime())) {
                int before = confirmed.size() + duplicates.size() + rejected.size();
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    String key = record.key();
                    if (key == null || key.isBlank() || !awaiting.contains(key)) {
                        continue; // another run's traffic, or a key we never published
                    }
                    JsonNode value = parse(record.value());
                    if (value == null || !runId.equals(value.path("runId").asText(""))) {
                        continue;
                    }
                    if (record.topic().equals(rejectionTopic)) {
                        rejected.put(key, value.path("reason").asText("unspecified"));
                        // A key that was confirmed and is now rejected counts as rejected;
                        // settleAgainst applies the same precedence, this just keeps the
                        // loop's own progress count honest.
                        confirmed.remove(key);
                        duplicates.remove(key);
                    } else if ("duplicate".equals(value.path("outcome").asText("created"))) {
                        // Already applied by an earlier delivery. Settled, but not something
                        // this run created. Absent `outcome` defaults to "created", which is
                        // what every event written before the field existed meant.
                        duplicates.add(key);
                        confirmed.remove(key);
                    } else {
                        confirmed.add(key);
                        duplicates.remove(key);
                    }
                }
                if (confirmed.size() + duplicates.size() + rejected.size() > before) {
                    patience.progressed(System.nanoTime());
                }
            }
        }
        return new Verdicts(confirmed, duplicates, rejected);
    }

    private static List<TopicPartition> partitionsOf(KafkaConsumer<String, String> consumer,
            String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return partitions;
        }
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(topic, info.partition()));
        }
        return partitions;
    }

    /**
     * A malformed return event is not a reason to fail the whole settle — it is a reason
     * to leave the document it referred to unsettled, which the caller already treats as
     * a failure. Logged so it is visible rather than swallowed.
     */
    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            System.err.printf("loader: could not parse return event, ignoring: %s%n",
                    e.getMessage());
            return null;
        }
    }
}
