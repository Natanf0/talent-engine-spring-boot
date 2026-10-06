package br.com.careermatcher.services;

import br.com.careermatcher.enums.RankingCriterion;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.rankers.ExperienceRanker;
import br.com.careermatcher.rankers.GraduateRanker;
import br.com.careermatcher.rankers.LanguageRanker;
import br.com.careermatcher.rankers.LocationRanker;
import br.com.careermatcher.rankers.ModalityRanker;
import br.com.careermatcher.rankers.RankingResult;
import br.com.careermatcher.rankers.RankerDetail;
import java.util.function.Consumer;
import br.com.careermatcher.rankers.SeniorityRanker;
import br.com.careermatcher.rankers.SkillRanker;
import br.com.careermatcher.rankers.UndergraduateRanker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds score-based preference lists containing only strictly positive scores
 * from each side's perspective. Matching algorithms require a pair to appear
 * in both lists before accepting it.
 */
@Service
@RequiredArgsConstructor
public class RankerService {

    private final SeniorityRanker seniorityRanker;
    private final ExperienceRanker experienceRanker;
    private final SkillRanker skillRanker;
    private final LanguageRanker languageRanker;
    private final UndergraduateRanker undergraduateRanker;
    private final GraduateRanker graduateRanker;
    private final LocationRanker locationRanker;
    private final ModalityRanker modalityRanker;

    /** Scores candidate suitability from the vacancy's business perspective. */
    public RankingResult rankCandidateForVacancy(Candidate candidate, Vacancy vacancy) {
        return score(candidate, vacancy, false);
    }

    /**
     * Scores the candidate's preferences, including the remote-vacancy bonus.
     */
    public RankingResult rankVacancyForCandidate(Candidate candidate, Vacancy vacancy) {
        return score(candidate, vacancy, true);
    }

    public Map<Candidate, List<Vacancy>> createCandidatePreferenceLists(
            List<Candidate> candidates,
            List<Vacancy> vacancies
    ) {
        return createCandidatePreferenceLists(candidates, vacancies, ignored -> {});
    }

    public Map<Candidate, List<Vacancy>> createCandidatePreferenceLists(
            List<Candidate> candidates, List<Vacancy> vacancies, Consumer<RankedPair> observer
    ) {
        List<Candidate> canonicalCandidates = distinctByIdentity(candidates);
        List<Vacancy> canonicalVacancies = distinctByIdentity(vacancies);
        Map<Candidate, Integer> candidateIndexes = identityIndexes(canonicalCandidates);
        Map<Vacancy, Integer> vacancyIndexes = identityIndexes(canonicalVacancies);
        Comparator<Candidate> candidateOrder = candidateComparator(candidateIndexes);
        Comparator<Vacancy> vacancyOrder = vacancyComparator(vacancyIndexes);

        canonicalCandidates.sort(candidateOrder);
        LinkedHashMap<Candidate, List<Vacancy>> preferences = new LinkedHashMap<>();
        for (Candidate candidate : canonicalCandidates) {
            List<Scored<Vacancy>> scores = canonicalVacancies.stream()
                    .map(vacancy -> {
                        RankingResult result = rankVacancyForCandidate(candidate, vacancy);
                        observer.accept(new RankedPair(candidate, vacancy, true, result));
                        return new Scored<>(vacancy, result.totalScore());
                    })
                    .filter(scored -> scored.score() > 0.0)
                    .sorted(Comparator.<Scored<Vacancy>>comparingDouble(Scored::score).reversed()
                            .thenComparing(Scored::value, vacancyOrder))
                    .toList();
            preferences.put(candidate, scores.stream().map(Scored::value).toList());
        }
        return preferences;
    }

    public Map<Vacancy, List<Candidate>> createVacancyPreferenceLists(
            List<Vacancy> vacancies,
            List<Candidate> candidates
    ) {
        List<Vacancy> canonicalVacancies = distinctByIdentity(vacancies);
        List<Candidate> canonicalCandidates = distinctByIdentity(candidates);
        Map<Vacancy, Integer> vacancyIndexes = identityIndexes(canonicalVacancies);
        Map<Candidate, Integer> candidateIndexes = identityIndexes(canonicalCandidates);
        Comparator<Vacancy> vacancyOrder = vacancyComparator(vacancyIndexes);
        Comparator<Candidate> candidateOrder = candidateComparator(candidateIndexes);

        canonicalVacancies.sort(vacancyOrder);
        LinkedHashMap<Vacancy, List<Candidate>> preferences = new LinkedHashMap<>();
        for (Vacancy vacancy : canonicalVacancies) {
            List<Scored<Candidate>> scores = canonicalCandidates.stream()
                    .map(candidate -> new Scored<>(candidate, rankCandidateForVacancy(candidate, vacancy).totalScore()))
                    .filter(scored -> scored.score() > 0.0)
                    .sorted(Comparator.<Scored<Candidate>>comparingDouble(Scored::score).reversed()
                            .thenComparing(Scored::value, candidateOrder))
                    .toList();
            preferences.put(vacancy, scores.stream().map(Scored::value).toList());
        }
        return preferences;
    }

