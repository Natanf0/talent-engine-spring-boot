package br.com.careermatcher.models;
import br.com.careermatcher.enums.UNDERGRADUATETYPE;
import lombok.*;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.Date;
import java.util.List;

@Node("UNDERGRADUATE")
@Getter
public class Undergraduate {
    @Id @GeneratedValue
    @Setter private Long id;

     private String field, institution;
     private Date completionDate;
     private UNDERGRADUATETYPE undergraduateType;
     @Relationship(type = "DEVELOPS_A", direction = Relationship.Direction.OUTGOING)
     @Setter private List<Project> projects;

     public Undergraduate(){}

        public Undergraduate(String field, String institution, Date completionDate, UNDERGRADUATETYPE undergraduatetype){
         this.field = field;
         this.institution = institution;
         this.completionDate = completionDate;
         this.undergraduateType = undergraduatetype;
     }

     public Undergraduate(String field, String institution){
         this.field = field;
         this.institution = institution;
     }

}
