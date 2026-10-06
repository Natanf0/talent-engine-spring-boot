package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.*;
import br.com.careermatcher.models.*;
import br.com.careermatcher.relations.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RankerEvidenceTest {
    @Test
    void skillEvidenceUsesTheMeanAcrossSourcesAndTheActualFixedPenalty() {
        Skill java = new Skill(1L, " Java ");
        Resume resume = new Resume();
        resume.setCandidateSkills(List.of(new CandidateHasSkill(1L, 2.0, java)));
        resume.setExperiences(List.of(new Experience("Developer", "ACME", "Work", 36, SENIORITY.PLENO,
                List.of(new ExperienceDevelopsSkill(2L, 6.0, new Skill(2L, "JAVA"))))));
        Candidate candidate = RankerFixtures.candidate(resume);
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.PLENO);
        vacancy.setCompetencias(List.of(new VacancySkill(3L, 2.0, true, java)));
        SkillRanker ranker = new SkillRanker();
        RankerDetail detail = ranker.evaluate(candidate, vacancy, false);
        assertThat(detail.points()).isEqualTo(12);
        assertThat(detail.contributions().getFirst().evidence()).containsEntry("candidateAverageWeight", 4.0)
                .containsEntry("minimumWeight", 2.0).containsEntry("ratio", 2.0);
        assertThat(ranker.vacancyRank(candidate, vacancy)).isEqualTo(detail.points());
        assertThat(ranker.evaluate(RankerFixtures.candidate(null), vacancy, false).points()).isEqualTo(-4);
        assertThat(ranker.evaluate(RankerFixtures.candidate(null), vacancy, true).points()).isZero();
    }

    @Test
    void languagesDoNotClaimThatLevelsWereComparedAndDisabledCriteriaAreExplicit() {
        Language english = new Language(1L, "Inglês");
        Resume resume = new Resume();
        resume.setLanguages(List.of(new CandidateLanguage(2L, 0.1, english)));
        Candidate candidate = RankerFixtures.candidate(resume);
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.PLENO);
        vacancy.setLanguages(List.of(new VacancyLanguage(3L, true, 9.0, english)));
        RankerDetail detail = new LanguageRanker().evaluate(candidate, vacancy, false);
        assertThat(detail.points()).isEqualTo(6);
        assertThat(detail.contributions().getFirst().evidence()).containsEntry("levelEvaluated", false);
        assertThat(new LanguageRanker().evaluate(candidate, vacancy, true).points()).isEqualTo(3);
        assertThat(new ExperienceRanker().evaluate(candidate, vacancy, false).contributions().getFirst().ruleCode())
                .isEqualTo("EXPERIENCE_DISABLED");
        assertThat(new LocationRanker().evaluate(candidate, vacancy, true).contributions().getFirst().ruleCode())
                .isEqualTo("LOCATION_DISABLED");
    }

    @Test
    void educationAlternativesAreExplainedWithoutAddingUnselectedPoints() {
        Undergraduate degree = new Undergraduate("Computação", "USP");
        Resume resume = new Resume();
        resume.setUndergraduate(degree);
        Candidate candidate = RankerFixtures.candidate(resume);
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.PLENO);
        vacancy.setUndergraduates(List.of(new VacancyUndergrad(1L, true, new Undergraduate("Computação", "UFRJ")),
                new VacancyUndergrad(2L, true, new Undergraduate("Computação", "USP"))));
        RankerDetail result = new UndergraduateRanker().evaluate(candidate, vacancy, false);
        assertThat(result.points()).isEqualTo(12);
        assertThat(result.contributions().getFirst().ruleCode()).isEqualTo("BEST_UNDERGRADUATE_ALTERNATIVE");
        assertThat(result.contributions().getFirst().evidence().get("alternatives")).asList().hasSize(2);
        assertThat(result.contributions().stream().mapToDouble(RankerDetail.Contribution::points).sum()).isEqualTo(12);
    }
}
