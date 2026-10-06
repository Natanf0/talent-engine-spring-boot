package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.DTOs.matching.AllocationResult;
import br.com.careermatcher.models.*;
import br.com.careermatcher.relations.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

import static br.com.careermatcher.DTOs.matching.AllocationResponse.*;

@Component
public class MatchingResponseMapper {

    public AllocationResponse toResponse(
            AllocationResult allocation,
            List<Candidate> candidates,
            List<Vacancy> vacancies
    ) {
        Map<String, Candidate> candidatesById = indexById(candidates, Candidate::getId);
        Map<String, Vacancy> vacanciesById = indexById(vacancies, Vacancy::getId);

        List<Shortlist> shortlists = allocation.shortlists().entrySet().stream()
                .map(entry -> {
                    String vacancyId = entry.getKey().id();
                    Vacancy vacancy = required(vacanciesById, vacancyId, "vaga");
                    List<CandidateDetails> allocatedCandidates = entry.getValue().stream()
                            .map(candidate -> mapCandidate(required(candidatesById, candidate.id(), "candidato")))
                            .toList();
                    return new Shortlist(mapVacancy(vacancy), allocatedCandidates);
                })
                .toList();

        Set<String> unassigned = Collections.unmodifiableSet(
                new LinkedHashSet<>(allocation.unassignedCandidateIds()));
        return new AllocationResponse(shortlists, unassigned, allocation.totalAllocated());
    }

    public VacancyDetails mapVacancy(Vacancy vacancy) {
        return new VacancyDetails(
                id(vacancy.getId()), vacancy.getRole(), vacancy.getCity(), vacancy.getSeniority(),
                vacancy.getModalidade(), map(vacancy.getCompetencias(), this::mapVacancySkill),
                mapVacancyExperience(vacancy.getExperience()),
                map(vacancy.getLanguages(), this::mapVacancyLanguage),
                map(vacancy.getUndergraduates(), this::mapVacancyUndergraduate),
                map(vacancy.getGraduate(), this::mapVacancyGraduate));
    }

    public CandidateDetails mapCandidate(Candidate candidate) {
        return new CandidateDetails(id(candidate.getId()), candidate.getName(), candidate.getAbout(),
                candidate.getProfessionalTitle(), mapResume(candidate.getResume()));
    }

    private ResumeDetails mapResume(Resume resume) {
        if (resume == null) {
            return null;
        }
        return new ResumeDetails(id(resume.getId()), map(resume.getCandidateSkills(), this::mapCandidateSkill),
                map(resume.getExperiences(), this::mapExperience),
                map(resume.getLanguages(), this::mapCandidateLanguage),
                mapUndergraduate(resume.getUndergraduate()),
                map(resume.getGraduates(), this::mapGraduate));
    }

    private ExperienceDetails mapExperience(Experience experience) {
        if (experience == null) {
            return null;
        }
        return new ExperienceDetails(id(experience.getId()), experience.getRole(), experience.getCompanyName(),
                experience.getDescription(), experience.getDurationMonths(), experience.getSeniority(),
                map(experience.getSkillsRelatedToExperience(), this::mapExperienceSkill));
    }

    private UndergraduateDetails mapUndergraduate(Undergraduate undergraduate) {
        if (undergraduate == null) {
            return null;
        }
        return new UndergraduateDetails(id(undergraduate.getId()), undergraduate.getField(),
                undergraduate.getInstitution(), instant(undergraduate.getCompletionDate()),
                undergraduate.getUndergraduateType(), map(undergraduate.getProjects(), this::mapProject));
    }

    private GraduateDetails mapGraduate(Graduate graduate) {
        if (graduate == null) {
            return null;
        }
        return new GraduateDetails(id(graduate.getId()), graduate.getDegree(), graduate.getField(),
                graduate.getSubarea(), graduate.getInstitution(), instant(graduate.getCompletionDate()),
                map(graduate.getProjects(), this::mapProject));
    }

    private ProjectDetails mapProject(Project project) {
        if (project == null) {
            return null;
        }
        return new ProjectDetails(id(project.getId()), project.getTitle(), project.getDescription(),
                instant(project.getStartDate()), instant(project.getEndDate()),
                map(project.getProjectDevelopsSkillList(), this::mapProjectSkill));
    }

    private WeightedSkill mapCandidateSkill(CandidateHasSkill relationship) {
        return new WeightedSkill(relationship.getWeight(), mapSkill(relationship.getSkill()));
    }

    private WeightedSkill mapExperienceSkill(ExperienceDevelopsSkill relationship) {
        return new WeightedSkill(relationship.getWeight(), mapSkill(relationship.getSkill()));
    }

    private WeightedSkill mapProjectSkill(ProjectDevelopsSkill relationship) {
        return new WeightedSkill(relationship.getWeight(), mapSkill(relationship.getSkill()));
    }

    private CandidateLanguageDetails mapCandidateLanguage(CandidateLanguage relationship) {
        return new CandidateLanguageDetails(relationship.getLevel(), mapLanguage(relationship.getLanguage()));
    }

    private VacancySkillRequirement mapVacancySkill(VacancySkill relationship) {
        return new VacancySkillRequirement(relationship.getWeight(), relationship.getRequired(),
                mapSkill(relationship.getSkill()));
    }

    private VacancyExperienceRequirement mapVacancyExperience(VacancyExperience relationship) {
        if (relationship == null) {
            return null;
        }
        return new VacancyExperienceRequirement(relationship.getRequired(), mapExperience(relationship.getExperience()));
    }

    private VacancyLanguageRequirement mapVacancyLanguage(VacancyLanguage relationship) {
        return new VacancyLanguageRequirement(relationship.getRequired(), relationship.getLevel(),
                mapLanguage(relationship.getLanguage()));
    }

    private VacancyUndergraduateRequirement mapVacancyUndergraduate(VacancyUndergrad relationship) {
        return new VacancyUndergraduateRequirement(relationship.getRequired(),
                mapUndergraduate(relationship.getUndergraduate()));
    }

    private VacancyGraduateRequirement mapVacancyGraduate(VacancyGraduate relationship) {
        return new VacancyGraduateRequirement(relationship.getRequired(), mapGraduate(relationship.getGraduate()));
    }

    private SkillDetails mapSkill(Skill skill) {
        return skill == null ? null : new SkillDetails(id(skill.getId()), skill.getName());
    }

    private LanguageDetails mapLanguage(Language language) {
        return language == null ? null : new LanguageDetails(id(language.getId()), language.getName());
    }

    private static java.time.Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static String id(Long id) {
        return id == null ? null : id.toString();
    }

    private static <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(Objects::nonNull).map(mapper).filter(Objects::nonNull).toList();
    }

    private static <T> Map<String, T> indexById(List<T> values, Function<T, Long> idExtractor) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) {
            Long valueId = idExtractor.apply(value);
            if (valueId != null) {
                result.put(valueId.toString(), value);
            }
        }
        return result;
    }

    private static <T> T required(Map<String, T> values, String id, String entityName) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalStateException("Não foi possível detalhar " + entityName + " com ID " + id + '.');
        }
        return value;
    }
}