    /** Raw vacancy-perspective scores, evaluated once per pair in canonical participant order. */
    public Map<Vacancy, Map<Candidate, Double>> calculateVacancyScores(
            List<Vacancy> vacancies, List<Candidate> candidates
    ) {
        return calculateVacancyScores(vacancies, candidates, ignored -> {});
    }

    public Map<Vacancy, Map<Candidate, Double>> calculateVacancyScores(
            List<Vacancy> vacancies, List<Candidate> candidates, Consumer<RankedPair> observer
    ) {
        List<Vacancy> canonicalVacancies = distinctByIdentity(vacancies);
        List<Candidate> canonicalCandidates = distinctByIdentity(candidates);
        canonicalVacancies.sort(vacancyComparator(identityIndexes(canonicalVacancies)));
        canonicalCandidates.sort(candidateComparator(identityIndexes(canonicalCandidates)));
        Map<Vacancy, Map<Candidate, Double>> scores = new LinkedHashMap<>();
        for (Vacancy vacancy : canonicalVacancies) {
            Map<Candidate, Double> candidateScores = new LinkedHashMap<>();
            for (Candidate candidate : canonicalCandidates) {
                RankingResult result = rankCandidateForVacancy(candidate, vacancy);
                observer.accept(new RankedPair(candidate, vacancy, false, result));
                candidateScores.put(candidate, result.totalScore());
            }
            scores.put(vacancy, candidateScores);
        }
        return scores;
    }

    private RankingResult score(Candidate candidate, Vacancy vacancy, boolean includeModality) {
        EnumMap<RankingCriterion, Double> breakdown = new EnumMap<>(RankingCriterion.class);
        List<RankerDetail> details = new ArrayList<>();
        addDetail(breakdown, details, RankingCriterion.SENIORITY, seniorityRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.EXPERIENCE, experienceRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.SKILL, skillRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.LANGUAGE, languageRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.UNDERGRADUATE, undergraduateRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.GRADUATE, graduateRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.LOCATION, locationRanker.evaluate(candidate, vacancy, includeModality));
        addDetail(breakdown, details, RankingCriterion.MODALITY, modalityRanker.evaluate(candidate, vacancy, includeModality));

        double total = 0.0;
        for (RankingCriterion criterion : RankingCriterion.values()) {
            total += breakdown.getOrDefault(criterion, 0.0);
        }
        return new RankingResult(total, breakdown, details);
    }

    private static void addDetail(Map<RankingCriterion, Double> breakdown, List<RankerDetail> details,
                                  RankingCriterion criterion, RankerDetail detail) {
        breakdown.put(criterion, detail.points());
        details.add(detail);
    }

    public record RankedPair(Candidate candidate, Vacancy vacancy, boolean candidatePerspective, RankingResult result) {}

    private static Comparator<Candidate> candidateComparator(Map<Candidate, Integer> indexes) {
        return Comparator.comparing(Candidate::getId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(candidate -> normalize(candidate.getName()))
                .thenComparing(candidate -> normalize(candidate.getProfessionalTitle()))
                .thenComparingInt(indexes::get);
    }

    private static Comparator<Vacancy> vacancyComparator(Map<Vacancy, Integer> indexes) {
        return Comparator.comparing(Vacancy::getId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(vacancy -> normalize(vacancy.getRole()))
                .thenComparing(vacancy -> normalize(vacancy.getCity()))
                .thenComparingInt(vacancy -> vacancy.getSeniority() == null
                        ? Integer.MAX_VALUE : vacancy.getSeniority().getValor())
                .thenComparing(vacancy -> vacancy.getModalidade() == null
                        ? "" : vacancy.getModalidade().name())
                .thenComparingInt(indexes::get);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static <T> List<T> distinctByIdentity(List<T> values) {
        List<T> distinct = new ArrayList<>();
        IdentityHashMap<T, Boolean> seen = new IdentityHashMap<>();
        if (values != null) {
            for (T value : values) {
                if (value != null && seen.put(value, Boolean.TRUE) == null) {
                    distinct.add(value);
                }
            }
        }
        return distinct;
    }

    private static <T> Map<T, Integer> identityIndexes(List<T> values) {
        IdentityHashMap<T, Integer> indexes = new IdentityHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            indexes.put(values.get(index), index);
        }
        return indexes;
    }

    private record Scored<T>(T value, double score) {
    }
}
