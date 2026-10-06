package br.com.careermatcher.services;

import br.com.careermatcher.enums.DEGREE;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.enums.UNDERGRADUATETYPE;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Graduate;
import br.com.careermatcher.models.Language;
import br.com.careermatcher.models.Project;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Skill;
import br.com.careermatcher.models.Undergraduate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateHasSkill;
import br.com.careermatcher.relations.CandidateLanguage;
import br.com.careermatcher.relations.ExperienceDevelopsSkill;
import br.com.careermatcher.relations.ProjectDevelopsSkill;
import br.com.careermatcher.relations.VacancyExperience;
import br.com.careermatcher.relations.VacancyGraduate;
import br.com.careermatcher.relations.VacancyLanguage;
import br.com.careermatcher.relations.VacancySkill;
import br.com.careermatcher.relations.VacancyUndergrad;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingTableFormatterTest {

    private static final String PASSWORD = "PASSWORD_MUST_NEVER_APPEAR_98237";

    @Test
    void includesTheCompleteCandidateAndVacancyGraphsInTheirOwnColumns() {
        Candidate candidate = candidate();
        ReflectionTestUtils.setField(candidate, "id", 1001L);
        candidate.setLinkedinURL("https://linkedin.example/ana");
        candidate.setPersonalWebsiteURL("https://ana.example");
        Resume resume = new Resume();
        ReflectionTestUtils.setField(resume, "id", 1002L);
        resume.setCandidateSkills(List.of(new CandidateHasSkill(1101L, 7.25,
                new Skill(1102L, "Java-candidato"))));
        resume.setLanguages(List.of(new CandidateLanguage(1201L, 3.5,
                new Language(1202L, "Português-candidato"))));
        resume.setExperiences(List.of(experience(1301L, "candidato")));
        resume.setUndergraduate(undergraduate(1401L, "candidato"));
        resume.setGraduates(List.of(graduate(1501L, "candidato")));
        candidate.setResume(resume);

        Vacancy vacancy = vacancy();
        ReflectionTestUtils.setField(vacancy, "id", 2001L);
        vacancy.setCompetencias(List.of(new VacancySkill(2101L, 6.75, true,
                new Skill(2102L, "Java-vaga"))));
        vacancy.setLanguages(List.of(new VacancyLanguage(2201L, false,
                new Language(2202L, "Português-vaga"))));
        vacancy.setExperience(new VacancyExperience(2300L, true, experience(2301L, "vaga")));
        vacancy.setUndergraduates(List.of(new VacancyUndergrad(2400L, false,
                undergraduate(2401L, "vaga"))));
        vacancy.setGraduate(List.of(new VacancyGraduate(2500L, true,
                graduate(2501L, "vaga"))));

        String table = MatchingTableFormatter.format(candidate, vacancy);
        assertAligned(table);
        String candidateColumn = column(table, 29);
        String vacancyColumn = column(table, 102);

        assertThat(candidateColumn).contains("Ana Souza", "Apresentação da candidata", "Engenheira",
                "11987654321", "https://linkedin.example/ana", "https://ana.example",
                "1001", "1002", "1101", "1102", "7.25", "Java-candidato",
                "1201", "1202", "3.5", "Português-candidato");
        assertGraphValues(candidateColumn, 1000, "candidato");
        assertThat(vacancyColumn).contains("Backend Java Developer", "São Paulo", "PLENO", "REMOTO",
                "2001", "2101", "2102", "6.75", "Java-vaga", "2201", "2202", "Português-vaga",
                "2300", "2400", "2500", "Obrigatório: true", "Obrigatório: false");
        assertGraphValues(vacancyColumn, 2000, "vaga");
        assertThat(candidateColumn).doesNotContain("Java-vaga", "Empresa-vaga");
        assertThat(vacancyColumn).doesNotContain("ana@example.test", "Empresa-candidato");
        assertThat(table).doesNotContain(PASSWORD, "password", "Senha", "mestrado", "SENIORITY");
    }

    @Test
    void distinguishesMissingValuesEmptyCollectionsAndNonApplicableFields() {
        Candidate candidate = new Candidate();
        Vacancy vacancy = new Vacancy();

        String withoutResume = MatchingTableFormatter.format(candidate, vacancy);
        assertAligned(withoutResume);
        assertThat(withoutResume).contains("Não informado", "—").doesNotContain("null");

        Resume resume = new Resume();
        resume.setCandidateSkills(List.of());
        resume.setLanguages(List.of());
        resume.setExperiences(List.of());
        resume.setGraduates(List.of());
        candidate.setResume(resume);
        vacancy.setCompetencias(List.of());
        vacancy.setLanguages(List.of());
        vacancy.setUndergraduates(List.of());
        vacancy.setGraduate(List.of());

        String emptyCollections = MatchingTableFormatter.format(candidate, vacancy);
        assertAligned(emptyCollections);
        assertThat(column(emptyCollections, 29)).contains("Nenhum", "Não informado");
        assertThat(column(emptyCollections, 102)).contains("Nenhum", "Não informado", "—");
    }

    @Test
    void toleratesMissingCollectionElementsAndRelationshipTargets() {
        Candidate candidate = candidate();
        Resume resume = new Resume();
        resume.setCandidateSkills(Arrays.asList(null, new CandidateHasSkill(1L, null, null)));
        resume.setLanguages(Arrays.asList(null, new CandidateLanguage(2L, null, null)));
        Experience experience = new Experience("Experiência incompleta", null, null, null, null,
                Arrays.asList(null, new ExperienceDevelopsSkill(3L, null, null)));
        resume.setExperiences(Arrays.asList(null, experience));
        Project project = new Project();
        ReflectionTestUtils.setField(project, "projectDevelopsSkillList",
                Arrays.asList(null, new ProjectDevelopsSkill(4L, null, null)));
        Undergraduate undergraduate = new Undergraduate();
        undergraduate.setProjects(Arrays.asList(null, project));
        resume.setUndergraduate(undergraduate);
        Graduate graduate = new Graduate();
        graduate.setProjects(Arrays.asList(null, project));
        resume.setGraduates(Arrays.asList(null, graduate));
        candidate.setResume(resume);

        Vacancy vacancy = vacancy();
        vacancy.setCompetencias(Arrays.asList(null, new VacancySkill(5L, null, null, null)));
        vacancy.setLanguages(Arrays.asList(null, new VacancyLanguage(6L, null, null)));
        vacancy.setExperience(new VacancyExperience(7L, null, null));
        vacancy.setUndergraduates(Arrays.asList(null, new VacancyUndergrad(8L, null, null)));
        vacancy.setGraduate(Arrays.asList(null, new VacancyGraduate(9L, null, null)));

        String table = MatchingTableFormatter.format(candidate, vacancy);

        assertAligned(table);
        assertThat(table).contains("Experiência incompleta", "Não informado").doesNotContain("null");
    }

    @Test
    void preservesTrueFalseAndAbsentRequiredForEveryRequirementKind() {
        for (Boolean required : Arrays.asList(true, false, null)) {
            Vacancy vacancy = vacancy();
            vacancy.setCompetencias(List.of(new VacancySkill(1L, 4.0, required, null)));
            vacancy.setLanguages(List.of(new VacancyLanguage(2L, required, null)));
            vacancy.setExperience(new VacancyExperience(3L, required, null));
            vacancy.setUndergraduates(List.of(new VacancyUndergrad(4L, required, null)));
            vacancy.setGraduate(List.of(new VacancyGraduate(5L, required, null)));

            String vacancyColumn = column(MatchingTableFormatter.format(candidate(), vacancy), 102);
            String expected = "Obrigatório: " + (required == null ? "Não informado" : required);

            assertThat(vacancyColumn.lines().filter(line -> line.contains(expected)).count())
                    .as("raw required value %s on all five requirement types", required).isEqualTo(5);
        }
    }

    @Test
    void wrapsLongUrlsAndPreservesMultilineTextAndColumnAlignment() {
        String longUrl = "https://portfolio.example/" + "abcdefghijklmnopqrstuvxyz0123456789".repeat(7);
        Candidate candidate = candidate();
        candidate.setPersonalWebsiteURL(longUrl);
        candidate.setAbout("Primeira linha com acentuação e | separador.\r\nSegunda linha completa.");

        String table = MatchingTableFormatter.format(candidate, vacancy());

        assertAligned(table);
        String candidateColumn = column(table, 29);
        assertThat(candidateColumn.replace("\n", "")).contains(longUrl);
        assertThat(candidateColumn).contains("Primeira linha com acentuação e | separador.",
                "Segunda linha completa.");
        assertThat(table).doesNotContain(PASSWORD, "\r");
    }

    @Test
    void exposesResumeIdToTheFormatterWithoutChangingItsJsonOutput() throws Exception {
        Resume resume = new Resume();
        ReflectionTestUtils.setField(resume, "id", 8675309L);
        Candidate candidate = candidate();
        candidate.setResume(resume);

        assertThat(column(MatchingTableFormatter.format(candidate, vacancy()), 29)).contains("8675309");
        assertThat(resume.getId()).isEqualTo(8675309L);
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(resume)).has("id")).isFalse();
    }

    private static void assertGraphValues(String column, int base, String suffix) {
        assertThat(column).contains("Cargo-" + suffix, "Empresa-" + suffix, "Descrição experiência-" + suffix,
                "37", "SENIOR", "Competência experiência-" + suffix, "8.25",
                "Área graduação-" + suffix, "Instituição graduação-" + suffix, "BACHARELADO",
                "Área pós-" + suffix, "Subárea-" + suffix, "Instituição pós-" + suffix, "MASTERS",
                "2020-06-01T12:34:56Z", "2021-07-02T13:35:57Z", "2022-08-03T14:36:58Z",
                "Projeto graduação-" + suffix, "Descrição projeto graduação-" + suffix,
                "Competência projeto graduação-" + suffix, "2.25",
                "Projeto pós-" + suffix, "Descrição projeto pós-" + suffix,
                "Competência projeto pós-" + suffix);
        for (int offset : new int[]{301, 302, 303, 401, 402, 403, 404, 501, 502, 503, 504}) {
            assertThat(column).as("node/relationship ID %s", base + offset).contains(String.valueOf(base + offset));
        }
    }

    private static Experience experience(long id, String suffix) {
        Experience experience = new Experience("Cargo-" + suffix, "Empresa-" + suffix,
                "Descrição experiência-" + suffix, 37, SENIORITY.SENIOR,
                List.of(new ExperienceDevelopsSkill(id + 1, 8.25,
                        new Skill(id + 2, "Competência experiência-" + suffix))));
        ReflectionTestUtils.setField(experience, "id", id);
        return experience;
    }

    private static Undergraduate undergraduate(long id, String suffix) {
        Undergraduate undergraduate = new Undergraduate("Área graduação-" + suffix,
                "Instituição graduação-" + suffix, date("2020-06-01T12:34:56Z"),
                UNDERGRADUATETYPE.BACHARELADO);
        ReflectionTestUtils.setField(undergraduate, "id", id);
        undergraduate.setProjects(List.of(project(id + 1, "graduação-" + suffix)));
        return undergraduate;
    }

    private static Graduate graduate(long id, String suffix) {
        return new Graduate(id, DEGREE.MASTERS, "Área pós-" + suffix, "Subárea-" + suffix,
                "Instituição pós-" + suffix, date("2020-06-01T12:34:56Z"),
                List.of(project(id + 1, "pós-" + suffix)));
    }

    private static Project project(long id, String suffix) {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", id);
        ReflectionTestUtils.setField(project, "title", "Projeto " + suffix);
        ReflectionTestUtils.setField(project, "description", "Descrição projeto " + suffix);
        ReflectionTestUtils.setField(project, "startDate", date("2021-07-02T13:35:57Z"));
        ReflectionTestUtils.setField(project, "endDate", date("2022-08-03T14:36:58Z"));
        ReflectionTestUtils.setField(project, "projectDevelopsSkillList", List.of(
                new ProjectDevelopsSkill(id + 1, 2.25, new Skill(id + 2, "Competência projeto " + suffix))));
        return project;
    }

    private static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    private static Candidate candidate() {
        return new Candidate("Ana Souza", "Apresentação da candidata", "Engenheira",
                "11987654321");
    }

    private static Vacancy vacancy() {
        return new Vacancy("Backend Java Developer", "São Paulo", SENIORITY.PLENO, Modalidade.REMOTO);
    }

    private static String column(String table, int start) {
        return table.lines().filter(line -> line.startsWith("|"))
                .map(line -> line.substring(start, start + 70).strip())
                .collect(Collectors.joining("\n"));
    }

    private static void assertAligned(String table) {
        assertThat(table).contains("Campo", "DE: Candidato", "PARA: Vaga").endsWith("\n");
        assertThat(table.lines()).allSatisfy(line -> {
            assertThat(line).hasSize(174);
            for (int boundary : new int[]{0, 27, 100, 173}) {
                assertThat(line.charAt(boundary)).isEqualTo(line.charAt(0));
            }
        });
    }
}
