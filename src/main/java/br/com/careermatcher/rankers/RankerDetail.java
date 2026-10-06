package br.com.careermatcher.rankers;

import java.util.*;

/** Evidence emitted by the same computation that awards the points. */
public record RankerDetail(String rankerName, double points, String explanation, List<Contribution> contributions) {
    public RankerDetail {
        contributions = List.copyOf(contributions);
    }

    public record Contribution(String ruleCode, double points, String explanation, Map<String, Object> evidence) {}

    public static Map<String, Object> facts(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return Collections.unmodifiableMap(result);
    }

    public static Contribution contribution(String code, double points, String text, Object... evidence) {
        return new Contribution(code, points, text, facts(evidence));
    }

    public static RankerDetail of(String name, String explanation, List<Contribution> contributions) {
        double total = 0;
        // Preserve the original accumulation order and floating-point behavior.
        for (Contribution contribution : contributions) total += contribution.points();
        return new RankerDetail(name, total, explanation, contributions);
    }

    public static RankerDetail single(String name, String code, double points, String text, Object... evidence) {
        return of(name, text, List.of(contribution(code, points, text, evidence)));
    }
}
