package io.agentscope.extensions.garr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 同一份 garr-v0.1-conformance.json 正式驱动 Java Planner，不允许复制一套 Java Fixture。
 */
class GarrGoldenFixtureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String get(JsonNode obj, String key) {
        return obj.path(key).asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static GarrPlanner.Snapshot snapshot(JsonNode raw) {
        Map<String, GarrPlanner.Node> nodes = new LinkedHashMap<>();
        raw.path("nodes")
                .forEach(
                        n -> {
                            String id = get(n, "node_id");
                            nodes.put(
                                    id,
                                    new GarrPlanner.Node(
                                            id,
                                            get(n, "domain"),
                                            GarrPlanner.NodeKind.valueOf(
                                                    get(n, "node_kind")
                                                            .toUpperCase(java.util.Locale.ROOT))));
                        });
        List<GarrPlanner.Edge> edges = new ArrayList<>();
        raw.path("edges")
                .forEach(
                        e ->
                                edges.add(
                                        new GarrPlanner.Edge(
                                                get(e, "from"),
                                                get(e, "to"),
                                                GarrPlanner.EdgeKind.valueOf(
                                                        get(e, "edge_kind")
                                                                .toUpperCase(
                                                                        java.util.Locale.ROOT)),
                                                GarrPlanner.Evidence.valueOf(
                                                        get(e, "evidence_state")
                                                                .toUpperCase(
                                                                        java.util.Locale.ROOT)),
                                                e.path("evidence_refs").isArray()
                                                                && e.path("evidence_refs").size()
                                                                        != 0
                                                        ? e.path("evidence_refs").get(0).asText()
                                                        : null)));
        List<GarrPlanner.Constraint> rules = new ArrayList<>();
        raw.path("constraints")
                .forEach(
                        c -> {
                            Set<GarrPlanner.Phase> phases = new java.util.HashSet<>();
                            c.path("phases")
                                    .forEach(
                                            p ->
                                                    phases.add(
                                                            GarrPlanner.Phase.valueOf(
                                                                    p.asText()
                                                                            .toUpperCase(
                                                                                    java.util.Locale
                                                                                            .ROOT))));
                            rules.add(
                                    new GarrPlanner.Constraint(
                                            get(c, "constraint_id"),
                                            Set.copyOf(strings(c.path("scope_nodes"))),
                                            GarrPlanner.Coupling.valueOf(
                                                    get(c, "coupling")
                                                            .toUpperCase(java.util.Locale.ROOT)),
                                            GarrPlanner.Evidence.valueOf(
                                                    get(c, "evidence_state")
                                                            .toUpperCase(java.util.Locale.ROOT)),
                                            c.has("validator_ref") ? get(c, "validator_ref") : null,
                                            phases));
                        });
        return new GarrPlanner.Snapshot(
                get(raw, "scope"), get(raw, "revision"), nodes, edges, rules);
    }

    @Test
    void executesTenCanonicalGoldenCases() throws Exception {
        Path path = Path.of("..", "..", "docs", "garr", "fixtures", "garr-v0.1-conformance.json");
        assertEquals(true, Files.isRegularFile(path), "Missing canonical fixture");
        JsonNode fixture = MAPPER.readTree(Files.readString(path));
        JsonNode baseTask = fixture.path("default_task");
        JsonNode baseBudget = fixture.path("default_budget");
        JsonNode cases = fixture.path("cases");
        assertEquals(10, cases.size());
        GarrPlanner planner = new GarrPlanner();

        for (JsonNode tc : cases) {
            String caseId = get(tc, "case_id");
            JsonNode overrides = tc.path("overrides");
            JsonNode taskOvr = overrides.path("task");
            JsonNode budgetOvr = overrides.path("budget");
            String phase = taskOvr.has("phase") ? get(taskOvr, "phase") : get(baseTask, "phase");
            String revision =
                    taskOvr.has("graph_revision")
                            ? get(taskOvr, "graph_revision")
                            : get(baseTask, "graph_revision");
            long tokens =
                    budgetOvr.has("max_tokens")
                            ? budgetOvr.path("max_tokens").asLong()
                            : baseBudget.path("max_tokens").asLong();
            GarrPlanner.Frame frame =
                    new GarrPlanner.Frame(
                            get(baseTask, "scope"),
                            revision,
                            get(baseTask, "goal"),
                            GarrPlanner.Phase.valueOf(phase.toUpperCase(java.util.Locale.ROOT)),
                            strings(baseTask.path("anchors")),
                            strings(baseTask.path("obligation_refs")),
                            new GarrPlanner.Budget(
                                    tokens,
                                    baseBudget.path("max_model_calls").asInt(),
                                    baseBudget.path("max_graph_reads").asInt()),
                            baseTask.path("depth").asInt());
            GarrPlanner.Snapshot graph =
                    snapshot(fixture.path("graph_fixtures").path(get(tc, "graph_ref")));
            GarrPlanner.Decision actual = planner.plan(frame, graph);
            JsonNode expected = tc.path("expected");
            assertEquals(
                    get(expected, "decision").toUpperCase(java.util.Locale.ROOT),
                    actual.action().name(),
                    caseId);
            if (expected.has("child_frames")) {
                assertEquals(
                        expected.path("child_frames").asInt(),
                        actual.plan() == null ? 0 : actual.plan().children().size(),
                        caseId);
            }
            if (expected.has("child_domains")) {
                assertNotNull(actual.plan(), caseId);
                List<List<String>> requiredDomains = new ArrayList<>();
                expected.path("child_domains").forEach(n -> requiredDomains.add(strings(n)));
                assertEquals(
                        requiredDomains,
                        actual.plan().children().stream().map(GarrPlanner.Child::domains).toList(),
                        caseId);
            }
            if (expected.has("boundary_contract_refs")) {
                assertNotNull(actual.plan(), caseId);
                assertEquals(
                        strings(expected.path("boundary_contract_refs")),
                        actual.plan().contracts().stream()
                                .map(GarrPlanner.BoundaryContract::constraintId)
                                .toList(),
                        caseId);
            }
            if (expected.has("error_code")) {
                assertEquals(get(expected, "error_code"), actual.reason(), caseId);
            }
        }
    }
}
