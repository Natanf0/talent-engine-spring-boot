package br.com.careermatcher.services;

import br.com.careermatcher.interfaces.MatchingAlgorithm;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;

@Service
public class GaleShapley implements MatchingAlgorithm {

    @Override
    public Map<Candidate, Vacancy> match(
            List<Candidate> candidates,
            Map<Candidate, List<Vacancy>> candidatePreferencesList,
            List<Vacancy> vacancies,
            Map<Vacancy, List<Candidate>> vacancyPreferencesList
    ) {
        CanonicalRegistry<Candidate> candidateRegistry = CanonicalRegistry.create(
                candidates, Candidate::getId, candidateComparator()
        );
        CanonicalRegistry<Vacancy> vacancyRegistry = CanonicalRegistry.create(
                vacancies, Vacancy::getId, vacancyComparator()
        );

        Map<Candidate, List<Vacancy>> candidatePreferences = canonicalCandidatePreferences(
                candidateRegistry, vacancyRegistry, candidatePreferencesList
        );
        Map<Vacancy, List<Candidate>> vacancyPreferences = canonicalVacancyPreferences(
                vacancyRegistry, candidateRegistry, vacancyPreferencesList
        );
        Map<Vacancy, Map<Candidate, Integer>> vacancyRanks = createVacancyRanks(
                vacancyRegistry.values(), vacancyPreferences
        );

        Queue<Candidate> freeCandidates = new ArrayDeque<>();
        IdentityHashMap<Candidate, Integer> nextProposal = new IdentityHashMap<>();
        for (Candidate candidate : candidateRegistry.values()) {
            nextProposal.put(candidate, 0);
            if (!candidatePreferences.get(candidate).isEmpty()) {
                freeCandidates.add(candidate);
            }
        }

        IdentityHashMap<Vacancy, Candidate> candidateByVacancy = new IdentityHashMap<>();
        IdentityHashMap<Candidate, Vacancy> vacancyByCandidate = new IdentityHashMap<>();

        while (!freeCandidates.isEmpty()) {
            Candidate proposer = freeCandidates.remove();
            List<Vacancy> preferences = candidatePreferences.get(proposer);
            int proposalIndex = nextProposal.get(proposer);
            if (proposalIndex >= preferences.size()) {
                continue;
            }

            Vacancy vacancy = preferences.get(proposalIndex);
            nextProposal.put(proposer, proposalIndex + 1);
            Map<Candidate, Integer> ranks = vacancyRanks.get(vacancy);
            Integer proposerRank = ranks.get(proposer);

            if (proposerRank != null) {
                Candidate current = candidateByVacancy.get(vacancy);
                if (current == null) {
                    engage(proposer, vacancy, candidateByVacancy, vacancyByCandidate);
                } else if (proposerRank < ranks.get(current)) {
                    vacancyByCandidate.remove(current);
                    engage(proposer, vacancy, candidateByVacancy, vacancyByCandidate);
                    requeueIfPossible(current, candidatePreferences, nextProposal, freeCandidates);
                } else {
                    requeueIfPossible(proposer, candidatePreferences, nextProposal, freeCandidates);
                }
            } else {
                requeueIfPossible(proposer, candidatePreferences, nextProposal, freeCandidates);
            }
        }

        LinkedHashMap<Candidate, Vacancy> result = new LinkedHashMap<>();
        for (Candidate candidate : candidateRegistry.values()) {
            Vacancy vacancy = vacancyByCandidate.get(candidate);
            if (vacancy != null) {
                result.put(candidate, vacancy);
            }
        }
        return result;
    }

    private static void engage(
            Candidate candidate,
            Vacancy vacancy,
            IdentityHashMap<Vacancy, Candidate> candidateByVacancy,
            IdentityHashMap<Candidate, Vacancy> vacancyByCandidate
    ) {
        candidateByVacancy.put(vacancy, candidate);
        vacancyByCandidate.put(candidate, vacancy);
    }

    private static void requeueIfPossible(
            Candidate candidate,
            Map<Candidate, List<Vacancy>> preferences,
            IdentityHashMap<Candidate, Integer> nextProposal,
            Queue<Candidate> freeCandidates
    ) {
        if (nextProposal.get(candidate) < preferences.get(candidate).size()) {
            freeCandidates.add(candidate);
        }
    }

