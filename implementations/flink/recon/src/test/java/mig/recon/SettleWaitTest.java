package mig.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Found on real GCP at 200,000 accounts: recon subscribed to the confirmation topic while the
 * target-system mock was still confirming (3,193 published of 200,000), treated the end offset it
 * saw at that instant as "everything", and reported a healthy run as 196,807 unconfirmed. At 50
 * documents the mock has always finished first, so no test at that size could see it.
 *
 * <p>These drive the read loop with a {@link MockConsumer} that delivers confirmations in LATER
 * polls — the shape of a consumer that is slow but moving.
 */
class SettleWaitTest {

  private static final TopicPartition TP = new TopicPartition("confirmations", 0);

  private static String doc(String run, int i) {
    return "{\"runId\":\"" + run + "\",\"accountKey\":\"k" + i + "\"}";
  }

  private static Set<String> keys(int n) {
    Set<String> s = new HashSet<>();
    for (int i = 0; i < n; i++) s.add("k" + i);
    return s;
  }

  private static MockConsumer<String, String> consumer() {
    MockConsumer<String, String> c = new MockConsumer<>(org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST);
    c.assign(List.of(TP));
    c.updateBeginningOffsets(java.util.Map.of(TP, 0L));
    return c;
  }

  @Test
  void confirmationsThatArriveAfterTheFirstPollAreStillWaitedFor() throws Exception {
    MockConsumer<String, String> c = consumer();
    // 100 already there; the other 900 arrive over the next polls, as a lagging mock's would.
    for (int i = 0; i < 100; i++) c.addRecord(new ConsumerRecord<>("confirmations", 0, i, "k" + i, doc("r", i)));
    int[] next = {100};
    c.schedulePollTask(() -> deliver(c, next, 300));
    for (int round = 0; round < 3; round++) {
      c.schedulePollTask(() -> deliver(c, next, 300));
    }

    Set<String> confirmed = new HashSet<>();
    ReconService.collect(c, List.of(TP), "r", keys(1000), 5_000, 30_000, confirmed);

    assertThat(confirmed).as("all 1000 waited for, not just the 100 present at subscribe time").hasSize(1000);
  }

  @Test
  void aConsumerThatStopsMakingProgressIsGivenUpOnAfterTheIdleTimeout() throws Exception {
    MockConsumer<String, String> c = consumer();
    for (int i = 0; i < 10; i++) c.addRecord(new ConsumerRecord<>("confirmations", 0, i, "k" + i, doc("r", i)));

    long t0 = System.currentTimeMillis();
    Set<String> confirmed = new HashSet<>();
    ReconService.collect(c, List.of(TP), "r", keys(50), 1_000, 30_000, confirmed);
    long took = System.currentTimeMillis() - t0;

    assertThat(confirmed).as("what did arrive is reported").hasSize(10);
    assertThat(took).as("gave up after ~the idle timeout, not the hard cap").isBetween(900L, 5_000L);
  }

  @Test
  void anotherRunsConfirmationsAreNotCounted() throws Exception {
    MockConsumer<String, String> c = consumer();
    for (int i = 0; i < 5; i++) c.addRecord(new ConsumerRecord<>("confirmations", 0, i, "k" + i, doc("other-run", i)));

    Set<String> confirmed = new HashSet<>();
    ReconService.collect(c, List.of(TP), "r", keys(5), 500, 5_000, confirmed);

    assertThat(confirmed).isEmpty();
  }

  private static void deliver(MockConsumer<String, String> c, int[] next, int n) {
    for (int i = 0; i < n; i++, next[0]++) {
      c.addRecord(new ConsumerRecord<>("confirmations", 0, next[0], "k" + next[0], doc("r", next[0])));
    }
  }
}
