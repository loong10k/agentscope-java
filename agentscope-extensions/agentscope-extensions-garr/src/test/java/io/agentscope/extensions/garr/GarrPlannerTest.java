package io.agentscope.extensions.garr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GarrPlannerTest {
    private final GarrPlanner planner = new GarrPlanner();

    private GarrPlanner.Snapshot graph(
            GarrPlanner.Coupling coupling,
            GarrPlanner.Evidence state,
            GarrPlanner.EdgeKind kind,
            boolean includeConstraint) {
        var nodes =
                Map.of(
                        "order",
                                new GarrPlanner.Node(
                                        "order", "order", GarrPlanner.NodeKind.COMPONENT),
                        "payment",
                                new GarrPlanner.Node(
                                        "payment", "payment", GarrPlanner.NodeKind.COMPONENT));
        var edge = new GarrPlanner.Edge("order", "payment", kind, state, "code://order-payment");
        var rule =
                new GarrPlanner.Constraint(
                        "refund-policy",
                        Set.of("order", "payment"),
                        coupling,
                        GarrPlanner.Evidence.VERIFIED,
                        "verify-refund",
                        Set.of());
        return new GarrPlanner.Snapshot(
                "project/demo",
                "git:abc123",
                nodes,
                List.of(edge),
                includeConstraint ? List.of(rule) : List.of());
    }

    private GarrPlanner.Frame frame(GarrPlanner.Phase phase, long tokens) {
        return new GarrPlanner.Frame(
                "project/demo",
                "git:abc123",
                "分析订单取消",
                phase,
                List.of("order"),
                List.of("state-validity", "refund-consistency"),
                new GarrPlanner.Budget(tokens, 8, 30),
                0);
    }

    @Test
    void contractibleProducesTwoFramesAndJoinObligations() {
        var result =
                planner.plan(
                        frame(GarrPlanner.Phase.IMPLEMENT, 12000),
                        graph(
                                GarrPlanner.Coupling.CONTRACTIBLE,
                                GarrPlanner.Evidence.VERIFIED,
                                GarrPlanner.EdgeKind.CALLS,
                                true));
        assertEquals(GarrPlanner.Action.SPLIT, result.action());
        assertEquals(2, result.plan().children().size());
        assertEquals(1, result.plan().contracts().size());
        assertEquals(
                List.of("state-validity", "refund-consistency"), result.plan().joinObligations());
        assertEquals(5500, result.plan().requiredBudget().tokens());
    }

    @Test
    void atomicCollapsesWriteOwnership() {
        assertEquals(
                GarrPlanner.Action.DIRECT,
                planner.plan(
                                frame(GarrPlanner.Phase.IMPLEMENT, 12000),
                                graph(
                                        GarrPlanner.Coupling.ATOMIC,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void atomicStillAllowsReadOnlyAnalysis() {
        assertEquals(
                GarrPlanner.Action.SPLIT,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 12000),
                                graph(
                                        GarrPlanner.Coupling.ATOMIC,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void proposedRequiresProbe() {
        assertEquals(
                GarrPlanner.Action.PROBE,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 12000),
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.PROPOSED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void disputedRequiresVerify() {
        assertEquals(
                GarrPlanner.Action.VERIFY,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 12000),
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.DISPUTED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void precedenceOnlyNotTraversedInAnalysis() {
        assertEquals(
                GarrPlanner.Action.DIRECT,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 12000),
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.PRECEDES,
                                        false))
                        .action());
    }

    @Test
    void callsTraversedInAnalysis() {
        assertEquals(
                GarrPlanner.Action.SPLIT,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 12000),
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        false))
                        .action());
    }

    @Test
    void budgetInsufficientReturnsDirect() {
        assertEquals(
                GarrPlanner.Action.DIRECT,
                planner.plan(
                                frame(GarrPlanner.Phase.ANALYZE, 4000),
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void revisionMismatchBlocked() {
        var old = frame(GarrPlanner.Phase.ANALYZE, 12000);
        var bad =
                new GarrPlanner.Frame(
                        old.scope(),
                        "git:other",
                        old.goal(),
                        old.phase(),
                        old.anchors(),
                        old.obligations(),
                        old.budget(),
                        old.depth());
        assertEquals(
                GarrPlanner.Action.BLOCKED,
                planner.plan(
                                bad,
                                graph(
                                        GarrPlanner.Coupling.CONTRACTIBLE,
                                        GarrPlanner.Evidence.VERIFIED,
                                        GarrPlanner.EdgeKind.CALLS,
                                        true))
                        .action());
    }

    @Test
    void graphReferenceIntegrityBlocked() {
        var snap =
                graph(
                        GarrPlanner.Coupling.CONTRACTIBLE,
                        GarrPlanner.Evidence.VERIFIED,
                        GarrPlanner.EdgeKind.CALLS,
                        true);
        var broken =
                new GarrPlanner.Snapshot(
                        snap.scope(),
                        snap.revision(),
                        snap.nodes(),
                        List.of(
                                new GarrPlanner.Edge(
                                        "order",
                                        "unknown",
                                        GarrPlanner.EdgeKind.CALLS,
                                        GarrPlanner.Evidence.VERIFIED,
                                        "code://unknown")),
                        snap.constraints());
        assertEquals(
                GarrPlanner.Action.BLOCKED,
                planner.plan(frame(GarrPlanner.Phase.ANALYZE, 12000), broken).action());
    }
}
