package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Language;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateLanguage;
import br.com.careermatcher.relations.VacancyLanguage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRankerTest {

    private final LanguageRanker ranker = new LanguageRanker();

    @Test
    void rewardsRequiredLanguageFound() {
        assertThat(ranker.vacancyRank(candidate("English"), vacancy("English", true))).isEqualTo(6.0);
    }

    @Test
    void penalizesRequiredLanguageMissing() {
        assertThat(ranker.vacancyRank(candidate("Portuguese"), vacancy("English", true))).isEqualTo(-4.0);
    }

    @Test
    void rewardsDesirableLanguageFound() {
        assertThat(ranker.vacancyRank(candidate("English"), vacancy("English", false))).isEqualTo(3.0);
    }

    @Test
    void doesNotPenalizeDesirableLanguageMissingOrMissingRequiredFlag() {
        assertThat(ranker.vacancyRank(candidate("Portuguese"), vacancy("English", false))).isZero();
        assertThat(ranker.vacancyRank(candidate("Portuguese"), vacancy("English", null))).isZero();
    }

    @Test
    void normalizesCaseAndExternalWhitespaceWithoutUsingCandidateLevel() {
        Resume resume = RankerFixtures.resume();
        resume.setLanguages(List.of(new CandidateLanguage(null, null, new Language(null, "  ENGLISH  "))));
        Candidate candidate = RankerFixtures.candidate(resume);

        assertThat(ranker.vacancyRank(candidate, vacancy("english", true))).isEqualTo(6.0);
    }

    @Test
    void candidatePerspectiveUsesLanguageBonusWithoutVacancyMultiplier() {
        assertThat(ranker.candidateRank(candidate("English"), vacancy("English", true))).isEqualTo(3.0);
        assertThat(ranker.candidateRank(candidate("Portuguese"), vacancy("English", true))).isEqualTo(-2.0);
    }

    private static Candidate candidate(String language) {
        Resume resume = RankerFixtures.resume();
        resume.setLanguages(List.of(new CandidateLanguage(null, 1.0, new Language(null, language))));
        return RankerFixtures.candidate(resume);
    }

    private static Vacancy vacancy(String language, Boolean required) {
        Vacancy vacancy = RankerFixtures.vacancy(SENIORITY.JUNIOR);
        vacancy.setLanguages(List.of(new VacancyLanguage(null, required, new Language(null, language))));
        return vacancy;
    }
}
