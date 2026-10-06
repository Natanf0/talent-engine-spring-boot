package br.com.careermatcher.interfaces;

import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;

import java.util.List;
import java.util.Map;

public interface MatchingAlgorithm {
    Map<Candidate, Vacancy> match(List<Candidate> candidates, Map<Candidate, List<Vacancy>> candidatePreferencesList,
                                  List<Vacancy> vacancies, Map<Vacancy, List<Candidate>> vacancyPreferencesList);
}
