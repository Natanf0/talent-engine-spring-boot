package br.com.careermatcher.rankers;

import br.com.careermatcher.interfaces.Ranker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;

/** Candidate and vacancy perspectives share the same scoring explanation. */
public abstract class ExplainedRanker implements Ranker {
    public abstract RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective);

    @Override public double candidateRank(Candidate candidate, Vacancy vacancy) {
        return evaluate(candidate, vacancy, true).points();
    }

    @Override public double vacancyRank(Candidate candidate, Vacancy vacancy) {
        return evaluate(candidate, vacancy, false).points();
    }
}
