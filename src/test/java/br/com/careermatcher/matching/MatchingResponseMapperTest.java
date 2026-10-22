package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.DTOs.matching.AllocationResult;
import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.DTOs.matching.VacancyDTO;
import br.com.careermatcher.enums.DEGREE;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.enums.UNDERGRADUATETYPE;
import br.com.careermatcher.models.*;
import br.com.careermatcher.relations.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingResponseMapperTest {

    private final MatchingResponseMapper mapper = new MatchingResponseMapper();

    @Test
    void mapsEveryLoadedNodeAndBusinessRelationshipAttribute() {
        Skill java = new Skill(101L, "Java");
        Skill neo4j = new Skill(102L, "Neo4j");
        Language english = new Language(201L, "Inglês");
        Date completionDate = Date.from(Instant.parse("2024-06-30T12:00:00Z"));

        Project project = new Project();
        set(project, "id", 301L);
        set(project, "title", "Talent Engine");
        set(project, "description", "Motor de matching");
        set(project, "startDate", Date.from(Instant.parse("2023-01-01T00:00:00Z")));
        set(project, "endDate", Date.from(Instant.parse("2023-12-31T00:00:00Z")));
        set(project, "projectDevelopsSkillList", List.of(new ProjectDevelopsSkill(1L, 0.75, neo4j)));

        Undergraduate undergraduate = new Undergraduate(
                "Ciência da Computação", "UFRJ", completionDate, UNDERGRADUATETYPE.BACHARELADO);
        set(undergraduate, "id", 401L);
        undergraduate.setProjects(List.of(project));

        Graduate graduate = new Graduate(501L, DEGREE.MASTERS, "Computação", "IA", "USP",
                completionDate, List.of(project));
        Experience experience = new Experience("Backend Engineer", "ACME", "Microsserviços", 36,
                SENIORITY.PLENO, List.of(new ExperienceDevelopsSkill(2L, 0.85, java)));
        set(experience, "id", 601L);

        Resume resume = new Resume();
        set(resume, "id", 701L);
        resume.setCandidateSkills(List.of(new CandidateHasSkill(3L, 0.95, java)));
        resume.setExperiences(List.of(experience));
        resume.setLanguages(List.of(new CandidateLanguage(4L, 0.8, english)));
        resume.setUndergraduate(undergraduate);
        resume.setGraduates(List.of(graduate));

        Candidate candidate = new Candidate("Ana", "Backend Java", "Engenheira", "11999999999");
        set(candidate, "id", 1L);
        candidate.setLinkedinURL("https://linkedin.example/ana");
        candidate.setPersonalWebsiteURL("https://ana.example");
        candidate.setResume(resume);

        Vacancy vacancy = new Vacancy("Java Developer", "Rio de Janeiro", SENIORITY.PLENO, Modalidade.REMOTO);
        set(vacancy, "id", 10L);
        vacancy.setCompetencias(List.of(new VacancySkill(5L, 0.9, true, java)));
        vacancy.setExperience(new VacancyExperience(6L, true, experience));
        vacancy.setLanguages(List.of(new VacancyLanguage(7L, false, 0.7, english)));
        vacancy.setUndergraduates(List.of(new VacancyUndergrad(8L, true, undergraduate)));
        vacancy.setGraduate(List.of(new VacancyGraduate(9L, false, graduate)));

        VacancyDTO vacancyDTO = new VacancyDTO("10", vacancy.getRole(), vacancy.getCity(),
                vacancy.getSeniority(), vacancy.getModalidade());
        CandidateDTO candidateDTO = new CandidateDTO("1", candidate.getName(), candidate.getAbout(),
                candidate.getProfessionalTitle(), candidate.getPhone(), null);
        LinkedHashMap<VacancyDTO, List<CandidateDTO>> shortlists = new LinkedHashMap<>();
        shortlists.put(vacancyDTO, List.of(candidateDTO));

        AllocationResponse response = mapper.toResponse(
                new AllocationResult(shortlists, Set.of("2"), 1), List.of(candidate), List.of(vacancy));

        AllocationResponse.Shortlist shortlist = response.shortlists().getFirst();
        assertThat(shortlist.vacancy().skillRequirements().getFirst())
                .isEqualTo(new AllocationResponse.VacancySkillRequirement(
                        0.9, true, new AllocationResponse.SkillDetails("101", "Java")));
        assertThat(shortlist.vacancy().experienceRequirement().required()).isTrue();
        assertThat(shortlist.vacancy().experienceRequirement().experience().durationMonths()).isEqualTo(36);
        assertThat(new ObjectMapper().valueToTree(shortlist.vacancy().experienceRequirement())
                .path("experience").has("skills")).isFalse();
        assertThat(shortlist.vacancy().languageRequirements().getFirst().level()).isEqualTo(0.7);
        assertThat(shortlist.vacancy().undergraduateRequirements().getFirst().required()).isTrue();
        assertThat(shortlist.vacancy().graduateRequirements().getFirst().required()).isFalse();

        AllocationResponse.CandidateDetails details = shortlist.candidates().getFirst();
        assertThat(details).extracting(AllocationResponse.CandidateDetails::id,
                        AllocationResponse.CandidateDetails::name, AllocationResponse.CandidateDetails::about,
                        AllocationResponse.CandidateDetails::professionalTitle)
                .containsExactly("1", "Ana", "Backend Java", "Engenheira");
        assertThat(details.resume().skills().getFirst().weight()).isEqualTo(0.95);
        assertThat(details.resume().languages().getFirst().level()).isEqualTo(0.8);
        assertThat(details.resume().experiences().getFirst().companyName()).isEqualTo("ACME");
        assertThat(details.resume().experiences().getFirst().skills().getFirst().weight()).isEqualTo(0.85);
        assertThat(details.resume().undergraduate().completionDate())
                .isEqualTo(Instant.parse("2024-06-30T12:00:00Z"));
        assertThat(details.resume().undergraduate().projects().getFirst().skills().getFirst().weight())
                .isEqualTo(0.75);
        assertThat(details.resume().graduates().getFirst().degree()).isEqualTo(DEGREE.MASTERS);
        assertThat(response.unassignedCandidateIds()).containsExactly("2");
    }

    @Test
    void preservesEmptyLoadedCollectionsAndNullOptionalRelationships() {
        Candidate candidate = new Candidate("Ana", "Backend", "Engenheira", "phone");
        set(candidate, "id", 1L);
        Resume resume = new Resume();
        set(resume, "id", 2L);
        resume.setCandidateSkills(List.of());
        resume.setExperiences(List.of());
        resume.setLanguages(List.of());
        resume.setGraduates(List.of());
        candidate.setResume(resume);

        Vacancy vacancy = new Vacancy("Developer", "Recife", SENIORITY.JUNIOR, Modalidade.HIBRIDO);
        set(vacancy, "id", 10L);
        vacancy.setCompetencias(List.of());
        vacancy.setLanguages(List.of());
        vacancy.setUndergraduates(List.of());
        vacancy.setGraduate(List.of());

        LinkedHashMap<VacancyDTO, List<CandidateDTO>> shortlists = new LinkedHashMap<>();
        shortlists.put(new VacancyDTO("10", "Developer", "Recife", SENIORITY.JUNIOR, Modalidade.HIBRIDO),
                List.of(new CandidateDTO("1", "Ana", "Backend", "Engenheira", "phone", "mail")));

        AllocationResponse response = mapper.toResponse(
                new AllocationResult(shortlists, Set.of(), 1), List.of(candidate), List.of(vacancy));

        assertThat(response.shortlists().getFirst().vacancy().skillRequirements()).isEmpty();
        assertThat(response.shortlists().getFirst().vacancy().experienceRequirement()).isNull();
        assertThat(response.shortlists().getFirst().candidates().getFirst().resume().experiences()).isEmpty();
        assertThat(response.shortlists().getFirst().candidates().getFirst().resume().undergraduate()).isNull();
    }

    private static void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
