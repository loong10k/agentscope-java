package io.agentscope.extensions.garr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Graph-Augmented Recursive Reasoning: CPARP/0.1 reference planner.
 *
 * <p>Typed graph and constraints control planning; this code never calls tools/models or grants
 * permissions. The authoritative semantics are partme-docs GARR Unified Contract v0.1.
 * Runtime adapters must supply only graph snapshots authorized for the caller.
 */
public final class GarrPlanner {
    /** UTF-8 字节序而非 Java UTF-16 顺序，防止不同语言的 Frame 分区排序漂移。 */
    private static final Comparator<String> UTF8_ORDER =
            (a, b) ->
                    Arrays.compareUnsigned(
                            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    public enum EdgeKind {
        IMPLEMENTS,
        CALLS,
        READS,
        WRITES,
        DEPENDS_ON,
        CONSTRAINED_BY,
        VERIFIED_BY,
        PRECEDES,
        CONFLICTS_WITH,
        HYPOTHESIZED_AFFECTS
    }

    public enum Evidence {
        OBSERVED,
        VERIFIED,
        PROPOSED,
        DISPUTED,
        STALE
    }

    public enum Coupling {
        INHERITED,
        CONTRACTIBLE,
        ATOMIC
    }

    public enum Phase {
        ANALYZE,
        DESIGN,
        IMPLEMENT,
        VERIFY
    }

    public enum NodeKind {
        DOMAIN,
        COMPONENT,
        CODE_SYMBOL,
        STATE_MACHINE,
        RULE,
        TEST,
        RESOURCE
    }

    public enum Action {
        DIRECT,
        PROBE,
        VERIFY,
        SPLIT,
        BLOCKED
    }

    public record Node(String id, String domain, NodeKind kind) {}

    public record Edge(
            String from, String to, EdgeKind kind, Evidence evidence, String sourceRef) {}

    public record Constraint(
            String id,
            Set<String> nodes,
            Coupling coupling,
            Evidence evidence,
            String validatorRef,
            Set<Phase> phases) {
        boolean appliesTo(Phase phase) {
            return phases.isEmpty() || phases.contains(phase);
        }
    }

    public record Snapshot(
            String scope,
            String revision,
            Map<String, Node> nodes,
            List<Edge> edges,
            List<Constraint> constraints) {}

    public record Budget(long tokens, int modelCalls, int graphReads) {
        public boolean covers(Budget need) {
            return tokens >= need.tokens
                    && modelCalls >= need.modelCalls
                    && graphReads >= need.graphReads;
        }
    }

    public record Frame(
            String scope,
            String revision,
            String goal,
            Phase phase,
            List<String> anchors,
            List<String> obligations,
            Budget budget,
            int depth) {}

    public record Child(
            List<String> domains,
            Set<String> owned,
            Set<String> sharedReadOnly,
            List<String> obligations,
            Budget budget) {}

    public record BoundaryContract(String constraintId, String validatorRef) {}

    public record SplitPlan(
            List<Child> children,
            List<BoundaryContract> contracts,
            List<String> joinObligations,
            Budget requiredBudget) {}

    public record Decision(
            Action action,
            String reason,
            Set<String> visited,
            Set<String> constraints,
            Long directScore,
            Long splitScore,
            List<String> targets,
            SplitPlan plan) {}

    private final int maxNodes, maxGraphDepth, maxChildren, maxDepth;
    private final long minimumGain;
    private final Budget planning = new Budget(300, 0, 2), child = new Budget(1800, 2, 2);
    private final Budget join = new Budget(900, 1, 1), verify = new Budget(700, 1, 1);

    /** CPARP/0.1 fixed policy. Changes require a new policy_id and shared fixtures. */
    public GarrPlanner() {
        this(120, 2, 3, 1, 10);
    }

    public GarrPlanner(
            int maxNodes, int maxGraphDepth, int maxChildren, int maxDepth, long minimumGain) {
        this.maxNodes = maxNodes;
        this.maxGraphDepth = maxGraphDepth;
        this.maxChildren = maxChildren;
        this.maxDepth = maxDepth;
        this.minimumGain = minimumGain;
    }

    public Decision plan(Frame frame, Snapshot graph) {
        Set<String> visited = new TreeSet<>(UTF8_ORDER),
                activeConstraints = new TreeSet<>(UTF8_ORDER);
        if (frame == null
                || graph == null
                || !valid(graph)
                || frame.anchors().isEmpty()
                || !graph.scope().equals(frame.scope())
                || !graph.revision().equals(frame.revision())
                || frame.anchors().stream().anyMatch(a -> !graph.nodes().containsKey(a))) {
            return decision(
                    Action.BLOCKED,
                    "snapshot_mismatch",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.of(),
                    null);
        }
        if (maxNodes <= 0 || maxGraphDepth <= 0 || frame.anchors().size() > maxNodes)
            return decision(
                    Action.BLOCKED,
                    "constraint_unresolved",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.of(),
                    null);

        ArrayDeque<String> queue = new ArrayDeque<>(frame.anchors());
        Map<String, Integer> depths = new HashMap<>();
        frame.anchors()
                .forEach(
                        a -> {
                            visited.add(a);
                            depths.put(a, 0);
                        });
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int depth = depths.get(current);
            if (depth >= maxGraphDepth) continue;
            for (Edge edge : graph.edges()) {
                String next = null;
                if (edge.from().equals(current) && forward(edge.kind(), frame.phase()))
                    next = edge.to();
                else if (edge.to().equals(current) && reverse(edge.kind(), frame.phase()))
                    next = edge.from();
                if (next != null && visited.add(next)) {
                    if (visited.size() > maxNodes)
                        return decision(
                                Action.BLOCKED,
                                "constraint_unresolved",
                                visited,
                                activeConstraints,
                                null,
                                null,
                                List.of(),
                                null);
                    depths.put(next, depth + 1);
                    queue.addLast(next);
                }
            }
        }

        boolean changed;
        do {
            changed = false;
            for (Constraint c : graph.constraints()) {
                if (!c.appliesTo(frame.phase()) || c.nodes().stream().noneMatch(visited::contains))
                    continue;
                for (String id : c.nodes()) {
                    if (visited.add(id)) {
                        if (visited.size() > maxNodes)
                            return decision(
                                    Action.BLOCKED,
                                    "constraint_unresolved",
                                    visited,
                                    activeConstraints,
                                    null,
                                    null,
                                    List.of(),
                                    null);
                        changed = true;
                    }
                }
            }
        } while (changed);

        List<Constraint> active =
                graph.constraints().stream()
                        .filter(
                                c ->
                                        c.appliesTo(frame.phase())
                                                && c.nodes().stream().anyMatch(visited::contains))
                        .toList();
        active.forEach(c -> activeConstraints.add(c.id()));
        TreeSet<String> probes = new TreeSet<>(UTF8_ORDER), disputes = new TreeSet<>(UTF8_ORDER);
        for (Edge e : graph.edges()) {
            if (!visited.contains(e.from()) || !visited.contains(e.to())) continue;
            String ref = e.sourceRef() == null ? e.from() + "->" + e.to() : e.sourceRef();
            if (e.evidence() == Evidence.DISPUTED) disputes.add(ref);
            if (e.evidence() == Evidence.PROPOSED || e.evidence() == Evidence.STALE)
                probes.add(ref);
        }
        for (Constraint c : active) {
            if (c.evidence() == Evidence.DISPUTED) disputes.add(c.id());
            if (c.evidence() == Evidence.PROPOSED || c.evidence() == Evidence.STALE)
                probes.add(c.id());
        }
        if (!disputes.isEmpty())
            return decision(
                    Action.VERIFY,
                    "conflicting_evidence",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.copyOf(disputes),
                    null);
        if (!probes.isEmpty())
            return decision(
                    Action.PROBE,
                    "insufficient_evidence",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.copyOf(probes),
                    null);
        if (frame.depth() >= maxDepth)
            return decision(
                    Action.DIRECT,
                    "depth_limit",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.of(),
                    null);

        Map<String, TreeSet<String>> domains = new TreeMap<>(UTF8_ORDER);
        for (String id : visited) {
            Node n = graph.nodes().get(id);
            if (n.kind() != NodeKind.TEST && n.kind() != NodeKind.RULE)
                domains.computeIfAbsent(n.domain(), _k -> new TreeSet<>(UTF8_ORDER)).add(id);
        }
        if (domains.size() < 2)
            return decision(
                    Action.DIRECT,
                    "single_domain",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.of(),
                    null);
        Map<String, String> parents = new TreeMap<>(UTF8_ORDER);
        domains.keySet().forEach(d -> parents.put(d, d));
        if (frame.phase() == Phase.IMPLEMENT)
            for (Constraint c : active) {
                if (c.coupling() != Coupling.ATOMIC) continue;
                List<String> affected =
                        domains.entrySet().stream()
                                .filter(
                                        entry ->
                                                entry.getValue().stream()
                                                        .anyMatch(c.nodes()::contains))
                                .map(Map.Entry::getKey)
                                .toList();
                for (int i = 1; i < affected.size(); i++)
                    union(parents, affected.get(0), affected.get(i));
            }
        Map<String, TreeSet<String>> groups = new TreeMap<>(UTF8_ORDER);
        Map<String, TreeSet<String>> groupDomains = new TreeMap<>(UTF8_ORDER);
        for (var entry : domains.entrySet()) {
            String root = root(parents, entry.getKey());
            groups.computeIfAbsent(root, _k -> new TreeSet<>(UTF8_ORDER)).addAll(entry.getValue());
            groupDomains.computeIfAbsent(root, _k -> new TreeSet<>(UTF8_ORDER)).add(entry.getKey());
        }
        if (groups.size() < 2 || groups.size() > maxChildren)
            return decision(
                    Action.DIRECT,
                    "partition_count",
                    visited,
                    activeConstraints,
                    null,
                    null,
                    List.of(),
                    null);

        Map<String, Integer> owner = new HashMap<>();
        List<String> roots = new ArrayList<>(groups.keySet());
        for (int i = 0; i < roots.size(); i++)
            for (String id : groups.get(roots.get(i))) owner.put(id, i);
        TreeSet<String> shared = new TreeSet<>(visited);
        shared.removeAll(owner.keySet());
        List<BoundaryContract> contracts = new ArrayList<>();
        for (Constraint c : active) {
            HashSet<Integer> owners = new HashSet<>();
            for (String id : c.nodes()) if (owner.containsKey(id)) owners.add(owner.get(id));
            if (owners.size() < 2) continue;
            if (c.coupling() == Coupling.CONTRACTIBLE) {
                if (c.validatorRef() == null || c.validatorRef().isBlank())
                    return decision(
                            Action.PROBE,
                            "missing_boundary_validator",
                            visited,
                            activeConstraints,
                            null,
                            null,
                            List.of(c.id()),
                            null);
                contracts.add(new BoundaryContract(c.id(), c.validatorRef()));
            }
            shared.addAll(c.nodes());
        }
        long cross =
                graph.edges().stream()
                        .filter(e -> visited.contains(e.from()) && visited.contains(e.to()))
                        .filter(
                                e ->
                                        owner.containsKey(e.from())
                                                && owner.containsKey(e.to())
                                                && !owner.get(e.from()).equals(owner.get(e.to())))
                        .count();
        long n = visited.size(), k = groups.size(), c = contracts.size();
        long direct = 25 + 6 * n + 85 * k + 4 * cross;
        long split = 25 + 6 * n + 30 * k + 26 * cross + 18 * c + 30;
        Budget total = new Budget(300 + 1800 * k + 900 + 700, (int) (2 * k + 2), (int) (2 * k + 4));
        if (!frame.budget().covers(total))
            return decision(
                    Action.DIRECT,
                    "budget_insufficient",
                    visited,
                    activeConstraints,
                    direct,
                    split,
                    List.of(),
                    null);
        if (Math.max(0, direct - split) < minimumGain)
            return decision(
                    Action.DIRECT,
                    "nonpositive_gain",
                    visited,
                    activeConstraints,
                    direct,
                    split,
                    List.of(),
                    null);
        List<Child> children = new ArrayList<>();
        for (String root : roots) {
            TreeSet<String> owned = groups.get(root);
            TreeSet<String> sharedForChild = new TreeSet<>(shared);
            sharedForChild.removeAll(owned);
            children.add(
                    new Child(
                            List.copyOf(groupDomains.get(root)),
                            Set.copyOf(owned),
                            Set.copyOf(sharedForChild),
                            List.of("domain-analysis:" + String.join("+", groupDomains.get(root))),
                            child));
        }
        return decision(
                Action.SPLIT,
                "split_positive_net_value",
                visited,
                activeConstraints,
                direct,
                split,
                List.of(),
                new SplitPlan(
                        List.copyOf(children), List.copyOf(contracts), frame.obligations(), total));
    }

