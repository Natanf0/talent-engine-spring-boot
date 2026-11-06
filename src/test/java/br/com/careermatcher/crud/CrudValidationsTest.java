package br.com.careermatcher.crud;

import br.com.careermatcher.validations.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.*;

class CrudValidationsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TextSanitizer texts = new TextSanitizer();
    private final GraphValidations validation = new GraphValidations(texts);

    @Test void sanitizesAllowedHtmlAndPlainFieldsWithoutDamagingCatalogNames() throws Exception {
        ObjectNode candidate = candidate();
        candidate.put("name", "<b>Ana</b><script>alert(1)</script>");
        candidate.put("about", "<p onclick='bad()'>Olá <strong>mundo</strong><img src=x onerror='bad()'><svg><script>bad()</script></svg></p>");
        ObjectNode clean = validation.validate(CANDIDATE, candidate);
        assertThat(clean.path("name").asText()).isEqualTo("Ana");
        assertThat(clean.path("about").asText()).contains("<p>", "<strong>mundo</strong>")
                .doesNotContain("onclick", "onerror", "script", "svg", "img", "bad()");
        assertThat(texts.plain("C++ &amp; C#")).isEqualTo("C++ & C#");
        assertThat(validation.validate(CANDIDATE, clean)).isEqualTo(clean);
    }

    @ParameterizedTest @ValueSource(strings = {"<script>alert(1)</script>", "<style>body{}</style>", "<iframe src='javascript:alert(1)'></iframe>", "<svg onload=alert(1)></svg>"})
    void rejectsRequiredNamesThatBecomeEmpty(String value) throws Exception {
        ObjectNode candidate = candidate().put("name", value);
        assertInvalid(CANDIDATE, candidate, "name");
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "\"12\"", "\"abc\"", "true", "2147483648"})
    void rejectsNonPositiveOrNonIntegerMonths(String value) throws Exception {
        ObjectNode experience = object("{\"role\":\"Dev\",\"seniority\":\"PLENO\",\"durationMonths\":" + value + "}");
        assertInvalid(EXPERIENCE, experience, "durationMonths");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-02-30T12:00:00Z", "2026-09-28", "2026-09-28T12:00:00", "not-a-date"})
    void rejectsInvalidDatesAndMissingTimezone(String date) throws Exception {
        ObjectNode project = object("{\"title\":\"Projeto\"}").put("startDate", date);
        assertInvalid(PROJECT, project, "startDate");
    }

    @Test void permitsFutureDatesAndChecksMergedChronology() throws Exception {
        ObjectNode project = object("{\"title\":\"Projeto\",\"startDate\":\"2030-01-01T00:00:00-03:00\",\"endDate\":\"2031-01-01T00:00:00Z\"}");
        ObjectNode clean = validation.validate(PROJECT, project);
        assertThat(clean.path("startDate").asText()).isEqualTo("2030-01-01T03:00:00Z");
        assertInvalid(PROJECT, validation.merge(clean, object("{\"endDate\":\"2029-01-01T00:00:00Z\"}")), "endDate");
    }

    @ParameterizedTest @ValueSource(strings = {"javascript:alert(1)", "data:text/html,hello", "vbscript:foo", "https://user:password@example.com", "/relative"})
    void rejectsDangerousOrRelativeUrls(String url) throws Exception {
        assertInvalid(CANDIDATE, candidate().put("personalWebsiteURL", url), "personalWebsiteURL");
    }

    @Test void rejectsUnknownFieldsIdsInvalidEnumsAndBounds() throws Exception {
        assertInvalid(CANDIDATE, candidate().put("name", "a".repeat(151)), "name");
        assertInvalid(CANDIDATE, candidate().put("id", 1), "id");
        assertInvalid(CANDIDATE, candidate().put("email", "not-an-email"), "email");
        assertInvalid(VACANCY, object("{\"role\":\"Dev\",\"city\":\"Recife\",\"seniority\":\"invalid\",\"modalidade\":\"REMOTO\"}"), "seniority");
        assertThat(validation.validate(CANDIDATE, candidate().put("name", "a".repeat(150))).path("name").asText()).hasSize(150);
    }

    @Test void sanitizesAndLimitsVacancyAdditionalInformation() throws Exception {
        ObjectNode vacancy = object("{\"role\":\"Dev\",\"city\":\"Recife\",\"seniority\":\"PLENO\",\"modalidade\":\"REMOTO\"}");
        vacancy.put("additionalInfo", "<p>Benefícios <strong>importantes</strong><script>bad()</script></p>");
        ObjectNode clean = validation.validate(VACANCY, vacancy);
        assertThat(clean.path("additionalInfo").asText()).contains("<p>", "<strong>importantes</strong>")
                .doesNotContain("script", "bad()");
        assertInvalid(VACANCY, vacancy.put("additionalInfo", "a".repeat(1501)), "additionalInfo");
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "null", "[{\"weight\":1,\"skill\":{\"name\":\"Java\"}}]"})
    void rejectsExperienceSkillsOnVacancyCreationAndEditing(String skills) throws Exception {
        ObjectNode vacancy = object("""
                {"role":"Dev","city":"Recife","seniority":"PLENO","modalidade":"REMOTO",
                 "experienceRequirement":{"required":true,"experience":{
                   "role":"Backend","durationMonths":12,"seniority":"PLENO"}}}
                """);
        ObjectNode clean = validation.validate(VACANCY, vacancy);
        assertThat(clean.path("experienceRequirement").path("experience").has("skills")).isFalse();
        ((ObjectNode) vacancy.path("experienceRequirement").path("experience"))
                .set("skills", mapper.readTree(skills));
        assertInvalid(VACANCY, vacancy, "experienceRequirement.experience.skills");
        ObjectNode patch = object("{\"experienceRequirement\":{\"experience\":{\"skills\":" + skills + "}}}");
        assertThatThrownBy(() -> new VagaValidations(validation).patch(clean, patch))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(ValidationException.FieldError::field)
                        .contains("experienceRequirement.experience.skills"));
    }

    @Test void rejectsDuplicateNamesAndNonFiniteNumbers() throws Exception {
        ObjectNode resume = object("{\"skills\":[{\"weight\":1,\"skill\":{\"name\":\" Java  Script \"}},{\"weight\":2,\"skill\":{\"name\":\"java script\"}}]}");
        assertInvalid(RESUME, resume, "skills[1]");
        resume = object("{\"skills\":[{\"weight\":1,\"skill\":{\"name\":\"Java\"}}]}");
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            ((ObjectNode) resume.path("skills").get(0)).put("weight", value);
            assertInvalid(RESUME, resume, "skills[0].weight");
        }
    }

    @Test void rejectsUnlistedLanguagesAndDuplicateLanguageAliases() throws Exception {
        assertInvalid(LANGUAGE, object("{\"name\":\"Inventado\"}"), "name");
        ObjectNode resume = object("""
                {"languages":[
                  {"level":1,"language":{"name":"English"}},
                  {"level":2,"language":{"name":" INGLÊS "}}
                ]}
                """);
        assertInvalid(RESUME, resume, "languages[1]");
        for (String alias : new String[]{"Inglês", "INGLES", "English"})
            assertThat(validation.validate(LANGUAGE, object("{}").put("name", alias)).path("name").asText()).isEqualTo(alias);
    }

    @Test void patchPreservesMissingFieldsAndTreatsNullAndArraysExplicitly() throws Exception {
        ObjectNode current = candidate();
        current.put("id", 1).put("phone", "123");
        current.set("resume", object("{\"id\":2,\"skills\":[{\"weight\":1,\"skill\":{\"id\":3,\"name\":\"Java\"}}]}"));
        CandidatoValidations candidates = new CandidatoValidations(validation);
        ObjectNode updated = candidates.patch(current, object("{\"phone\":null,\"resume\":{\"skills\":[]}}"));
        assertThat(updated.has("phone")).isFalse();
        assertThat(updated.path("name").asText()).isEqualTo("Ana");
        assertThat(updated.path("resume").path("skills").isEmpty()).isTrue();
        assertThat(candidates.patch(current, object("{\"resume\":null}")).has("resume")).isFalse();
        assertThatThrownBy(() -> candidates.patch(current, object("{\"name\":null}"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> candidates.patch(current, object("{\"resume\":{\"id\":10}}"))).isInstanceOf(ValidationException.class);
    }

    @Test void limitsOnlyResumeSkillsAndUsesConfiguredMaximumOnPatch() throws Exception {
        ObjectNode resume = object("{}");
        var skills = resume.putArray("skills");
        for (String name : new String[]{"Java", "SQL", "Python"})
            skills.addObject().put("weight", 1).putObject("skill").put("name", name);
        assertThat(validation.validate(RESUME, resume).path("skills")).hasSize(3);
        skills.addObject().put("weight", 1).putObject("skill").put("name", "Go");
        assertInvalid(RESUME, resume, "skills");
        var configured = new GraphValidations(texts, new ResumeSkillPolicy(4));
        assertThat(configured.validate(RESUME, resume).path("skills")).hasSize(4);
        ObjectNode candidate = candidate();
        candidate.set("resume", resume);
        assertThatThrownBy(() -> new CandidatoValidations(validation).patch(candidate, object("{}")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(ValidationException.FieldError::field).contains("resume.skills"));
        assertThatThrownBy(() -> new ResumeSkillPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsDuplicatesWithinEachSourceButPermitsReuseAcrossSources() throws Exception {
        ObjectNode experience = object("{\"role\":\"Dev\",\"durationMonths\":12,\"seniority\":\"PLENO\"}");
        ObjectNode project = object("{\"title\":\"App\",\"startDate\":\"2026-01-01T00:00:00Z\"}");
        var skills = object("{}").putArray("skills");
        for (String name : new String[]{"Java", "SQL", "Python", "Go"})
            skills.addObject().put("weight", 1).putObject("skill").put("name", name);
        experience.set("skills", skills);
        project.set("skills", skills);
        assertThat(validation.validate(EXPERIENCE, experience).path("skills")).hasSize(4);
        assertThat(validation.validate(PROJECT, project).path("skills")).hasSize(4);
        ObjectNode resume = object("{}");
        resume.putArray("experiences").add(experience).add(experience.deepCopy());
        var education = resume.putObject("undergraduate").put("field", "Computação");
        education.putArray("projects").add(project).add(project.deepCopy());
        resume.putArray("skills").add(skills.get(0).deepCopy());
        assertThatCode(() -> validation.validate(RESUME, resume)).doesNotThrowAnyException();
        skills.addObject().put("weight", 2).putObject("skill").put("name", " <b>JAVA</b> ");
        assertInvalid(EXPERIENCE, experience, "skills[4]");
        assertInvalid(PROJECT, project, "skills[4]");
    }

    @Test void calculatesCandidateDurationAndClearsEndForCurrentJob() throws Exception {
        ObjectNode experience = object("""
                {"role":"Dev","seniority":"PLENO","startDate":"2024-01-15","endDate":"2025-02-14","durationMonths":999}
                """);
        assertThat(validation.validate(EXPERIENCE, experience).path("durationMonths").intValue()).isEqualTo(12);
        experience.put("endDate", "2024-01-20");
        var shortExperience = validation.validate(EXPERIENCE, experience);
        assertThat(shortExperience.path("durationMonths").intValue()).isZero();
        assertThat(validation.validate(EXPERIENCE, shortExperience)).isEqualTo(shortExperience);
        experience.put("startDate", java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusMonths(2).toString());
        experience.put("currentJob", true).put("endDate", "ignored old value");
        var current = validation.validate(EXPERIENCE, experience);
        assertThat(current.has("endDate")).isFalse();
        assertThat(current.path("durationMonths").intValue()).isEqualTo(2);
        experience.put("currentJob", false).remove("endDate");
        assertInvalid(EXPERIENCE, experience, "endDate");
    }

    @Test void validatesDateOnlyPeriodsAndKeepsVacancyMonths() throws Exception {
        ObjectNode experience = object("{\"role\":\"Dev\",\"seniority\":\"PLENO\",\"endDate\":\"2025-01-01\"}");
        for (String date : new String[]{"2024-02-30", "2024-01-01T00:00:00Z", "01/01/2024"})
            assertInvalid(EXPERIENCE, experience.put("startDate", date), "startDate");
        experience.put("startDate", "2025-02-01");
        assertInvalid(EXPERIENCE, experience, "endDate");
        experience.put("currentJob", true).put("startDate", "2999-01-01");
        assertInvalid(EXPERIENCE, experience, "startDate");
        var vacancy = object("""
                {"role":"Dev","city":"Natal","seniority":"PLENO","modalidade":"REMOTO",
                "experienceRequirement":{"experience":{"role":"Dev","seniority":"PLENO","durationMonths":24}}}
                """);
        assertThat(validation.validate(VACANCY, vacancy).at("/experienceRequirement/experience/durationMonths").intValue()).isEqualTo(24);
        ((ObjectNode)vacancy.at("/experienceRequirement/experience")).put("startDate", "2024-01-01");
        assertInvalid(VACANCY, vacancy, "experienceRequirement.experience.startDate");
    }

    private ObjectNode candidate() throws Exception { return object("{\"name\":\"Ana\",\"professionalTitle\":\"Dev\"}"); }
    private ObjectNode object(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }
    private void assertInvalid(GraphSchema.Kind kind, ObjectNode input, String path) {
        assertThatThrownBy(() -> validation.validate(kind, input)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.errors()).extracting(ValidationException.FieldError::field).contains(path));
    }
}
