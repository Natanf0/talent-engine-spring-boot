package br.com.careermatcher.services;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GaleShapleyTest {

    private final GaleShapley algorithm = new GaleShapley();

    @Test
    void matchesEqualSizedGroupsAndProducesStableResult() {
        Candidate alice = candidate(1L, "Alice");
        Candidate bruno = candidate(2L, "Bruno");
        Vacancy backend = vacancy(10L, "Backend");
        Vacancy frontend = vacancy(20L, "Frontend");

        Map<Candidate, List<Vacancy>> candidatePreferences = linkedMap(
                entry(alice, List.of(backend, frontend)),
                entry(bruno, List.of(backend, frontend))
        );
        Map<Vacancy, List<Candidate>> vacancyPreferences = linkedMap(
                entry(backend, List.of(bruno, alice)),
                entry(frontend, List.of(alice, bruno))
        );

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(alice, bruno), candidatePreferences,
                List.of(backend, frontend), vacancyPreferences
        );

        assertThat(result).containsExactly(entry(alice, frontend), entry(bruno, backend));
        assertStable(result, candidatePreferences, vacancyPreferences);
        assertNoDuplicateParticipants(result);
    }

    @Test
    void leavesExtraCandidatesUnmatched() {
        Candidate first = candidate(1L, "First");
        Candidate preferred = candidate(2L, "Preferred");
        Candidate third = candidate(3L, "Third");
        Vacancy vacancy = vacancy(10L, "Only");

        Map<Candidate, List<Vacancy>> candidatePreferences = linkedMap(
                entry(first, List.of(vacancy)),
                entry(preferred, List.of(vacancy)),
                entry(third, List.of(vacancy))
        );
        Map<Vacancy, List<Candidate>> vacancyPreferences = linkedMap(
                entry(vacancy, List.of(preferred, first, third))
        );

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(first, preferred, third), candidatePreferences,
                List.of(vacancy), vacancyPreferences
        );

        assertThat(result).containsExactly(entry(preferred, vacancy));
        assertThat(result).doesNotContainKeys(first, third);
    }

    @Test
    void leavesExtraVacanciesUnmatched() {
        Candidate candidate = candidate(1L, "Candidate");
        Vacancy preferred = vacancy(10L, "Preferred");
        Vacancy extra = vacancy(20L, "Extra");

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(candidate), linkedMap(entry(candidate, List.of(preferred, extra))),
                List.of(preferred, extra), linkedMap(
                        entry(preferred, List.of(candidate)),
                        entry(extra, List.of(candidate))
                )
        );

        assertThat(result).containsExactly(entry(candidate, preferred));
        assertThat(result).doesNotContainValue(extra);
    }

    @Test
    void supportsIncompleteListsAndExhaustedCandidates() {
        Candidate accepted = candidate(1L, "Accepted");
        Candidate exhausted = candidate(2L, "Exhausted");
        Candidate noPreferences = candidate(3L, "No preferences");
        Vacancy vacancy = vacancy(10L, "Vacancy");

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(accepted, exhausted, noPreferences),
                linkedMap(
                        entry(accepted, List.of(vacancy)),
                        entry(exhausted, List.of(vacancy))
                ),
                List.of(vacancy),
                linkedMap(entry(vacancy, List.of(accepted, exhausted)))
        );

        assertThat(result).containsExactly(entry(accepted, vacancy));
        assertThat(result).doesNotContainKeys(exhausted, noPreferences);
    }

    @Test
    void treatsCandidateMissingFromVacancyListAsUnacceptable() {
        Candidate candidate = candidate(1L, "Candidate");
        Vacancy vacancy = vacancy(10L, "Vacancy");

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(candidate), linkedMap(entry(candidate, List.of(vacancy))),
                List.of(vacancy), linkedMap(entry(vacancy, List.of()))
        );

        assertThat(result).isEmpty();
    }

    @Test
    void replacesProvisionalMatchAfterEveryVacancyIsOccupied() {
        Candidate initiallyAccepted = candidate(1L, "Initially accepted");
        Candidate laterPreferred = candidate(2L, "Later preferred");
        Vacancy vacancy = vacancy(10L, "Vacancy");

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(initiallyAccepted, laterPreferred),
                linkedMap(
                        entry(initiallyAccepted, List.of(vacancy)),
                        entry(laterPreferred, List.of(vacancy))
                ),
                List.of(vacancy),
                linkedMap(entry(vacancy, List.of(laterPreferred, initiallyAccepted)))
        );

        assertThat(result).containsExactly(entry(laterPreferred, vacancy));
    }

    @Test
    void canonicalizesReloadedPreferenceInstancesById() {
        Candidate canonicalCandidate = candidate(1L, "Canonical candidate");
        Candidate reloadedCandidate = candidate(1L, "Reloaded candidate");
        Vacancy canonicalVacancy = vacancy(10L, "Canonical vacancy");
        Vacancy reloadedVacancy = vacancy(10L, "Reloaded vacancy");

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(canonicalCandidate),
                linkedMap(entry(reloadedCandidate, List.of(reloadedVacancy))),
                List.of(canonicalVacancy),
                linkedMap(entry(reloadedVacancy, List.of(reloadedCandidate)))
        );

        assertThat(result).containsExactly(entry(canonicalCandidate, canonicalVacancy));
    }

    @Test
    void ignoresNullUnknownAndDuplicatePreferences() {
        Candidate candidate = candidate(1L, "Candidate");
        Vacancy known = vacancy(10L, "Known");
        Vacancy unknown = vacancy(99L, "Unknown");
        List<Vacancy> preferences = new ArrayList<>();
        preferences.add(null);
        preferences.add(unknown);
        preferences.add(known);
        preferences.add(known);

        Map<Candidate, Vacancy> result = algorithm.match(
                List.of(candidate), linkedMap(entry(candidate, preferences)),
                List.of(known), linkedMap(entry(known, List.of(candidate, candidate)))
        );

        assertThat(result).containsExactly(entry(candidate, known));
    }

    @Test
    void treatsNullPreferenceMapsAsEmpty() {
        Candidate candidate = candidate(1L, "Candidate");
        Vacancy vacancy = vacancy(10L, "Vacancy");

        assertThat(algorithm.match(List.of(candidate), null, List.of(vacancy), null)).isEmpty();
        assertThat(algorithm.match(null, null, null, null)).isEmpty();
    }

    @Test
    void rejectsDuplicateNonNullIds() {
        Candidate first = candidate(1L, "First");
        Candidate duplicate = candidate(1L, "Duplicate");

        assertThatThrownBy(() -> algorithm.match(
                List.of(first, duplicate), Map.of(), List.of(), Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    private static void assertStable(
            Map<Candidate, Vacancy> matching,
            Map<Candidate, List<Vacancy>> candidatePreferences,
            Map<Vacancy, List<Candidate>> vacancyPreferences
    ) {
        Map<Vacancy, Candidate> candidateByVacancy = new LinkedHashMap<>();
        matching.forEach((candidate, vacancy) -> candidateByVacancy.put(vacancy, candidate));

        for (Map.Entry<Candidate, List<Vacancy>> entry : candidatePreferences.entrySet()) {
            Candidate candidate = entry.getKey();
            Vacancy currentVacancy = matching.get(candidate);
            for (Vacancy vacancy : entry.getValue()) {
                if (vacancy == currentVacancy) {
                    break;
                }
                List<Candidate> acceptable = vacancyPreferences.getOrDefault(vacancy, List.of());
                int candidateRank = acceptable.indexOf(candidate);
                if (candidateRank < 0) {
                    continue;
                }
                Candidate currentCandidate = candidateByVacancy.get(vacancy);
                int currentRank = currentCandidate == null ? Integer.MAX_VALUE : acceptable.indexOf(currentCandidate);
                assertThat(candidateRank)
                        .as("blocking pair between %s and %s", candidate.getName(), vacancy.getRole())
                        .isGreaterThanOrEqualTo(currentRank);
            }
        }
    }

    private static void assertNoDuplicateParticipants(Map<Candidate, Vacancy> matching) {
        assertThat(new HashSet<>(matching.keySet())).hasSameSizeAs(matching.keySet());
        Set<Vacancy> distinctVacancies = new HashSet<>(matching.values());
        assertThat(distinctVacancies).hasSameSizeAs(matching.values());
    }

    private static Candidate candidate(Long id, String name) {
        Candidate candidate = new Candidate(
                name, "About", "Developer", "0000");
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    private static Vacancy vacancy(Long id, String role) {
        Vacancy vacancy = new Vacancy(role, "Rio de Janeiro", SENIORITY.JUNIOR, Modalidade.REMOTO);
        ReflectionTestUtils.setField(vacancy, "id", id);
        return vacancy;
    }

    @SafeVarargs
    private static <K, V> LinkedHashMap<K, V> linkedMap(Map.Entry<K, V>... entries) {
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return Map.entry(key, value);
    }
}
