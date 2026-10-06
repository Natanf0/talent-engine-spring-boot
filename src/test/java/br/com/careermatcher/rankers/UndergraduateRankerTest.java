package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.enums.UNDERGRADUATETYPE;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Undergraduate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.VacancyUndergrad;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UndergraduateRankerTest {

    private final UndergraduateRanker ranker = new UndergraduateRanker();

    @Test
    void treatsCorrelatedCoursesAsAlternativesAndUsesBestMatch() {
        Candidate candidate = candidate(degree("Computer Science", "UFRJ", null,
                UNDERGRADUATETYPE.BACHARELADO));
        Vacancy vacancy = vacancy(
                requirement(degree("Information Systems", null, null, null), true),
                requirement(degree(" computer   science ", null, null,
                        UNDERGRADUATETYPE.BACHARELADO), true)
        );

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(8.0);
    }

    @Test
    void penalizesWhenNoRequiredAlternativeMatches() {
        assertThat(ranker.vacancyRank(candidate(degree("Design", null, null, null)),
                vacancy(requirement(degree("Computer Science", null, null, null), true))))
                .isEqualTo(-8.0);
    }

    @Test
    void doesNotPenalizeMissingDesirableAlternativeAndRewardsMatch() {
        Candidate candidate = candidate(degree("Design", null, null, null));

        assertThat(ranker.vacancyRank(candidate,
                vacancy(requirement(degree("Computer Science", null, null, null), false)))).isZero();
        assertThat(ranker.vacancyRank(candidate,
                vacancy(requirement(degree("Design", null, null, null), false)))).isEqualTo(4.0);
    }

    @Test
    void requiresUndergraduateTypeWhenRequirementProvidesIt() {
        Candidate candidate = candidate(degree("Computer Science", null, null,
                UNDERGRADUATETYPE.TECNOLOGO));
        Vacancy vacancy = vacancy(requirement(degree("Computer Science", null, null,
                UNDERGRADUATETYPE.BACHARELADO), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(-8.0);
    }

    @Test
    void addsInstitutionBonus() {
        Candidate candidate = candidate(degree("Computer Science", "UFRJ", null, null));
        Vacancy vacancy = vacancy(requirement(degree("Computer Science", " ufrj ", null, null), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(12.0);
    }

    @Test
    void addsCompletionDateBonusWhenCandidateCompletedByCutoff() {
        Candidate candidate = candidate(degree("Computer Science", null, date(2020, 1, 1), null));
        Vacancy vacancy = vacancy(requirement(
                degree("Computer Science", null, date(2021, 1, 1), null), true));

        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(12.0);
    }

    @Test
    void returnsZeroForEmptyRequirementList() {
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setUndergraduates(List.of());

        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null), vacancy)).isZero();
    }

    private static Undergraduate degree(
            String field,
            String institution,
            Date completionDate,
            UNDERGRADUATETYPE type
    ) {
        return new Undergraduate(field, institution, completionDate, type);
    }

    private static Candidate candidate(Undergraduate degree) {
        Resume resume = RankerFixtures.resume();
        resume.setUndergraduate(degree);
        return RankerFixtures.candidate(resume);
    }

    private static VacancyUndergrad requirement(Undergraduate degree, Boolean required) {
        return new VacancyUndergrad(null, required, degree);
    }

    private static Vacancy vacancy(VacancyUndergrad... requirements) {
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setUndergraduates(List.of(requirements));
        return vacancy;
    }

    private static Date date(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
