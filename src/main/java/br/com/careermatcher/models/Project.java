package br.com.careermatcher.models;

import br.com.careermatcher.relations.ProjectDevelopsSkill;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.Date;
import java.util.List;

@Node("PROJECT")
@Getter
@NoArgsConstructor
public class Project {
    @Id @GeneratedValue @Setter private Long id;

    private String title, description;
    private Date startDate, endDate;
    @Relationship(type = "DEVELOPS_SKILL", direction = Relationship.Direction.OUTGOING)
    private List<ProjectDevelopsSkill> projectDevelopsSkillList;

    public Project(String title, String description, Date startDate, Date endDate,
                   List<ProjectDevelopsSkill> projectDevelopsSkillList) {
        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.projectDevelopsSkillList = projectDevelopsSkillList;
    }
}
