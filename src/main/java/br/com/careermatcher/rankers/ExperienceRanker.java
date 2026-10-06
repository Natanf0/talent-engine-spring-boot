package br.com.careermatcher.rankers;

import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import org.springframework.stereotype.Component;

@Component
public class ExperienceRanker extends ExplainedRanker {
    @Override public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        return RankerDetail.single("ExperienceRanker", "EXPERIENCE_DISABLED", 0, "Critério de experiência desativado na implementação atual; não avalia meses ou cargo.");
    }
}
