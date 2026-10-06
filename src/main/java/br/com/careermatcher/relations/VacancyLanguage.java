package br.com.careermatcher.relations;

import br.com.careermatcher.models.Language;
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
public class VacancyLanguage {
    @RelationshipId
    private Long id;

    private Boolean required;
    private Double level;

    @TargetNode
    private Language language;

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public VacancyLanguage(Long id, Boolean required, Language language) {
        this(id, required, 1.0, language);
    }
}
