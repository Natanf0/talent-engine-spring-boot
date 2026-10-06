package br.com.careermatcher.DTOs.matching;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;

/** Vacancy details; shortlist capacity belongs to the matching execution. */
public record VacancyDTO(
        String id,
        String role,
        String city,
        SENIORITY seniority,
        Modalidade modalidade
) {
}
