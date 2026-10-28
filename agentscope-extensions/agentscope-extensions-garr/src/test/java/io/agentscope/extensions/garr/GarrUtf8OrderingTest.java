package io.agentscope.extensions.garr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GARR-015: UTF-8 字节序跨语言一致，不能按 UTF-16 字符序给 Frame 排名。
 */
class GarrUtf8OrderingTest {
    @Test
    void domainsMustSortInUtf8ByteOrder() {
        var graph =
                new GarrPlanner.Snapshot(
                        "project/demo",
                        "git:abc123",
                        Map.of(
                                "order",
                                        new GarrPlanner.Node(
                                                "order", "\uE000", GarrPlanner.NodeKind.COMPONENT),
                                "payment",
                                        new GarrPlanner.Node(
                                                "payment",
                                                "\uD83D\uDE00",
                                                GarrPlanner.NodeKind.COMPONENT)),
                        List.of(
                                new GarrPlanner.Edge(
                                        "order",
                                        "payment",
                                        GarrPlanner.EdgeKind.CALLS,
                                        GarrPlanner.Evidence.VERIFIED,
                                        "code://unicode")),
                        List.of());
        var task =
                new GarrPlanner.Frame(
                        "project/demo",
                        "git:abc123",
                        "Unicode 边界",
                        GarrPlanner.Phase.ANALYZE,
                        List.of("order"),
                        List.of("domain-coverage"),
                        new GarrPlanner.Budget(12000, 8, 30),
                        0);
        var result = new GarrPlanner().plan(task, graph);
        assertEquals(GarrPlanner.Action.SPLIT, result.action());
        assertEquals(
                List.of(List.of("\uE000"), List.of("\uD83D\uDE00")),
                result.plan().children().stream().map(GarrPlanner.Child::domains).toList());
    }
}