    private static Map<Candidate, List<Vacancy>> canonicalCandidatePreferences(
            CanonicalRegistry<Candidate> candidates,
            CanonicalRegistry<Vacancy> vacancies,
            Map<Candidate, List<Vacancy>> source
    ) {
        IdentityHashMap<Candidate, List<Vacancy>> result = new IdentityHashMap<>();
        for (Candidate candidate : candidates.values()) {
            result.put(candidate, List.of());
        }
        if (source == null) {
            return result;
        }

        IdentityHashMap<Candidate, Boolean> assigned = new IdentityHashMap<>();
        for (Map.Entry<Candidate, List<Vacancy>> entry : source.entrySet()) {
            Candidate candidate = candidates.canonicalize(entry.getKey());
            if (candidate == null) {
                continue;
            }
            if (assigned.put(candidate, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Multiple preference lists for candidate id " + candidate.getId());
            }
            result.put(candidate, canonicalizeList(entry.getValue(), vacancies));
        }
        return result;
    }

    private static Map<Vacancy, List<Candidate>> canonicalVacancyPreferences(
            CanonicalRegistry<Vacancy> vacancies,
            CanonicalRegistry<Candidate> candidates,
            Map<Vacancy, List<Candidate>> source
    ) {
        IdentityHashMap<Vacancy, List<Candidate>> result = new IdentityHashMap<>();
        for (Vacancy vacancy : vacancies.values()) {
            result.put(vacancy, List.of());
        }
        if (source == null) {
            return result;
        }

        IdentityHashMap<Vacancy, Boolean> assigned = new IdentityHashMap<>();
        for (Map.Entry<Vacancy, List<Candidate>> entry : source.entrySet()) {
            Vacancy vacancy = vacancies.canonicalize(entry.getKey());
            if (vacancy == null) {
                continue;
            }
            if (assigned.put(vacancy, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Multiple preference lists for vacancy id " + vacancy.getId());
            }
            result.put(vacancy, canonicalizeList(entry.getValue(), candidates));
        }
        return result;
    }

    private static <T> List<T> canonicalizeList(List<T> source, CanonicalRegistry<T> registry) {
        if (source == null) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        IdentityHashMap<T, Boolean> seen = new IdentityHashMap<>();
        for (T value : source) {
            T canonical = registry.canonicalize(value);
            if (canonical != null && seen.put(canonical, Boolean.TRUE) == null) {
                result.add(canonical);
            }
        }
        return List.copyOf(result);
    }

    private static Map<Vacancy, Map<Candidate, Integer>> createVacancyRanks(
            List<Vacancy> vacancies,
            Map<Vacancy, List<Candidate>> preferences
    ) {
        IdentityHashMap<Vacancy, Map<Candidate, Integer>> ranks = new IdentityHashMap<>();
        for (Vacancy vacancy : vacancies) {
            IdentityHashMap<Candidate, Integer> positions = new IdentityHashMap<>();
            List<Candidate> ordered = preferences.get(vacancy);
            for (int index = 0; index < ordered.size(); index++) {
                positions.put(ordered.get(index), index);
            }
            ranks.put(vacancy, positions);
        }
        return ranks;
    }

    private static Comparator<Candidate> candidateComparator() {
        return Comparator.comparing(Candidate::getId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(candidate -> normalize(candidate.getName()))
                .thenComparing(candidate -> normalize(candidate.getProfessionalTitle()));
    }

    private static Comparator<Vacancy> vacancyComparator() {
        return Comparator.comparing(Vacancy::getId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(vacancy -> normalize(vacancy.getRole()))
                .thenComparing(vacancy -> normalize(vacancy.getCity()))
                .thenComparingInt(vacancy -> vacancy.getSeniority() == null
                        ? Integer.MAX_VALUE : vacancy.getSeniority().getValor())
                .thenComparing(vacancy -> vacancy.getModalidade() == null
                        ? "" : vacancy.getModalidade().name());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static final class CanonicalRegistry<T> {
        private final List<T> values;
        private final Map<Long, T> valuesById;
        private final IdentityHashMap<T, Boolean> nullIdValues;
        private final Function<T, Long> idExtractor;

        private CanonicalRegistry(
                List<T> values,
                Map<Long, T> valuesById,
                IdentityHashMap<T, Boolean> nullIdValues,
                Function<T, Long> idExtractor
        ) {
            this.values = values;
            this.valuesById = valuesById;
            this.nullIdValues = nullIdValues;
            this.idExtractor = idExtractor;
        }

        static <T> CanonicalRegistry<T> create(
                List<T> source,
                Function<T, Long> idExtractor,
                Comparator<T> comparator
        ) {
            List<T> values = new ArrayList<>();
            Map<Long, T> valuesById = new HashMap<>();
            IdentityHashMap<T, Boolean> nullIdValues = new IdentityHashMap<>();
            IdentityHashMap<T, Boolean> seenInstances = new IdentityHashMap<>();

            if (source != null) {
                for (T value : source) {
                    if (value == null || seenInstances.put(value, Boolean.TRUE) != null) {
                        continue;
                    }
                    Long id = idExtractor.apply(value);
                    if (id == null) {
                        nullIdValues.put(value, Boolean.TRUE);
                    } else {
                        T existing = valuesById.putIfAbsent(id, value);
                        if (existing != null && existing != value) {
                            throw new IllegalArgumentException("Duplicate participant id " + id);
                        }
                    }
                    values.add(value);
                }
            }
            values.sort(comparator);
            return new CanonicalRegistry<>(List.copyOf(values), valuesById, nullIdValues, idExtractor);
        }

        List<T> values() {
            return values;
        }

        T canonicalize(T value) {
            if (value == null) {
                return null;
            }
            Long id = idExtractor.apply(value);
            if (id != null) {
                return valuesById.get(id);
            }
            return nullIdValues.containsKey(value) ? value : null;
        }
    }
}