    private static Decision decision(
            Action action,
            String reason,
            Set<String> visited,
            Set<String> constraints,
            Long direct,
            Long split,
            List<String> targets,
            SplitPlan plan) {
        return new Decision(
                action,
                reason,
                Set.copyOf(visited),
                Set.copyOf(constraints),
                direct,
                split,
                targets,
                plan);
    }

    private static boolean valid(Snapshot g) {
        if (g.scope() == null
                || g.scope().isBlank()
                || g.revision() == null
                || g.revision().isBlank()
                || g.nodes() == null
                || g.edges() == null
                || g.constraints() == null) return false;
        for (var entry : g.nodes().entrySet()) {
            Node n = entry.getValue();
            if (n == null
                    || n.kind() == null
                    || n.domain() == null
                    || n.domain().isBlank()
                    || !entry.getKey().equals(n.id())) return false;
        }
        for (Edge e : g.edges())
            if (e == null
                    || e.kind() == null
                    || e.evidence() == null
                    || !g.nodes().containsKey(e.from())
                    || !g.nodes().containsKey(e.to())) return false;
        for (Constraint c : g.constraints()) {
            if (c == null
                    || c.id() == null
                    || c.id().isBlank()
                    || c.nodes() == null
                    || c.nodes().isEmpty()
                    || c.coupling() == null
                    || c.evidence() == null
                    || c.phases() == null
                    || !g.nodes().keySet().containsAll(c.nodes())) return false;
        }
        return true;
    }

    private static boolean forward(EdgeKind kind, Phase phase) {
        return kind != EdgeKind.PRECEDES || phase == Phase.IMPLEMENT;
    }

    private static boolean reverse(EdgeKind kind, Phase phase) {
        return switch (kind) {
            case IMPLEMENTS, DEPENDS_ON, CONSTRAINED_BY, CONFLICTS_WITH -> true;
            case WRITES -> phase == Phase.IMPLEMENT;
            default -> false;
        };
    }

    private static String root(Map<String, String> parents, String id) {
        String current = id;
        while (!current.equals(parents.get(current))) current = parents.get(current);
        return current;
    }

    private static void union(Map<String, String> parents, String a, String b) {
        String first = root(parents, a), second = root(parents, b);
        if (!first.equals(second)) {
            if (UTF8_ORDER.compare(first, second) < 0) parents.put(second, first);
            else parents.put(first, second);
        }
    }
}
