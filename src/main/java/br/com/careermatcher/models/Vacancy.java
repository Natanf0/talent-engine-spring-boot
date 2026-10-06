package br.com.careermatcher.models;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.relations.*;
import lombok.*;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Node("VACANCY")
@Getter
@RequiredArgsConstructor
@NoArgsConstructor
public class Vacancy {
    @Id @GeneratedValue @Setter private Long id;
    @NonNull private String role, city;
    @NonNull private SENIORITY seniority;
    @NonNull private Modalidade  modalidade;

    @Relationship(type = "NEEDS_UNDERGRAD", direction = Relationship.Direction.OUTGOING)
    @Setter private List<VacancyUndergrad> undergraduates; // Normalmente vagas possuem uma lista de graduações correlatas de interesse. Então decidi cobrir isto

    @Relationship(type = "NEEDS_EXPERIENCE", direction = Relationship.Direction.OUTGOING)
    @Setter private VacancyExperience experience; // As descrições das vagas são agnósticas as formas das experiencias. Costumam pedir algo mais exato como "Y anos de experiência em"

    @Relationship(type = "NEEDS_SKILL", direction = Relationship.Direction.OUTGOING)
    @Setter private List<VacancySkill> competencias;

    @Relationship(type = "NEEDS_GRADUATE", direction = Relationship.Direction.OUTGOING)
    @Setter private List<VacancyGraduate> graduate;

    @Relationship(type = "NEEDS_LANGUAGE", direction = Relationship.Direction.OUTGOING)
    @Setter private List<VacancyLanguage> languages;

    public SENIORITY getSeniority() {
        return seniority;
    }

}
