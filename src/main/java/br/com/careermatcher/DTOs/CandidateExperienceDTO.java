package br.com.careermatcher.DTOs;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.relations.ExperienceDevelopsSkill;

import java.util.List;

public record CandidateExperienceDTO(String role,
                                     String company,
                                     String description,
                                     Integer durationMonths,
                                     SENIORITY seniority,
                                     List<ExperienceDevelopsSkill> skills) {
}
