package br.com.careermatcher.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Node("COMPANY")
@Getter
@NoArgsConstructor
public class Company {
    @Id @GeneratedValue Long id;
    private String name, cnpj, about;

    @Relationship(type = "PUBLISHES", direction = Relationship.Direction.OUTGOING)
    @Setter private List<Vacancy> vacancies;

    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    @Setter private List<MatchingExecution> executions;

    public Company(String name, String cnpj, String password){
        this.name = name;
        this.cnpj = cnpj;
    }
}
