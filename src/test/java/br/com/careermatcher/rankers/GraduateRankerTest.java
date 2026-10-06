package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.DEGREE;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Graduate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.VacancyGraduate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraduateRankerTest {

    private final GraduateRanker ranker = new GraduateRanker();

    @ParameterizedTest
    @EnumSource(DEGREE.class)
    void supportsEveryGraduateDegree(DEGREE degree) {
        Candidate candidate = candidate(graduate(degree, "Computing", null, null, null));
        Vacancy vacancy = vacancy(requirement(
                graduate(degree, " computing ", null, null, null), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(8.0);
    }

    @Test
    void matchesBySubareaWhenFieldDiffers() {
        Candidate candidate = candidate(graduate(DEGREE.MASTERS, "Engineering", "Machine Learning", null, null));
        Vacancy vacancy = vacancy(requirement(
                graduate(DEGREE.MASTERS, "Computing", " machine   learning ", null, null), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(8.0);
    }

    @Test
    void addsInstitutionBonus() {
        Candidate candidate = candidate(graduate(DEGREE.MBA, "Management", null, "FGV", null));
        Vacancy vacancy = vacancy(requirement(
                graduate(DEGREE.MBA, "Management", null, " fgv ", null), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(12.0);
    }

    @Test
    void addsCompletionDateBonusWhenCandidateCompletedByCutoff() {
        Candidate candidate = candidate(graduate(
                DEGREE.PHD, "Physics", null, null, date(2020, 1, 1)));
        Vacancy vacancy = vacancy(requirement(
                graduate(DEGREE.PHD, "Physics", null, null, date(2021, 1, 1)), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(12.0);
    }

    @Test
    void penalizesMissingRequiredGraduateDegree() {
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null), vacancy(requirement(
                graduate(DEGREE.SPECIALIZATION, "Security", null, null, null), true))))
                .isEqualTo(-8.0);
    }

    @Test
    void doesNotPenalizeMissingDesirableDegreeAndRewardsItWhenFound() {
        Vacancy vacancy = vacancy(requirement(
                graduate(DEGREE.MBA, "Management", null, null, null), false));

        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null), vacancy)).isZero();
        assertThat(ranker.vacancyRank(candidate(graduate(DEGREE.MBA, "Management", null, null, null)), vacancy))
                .isEqualTo(4.0);
    }

    @Test
    void treatsSameDegreeRequirementsAsAlternatives() {
        Candidate candidate = candidate(graduate(DEGREE.MASTERS, "Computing", null, null, null));
        Vacancy vacancy = vacancy(
                requirement(graduate(DEGREE.MASTERS, "Design", null, null, null), true),
                requirement(graduate(DEGREE.MASTERS, "Computing", null, null, null), true)
        );

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(8.0);
    }

    @Test
    void accumulatesRequirementsAcrossDifferentDegrees() {
        Candidate candidate = candidate(
                graduate(DEGREE.SPECIALIZATION, "Security", null, null, null),
                graduate(DEGREE.MASTERS, "Computing", null, null, null)
        );
        Vacancy vacancy = vacancy(
                requirement(graduate(DEGREE.SPECIALIZATION, "Security", null, null, null), true),
                requirement(graduate(DEGREE.MASTERS, "Computing", null, null, null), true)
        );

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(16.0);
    }

    private static Graduate graduate(
            DEGREE degree,
            String field,
            String subarea,
            String institution,
            Date completionDate
    ) {
        return new Graduate(null, degree, field, subarea, institution, completionDate, List.of());
    }

    private static Candidate candidate(Graduate... degrees) {
        Resume resume = RankerFixtures.resume();
        resume.setGraduates(List.of(degrees));
        return RankerFixtures.candidate(resume);
    }

    private static VacancyGraduate requirement(Graduate graduate, Boolean required) {
        return new VacancyGraduate(null, required, graduate);
    }

    private static Vacancy vacancy(VacancyGraduate... requirements) {
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setGraduate(List.of(requirements));
        return vacancy;
    }

    private static Date date(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
