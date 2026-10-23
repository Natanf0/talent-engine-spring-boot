package br.com.careermatcher.models;

import br.com.careermatcher.relations.VacancyApplied;
import lombok.*;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;


@Node("CANDIDATE")
@Getter
@RequiredArgsConstructor
@NoArgsConstructor
public class Candidate {
    @Id @GeneratedValue @Setter private Long id;

    @Setter @NonNull private String name, about, professionalTitle, phone;

    @Setter private String linkedinURL, personalWebsiteURL;

    @Relationship(type = "HAS", direction = Relationship.Direction.OUTGOING)
    @Setter private Resume resume;

    @Relationship(type = "INTERESTED IN", direction = Relationship.Direction.OUTGOING)
    @Setter private List<Company> companiesInterestedIn;

    @Relationship(type = "APPLIED TO", direction = Relationship.Direction.OUTGOING)
    @Setter private List<VacancyApplied> vacanciesAppliedTo;

}
