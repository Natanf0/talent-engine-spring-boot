package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.AllocationResult;
import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.DTOs.matching.VacancyDTO;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.services.GaleShapley;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HRMatchingAlgorithmTest {

    private final HRMatchingAlgorithm algorithm = new HRMatchingAlgorithm();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void enforcesCapacityExclusivityRankingAndCompleteAccounting(int k) {
        List<CandidateDTO> candidates = IntStream.rangeClosed(1, 23)
                .mapToObj(id -> candidate(Integer.toString(id))).toList();
        List<VacancyDTO> vacancies = List.of(vacancy("H1"), vacancy("H2"), vacancy("H3"));
        Map<String, List<String>> candidatePreferences = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidatePreferences.put(candidate.id(), List.of("H1", "H2", "H3")));
        List<String> vacancyOrder = IntStream.iterate(23, id -> id > 0, id -> id - 1)
                .mapToObj(Integer::toString).toList();
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", vacancyOrder, "H2", vacancyOrder, "H3", vacancyOrder
        );

        AllocationResult result = algorithm.executeMatching(
                candidates, vacancies, candidatePreferences, vacancyPreferences, k
        );

        assertThat(result.totalAllocated()).isEqualTo(Math.min(candidates.size(), vacancies.size() * k));
        assertValidAndStable(result, candidates, vacancies, candidatePreferences, vacancyPreferences, k);
        for (int index = 0; index < vacancies.size(); index++) {
            int start = Math.min(index * k, candidates.size());
            int end = Math.min(start + k, candidates.size());
            assertThat(result.shortlists().get(vacancies.get(index)))
                    .extracting(CandidateDTO::id).containsExactlyElementsOf(vacancyOrder.subList(start, end));
        }
    }

    @Test
    void rejectionAfterDeleteStepDoesNotSkipTheNextVacancy() {
        List<CandidateDTO> candidates = List.of(candidate("A"), candidate("B"));
        List<VacancyDTO> vacancies = List.of(vacancy("H1"), vacancy("H2"));
        Map<String, List<String>> candidatePreferences = Map.of(
                "A", List.of("H1", "H2"), "B", List.of("H1")
        );
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", List.of("B", "A"), "H2", List.of("A")
        );

        AllocationResult result = algorithm.executeMatching(
                candidates, vacancies, candidatePreferences, vacancyPreferences, 1
        );

        assertThat(assignmentsByCandidate(result)).containsExactlyInAnyOrderEntriesOf(Map.of("A", "H2", "B", "H1"));
        assertValidAndStable(result, candidates, vacancies, candidatePreferences, vacancyPreferences, 1);
    }

    @Test
    void propagatesRejectionsAcrossFullShortlists() {
        List<CandidateDTO> candidates = List.of("A", "B", "C", "D", "E", "F", "G", "H")
                .stream().map(HRMatchingAlgorithmTest::candidate).toList();
        List<VacancyDTO> vacancies = List.of(vacancy("H1"), vacancy("H2"), vacancy("H3"));
        Map<String, List<String>> candidatePreferences = Map.of(
                "A", List.of("H1", "H2", "H3"), "B", List.of("H1"),
                "C", List.of("H1", "H2"), "D", List.of("H2", "H3"),
                "E", List.of("H2"), "F", List.of("H1"),
                "G", List.of("H3"), "H", List.of("H3")
        );
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", List.of("F", "C", "B", "A"),
                "H2", List.of("A", "E", "D", "C"),
                "H3", List.of("D", "G", "H", "A")
        );

        AllocationResult result = algorithm.executeMatching(
                candidates, vacancies, candidatePreferences, vacancyPreferences, 2
        );

        assertThat(result.shortlists().get(vacancies.get(0))).extracting(CandidateDTO::id).containsExactly("F", "C");
        assertThat(result.shortlists().get(vacancies.get(1))).extracting(CandidateDTO::id).containsExactly("A", "E");
        assertThat(result.shortlists().get(vacancies.get(2))).extracting(CandidateDTO::id).containsExactly("D", "G");
        assertThat(result.unassignedCandidateIds()).containsExactlyInAnyOrder("B", "H");
        assertValidAndStable(result, candidates, vacancies, candidatePreferences, vacancyPreferences, 2);
    }

    @Test
    void acceptsOnlyMutuallyListedPairsAndPreservesEmptyVacancies() {
        List<CandidateDTO> candidates = List.of(candidate("A"), candidate("B"), candidate("C"));
        List<VacancyDTO> vacancies = List.of(vacancy("H1"), vacancy("H2"), vacancy("H3"));
        Map<String, List<String>> candidatePreferences = Map.of(
                "A", List.of("H1", "H2"), "B", List.of("H1")
        );
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", List.of("C"), "H2", List.of("A"), "H3", List.of("B")
        );

        AllocationResult result = algorithm.executeMatching(
                candidates, vacancies, candidatePreferences, vacancyPreferences, 5
        );

        assertThat(assignmentsByCandidate(result)).containsExactlyEntriesOf(Map.of("A", "H2"));
        assertThat(result.shortlists().get(vacancies.get(0))).isEmpty();
        assertThat(result.shortlists().get(vacancies.get(2))).isEmpty();
        assertValidAndStable(result, candidates, vacancies, candidatePreferences, vacancyPreferences, 5);
    }

    @Test
    void handlesEmptyParticipantGroups() {
        CandidateDTO candidate = candidate("A");
        VacancyDTO vacancy = vacancy("H1");

        AllocationResult noCandidates = algorithm.executeMatching(List.of(), List.of(vacancy), Map.of(), Map.of(), 2);
        assertThat(noCandidates.shortlists()).containsExactlyEntriesOf(Map.of(vacancy, List.of()));
        assertThat(noCandidates.unassignedCandidateIds()).isEmpty();
        assertThat(noCandidates.totalAllocated()).isZero();

        AllocationResult noVacancies = algorithm.executeMatching(List.of(candidate), List.of(), Map.of(), Map.of(), 2);
        assertThat(noVacancies.shortlists()).isEmpty();
        assertThat(noVacancies.unassignedCandidateIds()).containsExactly("A");
        assertThat(noVacancies.totalAllocated()).isZero();
    }

    @Test
    void treatsNullPreferenceMapsAndListsAsEmpty() {
        CandidateDTO candidate = candidate("A");
        VacancyDTO vacancy = vacancy("H1");
        AllocationResult noPreferences = algorithm.executeMatching(List.of(candidate), List.of(vacancy), null, null, 2);
        assertThat(noPreferences.shortlists()).containsExactlyEntriesOf(Map.of(vacancy, List.of()));
        assertThat(noPreferences.unassignedCandidateIds()).containsExactly("A");

        Map<String, List<String>> candidatePreferences = new LinkedHashMap<>();
        candidatePreferences.put("A", null);
        Map<String, List<String>> vacancyPreferences = new LinkedHashMap<>();
        vacancyPreferences.put("H1", null);
        assertThat(algorithm.executeMatching(List.of(candidate), List.of(vacancy), candidatePreferences, vacancyPreferences, 2))
                .isEqualTo(noPreferences);

    }

    @Test
    void rejectsNullParticipantLists() {
        assertThatThrownBy(() -> algorithm.executeMatching(null, List.of(), Map.of(), Map.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.executeMatching(List.of(), null, Map.of(), Map.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresUnknownAndNullReferencesAndDeduplicatesPreferencesInOrder() {
        CandidateDTO candidate = candidate("A");
        VacancyDTO first = vacancy("H1");
        VacancyDTO second = vacancy("H2");
        Map<String, List<String>> candidatePreferences = Map.of(
                "A", Arrays.asList(null, "unknown-vacancy", "H2", "H2", "H1"),
                "unknown-candidate", List.of("H1")
        );
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", List.of("A"),
                "H2", Arrays.asList(null, "unknown-candidate", "A", "A"),
                "unknown-vacancy", List.of("A")
        );

        AllocationResult result = algorithm.executeMatching(
                List.of(candidate), List.of(first, second), candidatePreferences, vacancyPreferences, 2
        );

        assertThat(result.shortlists()).containsExactlyInAnyOrderEntriesOf(Map.of(first, List.of(), second, List.of(candidate)));
        assertThat(result.totalAllocated()).isEqualTo(1);
        assertThat(result.unassignedCandidateIds()).isEmpty();
    }

    @Test
    void doesNotModifyPreferencesAndCanBeRunRepeatedlyWithDifferentCapacities() {
        List<CandidateDTO> candidates = List.of(candidate("A"), candidate("B"), candidate("C"));
        List<VacancyDTO> vacancies = List.of(vacancy("H1"), vacancy("H2"));
        Map<String, List<String>> candidatePreferences = Map.of(
                "A", List.of("H1", "H2"), "B", List.of("H1", "H2"), "C", List.of("H1", "H2")
        );
        Map<String, List<String>> vacancyPreferences = Map.of(
                "H1", List.of("C", "B", "A"), "H2", List.of("A", "B", "C")
        );
        Map<String, List<String>> mutableCandidates = mutableCopy(candidatePreferences);
        Map<String, List<String>> mutableVacancies = mutableCopy(vacancyPreferences);

        AllocationResult first = algorithm.executeMatching(candidates, vacancies, mutableCandidates, mutableVacancies, 1);
        assertThat(mutableCandidates).isEqualTo(candidatePreferences);
        assertThat(mutableVacancies).isEqualTo(vacancyPreferences);
        AllocationResult larger = algorithm.executeMatching(candidates, vacancies, candidatePreferences, vacancyPreferences, 2);
        AllocationResult repeated = algorithm.executeMatching(candidates, vacancies, candidatePreferences, vacancyPreferences, 1);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.totalAllocated()).isEqualTo(2);
        assertThat(larger.totalAllocated()).isEqualTo(3);
        assertValidAndStable(larger, candidates, vacancies, candidatePreferences, vacancyPreferences, 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveCapacity(int k) {
        assertThatThrownBy(() -> algorithm.executeMatching(List.of(), List.of(), Map.of(), Map.of(), k))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingParticipantIds(String id) {
        assertThatThrownBy(() -> algorithm.executeMatching(List.of(candidate(id)), List.of(), Map.of(), Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.executeMatching(List.of(), List.of(vacancy(id)), Map.of(), Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateParticipantIds() {
        assertThatThrownBy(() -> algorithm.executeMatching(
                List.of(candidate("A"), candidate("A")), List.of(), Map.of(), Map.of(), 1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.executeMatching(
                List.of(), List.of(vacancy("H1"), vacancy("H1")), Map.of(), Map.of(), 1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomIncompletePreferencesStayStableAndMatchGaleShapleyForCapacityOne() {
        Random random = new Random(438572L);
        for (int scenario = 0; scenario < 100; scenario++) {
            List<CandidateDTO> candidates = IntStream.rangeClosed(1, 1 + random.nextInt(9))
                    .mapToObj(id -> candidate(Integer.toString(id))).toList();
            List<VacancyDTO> vacancies = IntStream.rangeClosed(10, 10 + random.nextInt(4))
                    .mapToObj(id -> vacancy(Integer.toString(id))).toList();
            Map<String, List<String>> candidatePreferences = new LinkedHashMap<>();
            for (CandidateDTO candidate : candidates) {
                candidatePreferences.put(candidate.id(), randomPreferences(vacancies.stream().map(VacancyDTO::id).toList(), random));
            }
            Map<String, List<String>> vacancyPreferences = new LinkedHashMap<>();
            for (VacancyDTO vacancy : vacancies) {
                vacancyPreferences.put(vacancy.id(), randomPreferences(candidates.stream().map(CandidateDTO::id).toList(), random));
            }

            for (int k : new int[]{1, 2, 3, 5, 10}) {
                AllocationResult result = algorithm.executeMatching(candidates, vacancies, candidatePreferences, vacancyPreferences, k);
                assertValidAndStable(result, candidates, vacancies, candidatePreferences, vacancyPreferences, k);
                if (k == 1) {
                    assertThat(assignmentsByCandidate(result)).as("Gale-Shapley parity in scenario %s", scenario)
                            .containsExactlyInAnyOrderEntriesOf(galeShapley(candidates, vacancies, candidatePreferences, vacancyPreferences));
                }
            }
        }
    }

    private static void assertValidAndStable(
            AllocationResult result,
            List<CandidateDTO> candidates,
            List<VacancyDTO> vacancies,
            Map<String, List<String>> candidatePreferences,
            Map<String, List<String>> vacancyPreferences,
            int k
    ) {
        assertThat(result.shortlists().keySet()).containsExactlyInAnyOrderElementsOf(vacancies);
        List<String> allocatedIds = result.shortlists().values().stream()
                .flatMap(List::stream).map(CandidateDTO::id).toList();
        assertThat(allocatedIds).doesNotHaveDuplicates();
        assertThat(result.totalAllocated()).isEqualTo(allocatedIds.size());
        Set<String> expectedUnassigned = new HashSet<>(candidates.stream().map(CandidateDTO::id).toList());
        assertThat(expectedUnassigned).containsAll(allocatedIds);
        expectedUnassigned.removeAll(allocatedIds);
        assertThat(result.unassignedCandidateIds()).containsExactlyInAnyOrderElementsOf(expectedUnassigned);

        Map<String, List<String>> assignedByVacancy = new LinkedHashMap<>();
        result.shortlists().forEach((vacancy, assigned) -> {
            assertThat(assigned).hasSizeLessThanOrEqualTo(k);
            List<String> preferenceOrder = vacancyPreferences.getOrDefault(vacancy.id(), List.of());
            assertThat(assigned.stream().map(candidate -> preferenceOrder.indexOf(candidate.id())).toList()).isSorted();
            for (CandidateDTO candidate : assigned) {
                assertThat(preferenceOrder).contains(candidate.id());
                assertThat(candidatePreferences.getOrDefault(candidate.id(), List.of())).contains(vacancy.id());
            }
            assignedByVacancy.put(vacancy.id(), assigned.stream().map(CandidateDTO::id).toList());
        });

        Map<String, String> currentVacancyByCandidate = assignmentsByCandidate(result);
        for (CandidateDTO candidate : candidates) {
            String currentVacancy = currentVacancyByCandidate.get(candidate.id());
            for (String preferredVacancy : candidatePreferences.getOrDefault(candidate.id(), List.of())) {
                if (preferredVacancy.equals(currentVacancy)) {
                    break;
                }
                List<String> preferenceOrder = vacancyPreferences.getOrDefault(preferredVacancy, List.of());
                int candidateRank = preferenceOrder.indexOf(candidate.id());
                if (candidateRank < 0) {
                    continue;
                }
                List<String> assigned = assignedByVacancy.get(preferredVacancy);
                String blockingPair = "blocking pair candidate " + candidate.id() + " / vacancy " + preferredVacancy + " at K=" + k;
                assertThat(assigned).as(blockingPair + " with spare capacity").hasSize(k);
                assertThat(assigned.stream().mapToInt(preferenceOrder::indexOf).max().orElseThrow())
                        .as(blockingPair + " replacing a worse candidate").isLessThan(candidateRank);
            }
        }
    }

    private static Map<String, String> galeShapley(
            List<CandidateDTO> candidateDTOs,
            List<VacancyDTO> vacancyDTOs,
            Map<String, List<String>> candidatePreferences,
            Map<String, List<String>> vacancyPreferences
    ) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (CandidateDTO dto : candidateDTOs) {
            Candidate candidate = new Candidate(dto.name(), dto.about(), dto.professionalTitle(), dto.phone());
            ReflectionTestUtils.setField(candidate, "id", Long.valueOf(dto.id()));
            candidates.put(dto.id(), candidate);
        }
        Map<String, Vacancy> vacancies = new LinkedHashMap<>();
        for (VacancyDTO dto : vacancyDTOs) {
            Vacancy vacancy = new Vacancy(dto.role(), dto.city(), dto.seniority(), dto.modalidade());
            ReflectionTestUtils.setField(vacancy, "id", Long.valueOf(dto.id()));
            vacancies.put(dto.id(), vacancy);
        }
        Map<Candidate, List<Vacancy>> candidateChoices = new LinkedHashMap<>();
        candidatePreferences.forEach((id, choices) -> candidateChoices.put(candidates.get(id), choices.stream().map(vacancies::get).toList()));
        Map<Vacancy, List<Candidate>> vacancyChoices = new LinkedHashMap<>();
        vacancyPreferences.forEach((id, choices) -> vacancyChoices.put(vacancies.get(id), choices.stream().map(candidates::get).toList()));
        Map<Candidate, Vacancy> matching = new GaleShapley().match(
                new ArrayList<>(candidates.values()), candidateChoices, new ArrayList<>(vacancies.values()), vacancyChoices
        );
        Map<String, String> result = new LinkedHashMap<>();
        matching.forEach((candidate, vacancy) -> result.put(candidate.getId().toString(), vacancy.getId().toString()));
        return result;
    }

    private static Map<String, String> assignmentsByCandidate(AllocationResult result) {
        Map<String, String> assignments = new LinkedHashMap<>();
        result.shortlists().forEach((vacancy, candidates) -> candidates.forEach(candidate -> assignments.put(candidate.id(), vacancy.id())));
        return assignments;
    }

    private static List<String> randomPreferences(List<String> ids, Random random) {
        List<String> result = new ArrayList<>(ids);
        Collections.shuffle(result, random);
        result.removeIf(ignored -> random.nextDouble() < 0.3);
        return List.copyOf(result);
    }

    private static Map<String, List<String>> mutableCopy(Map<String, List<String>> preferences) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        preferences.forEach((id, choices) -> copy.put(id, new ArrayList<>(choices)));
        return copy;
    }

    private static CandidateDTO candidate(String id) {
        return new CandidateDTO(id, "Candidate " + id, "About", "Developer", "0000", id + "@test.dev");
    }

    private static VacancyDTO vacancy(String id) {
        return new VacancyDTO(id, "Vacancy " + id, "Rio de Janeiro", SENIORITY.JUNIOR, Modalidade.REMOTO);
    }
}
