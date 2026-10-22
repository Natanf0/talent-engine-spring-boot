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

    private ObjectNode candidate() throws Exception { return object("{\"name\":\"Ana\",\"professionalTitle\":\"Dev\"}"); }
    private ObjectNode object(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }
    private void assertInvalid(GraphSchema.Kind kind, ObjectNode input, String path) {
        assertThatThrownBy(() -> validation.validate(kind, input)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.errors()).extracting(ValidationException.FieldError::field).contains(path));
    }
}
