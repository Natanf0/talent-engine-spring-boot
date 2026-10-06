package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DecisionEventsTest {
    @Test
    void cutoffKeepsTheOriginalThresholdAndDoesNotPretendToEvaluateTheTail() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 28.0); scores.put("B", 26.0); scores.put("C", 25.0);
        scores.put("D", 8.0); scores.put("E", 7.0); scores.put("F", 3.0);
        List<CutoffDecision> events = new ArrayList<>();
        var service = new DynamicCutoffService();
        var traced = service.applyDynamicCutoff(Map.of("V", scores), .65, 5, events::add);
        assertThat(traced).isEqualTo(service.applyDynamicCutoff(Map.of("V", scores)));
        CutoffDecision drop = events.stream().filter(e -> e.code().equals("ELBOW_DROP")).findFirst().orElseThrow();
        assertThat(drop.acceptedCount()).isEqualTo(3);
        assertThat(drop.clusterAverage()).isEqualTo(79.0 / 3);
        assertThat(drop.threshold()).isEqualTo(.65 * (79.0 / 3));
        CutoffDecision tail = events.stream().filter(e -> e.candidateId().equals("E")).findFirst().orElseThrow();
        assertThat(tail.code()).isEqualTo("AFTER_ELBOW");
        assertThat(tail.triggerCandidateId()).isEqualTo("D");
        assertThat(tail.clusterAverage()).isEqualTo(drop.clusterAverage());
        assertThat(events.stream().filter(e -> e.candidateId().equals("F")).findFirst().orElseThrow().code())
                .isEqualTo("BELOW_ABSOLUTE_MINIMUM");
        assertThat(events.stream().filter(e -> e.candidateId().equals("A")).findFirst().orElseThrow().threshold()).isNull();
    }

    @Test
    void hrRecordsActualDeletesRejectionsAndExhaustionWithRanksAndCause() {
        var candidates = List.of("A", "B", "C").stream().map(id -> new CandidateDTO(id, id, null, null, null, null)).toList();
        var vacancies = List.of(new VacancyDTO("V", null, null, null, null));
        var cp = Map.of("A", List.of("V"), "B", List.of("V"), "C", List.of("V"));
        var vp = Map.of("V", List.of("B", "A", "C"));
        List<HrEvent> events = new ArrayList<>();
        HRMatchingAlgorithm hr = new HRMatchingAlgorithm();
        var traced = hr.executeMatching(candidates, vacancies, cp, vp, 1, events::add);
        assertThat(traced).isEqualTo(hr.executeMatching(candidates, vacancies, cp, vp, 1));
        HrEvent delete = events.stream().filter(e -> e.type().equals("DELETE_STEP")).findFirst().orElseThrow();
        assertThat(delete.candidateId()).isEqualTo("C");
        assertThat(delete.causeCandidateId()).isEqualTo("A");
        assertThat(delete.vacancyPreferenceRank()).isEqualTo(3);
        assertThat(delete.causeVacancyRank()).isEqualTo(2);
        assertThat(delete.occupancy()).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.candidateId().equals("C")).map(HrEvent::type))
                .contains("OPTIONS_EXHAUSTED", "FINAL_UNASSIGNED").doesNotContain("PROPOSAL", "CAPACITY_REJECTION");
        assertThat(events.stream().filter(e -> e.candidateId().equals("A")).map(HrEvent::type))
                .contains("CAPACITY_REJECTION", "OPTIONS_EXHAUSTED", "FINAL_UNASSIGNED");
    }
}
