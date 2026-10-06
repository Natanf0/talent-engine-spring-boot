package br.com.careermatcher.models;

import br.com.careermatcher.enums.DEGREE;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.Date;
import java.util.List;

@Node("GRADUATE")
@AllArgsConstructor
@Getter
@NoArgsConstructor
public class Graduate {
    @Id
    @GeneratedValue
    private Long id;
    private DEGREE degree;
    private String field, subarea, institution;
    private Date completionDate;
    @Relationship(type = "DEVELOPS_A", direction = Relationship.Direction.OUTGOING)
    @Setter private List<Project> projects;
}
