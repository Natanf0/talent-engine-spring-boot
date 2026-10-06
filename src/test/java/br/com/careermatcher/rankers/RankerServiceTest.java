package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.RankingCriterion;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.services.RankerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankerServiceTest {

    private final RankerService service = new RankerService(
            new SeniorityRanker(),
            new ExperienceRanker(),
            new SkillRanker(),
            new LanguageRanker(),
            new UndergraduateRanker(),
            new GraduateRanker(),
            new LocationRanker(),
            new ModalityRanker()
    );

    @Test
    void exposesImmutableBreakdownWhoseCriteriaSumToTotal() {
        Candidate candidate = RankerFixtures.candidate(null);
        Vacancy vacancy = RankerFixtures.vacancy(null, "Developer", SENIORITY.ESTAGIARIO, Modalidade.REMOTO);

        RankingResult result = service.rankVacancyForCandidate(candidate, vacancy);

        assertThat(result.breakdown()).containsKeys(RankingCriterion.values());
        assertThat(result.breakdown().values().stream().mapToDouble(Double::doubleValue).sum())
                .isEqualTo(result.totalScore());
        assertThatThrownBy(() -> result.breakdown().put(RankingCriterion.SKILL, 100.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modalityOnlyAffectsCandidatePerspective() {
        Candidate candidate = RankerFixtures.candidate(null);
        Vacancy vacancy = RankerFixtures.vacancy(null, "Developer", SENIORITY.ESTAGIARIO, Modalidade.REMOTO);

        RankingResult vacancyPerspective = service.rankCandidateForVacancy(candidate, vacancy);
        RankingResult candidatePerspective = service.rankVacancyForCandidate(candidate, vacancy);

        assertThat(vacancyPerspective.breakdown().get(RankingCriterion.MODALITY)).isZero();
        assertThat(candidatePerspective.breakdown().get(RankingCriterion.MODALITY)).isEqualTo(6.0);
        assertThat(candidatePerspective.totalScore() - vacancyPerspective.totalScore()).isEqualTo(6.0);
    }

    @Test
    void candidatePreferencesUseDescendingScoreThenIdAndLinkedOrder() {
        Candidate candidate = RankerFixtures.candidate(5L, "Candidate", null);
        Vacancy idTwenty = RankerFixtures.vacancy(20L, "Developer", SENIORITY.ESTAGIARIO,
                Modalidade.PRESENCIAL);
        Vacancy idTen = RankerFixtures.vacancy(10L, "Developer", SENIORITY.ESTAGIARIO,
                Modalidade.PRESENCIAL);
        Vacancy remote = RankerFixtures.vacancy(30L, "Developer", SENIORITY.ESTAGIARIO,
                Modalidade.REMOTO);

        Map<Candidate, List<Vacancy>> preferences = service.createCandidatePreferenceLists(
                List.of(candidate), List.of(idTwenty, idTen, remote));

        assertThat(preferences).isInstanceOf(LinkedHashMap.class);
        assertThat(preferences.get(candidate)).containsExactly(remote, idTen, idTwenty);
    }

    @Test
    void vacancyPreferencesUseDescendingCompatibilityThenCandidateId() {
        Vacancy vacancy = RankerFixtures.vacancy(10L, "Developer", SENIORITY.SENIOR,
                Modalidade.PRESENCIAL);
        Candidate noExperience = RankerFixtures.candidate(1L, "No experience", null);
        Candidate senior = candidateWithExperience(3L, "Senior", SENIORITY.SENIOR);
        Candidate equalSenior = candidateWithExperience(2L, "Equal senior", SENIORITY.SENIOR);

        Map<Vacancy, List<Candidate>> preferences = service.createVacancyPreferenceLists(
                List.of(vacancy), List.of(noExperience, senior, equalSenior));

        assertThat(preferences).isInstanceOf(LinkedHashMap.class);
        assertThat(preferences.get(vacancy)).containsExactly(equalSenior, senior);
    }

    @Test
    void bothPreferenceBuildersKeepOnlyStrictlyPositiveScoresAndRetainEmptyLists() {
        Candidate candidate = RankerFixtures.candidate(1L, "Candidate", null);
        Vacancy positive = RankerFixtures.vacancy(10L, "Developer", SENIORITY.ESTAGIARIO,
                Modalidade.PRESENCIAL);
        Vacancy zero = RankerFixtures.vacancy(20L, "Developer", SENIORITY.ESTAGIARIO, Modalidade.PRESENCIAL);
        ReflectionTestUtils.setField(zero, "seniority", null);
        Vacancy negative = RankerFixtures.vacancy(30L, "Developer", SENIORITY.SENIOR,
                Modalidade.PRESENCIAL);
        List<Vacancy> vacancies = List.of(negative, zero, positive);

        assertThat(service.rankVacancyForCandidate(candidate, positive).totalScore()).isPositive();
        assertThat(service.rankVacancyForCandidate(candidate, zero).totalScore()).isZero();
        assertThat(service.rankVacancyForCandidate(candidate, negative).totalScore()).isNegative();
        assertThat(service.rankCandidateForVacancy(candidate, positive).totalScore()).isPositive();
        assertThat(service.rankCandidateForVacancy(candidate, zero).totalScore()).isZero();
        assertThat(service.rankCandidateForVacancy(candidate, negative).totalScore()).isNegative();

        Map<Candidate, List<Vacancy>> candidatePreferences = service.createCandidatePreferenceLists(
                List.of(candidate), vacancies);
        Map<Vacancy, List<Candidate>> vacancyPreferences = service.createVacancyPreferenceLists(
                vacancies, List.of(candidate));

        assertThat(candidatePreferences.get(candidate)).containsExactly(positive);
        assertThat(vacancyPreferences).hasSize(3);
        assertThat(vacancyPreferences.get(positive)).containsExactly(candidate);
        assertThat(vacancyPreferences.get(zero)).isEmpty();
        assertThat(vacancyPreferences.get(negative)).isEmpty();
        assertThat(service.createCandidatePreferenceLists(List.of(candidate), List.of(zero, negative)))
                .containsEntry(candidate, List.of());
    }

    @Test
    void remoteBonusCanMakePairAcceptableOnlyFromCandidatePerspective() {
        Candidate candidate = RankerFixtures.candidate(1L, "Candidate", null);
        Vacancy zeroCompatibility = RankerFixtures.vacancy(20L, "Developer", SENIORITY.ESTAGIARIO, Modalidade.REMOTO);
        ReflectionTestUtils.setField(zeroCompatibility, "seniority", null);
        Vacancy negativeCompatibility = RankerFixtures.vacancy(10L, "Developer", SENIORITY.SENIOR,
                Modalidade.REMOTO);
        List<Vacancy> vacancies = List.of(negativeCompatibility, zeroCompatibility);

        assertThat(service.rankVacancyForCandidate(candidate, zeroCompatibility).totalScore()).isPositive();
        assertThat(service.rankVacancyForCandidate(candidate, negativeCompatibility).totalScore()).isPositive();
        assertThat(service.rankCandidateForVacancy(candidate, zeroCompatibility).totalScore()).isZero();
        assertThat(service.rankCandidateForVacancy(candidate, negativeCompatibility).totalScore()).isNegative();

        Map<Candidate, List<Vacancy>> candidatePreferences = service.createCandidatePreferenceLists(
                List.of(candidate), vacancies);
        Map<Vacancy, List<Candidate>> vacancyPreferences = service.createVacancyPreferenceLists(
                vacancies, List.of(candidate));

        assertThat(candidatePreferences.get(candidate)).containsExactly(zeroCompatibility, negativeCompatibility);
        assertThat(vacancyPreferences.get(zeroCompatibility)).isEmpty();
        assertThat(vacancyPreferences.get(negativeCompatibility)).isEmpty();
    }

    @Test
    void handlesNullIdsExplicitlyUsingStableAttributesThenInputOrder() {
        Candidate candidate = RankerFixtures.candidate(null);
        Vacancy beta = RankerFixtures.vacancy(null, "Beta", SENIORITY.ESTAGIARIO, Modalidade.PRESENCIAL);
        Vacancy alphaSecond = RankerFixtures.vacancy(null, "Alpha", SENIORITY.ESTAGIARIO,
                Modalidade.PRESENCIAL);
        Vacancy alphaFirst = RankerFixtures.vacancy(null, "Alpha", SENIORITY.ESTAGIARIO,
                Modalidade.PRESENCIAL);

        List<Vacancy> ordered = service.createCandidatePreferenceLists(
                List.of(candidate), List.of(beta, alphaFirst, alphaSecond)).get(candidate);

        assertThat(ordered).containsExactly(alphaFirst, alphaSecond, beta);
    }

    @Test
    void rawVacancyScoresPreserveCanonicalOrderAndIncludeNonPositiveScoresAndEmptyVacancies() {
        Candidate first = RankerFixtures.candidate(2L, "First", null);
        Candidate second = RankerFixtures.candidate(10L, "Second", null);
        Vacancy positive = RankerFixtures.vacancy(2L, "Developer", SENIORITY.ESTAGIARIO, Modalidade.REMOTO);
        Vacancy negative = RankerFixtures.vacancy(10L, "Developer", SENIORITY.SENIOR, Modalidade.REMOTO);

        Map<Vacancy, Map<Candidate, Double>> scores = service.calculateVacancyScores(
                List.of(negative, positive), List.of(second, first));

        assertThat(scores.keySet()).containsExactly(positive, negative);
        assertThat(scores.get(positive).keySet()).containsExactly(first, second);
        assertThat(scores.get(positive).get(first))
                .isEqualTo(service.rankCandidateForVacancy(first, positive).totalScore());
        assertThat(scores.get(negative).get(first)).isNegative();
        assertThat(service.calculateVacancyScores(List.of(positive), List.of()))
                .containsEntry(positive, Map.of());
        assertThat(service.calculateVacancyScores(List.of(), List.of(first))).isEmpty();
    }

    private static Candidate candidateWithExperience(Long id, String name, SENIORITY seniority) {
        Resume resume = RankerFixtures.resume();
        resume.setExperiences(List.of(new Experience("Developer", 60, seniority)));
        return RankerFixtures.candidate(id, name, resume);
    }
}
