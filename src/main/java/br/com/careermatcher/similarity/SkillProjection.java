package br.com.careermatcher.similarity;

import java.util.*;
import static br.com.careermatcher.similarity.SimilaritySnapshot.*;

/** Compressed integer multigraph; candidate and skill identities never overlap. */
public final class SkillProjection {
    public enum Type { CANDIDATE, SKILL }
    public record Node(Type type, long id) implements Comparable<Node> {
        public int compareTo(Node b) { int t = type.compareTo(b.type); return t != 0 ? t : Long.compare(id, b.id); }
    }
    public record Arc(int target, long weight) {}
    public record Pair(long candidateId, long skillId) {}
    private record EvidenceKey(long candidate, long skill, SourceType type, long source) {}
    final List<Node> nodes;
    final Map<Node, Integer> index;
    final List<List<Arc>> adjacency;
    final long[] degree;
    final long multiplicity;
    final Map<Pair, List<Evidence>> evidence;
    final Map<Long, Profile> profiles;
    final Map<Long, String> skillNames;

    public SkillProjection(SimilaritySnapshot snapshot) {
        var ps = new TreeMap<Long, Profile>();
        snapshot.profiles().forEach(p -> ps.put(p.id(), p));
        profiles = Collections.unmodifiableMap(ps);
        var facts = new HashMap<Pair, List<Evidence>>();
        var seen = new HashSet<EvidenceKey>();
        var names = new TreeMap<Long, String>();
        var keys = new TreeSet<Node>();
        ps.keySet().forEach(id -> keys.add(new Node(Type.CANDIDATE, id)));
        for (var e : snapshot.evidence()) {
            if (!ps.containsKey(e.candidateId()) || e.sourceType() == null) throw new IllegalArgumentException("Invalid evidence");
            if (!seen.add(new EvidenceKey(e.candidateId(), e.skillId(), e.sourceType(), e.sourceId()))) continue;
            facts.computeIfAbsent(new Pair(e.candidateId(), e.skillId()), ignored -> new ArrayList<>()).add(e);
            names.put(e.skillId(), e.skillName());
            keys.add(new Node(Type.SKILL, e.skillId()));
        }
        var immutableFacts = new HashMap<Pair, List<Evidence>>();
        facts.forEach((pair, es) -> immutableFacts.put(pair, es.stream()
                .sorted(Comparator.comparing(Evidence::sourceType).thenComparingLong(Evidence::sourceId)).toList()));
        evidence = Map.copyOf(immutableFacts);
        skillNames = Collections.unmodifiableMap(names);
        nodes = List.copyOf(keys);
        var indices = new HashMap<Node, Integer>();
        for (int i = 0; i < nodes.size(); i++) indices.put(nodes.get(i), i);
        index = Map.copyOf(indices);
        var edges = new ArrayList<List<Arc>>();
        for (int i = 0; i < nodes.size(); i++) edges.add(new ArrayList<>());
        facts.forEach((pair, es) -> {
            int c = index.get(new Node(Type.CANDIDATE, pair.candidateId()));
            int s = index.get(new Node(Type.SKILL, pair.skillId()));
            edges.get(c).add(new Arc(s, es.size())); edges.get(s).add(new Arc(c, es.size()));
        });
        degree = new long[nodes.size()];
        long m = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (edges.get(i).isEmpty()) edges.get(i).add(new Arc(i, 1));
            edges.get(i).sort(Comparator.comparingInt(Arc::target));
            for (var a : edges.get(i)) degree[i] = Math.addExact(degree[i], a.weight());
            m = Math.addExact(m, degree[i]);
        }
        adjacency = edges.stream().map(List::copyOf).toList();
        multiplicity = m;
    }
    public long frequency(long candidate, long skill) { return evidence.getOrDefault(new Pair(candidate, skill), List.of()).size(); }
    public double[] sigma(long candidate) {
        double[] result = new double[nodes.size()];
        long total = skillNames.keySet().stream().mapToLong(s -> frequency(candidate, s)).sum();
        if (total > 0) for (long skill : skillNames.keySet())
            result[index.get(new Node(Type.SKILL, skill))] = (double) frequency(candidate, skill) / total;
        return result;
    }
    public List<Integer> targets(long origin, double[] sigma) {
        var reached = new BitSet(nodes.size()); var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < sigma.length; i++) if (sigma[i] > 0) { reached.set(i); queue.add(i); }
        while (!queue.isEmpty()) for (var a : adjacency.get(queue.remove()))
            if (!reached.get(a.target())) { reached.set(a.target()); queue.add(a.target()); }
        return reached.stream().filter(i -> nodes.get(i).type() == Type.CANDIDATE && nodes.get(i).id() != origin).boxed().toList();
    }
}
