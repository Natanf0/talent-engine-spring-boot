package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.relations.VacancyExperience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExperienceRankerTest {
    private final ExperienceRanker ranker = new ExperienceRanker();

    @ParameterizedTest
    @CsvSource({"true,0", "true,11", "true,12", "true,120", "false,0", "false,120"})
    void experienceCriterionIsNeutralForBothPerspectives(boolean required, int months) {
        var resume = RankerFixtures.resume();
        resume.setExperiences(List.of(new Experience("Developer", months, SENIORITY.SENIOR)));
        var candidate = RankerFixtures.candidate(resume);
        var vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setExperience(new VacancyExperience(null, required, new Experience("Developer", 12, SENIORITY.JUNIOR)));
        assertThat(ranker.candidateRank(candidate, vacancy)).isZero();
        assertThat(ranker.vacancyRank(candidate, vacancy)).isZero();
    }

    @Test
    void missingResumeAndRequirementRemainNeutral() {
        assertThat(ranker.candidateRank(null, null)).isZero();
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null), RankerFixtures.vacancy(SENIORITY.JUNIOR))).isZero();
    }
}
