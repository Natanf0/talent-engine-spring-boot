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
public class CandidateHasSkill {
    @RelationshipId
    private Long id;

    private Double weight;

    @TargetNode
    private Skill skill;
}