package br.com.careermatcher.matching;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Prunes each vacancy once, before HR, using the mean of its accepted prefix. */
@Service
public class DynamicCutoffService {
    public static final double DEFAULT_TOLERANCE_FACTOR = 0.65;
    public static final double DEFAULT_ABSOLUTE_MIN_SCORE = 5.0;

    public Map<String, List<String>> applyDynamicCutoff(Map<String, Map<String, Double>> rawScores) {
        return applyDynamicCutoff(rawScores, DEFAULT_TOLERANCE_FACTOR, DEFAULT_ABSOLUTE_MIN_SCORE);
    }

    /**
     * Score ties retain input order. The pipeline supplies maps in canonical ID order.
     * The absolute floor applies to every candidate; equality at either threshold passes.
     * Inputs are not modified, and empty vacancies remain in the result.
     */
    public Map<String, List<String>> applyDynamicCutoff(
            Map<String, Map<String, Double>> rawScores, double toleranceFactor, double absoluteMinScore
    ) {
        return applyDynamicCutoff(rawScores, toleranceFactor, absoluteMinScore, ignored -> {});
    }

    public Map<String, List<String>> applyDynamicCutoff(
            Map<String, Map<String, Double>> rawScores, double toleranceFactor, double absoluteMinScore,
            Consumer<CutoffDecision> observer
    ) {
        validateParameters(toleranceFactor, absoluteMinScore);
        Map<String, List<String>> result = new LinkedHashMap<>();
        rawScores.forEach((vacancyId, scores) -> {
            scores.forEach((candidateId, score) -> {
                String code = score == null || !Double.isFinite(score) ? "INVALID_SCORE"
                        : score <= 0 ? "NON_POSITIVE_SCORE" : score < absoluteMinScore ? "BELOW_ABSOLUTE_MINIMUM" : null;
                if (code != null) observer.accept(new CutoffDecision(vacancyId, candidateId, null, score,
                        absoluteMinScore, toleranceFactor, 0, null, null, code, false, null,
                        "Excluído antes da ordenação da poda: " + code + "; nota " + score + ", piso " + absoluteMinScore + "."));
            });
            List<Map.Entry<String, Double>> sorted = scores.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && Double.isFinite(entry.getValue())
                            && entry.getValue() > 0.0 && entry.getValue() >= absoluteMinScore)
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .toList();
            List<String> accepted = new ArrayList<>();
            double clusterAverage = 0.0;
            String trigger = null;
            int position = 0;
            for (Map.Entry<String, Double> candidate : sorted) {
                position++;
                double score = candidate.getValue();
                Double mean = accepted.isEmpty() ? null : clusterAverage;
                Double threshold = mean == null ? null : toleranceFactor * mean;
                if (trigger != null) {
                    observer.accept(new CutoffDecision(vacancyId, candidate.getKey(), position, score,
                            absoluteMinScore, toleranceFactor, accepted.size(), mean, threshold,
                            "AFTER_ELBOW", false, trigger, "Não avaliado individualmente: lista interrompida no candidato " + trigger + "."));
                    continue;
                }
                if (!accepted.isEmpty() && score < toleranceFactor * clusterAverage) {
                    trigger = candidate.getKey();
                    observer.accept(new CutoffDecision(vacancyId, candidate.getKey(), position, score,
                            absoluteMinScore, toleranceFactor, accepted.size(), mean, threshold,
                            "ELBOW_DROP", false, trigger, "Corte: nota " + score + " < " + threshold
                                    + " (fator " + toleranceFactor + " × média anterior " + mean + ")."));
                    continue;
                }
                observer.accept(new CutoffDecision(vacancyId, candidate.getKey(), position, score,
                        absoluteMinScore, toleranceFactor, accepted.size(), mean, threshold,
                        accepted.isEmpty() ? "LEADER_ACCEPTED" : "CLUSTER_ACCEPTED", true, null,
                        accepted.isEmpty() ? "Líder aprovado pelo piso absoluto " + absoluteMinScore + "."
                                : "Aprovado: nota " + score + " >= " + threshold + " (média anterior " + mean + ")."));
                accepted.add(candidate.getKey());
                // Incremental mean avoids overflow when summing large finite scores.
                clusterAverage += (score - clusterAverage) / accepted.size();
            }
            result.put(vacancyId, List.copyOf(accepted));
        });
        return result;
    }

    public static void validateParameters(double toleranceFactor, double absoluteMinScore) {
        if (!Double.isFinite(toleranceFactor) || toleranceFactor <= 0.0 || toleranceFactor > 1.0) {
            throw new IllegalArgumentException("O fator de tolerância deve ser finito, maior que zero e no máximo 1.");
        }
        if (!Double.isFinite(absoluteMinScore) || absoluteMinScore < 0.0) {
            throw new IllegalArgumentException("A pontuação mínima absoluta deve ser finita e maior ou igual a zero.");
        }
    }
}
