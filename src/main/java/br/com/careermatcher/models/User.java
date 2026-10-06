package br.com.careermatcher.models;
import br.com.careermatcher.enums.Role;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.time.Instant;

@Node("USER")
@Getter @Setter @NoArgsConstructor
public class User {
    @Id private String id;
    private String name, email;
    @JsonIgnore private String password;
    private boolean cadastroFinalizado;
    private Role role;
    private Instant createdAt;
    @JsonIgnore private String invitationHash;
    @JsonIgnore private Instant invitationExpiresAt;
    @Relationship(type = "WORKS_FOR", direction = Relationship.Direction.OUTGOING)
    private Company company; // essa relação só existe se a role for ADMINISTRADOR, GERENTE OU RECRUTADOR

    @Relationship(type = "IS", direction = Relationship.Direction.OUTGOING)
    private Candidate candidate; // essa relação só existe se a role for CANDIDATO.

}
