package br.com.careermatcher.models;

import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.relations.ExperienceDevelopsSkill;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Node("EXPERIENCE")
@Getter
@NoArgsConstructor
public class Experience {
    @Id @GeneratedValue @Setter private Long id;

    private String role;
    private String companyName;
    private String description;
    private Integer durationMonths;
    private SENIORITY seniority;
    @Relationship(type = "DEVELOPS_SKILL", direction = Relationship.Direction.OUTGOING)
    private List<ExperienceDevelopsSkill> skillsRelatedToExperience;

    public Experience(String role, String companyName, String description, Integer durationMonths, SENIORITY seniority, List<ExperienceDevelopsSkill> skillsRelatedToExperience){
        this.role=role;
        this.companyName=companyName;
        this.description=description;
        this.durationMonths=durationMonths;
        this.seniority=seniority;
        this.skillsRelatedToExperience = skillsRelatedToExperience;
    }

    public Experience(String role, Integer durationMonths, SENIORITY seniority){
        this.role=role;
        this.durationMonths = durationMonths;
        this.seniority=seniority;
    }
}
