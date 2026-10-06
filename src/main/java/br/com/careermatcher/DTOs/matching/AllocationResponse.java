package br.com.careermatcher.DTOs.matching;

import br.com.careermatcher.enums.DEGREE;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.enums.UNDERGRADUATETYPE;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Detailed HTTP representation of the allocation and its loaded graph data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AllocationResponse(
        List<Shortlist> shortlists,
        Set<String> unassignedCandidateIds,
        int totalAllocated
) {
    public AllocationResponse {
        shortlists = List.copyOf(shortlists);
        unassignedCandidateIds = Collections.unmodifiableSet(new LinkedHashSet<>(unassignedCandidateIds));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shortlist(VacancyDetails vacancy, List<CandidateDetails> candidates) {
        public Shortlist {
            candidates = List.copyOf(candidates);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyDetails(
            String id,
            String role,
            String city,
            SENIORITY seniority,
            Modalidade modalidade,
            List<VacancySkillRequirement> skillRequirements,
            VacancyExperienceRequirement experienceRequirement,
            List<VacancyLanguageRequirement> languageRequirements,
            List<VacancyUndergraduateRequirement> undergraduateRequirements,
            List<VacancyGraduateRequirement> graduateRequirements
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateDetails(String id, String name, String about, String professionalTitle, ResumeDetails resume) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResumeDetails(
            String id,
            List<WeightedSkill> skills,
            List<ExperienceDetails> experiences,
            List<CandidateLanguageDetails> languages,
            UndergraduateDetails undergraduate,
            List<GraduateDetails> graduates
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExperienceDetails(
            String id,
            String role,
            String companyName,
            String description,
            Integer durationMonths,
            SENIORITY seniority,
            List<WeightedSkill> skills
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UndergraduateDetails(
            String id,
            String field,
            String institution,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant completionDate,
            UNDERGRADUATETYPE undergraduateType,
            List<ProjectDetails> projects
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GraduateDetails(
            String id,
            DEGREE degree,
            String field,
            String subarea,
            String institution,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant completionDate,
            List<ProjectDetails> projects
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectDetails(
            String id,
            String title,
            String description,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant startDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant endDate,
            List<WeightedSkill> skills
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillDetails(String id, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LanguageDetails(String id, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeightedSkill(Double weight, SkillDetails skill) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateLanguageDetails(Double level, LanguageDetails language) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancySkillRequirement(Double weight, Boolean required, SkillDetails skill) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyExperienceRequirement(Boolean required, ExperienceDetails experience) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyLanguageRequirement(Boolean required, Double level, LanguageDetails language) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyUndergraduateRequirement(Boolean required, UndergraduateDetails undergraduate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyGraduateRequirement(Boolean required, GraduateDetails graduate) {
    }
}
