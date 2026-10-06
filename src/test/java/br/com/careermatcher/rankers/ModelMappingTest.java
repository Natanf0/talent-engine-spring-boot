package br.com.careermatcher.rankers;

import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Project;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Skill;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateLanguage;
import br.com.careermatcher.relations.VacancyExperience;
import br.com.careermatcher.relations.VacancyGraduate;
import br.com.careermatcher.relations.VacancyLanguage;
import br.com.careermatcher.relations.VacancySkill;
import br.com.careermatcher.relations.VacancyUndergrad;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.RelationshipId;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMappingTest {

    @Test
    void relationshipPropertyUsesRelationshipId() throws NoSuchFieldException {
        assertThat(CandidateLanguage.class.getDeclaredField("id").getAnnotation(RelationshipId.class))
                .isNotNull();
    }

    @Test
    void experienceAndProjectSkillsUseDevelopsSkillRelationship() throws NoSuchFieldException {
        assertRelationship(Experience.class, "skillsRelatedToExperience", "DEVELOPS_SKILL");
        assertRelationship(Project.class, "projectDevelopsSkillList", "DEVELOPS_SKILL");
    }

    @Test
    void vacancyUsesCanonicalSkillRelationshipAndLowercaseSeniorityField() throws NoSuchFieldException {
        assertRelationship(Vacancy.class, "competencias", "NEEDS_SKILL");
        assertThat(Vacancy.class.getDeclaredField("seniority")).isNotNull();
        assertThat(Vacancy.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .contains("getSeniority").doesNotContain("getSENIORITY");
    }

    @Test
    void resumeUsesGraduateCollection() throws NoSuchFieldException {
        assertRelationship(Resume.class, "graduates", "POSTGRADUATED_IN");
        assertThat(Resume.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .contains("getGraduates", "setGraduates").doesNotContain("getMestrado", "setMestrado");
    }

    @Test
    void absentRequiredPropertyDefaultsToDesirableForEveryRequirementRelationship() {
        VacancySkill relationship = new VacancySkill(null, 5.0, null, new Skill(null, "Java"));

        assertThat(relationship.getRequired()).isNull();
        assertThat(relationship.isRequired()).isFalse();
        assertThat(new VacancyExperience(null, null, null).isRequired()).isFalse();
        assertThat(new VacancyGraduate(null, null, null).isRequired()).isFalse();
        assertThat(new VacancyLanguage(null, null, null).isRequired()).isFalse();
        assertThat(new VacancyUndergrad(null, null, null).isRequired()).isFalse();
    }

    private static void assertRelationship(Class<?> owner, String field, String expectedType)
            throws NoSuchFieldException {
        Relationship relationship = owner.getDeclaredField(field).getAnnotation(Relationship.class);
        assertThat(relationship).isNotNull();
        assertThat(relationship.type()).isEqualTo(expectedType);
        assertThat(relationship.direction()).isEqualTo(Relationship.Direction.OUTGOING);
    }
}
