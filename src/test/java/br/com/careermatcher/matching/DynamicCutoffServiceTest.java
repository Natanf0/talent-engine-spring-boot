package br.com.careermatcher.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicCutoffServiceTest {
    private final DynamicCutoffService service = new DynamicCutoffService();

    @Test
    void cutsAtFirstDropAndUsesAccumulatedMeanRatherThanPreviousScore() {
        assertThat(cut(28, 26, 25, 8, 3)).containsExactly("1", "2", "3");
        assertThat(cut(100, 70, 50, 49)).containsExactly("1", "2");
    }

    @Test
    void appliesFloorToEveryCandidateAndAcceptsThresholdEquality() {
        assertThat(cut(6, 4, 3)).containsExactly("1");
        assertThat(cut(5)).containsExactly("1");
        assertThat(cut(4, 3)).isEmpty();
        assertThat(cut(20, 13)).containsExactly("1", "2");
        assertThat(cut(20, 12.99)).containsExactly("1");
    }

    @Test
    void preservesWholeClusterWithoutCapacityLimit() {
        assertThat(cut(30, 29, 28, 27, 26, 25, 24, 23, 22, 21)).hasSize(10);
    }

    @Test
    void sortsScoresStablyPreservesVacanciesAndDoesNotMutateInputs() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("2", 26.0);
        scores.put("10", 26.0);
        scores.put("20", 28.0);
        scores.put("30", 8.0);
        Map<String, Double> original = new LinkedHashMap<>(scores);
        Map<String, Map<String, Double>> raw = new LinkedHashMap<>();
        raw.put("10", scores);
        raw.put("20", Map.of());
        raw.put("30", Map.of("1", 4.0));

        Map<String, List<String>> result = service.applyDynamicCutoff(raw);

        assertThat(result.keySet()).containsExactly("10", "20", "30");
        assertThat(result.get("10")).containsExactly("20", "2", "10");
        assertThat(result.get("20")).isEmpty();
        assertThat(result.get("30")).isEmpty();
        assertThat(scores).containsExactlyEntriesOf(original);
        assertThat(raw.get("10")).isSameAs(scores);
        assertThat(service.applyDynamicCutoff(Map.of())).isEmpty();
    }

    @Test
    void excludesNullNonFiniteAndNonPositiveScoresEvenWithZeroFloor() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("null", null);
        scores.put("nan", Double.NaN);
        scores.put("infinity", Double.POSITIVE_INFINITY);
        scores.put("negativeInfinity", Double.NEGATIVE_INFINITY);
        scores.put("zero", 0.0);
        scores.put("negative", -1.0);
        scores.put("positive", 0.1);
        assertThat(service.applyDynamicCutoff(Map.of("v", scores), 0.65, 0).get("v"))
                .containsExactly("positive");
    }

    @Test
    void supportsCustomParametersAndLargeFiniteScores() {
        assertThat(service.applyDynamicCutoff(Map.of("v", scores(10, 9, 8)), 1, 0).get("v"))
                .containsExactly("1");
        assertThat(service.applyDynamicCutoff(Map.of("v", scores(28, 26, 25, 8)), 0.1, 8).get("v"))
                .containsExactly("1", "2", "3", "4");
        assertThat(cut(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -0.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidFactorEvenForEmptyInput(double factor) {
        assertThatThrownBy(() -> service.applyDynamicCutoff(Map.of(), factor, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidFloorEvenForEmptyInput(double floor) {
        assertThatThrownBy(() -> service.applyDynamicCutoff(Map.of(), 0.65, floor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> cut(double... values) {
        return service.applyDynamicCutoff(Map.of("v", scores(values))).get("v");
    }

    private static Map<String, Double> scores(double... values) {
        Map<String, Double> scores = new LinkedHashMap<>();
        IntStream.range(0, values.length).forEach(i -> scores.put(Integer.toString(i + 1), values[i]));
        return scores;
    }
}
