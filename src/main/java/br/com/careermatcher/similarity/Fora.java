package br.com.careermatcher.similarity;

import java.util.*;
import java.util.function.LongSupplier;
import br.com.careermatcher.similarity.SimilaritySettings.Parameters;

/** FORA without an index. Alpha is termination probability, not continuation damping. */
public final class Fora {
    public enum QualityStatus { APPROXIMATION_TARGET_MET, BUDGET_EXCEEDED, NOT_APPLICABLE }
    public record Result(double[] scores, List<Integer> top, QualityStatus qualityStatus,
                         boolean approximationTargetMet, int rounds, long pushes, long walks, double delta) {}
    record Push(double[] reserve, double[] residual) {}
    public static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() { super("Similarity budget exhausted before a complete round"); }
    }
    private final LongSupplier clock;
    public Fora() { this(System::nanoTime); }
    Fora(LongSupplier clock) { this.clock = clock; }
    static final class Budget {
        final LongSupplier clock; final long start, nanos, maxWalks;
        long pushes, walks;
        Budget(Parameters p, LongSupplier clock) {
            this.clock = clock; start = clock.getAsLong(); nanos = p.maxMillis() * 1_000_000; maxWalks = p.maxWalks();
        }
        void check() { if (clock.getAsLong() - start >= nanos || Thread.currentThread().isInterrupted()) throw new BudgetExceededException(); }
    }
    public Result compute(SkillProjection g, double[] sigma, List<Integer> targets, int k, Parameters p) {
        if (sigma.length != g.nodes.size() || k < 1 || k > p.maxK()) throw new IllegalArgumentException("Invalid query");
        double sum = 0;
        for (double x : sigma) { if (!Double.isFinite(x) || x < 0) throw new IllegalArgumentException("Invalid sigma"); sum += x; }
        if (Math.abs(sum - 1) > 1e-10 || targets.isEmpty()) throw new IllegalArgumentException("Normalized sigma and targets required");
        int effective = Math.min(k, targets.size());
        double delta = 1.0 / effective, e = p.epsilon() / 2;
        double pUnit = p.failureProbability() / ((double) g.nodes.size() * p.maxRounds());
        var budget = new Budget(p, clock);
        Result last = null;
        for (int round = 0; round < p.maxRounds(); round++, delta /= 2) {
            try {
                budget.check();
                double b = (2 + 2 * e / 3) * Math.log(2 / pUnit) / (e * e * delta);
                if (!Double.isFinite(b) || b <= 0) throw new BudgetExceededException();
                double rMax = (1 / Math.sqrt(g.multiplicity)) / Math.sqrt(b);
                if (g.multiplicity * rMax > 1) rMax = 1 / b;
                var pushed = push(g, sigma, p.alpha(), rMax, budget);
                var random = new SplittableRandom(p.seed()).split();
                // Independent, reproducible streams for each round; never shared between requests.
                for (int j = 0; j < round; j++) random = random.split();
                double[] scores = correct(g, pushed, p.alpha(), b, random, budget);
                budget.check();
                var top = targets.stream().filter(i -> scores[i] > 0)
                        .sorted(Comparator.<Integer>comparingDouble(i -> -scores[i])
                                .thenComparingLong(i -> g.nodes.get(i).id())).limit(effective).toList();
                budget.check();
                boolean met = top.size() == effective && scores[top.getLast()] >= (1 + p.epsilon()) * delta;
                last = new Result(scores, top, met ? QualityStatus.APPROXIMATION_TARGET_MET : QualityStatus.BUDGET_EXCEEDED,
                        met, round + 1, budget.pushes, budget.walks, delta);
                if (met) return last;
            } catch (BudgetExceededException exhausted) {
                if (last == null) throw exhausted;
                return new Result(last.scores(), last.top(), QualityStatus.BUDGET_EXCEEDED, false,
                        last.rounds(), budget.pushes, budget.walks, last.delta());
            }
        }
        return last;
    }
    static Push push(SkillProjection g, double[] sigma, double alpha, double rMax, Budget budget) {
        double[] reserve = new double[sigma.length], residual = sigma.clone();
        var queue = new ArrayDeque<Integer>(); boolean[] queued = new boolean[sigma.length];
        for (int i = 0; i < sigma.length; i++) enqueue(i, g, residual, rMax, queue, queued);
        while (!queue.isEmpty()) {
            budget.check(); int v = queue.remove(); queued[v] = false;
            double mass = residual[v]; residual[v] = 0; reserve[v] += alpha * mass; budget.pushes++;
            for (var a : g.adjacency.get(v)) {
                budget.check();
                residual[a.target()] += (1 - alpha) * mass * ((double) a.weight() / g.degree[v]);
                enqueue(a.target(), g, residual, rMax, queue, queued);
            }
        }
        return new Push(reserve, residual);
    }
    private static void enqueue(int v, SkillProjection g, double[] r, double threshold, ArrayDeque<Integer> q, boolean[] queued) {
        if (!queued[v] && r[v] / g.degree[v] > threshold) { queued[v] = true; q.add(v); }
    }
    static double[] correct(SkillProjection g, Push push, double alpha, double b, SplittableRandom random, Budget budget) {
        double[] score = push.reserve().clone();
        for (int v = 0; v < score.length; v++) {
            double mass = push.residual()[v]; if (mass == 0) continue;
            double count = Math.ceil(mass * b);
            if (!Double.isFinite(count) || count > budget.maxWalks - budget.walks) throw new BudgetExceededException();
            long walks = (long) count;
            for (long j = 0; j < walks; j++) {
                budget.check(); int t = v;
                while (random.nextDouble() >= alpha) {
                    budget.check(); t = next(g, t, random);
                }
                score[t] += mass / walks; budget.walks++;
            }
        }
        for (double x : score) if (!Double.isFinite(x) || x < 0) throw new ArithmeticException("Invalid PPR estimate");
        return score;
    }
    static int next(SkillProjection g, int v, SplittableRandom random) {
        long draw = random.nextLong(g.degree[v]);
        for (var a : g.adjacency.get(v)) { if (draw < a.weight()) return a.target(); draw -= a.weight(); }
        throw new IllegalStateException("Invalid adjacency");
    }
}
