package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModalityAndLocationRankerTest {

    private final ModalityRanker modalityRanker = new ModalityRanker();
    private final LocationRanker locationRanker = new LocationRanker();

    @Test
    void appliesProvisionalRemoteVacancyBonus() {
        assertThat(modalityRanker.candidateRank(RankerFixtures.candidate(null),
                RankerFixtures.vacancy(null, "Developer", SENIORITY.JUNIOR, Modalidade.REMOTO)))
                .isEqualTo(6.0);
    }

    @Test
    void modalityDoesNotAffectVacancyPerspective() {
        assertThat(modalityRanker.vacancyRank(RankerFixtures.candidate(null),
                RankerFixtures.vacancy(null, "Developer", SENIORITY.JUNIOR, Modalidade.REMOTO))).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Modalidade.class, names = {"HIBRIDO", "PRESENCIAL"})
    void doesNotApplyBonusToNonRemoteVacancies(Modalidade modalidade) {
        assertThat(modalityRanker.candidateRank(RankerFixtures.candidate(null),
                RankerFixtures.vacancy(null, "Developer", SENIORITY.JUNIOR, modalidade)))
                .isZero();
    }

    @Test
    void locationRemainsNeutralWithoutStructuredCandidateLocation() {
        assertThat(locationRanker.vacancyRank(null, null)).isZero();
        assertThat(locationRanker.vacancyRank(RankerFixtures.candidate(null),
                RankerFixtures.vacancy(SENIORITY.JUNIOR))).isZero();
    }
}
