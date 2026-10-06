package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.crud.CrudDtos;
import br.com.careermatcher.DTOs.matching.AllocationResponse.*;
import br.com.careermatcher.models.*;
import br.com.careermatcher.relations.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Rebuilds detached scoring graphs from the full CRUD read representations. */
@Component
public class SelectedParticipantsMapper {
    public Candidate candidate(CrudDtos.CandidateView source) {
        Candidate result = new Candidate(value(source.name()), value(source.about()), value(source.professionalTitle()),
                value(source.phone()));
        result.setId(source.id());
        result.setLinkedinURL(source.linkedinURL());
        result.setPersonalWebsiteURL(source.personalWebsiteURL());
        if (source.resume() != null) result.setResume(resume(source.resume()));
        return result;
    }

    public Vacancy vacancy(CrudDtos.VacancyView source) {
        Vacancy result = new Vacancy(source.role(), source.city(), source.seniority(), source.modalidade());
        result.setId(source.id());
        result.setCompetencias(safe(source.skillRequirements()).stream()
                .map(item -> new VacancySkill(null, item.weight(), item.required(), skill(item.skill()))).toList());
        if (source.experienceRequirement() != null) {
            var item = source.experienceRequirement();
            result.setExperience(new VacancyExperience(null, item.required(), experience(item.experience())));
        }
        result.setLanguages(safe(source.languageRequirements()).stream()
                .map(item -> new VacancyLanguage(null, item.required(), item.level(), language(item.language()))).toList());
        result.setUndergraduates(safe(source.undergraduateRequirements()).stream()
                .map(item -> new VacancyUndergrad(null, item.required(), undergraduate(item.undergraduate()))).toList());
        result.setGraduate(safe(source.graduateRequirements()).stream()
                .map(item -> new VacancyGraduate(null, item.required(), graduate(item.graduate()))).toList());
        return result;
    }

    private Resume resume(ResumeDetails source) {
        Resume result = new Resume();
        result.setId(id(source.id()));
        result.setCandidateSkills(safe(source.skills()).stream()
                .map(item -> new CandidateHasSkill(null, item.weight(), skill(item.skill()))).toList());
        result.setExperiences(safe(source.experiences()).stream().map(this::experience).toList());
        result.setLanguages(safe(source.languages()).stream()
                .map(item -> new CandidateLanguage(null, item.level(), language(item.language()))).toList());
        if (source.undergraduate() != null) result.setUndergraduate(undergraduate(source.undergraduate()));
        result.setGraduates(safe(source.graduates()).stream().map(this::graduate).toList());
        return result;
    }

    private Experience experience(ExperienceDetails source) {
        if (source == null) return null;
        Experience result = new Experience(source.role(), source.companyName(), source.description(),
                source.durationMonths(), source.seniority(), safe(source.skills()).stream()
                .map(item -> new ExperienceDevelopsSkill(null, item.weight(), skill(item.skill()))).toList());
        result.setId(id(source.id()));
        return result;
    }

    private Undergraduate undergraduate(UndergraduateDetails source) {
        if (source == null) return null;
        Undergraduate result = new Undergraduate(source.field(), source.institution(), date(source.completionDate()),
                source.undergraduateType());
        result.setId(id(source.id()));
        result.setProjects(safe(source.projects()).stream().map(this::project).toList());
        return result;
    }

    private Graduate graduate(GraduateDetails source) {
        if (source == null) return null;
        return new Graduate(id(source.id()), source.degree(), source.field(), source.subarea(),
                source.institution(), date(source.completionDate()),
                safe(source.projects()).stream().map(this::project).toList());
    }

    private Project project(ProjectDetails source) {
        Project result = new Project(source.title(), source.description(), date(source.startDate()),
                date(source.endDate()), safe(source.skills()).stream()
                .map(item -> new ProjectDevelopsSkill(null, item.weight(), skill(item.skill()))).toList());
        result.setId(id(source.id()));
        return result;
    }

    private Skill skill(SkillDetails source) {
        return source == null ? null : new Skill(id(source.id()), source.name());
    }

    private Language language(LanguageDetails source) {
        return source == null ? null : new Language(id(source.id()), source.name());
    }

    private static Long id(String value) { return value == null ? null : Long.valueOf(value); }
    private static Date date(Instant value) { return value == null ? null : Date.from(value); }
    private static String value(String value) { return value == null ? "" : value; }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
