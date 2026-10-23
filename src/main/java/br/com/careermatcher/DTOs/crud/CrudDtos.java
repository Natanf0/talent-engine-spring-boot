package br.com.careermatcher.DTOs.crud;

import br.com.careermatcher.DTOs.matching.AllocationResponse.*;
import br.com.careermatcher.enums.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class CrudDtos {
    private CrudDtos() {}
    // A JSON tree is intentional: PATCH must distinguish missing fields from explicit null.
    public record CandidateWriteRequest(@JsonValue ObjectNode fields) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) public CandidateWriteRequest {}
    }
    public record VacancyWriteRequest(@JsonValue ObjectNode fields) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) public VacancyWriteRequest {}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateView(Long id, String name, String about, String professionalTitle, String phone, String email,
                                String linkedinURL, String personalWebsiteURL, ResumeDetails resume) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VacancyView(Long id, String role, String city, SENIORITY seniority, Modalidade modalidade,
                              String additionalInfo,
                              List<VacancySkillRequirement> skillRequirements,
                              VacancyExperienceRequirement experienceRequirement,
                              List<VacancyLanguageRequirement> languageRequirements,
                              List<VacancyUndergraduateRequirement> undergraduateRequirements,
                              List<VacancyGraduateRequirement> graduateRequirements) {
        public VacancyView(Long id, String role, String city, SENIORITY seniority, Modalidade modalidade,
                           List<VacancySkillRequirement> skillRequirements,
                           VacancyExperienceRequirement experienceRequirement,
                           List<VacancyLanguageRequirement> languageRequirements,
                           List<VacancyUndergraduateRequirement> undergraduateRequirements,
                           List<VacancyGraduateRequirement> graduateRequirements) {
            this(id, role, city, seniority, modalidade, null, skillRequirements, experienceRequirement,
                    languageRequirements, undergraduateRequirements, graduateRequirements);
        }
    }
    public record PageView<T>(List<T> content, int page, int size, long totalElements, long totalPages) {
        public PageView { content = List.copyOf(content); }
    }
    public record CandidateSummary(Long id, String name, String professionalTitle, String phone) {}
    public record VacancySummary(Long id, String role, String city, SENIORITY seniority, Modalidade modalidade, String companyName) {}

}
