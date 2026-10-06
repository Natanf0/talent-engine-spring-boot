package br.com.careermatcher.interfaces;

import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;

public interface Ranker {
    double candidateRank(Candidate candidate, Vacancy vacancy);
    double vacancyRank(Candidate candidate, Vacancy vacancy);

}
