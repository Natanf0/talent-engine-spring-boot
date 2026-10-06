package br.com.careermatcher.relations;

import br.com.careermatcher.models.Skill;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class VacancySkill {
    @RelationshipId
    private Long id;

    private Double weight;

    /**
     * {@code null} is treated as {@code false} so relationships created before
     * this property existed remain desirable rather than becoming eliminatory.
     */
    private Boolean required;

    @TargetNode
    private Skill skill;

    public VacancySkill(Long id, Double weight, Skill skill) {
        this(id, weight, null, skill);
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
