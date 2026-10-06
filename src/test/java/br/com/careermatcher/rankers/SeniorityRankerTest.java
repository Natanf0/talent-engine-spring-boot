package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Resume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SeniorityRankerTest {

    private final SeniorityRanker ranker = new SeniorityRanker();

    @Test
    void penalizesCandidateBelowRequiredSeniority() {
        Candidate candidate = candidateWithExperience(12, SENIORITY.JUNIOR);

        assertThat(ranker.vacancyRank(candidate, RankerFixtures.vacancy(SENIORITY.SENIOR)))
                .isCloseTo(-2.0 / 3.0, within());
    }

    @Test
    void rewardsExactSeniority() {
        Candidate candidate = candidateWithExperience(36, SENIORITY.PLENO);

        assertThat(ranker.vacancyRank(candidate, RankerFixtures.vacancy(SENIORITY.PLENO))).isEqualTo(4.0);
    }

    @Test
    void usesSeniorityRatioWithoutCap() {
        Candidate candidate = candidateWithExperience(120, SENIORITY.ESPECIALISTA);

        assertThat(ranker.vacancyRank(candidate, RankerFixtures.vacancy(SENIORITY.JUNIOR))).isEqualTo(7.0);
    }

    @Test
    void handlesInternshipWithoutDivisionByZero() {
        assertThat(ranker.vacancyRank(RankerFixtures.candidate(null), RankerFixtures.vacancy(SENIORITY.ESTAGIARIO)))
                .isEqualTo(4.0);
    }

    @Test
    void handlesCandidateWithoutResume() {
        assertThat(ranker.inferCandidateSeniority(RankerFixtures.candidate(null)))
                .isEqualTo(SENIORITY.ESTAGIARIO);
    }

    @Test
    void handlesCandidateWithoutExperiences() {
        Resume resume = RankerFixtures.resume();
        resume.setExperiences(List.of());

        assertThat(ranker.inferCandidateSeniority(RankerFixtures.candidate(resume)))
                .isEqualTo(SENIORITY.ESTAGIARIO);
    }

    @ParameterizedTest
    @MethodSource("monthThresholds")
    void infersSeniorityFromMonths(int months, SENIORITY expected) {
        assertThat(ranker.inferCandidateSeniority(candidateWithExperience(months, null))).isEqualTo(expected);
    }

    @Test
    void declaredSeniorityTakesPrecedenceOverDurationAndUsesTheHighestValue() {
        Resume resume = RankerFixtures.resume();
        resume.setExperiences(List.of(
                new Experience("Developer", 120, SENIORITY.JUNIOR),
                new Experience("Lead", 1, SENIORITY.GERENTE)
        ));

        assertThat(ranker.inferCandidateSeniority(RankerFixtures.candidate(resume)))
                .isEqualTo(SENIORITY.GERENTE);
    }

    private static Stream<Arguments> monthThresholds() {
        return Stream.of(
                Arguments.of(23, SENIORITY.JUNIOR),
                Arguments.of(24, SENIORITY.PLENO),
                Arguments.of(59, SENIORITY.PLENO),
                Arguments.of(60, SENIORITY.SENIOR)
        );
    }

    private static Candidate candidateWithExperience(int months, SENIORITY seniority) {
        Resume resume = RankerFixtures.resume();
        resume.setExperiences(List.of(new Experience("Developer", months, seniority)));
        return RankerFixtures.candidate(resume);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.000001);
    }
}
