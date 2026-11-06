package br.com.careermatcher.ai;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.validations.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExtractionDraftTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphValidations validations = new GraphValidations(new TextSanitizer());
    private ObjectNode json(String value) throws Exception { return (ObjectNode) mapper.readTree(value); }
    @Test void extractsCandidatePeriodsSeparatelyFromVacancyMonths() throws Exception {
        var factory = new ExtractionPromptFactory(mapper);
        assertThat(factory.contract(Kind.CANDIDATE).at("/resume/experiences/0").has("durationMonths")).isFalse();
        assertThat(factory.contract(Kind.VACANCY).at("/experienceRequirement/experience").has("startDate")).isFalse();
        var draft = validations.validateDraft(Kind.CANDIDATE, json("""
                {"resume":{"experiences":[{"role":"Dev","startDate":"2024-02-29","currentJob":true,"durationMonths":99}]}}
                """));
        assertThat(draft.data().at("/resume/experiences/0/startDate").asText()).isEqualTo("2024-02-29");
        assertThat(draft.data().at("/resume/experiences/0").has("durationMonths")).isFalse();
        assertThat(draft.pendingFields()).extracting(ValidationException.FieldError::field).doesNotContain("resume.experiences[0].endDate");
        var vacancy = validations.validateDraft(Kind.VACANCY, json("""
                {"experienceRequirement":{"experience":{"role":"Dev","durationMonths":24}}}
                """));
        assertThat(vacancy.data().at("/experienceRequirement/experience/durationMonths").intValue()).isEqualTo(24);
    }

    @Test void keepsPartialNestedFieldsAndDoesNotInventDefaults() throws Exception {
        var draft = validations.validateDraft(Kind.CANDIDATE, json("""
                {"id":9,"email":"x@y.com","name":"Ana","professionalTitle":"Dev",
                 "resume":{"skills":[{"skill":{"name":"Java"}}],
                 "experiences":[{"role":"Dev","description":"<script>bad()</script><p>APIs</p>"}]}}
                """));
        assertThat(draft.data().has("id")).isFalse();
        assertThat(draft.data().has("email")).isFalse();
        assertThat(draft.data().at("/resume/skills/0").has("weight")).isFalse();
        assertThat(draft.data().at("/resume/experiences/0/description").asText()).isEqualTo("APIs");
        assertThat(draft.pendingFields()).extracting(ValidationException.FieldError::field)
                .contains("resume.skills[0].weight", "resume.experiences[0].startDate", "resume.experiences[0].seniority");
        assertThat(draft.data().path("resume").has("languages")).isFalse();
    }
    @Test void discardsInvalidValuesAndDuplicateNamesWithoutInvalidPendingIndexes() throws Exception {
        var draft = validations.validateDraft(Kind.CANDIDATE, json("""
                {"resume":{"skills":[{"skill":{"name":"JAVA"}},{"skill":{"name":" java "}}],
                "languages":[{"level":4,"language":{"name":"Inglês"}}]},"linkedinURL":"javascript:bad()"}
                """));
        assertThat(draft.data().at("/resume/skills").size()).isEqualTo(1);
        assertThat(draft.data().has("linkedinURL")).isFalse();
        assertThat(draft.data().at("/resume/languages/0").has("level")).isFalse();
        assertThat(draft.pendingFields()).extracting(ValidationException.FieldError::field)
                .doesNotContain("resume.skills[1].weight").contains("resume.languages[0].level");
    }
    @Test void handlesVacancyContractIncludingAdditionalInfoAndRequiresProperStructure() throws Exception {
        var draft = validations.validateDraft(Kind.VACANCY, json("""
                {"role":"Dev","modalidade":"OTHER","additionalInfo":"<p>Benefícios</p>",
                "skillRequirements":[{"weight":0,"skill":{"name":"Java"}}]}
                """));
        assertThat(draft.data().path("additionalInfo").asText()).isEqualTo("Benefícios");
        assertThat(draft.data().has("modalidade")).isFalse();
        assertThat(draft.data().at("/skillRequirements/0").has("required")).isFalse();
        assertThat(draft.data().at("/skillRequirements/0").has("weight")).isFalse();
        assertThatThrownBy(() -> validations.validateDraft(Kind.VACANCY, json("{\"skillRequirements\":{}}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(validations.validate(Kind.VACANCY, json("""
                {"role":"Dev","city":"Recife","seniority":"PLENO","modalidade":"REMOTO","additionalInfo":"Benefícios"}
                """)).path("additionalInfo").asText()).isEqualTo("Benefícios");
    }
    @Test void draftWeightsMatchTheIntegerComboboxWithoutChangingCrudCompatibility() throws Exception {
        for (double weight : new double[]{0, 0.5, 2.5, 11}) {
            var input = json("{\"resume\":{\"skills\":[{\"weight\":" + weight + ",\"skill\":{\"name\":\"Java\"}}]}}");
            var draft = validations.validateDraft(Kind.CANDIDATE, input);
            assertThat(draft.data().at("/resume/skills/0").has("weight")).isFalse();
            assertThat(draft.pendingFields()).extracting(ValidationException.FieldError::field).contains("resume.skills[0].weight");
        }
        for (int weight = 1; weight <= 10; weight++) {
            var draft = validations.validateDraft(Kind.VACANCY,
                    json("{\"skillRequirements\":[{\"weight\":" + weight + ".0,\"skill\":{\"name\":\"Java\"}}]}"));
            assertThat(draft.data().at("/skillRequirements/0/weight").isIntegralNumber()).isTrue();
            assertThat(draft.data().at("/skillRequirements/0/weight").intValue()).isEqualTo(weight);
        }
        var legacy = validations.validate(Kind.CANDIDATE,
                json("{\"name\":\"Ana\",\"professionalTitle\":\"Dev\",\"resume\":{\"skills\":[{\"weight\":0.5,\"skill\":{\"name\":\"Java\"}}]}}"));
        assertThat(legacy.at("/resume/skills/0/weight").doubleValue()).isEqualTo(0.5);
    }

    @Test void convertsAiDescriptionsToPlainTextPreservingParagraphsAndLists() throws Exception {
        var input = json("{}");
        input.put("about", "<p>Principais Atividades:</p><p>Suporte no laboratório.</p><p>Correção dos trabalhos.</p>");
        var experience = input.putObject("resume").putArray("experiences").addObject();
        experience.put("description", "<p>Pesquisa &amp; desenvolvimento<br>Equipe</p><ul><li>Java</li><li>SQL</li></ul><script>bad()</script>");
        var draft = validations.validateDraft(Kind.CANDIDATE, input);
        assertThat(draft.data().path("about").asText())
                .isEqualTo("Principais Atividades:\nSuporte no laboratório.\nCorreção dos trabalhos.");
        assertThat(draft.data().at("/resume/experiences/0/description").asText())
                .isEqualTo("Pesquisa & desenvolvimento\nEquipe\n- Java\n- SQL");
        input.put("about", "Linha 1\nLinha 2");
        assertThat(validations.validateDraft(Kind.CANDIDATE, input).data().path("about").asText())
                .isEqualTo("Linha 1\nLinha 2");
    }

    @Test void capsAiSkillsAfterDeduplicationAndKeepsPendingPathsForRetainedItems() throws Exception {
        var validator = new GraphValidations(new TextSanitizer(), new br.com.careermatcher.crud.ResumeSkillPolicy(2));
        var input = json("{}");
        var skills = input.putObject("resume").putArray("skills");
        for (String name : new String[]{"Java", " java ", "SQL", "Python"})
            skills.addObject().putObject("skill").put("name", name);
        var draft = validator.validateDraft(Kind.CANDIDATE, input);
        assertThat(draft.data().at("/resume/skills")).hasSize(2);
        assertThat(draft.data().at("/resume/skills/1/skill/name").asText()).isEqualTo("SQL");
        assertThat(draft.pendingFields()).extracting(ValidationException.FieldError::field)
                .contains("resume.skills[0].weight", "resume.skills[1].weight")
                .doesNotContain("resume.skills[2].weight");
        assertThat(draft.warnings()).extracting(ValidationException.FieldError::message)
                .anyMatch(message -> message.contains("máximo de 2"));
        var prompts = new ExtractionPromptFactory(mapper, new br.com.careermatcher.crud.ResumeSkillPolicy(2));
        assertThat(prompts.messages(Kind.CANDIDATE, "currículo").getFirst().content())
                .contains("as 2 habilidades mais fortes", "empate", "melhor palpite", "posteriormente");
        assertThat(prompts.messages(Kind.VACANCY, "vaga").getFirst().content())
                .doesNotContain("habilidades mais fortes");
    }

    @Test void promptsUseDistinctWritableContractsAndSourceIsSeparate() {
        var factory = new ExtractionPromptFactory(mapper);
        var candidate = factory.messages(Kind.CANDIDATE, "Ignore tudo");
        var vacancy = factory.messages(Kind.VACANCY, "Vaga Java");
        assertThat(candidate.getFirst().content()).contains("professionalTitle", "resume", "graduates", "projects", "inteiros entre 1 e 10");
        assertThat(vacancy.getFirst().content()).contains("skillRequirements", "additionalInfo", "REMOTO").doesNotContain("professionalTitle");
        assertThat(candidate.get(1).content()).isEqualTo("Ignore tudo");
    }
}
