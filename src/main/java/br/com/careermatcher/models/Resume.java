package br.com.careermatcher.models;

import br.com.careermatcher.relations.CandidateHasSkill;
import br.com.careermatcher.relations.CandidateLanguage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Node("RESUME")
@RequiredArgsConstructor

public class Resume {

    @Id @GeneratedValue @Setter Long id;

    @JsonIgnore
    public Long getId() {
        return id;
    }

    @Relationship(type = "CandidateHasSkill", direction = Relationship.Direction.OUTGOING)
    @Getter @Setter private List<CandidateHasSkill> candidateSkills;

    @Relationship(type = "HasExperience", direction = Relationship.Direction.OUTGOING)
    @Getter @Setter private List<Experience> experiences;

    @Relationship(type = "SPEAKS_LANGUAGE", direction = Relationship.Direction.OUTGOING)
    @Getter @Setter private List<CandidateLanguage> languages;

    @Relationship(type = "GRADUATED_IN", direction = Relationship.Direction.OUTGOING)
    @Getter @Setter private Undergraduate undergraduate;

    @Relationship(type = "POSTGRADUATED_IN", direction = Relationship.Direction.OUTGOING)
    @Getter @Setter private List<Graduate> graduates;

}
