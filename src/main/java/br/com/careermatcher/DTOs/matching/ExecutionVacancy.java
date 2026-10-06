package br.com.careermatcher.DTOs.matching;

import br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView;
import br.com.careermatcher.DTOs.crud.CrudDtos.VacancyView;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** One item of the report document's root array. */
public record ExecutionVacancy(@JsonUnwrapped VacancyView vacancy, List<CandidateView> candidates) {
    public ExecutionVacancy { candidates = List.copyOf(candidates); }
}
