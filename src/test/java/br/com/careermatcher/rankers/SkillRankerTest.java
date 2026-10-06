package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Skill;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateHasSkill;
import br.com.careermatcher.relations.VacancySkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRankerTest {

    private final SkillRanker ranker = new SkillRanker();

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(ranker.vacancyRank(candidate(skill("  JAVA  ", 5.0)),
                vacancy(requiredSkill("java", 5.0, true)))).isEqualTo(6.0);
    }

    @Test
    void appliesFixedPenaltyForRequiredSkillBelowWeight() {
        assertThat(ranker.vacancyRank(candidate(skill("Java", 5.0)),
                vacancy(requiredSkill("Java", 10.0, true)))).isEqualTo(-4.0);
    }

    @Test
    void rewardsEqualWeight() {
        assertThat(ranker.vacancyRank(candidate(skill("Java", 10.0)),
                vacancy(requiredSkill("Java", 10.0, true)))).isEqualTo(6.0);
    }

    @Test
    void rewardsWeightAboveMinimumWithoutCap() {
        assertThat(ranker.vacancyRank(candidate(skill("Java", 30.0)),
                vacancy(requiredSkill("Java", 10.0, true)))).isEqualTo(18.0);
    }

    @Test
    void penalizesMissingRequiredSkill() {
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null),
                vacancy(requiredSkill("Java", 5.0, true)))).isEqualTo(-4.0);
    }

    @Test
    void doesNotPenalizeMissingDesirableSkillOrMissingRequiredFlag() {
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null),
                vacancy(requiredSkill("Java", 5.0, false)))).isZero();
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null),
                vacancy(requiredSkill("Java", 5.0, null)))).isZero();
    }

    @Test
    void averagesCandidateWeightsForDuplicates() {
        assertThat(ranker.vacancyRank(candidate(skill("Java", 12.0), skill(" java ", 18.0)),
                vacancy(requiredSkill("JAVA", 10.0, true)))).isEqualTo(9.0);
    }

    @Test
    void handlesInvalidWeightsDeterministically() {
        assertThat(ranker.vacancyRank(candidate(skill("Java", Double.NaN)),
                vacancy(requiredSkill("Java", 0.0, true)))).isEqualTo(-4.0);
    }

    @Test
    void candidatePerspectiveDoesNotPenalizeMissingRequiredSkills() {
        assertThat(ranker.candidateRank(RankerFixtures.candidate(null),
                vacancy(requiredSkill("Java", 5.0, true)))).isZero();
    }

    private static CandidateHasSkill skill(String name, Double weight) {
        return new CandidateHasSkill(null, weight, new Skill(null, name));
    }

    private static VacancySkill requiredSkill(String name, Double weight, Boolean required) {
        return new VacancySkill(null, weight, required, new Skill(null, name));
    }

    private static Candidate candidate(CandidateHasSkill... skills) {
        Resume resume = RankerFixtures.resume();
        resume.setCandidateSkills(List.of(skills));
        return RankerFixtures.candidate(resume);
    }

    private static Vacancy vacancy(VacancySkill... skills) {
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setCompetencias(List.of(skills));
        return vacancy;
    }
}
