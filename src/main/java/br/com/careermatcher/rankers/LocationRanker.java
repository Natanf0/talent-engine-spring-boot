package br.com.careermatcher.rankers;

import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import org.springframework.stereotype.Component;

@Component
public class LocationRanker extends ExplainedRanker {
    @Override public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        return RankerDetail.single("LocationRanker", "LOCATION_DISABLED", 0, "Localização não pontuada: o candidato não possui preferência estruturada de localização.");
    }
}
