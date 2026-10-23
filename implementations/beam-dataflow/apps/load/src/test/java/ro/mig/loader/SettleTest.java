package ro.mig.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ro.mig.loader.LoaderApp.Tally;

/**
 * Exercises the settle set-difference in {@link LoaderApp#settleAgainst} — the arithmetic
 * that replaces the HTTP status code once the Load edge is Kafka
 *.
 *
 * <p>
 * Pure logic, no broker. That matters more here than it looks: on the HTTP path a wrong
 * tally was hard to produce, because the response code settled each document one at a
 * time. On the Kafka path the tally is derived from three sets after the fact, so
 * double-counting and off-by-one are the natural failure modes — and every one of them
 * would surface as a run that reports success while documents went missing.
 */
class SettleTest {

    private static final Map<String, String> NO_IDS = Map.of();
    private static final Map<String, String> NO_RAW = Map.of();

    private static Set<String> keys(String... values) {
        return new LinkedHashSet<>(java.util.List.of(values));
    }

    @Test
    void everyPublishedKeyConfirmedLeavesNothingUnsettled() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2", "k3"), keys("k1", "k2", "k3"), keys(), Map.of(), NO_IDS, NO_RAW);

        assertEquals(3, t.published());
        assertEquals(3, t.accepted());
        assertEquals(0, t.unsettled());
        assertTrue(t.errors().isEmpty());
    }

    @Test
    void aRejectedKeyBecomesAnErrorCarryingTargetSystemsReason() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2"),
                keys("k1"),
                keys(),
                Map.of("k2", "schema_violation"),
                Map.of("k2", "ACC-2"),
                Map.of("k2", "{\"accountId\":\"ACC-2\"}"));

        assertEquals(1, t.accepted());
        assertEquals(0, t.unsettled());
        assertEquals(1, t.errors().size());
        // The reason string is the whole point of the rejection topic: without it an .ERR
        // row could say only "not confirmed", which is what `unsettled` already says.
        assertTrue(t.errors().get(0).reason().contains("schema_violation"));
        assertEquals("ACC-2", t.errors().get(0).accountId());
        // The raw document travels too, so .ERR stays replayable.
        assertTrue(t.errors().get(0).raw().contains("ACC-2"));
    }

    @Test
    void aKeyNobodySpokeAboutIsUnsettled() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2", "k3"), keys("k1"), keys(), Map.of("k2", "bad"), NO_IDS, NO_RAW);

        // k3 was published and never confirmed or rejected. This is the failure mode with
        // no HTTP analogue, and the one the run must fail on rather than report quietly.
        assertEquals(3, t.published());
        assertEquals(1, t.accepted());
        assertEquals(1, t.errors().size());
        assertEquals(1, t.unsettled());
    }

    @Test
    void confirmationsForOtherRunsAreIgnored() {
        // A topic in steady state carries other runs' traffic. Counting a stranger's
        // confirmation as ours would mask a document we actually lost — the tally would
        // balance while the account never arrived.
        Tally t = LoaderApp.settleAgainst(
                keys("k1"), keys("k1", "someone-elses-key"), keys(), Map.of(), NO_IDS, NO_RAW);

        assertEquals(1, t.published());
        assertEquals(1, t.accepted());
        assertEquals(0, t.unsettled());
    }

    @Test
    void rejectionsForOtherRunsAreIgnored() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1"), keys("k1"), keys(), Map.of("not-ours", "bad"), NO_IDS, NO_RAW);

        assertEquals(1, t.accepted());
        assertTrue(t.errors().isEmpty());
        assertEquals(0, t.unsettled());
    }

    @Test
    void aKeyBothConfirmedAndRejectedCountsAsRejectedOnly() {
        // Contradictory verdicts must not be double-counted: counting the key twice would
        // drive `unsettled` negative and make the arithmetic claim more settled documents
        // than were published. Rejection wins because it is the claim that something is
        // wrong, and preferring the confirmation would bury a real defect.
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2"),
                keys("k1", "k2"),
                keys(),
                Map.of("k2", "duplicate_account"),
                NO_IDS, NO_RAW);

        assertEquals(2, t.published());
        assertEquals(1, t.accepted());
        assertEquals(1, t.errors().size());
        assertEquals(0, t.unsettled());
    }

    @Test
    void aDuplicateSettlesTheDocumentWithoutCountingAsAccepted() {
        // The HTTP path had this for free: a replay returned 200 and was tallied as a
        // duplicate. On Kafka a silent replay would leave the document with no verdict at
        // all, so re-running a perfectly good batch would report 100% unsettled and fail.
        // Target System publishes outcome=duplicate instead, which settles it.
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2"), keys("k1"), keys("k2"), Map.of(), NO_IDS, NO_RAW);

        assertEquals(2, t.published());
        assertEquals(1, t.accepted());
        assertEquals(1, t.duplicates());
        assertEquals(0, t.unsettled());
    }

    @Test
    void aFullyReplayedBatchIsAllDuplicatesAndStillSettles() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1", "k2", "k3"), keys(), keys("k1", "k2", "k3"),
                Map.of(), NO_IDS, NO_RAW);

        assertEquals(0, t.accepted());
        assertEquals(3, t.duplicates());
        // The point of the whole change: re-running an already-loaded batch must not
        // look like data loss.
        assertEquals(0, t.unsettled());
    }

    @Test
    void aKeyReportedBothCreatedAndDuplicateCountsOnceAsDuplicate() {
        // Both events are true — created by this delivery, duplicate on a later one — so
        // the sets can genuinely overlap. Counting the key in both would make settled
        // exceed published and drive `unsettled` negative.
        Tally t = LoaderApp.settleAgainst(
                keys("k1"), keys("k1"), keys("k1"), Map.of(), NO_IDS, NO_RAW);

        assertEquals(1, t.published());
        assertEquals(0, t.accepted());
        assertEquals(1, t.duplicates());
        assertEquals(0, t.unsettled());
    }

    @Test
    void aRejectionOutranksBothCreatedAndDuplicate() {
        Tally t = LoaderApp.settleAgainst(
                keys("k1"), keys("k1"), keys("k1"), Map.of("k1", "late_validation_failure"),
                NO_IDS, NO_RAW);

        assertEquals(0, t.accepted());
        assertEquals(0, t.duplicates());
        assertEquals(1, t.errors().size());
        assertEquals(0, t.unsettled());
    }

    @Test
    void nothingPublishedSettlesTrivially() {
        Tally t = LoaderApp.settleAgainst(
                keys(), keys(), keys(), Map.of(), NO_IDS, NO_RAW);

        assertEquals(0, t.published());
        assertEquals(0, t.unsettled());
    }

    @Test
    void aRejectionWithNoKnownAccountIdStillProducesAnErrorRow() {
        // The account id map is populated at publish time, so a rejection for a key we
        // never recorded means the two sides disagree about what was sent. That is worth
        // an .ERR row with a placeholder rather than a dropped error.
        Tally t = LoaderApp.settleAgainst(
                keys("k1"), keys(), keys(), Map.of("k1", "unknown_field"), NO_IDS, NO_RAW);

        assertEquals(1, t.errors().size());
        assertEquals("<unidentified>", t.errors().get(0).accountId());
        assertEquals(0, t.unsettled());
    }

    // ── Patience: the settle wait is an idle window, not an absolute deadline (2026-10-17) ──

    private static final long SEC = 1_000_000_000L;

    @Test
    void aSteadyStreamOutlivesTheIdleWindow() {
        // The defect: 200,000 documents at ~320 verdicts/s need ~10 minutes in total. With a
        // 120 s idle window the wait must continue as long as verdicts keep arriving.
        ReturnStream.Patience p = new ReturnStream.Patience(120, 0);
        for (long t = 10; t <= 600; t += 10) {
            p.progressed(t * SEC);
            assertTrue(!p.exhausted(t * SEC), "still progressing at " + t + "s");
        }
        assertTrue(!p.exhausted(600 * SEC + 119 * SEC), "119 s after the last verdict is not idle enough");
    }

    @Test
    void aSilentStreamStillFailsAtTheIdleWindow() {
        // No verdict at all (a dead or wedged Target System) is caught as fast as it ever was.
        ReturnStream.Patience p = new ReturnStream.Patience(120, 0);
        assertTrue(!p.exhausted(119 * SEC));
        assertTrue(p.exhausted(120 * SEC));
    }

    @Test
    void aStreamThatStallsMidRunIsCaughtAfterTheWindow() {
        ReturnStream.Patience p = new ReturnStream.Patience(120, 0);
        p.progressed(300 * SEC); // last verdict at 300 s, then silence
        assertTrue(!p.exhausted(300 * SEC + 119 * SEC));
        assertTrue(p.exhausted(300 * SEC + 120 * SEC));
    }

    @Test
    void aTrickleCannotHoldTheRunPastTheHardCap() {
        ReturnStream.Patience p = new ReturnStream.Patience(120, 0);
        long t = ReturnStream.Patience.HARD_CAP_SECONDS * SEC;
        p.progressed(t - SEC); // a verdict one second before the cap: still progressing...
        assertTrue(p.exhausted(t), "...but the absolute cap ends the wait");
    }
}
