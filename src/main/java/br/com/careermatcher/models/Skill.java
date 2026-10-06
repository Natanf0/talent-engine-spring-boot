package br.com.careermatcher.models;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("SKILL")
@AllArgsConstructor
@Getter
@NoArgsConstructor
public class Skill {
    @Id @GeneratedValue
    private Long id;

    private String name;
}
