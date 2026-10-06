package br.com.careermatcher.relations;


import br.com.careermatcher.models.Undergraduate;
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
public class VacancyUndergrad {
    @RelationshipId
    private Long id;

    private Boolean required;

    @TargetNode
    private Undergraduate undergraduate;

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
