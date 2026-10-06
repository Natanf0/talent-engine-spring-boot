package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.RankingCriterion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;

public record RankingResult(double totalScore, Map<RankingCriterion, Double> breakdown, List<RankerDetail> rankers) {

    public RankingResult(double totalScore, Map<RankingCriterion, Double> breakdown) {
        this(totalScore, breakdown, List.of());
    }

    public RankingResult {
        EnumMap<RankingCriterion, Double> copy = new EnumMap<>(RankingCriterion.class);
        if (breakdown != null) {
            copy.putAll(breakdown);
        }
        breakdown = Collections.unmodifiableMap(copy);
        rankers = List.copyOf(rankers);
    }
}
