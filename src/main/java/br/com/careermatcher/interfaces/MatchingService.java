package br.com.careermatcher.interfaces;

import br.com.careermatcher.DTOs.crud.CrudDtos;
import br.com.careermatcher.DTOs.matching.MatchingRunResponse;
import br.com.careermatcher.matching.DynamicCutoffService;
import java.util.List;

public interface MatchingService {
    /** Generates exclusive shortlists of at most {@code k} candidates per vacancy. */
    default MatchingRunResponse generateShortlists(List<CrudDtos.CandidateView> candidates,
                                                   List<CrudDtos.VacancyView> vacancies, int k) {
        return generateShortlists(candidates, vacancies, k, DynamicCutoffService.DEFAULT_TOLERANCE_FACTOR,
                DynamicCutoffService.DEFAULT_ABSOLUTE_MIN_SCORE);
    }

    MatchingRunResponse generateShortlists(List<CrudDtos.CandidateView> candidates,
                                           List<CrudDtos.VacancyView> vacancies,
                                           int k, double toleranceFactor, double absoluteMinScore);
}
