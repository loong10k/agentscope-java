package io.agentscope.extensions.garr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.garr.GarrP1.Budget;
import io.agentscope.extensions.garr.GarrP1.CancelSignal;
import io.agentscope.extensions.garr.GarrP1.Evidence;
import io.agentscope.extensions.garr.GarrP1.Frame;
import io.agentscope.extensions.garr.GarrP1.ObligationVerifier;
import io.agentscope.extensions.garr.GarrP1.Plan;
import io.agentscope.extensions.garr.GarrP1.PortOutput;
import io.agentscope.extensions.garr.GarrP1.Receipt;
import io.agentscope.extensions.garr.GarrP1.State;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class GarrP1ContractTest {
    private static Frame frame(String id, String domain) {
        return new Frame(
                id,
                "tenant/a",
                "git:abc",
                "Read only analysis",
                List.of(domain),
                Set.of(domain),
                Set.of(),
                List.of(new Evidence(domain, "source://" + domain, "git:abc", "observed source")),
                List.of("verify:" + domain),
                new Budget(1000, 1, 1));
    }

    private static Plan plan() {
        return new Plan(
                "tenant/a",
                "git:abc",
                List.of(frame("root:0", "order"), frame("root:1", "payment")),
                List.of("state-validity", "refund-consistency"),
                List.of("refund-policy"),
                new Budget(3000, 3, 3));
    }

    private static ObligationVerifier check(boolean skipContract) {
        return (obligation, artifacts) ->
                skipContract && obligation.startsWith("contract:")
                        ? Optional.empty()
                        : Optional.of(
                                new Receipt(
                                        obligation,
                                        "independent-test-verifier",
                                        "test://proof/" + obligation,
                                        "git:abc"));
    }

    private static GarrP1.ReadOnlyPort port(AtomicInteger count) {
        return frame ->
                Mono.fromSupplier(
                        () -> {
                            count.incrementAndGet();
                            return new PortOutput(
                                    "model says all tests passed",
                                    List.of("source://" + frame.domains().get(0)),
                                    new Budget(100, 1, 0));
                        });
    }

    private static GarrP1.Report run(
            Plan p, GarrP1.ReadOnlyPort port, ObligationVerifier verifier, CancelSignal cancel) {
        return GarrP1.run(p, port, verifier, cancel, Duration.ofMillis(70))
                .block(Duration.ofSeconds(3));
    }

    @Test
    void completedOnlyAfterIndependentReceipts() {
        AtomicInteger calls = new AtomicInteger();
        var result = run(plan(), port(calls), check(false), new CancelSignal());
        assertEquals(State.COMPLETED, result.state());
        assertEquals(3, result.receipts().size());
        assertEquals(2, calls.get());
        assertEquals("run_completed", result.events().get(result.events().size() - 1).eventType());
    }

    @Test
    void missingBoundaryReceiptMeansPartial() {
        assertEquals(
                State.PARTIAL,
                run(plan(), port(new AtomicInteger()), check(true), new CancelSignal()).state());
    }

    @Test
    void wrongScopeAndRevisionBlockBeforeCallingPort() {
        var p = plan();
        AtomicInteger calls = new AtomicInteger();
        var bad =
                new Frame(
                        "root:0",
                        "tenant/b",
                        "git:abc",
                        p.frames().get(0).goal(),
                        p.frames().get(0).domains(),
                        p.frames().get(0).ownedNodes(),
                        Set.of(),
                        p.frames().get(0).evidence(),
                        p.frames().get(0).obligations(),
                        p.frames().get(0).budget());
        var mismatch =
                new Plan(
                        p.scope(),
                        p.graphRevision(),
                        List.of(bad, p.frames().get(1)),
                        p.joinObligations(),
                        p.boundaryContractIds(),
                        p.reservedBudget());
        assertEquals(
                State.BLOCKED,
                run(mismatch, port(calls), check(false), new CancelSignal()).state());
        assertEquals(0, calls.get());
    }

    @Test
    void duplicateFrameBlocks() {
        var p = plan();
        var dup =
                new Plan(
                        p.scope(),
                        p.graphRevision(),
                        List.of(p.frames().get(0), p.frames().get(0)),
                        p.joinObligations(),
                        p.boundaryContractIds(),
                        p.reservedBudget());
        assertEquals(
                State.BLOCKED,
                run(dup, port(new AtomicInteger()), check(false), new CancelSignal()).state());
    }

    @Test
    void invalidEvidenceNodeBlocks() {
        var p = plan();
        var f = p.frames().get(0);
        var altered =
                new Frame(
                        f.frameId(),
                        f.scope(),
                        f.graphRevision(),
                        f.goal(),
                        f.domains(),
                        f.ownedNodes(),
                        f.sharedReadOnlyNodes(),
                        List.of(new Evidence("secret", "source://secret", "git:abc", "private")),
                        f.obligations(),
                        f.budget());
        var bad =
                new Plan(
                        p.scope(),
                        p.graphRevision(),
                        List.of(altered, p.frames().get(1)),
                        p.joinObligations(),
                        p.boundaryContractIds(),
                        p.reservedBudget());
        assertEquals(
                State.BLOCKED,
                run(bad, port(new AtomicInteger()), check(false), new CancelSignal()).state());
    }

    @Test
    void unauthorizedModelCitationBlocks() {
        assertEquals(
                State.BLOCKED,
                run(
                                plan(),
                                frame ->
                                        Mono.just(
                                                new PortOutput(
                                                        "fake model",
                                                        List.of("source://secret"),
                                                        new Budget(10, 1, 0))),
                                check(false),
                                new CancelSignal())
                        .state());
    }

    @Test
    void cancellationBeforeExecutionCallsNoPort() {
        AtomicInteger calls = new AtomicInteger();
        var cancel = new CancelSignal();
        cancel.cancel();
        assertEquals(State.CANCELLED, run(plan(), port(calls), check(false), cancel).state());
        assertEquals(0, calls.get());
    }

    @Test
    void portFailureAndTimeoutNeverComplete() {
        assertEquals(
                State.FAILED,
                run(
                                plan(),
                                f -> Mono.error(new IllegalStateException("model error")),
                                check(false),
                                new CancelSignal())
                        .state());
        assertEquals(
                State.FAILED,
                run(plan(), f -> Mono.never(), check(false), new CancelSignal()).state());
    }

    @Test
    void excessiveOrDuplicateBudgetSettlementRejected() {
        var ledger = new GarrP1.BudgetLedger(new Budget(1000, 2, 2));
        assertTrue(ledger.reserve(new Budget(800, 2, 2)));
        assertFalse(ledger.reserve(new Budget(10, 1, 1)));
        assertFalse(ledger.settle(new Budget(900, 1, 1)));
        assertTrue(ledger.settle(new Budget(500, 1, 1)));
        assertFalse(ledger.settle(new Budget(100, 0, 0)));
        assertEquals(500, ledger.available().tokens());
    }

    @Test
    void missingJoinReserveRejected() {
        var p = plan();
        var bad =
                new Plan(
                        p.scope(),
                        p.graphRevision(),
                        p.frames(),
                        p.joinObligations(),
                        p.boundaryContractIds(),
                        new Budget(2000, 2, 2));
        assertEquals(
                State.BLOCKED,
                run(bad, port(new AtomicInteger()), check(false), new CancelSignal()).state());
    }
}
