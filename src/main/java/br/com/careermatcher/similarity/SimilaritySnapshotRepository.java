package br.com.careermatcher.similarity;

import br.com.careermatcher.repositories.CompanyInterestRepository;
import br.com.careermatcher.security.CurrentActor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static br.com.careermatcher.similarity.SimilaritySnapshot.*;

@Repository
public class SimilaritySnapshotRepository {
    private final Neo4jClient client;
    private final CurrentActor actor;
    public SimilaritySnapshotRepository(Neo4jClient client, CurrentActor actor) { this.client = client; this.actor = actor; }

    /** One read statement for origins, targets and facts; never rehydrates entities or writes derived edges. */
    @Transactional(readOnly = true)
    public SimilaritySnapshot load() {
        long company = actor.requireCompany();
        var rows = client.query(QUERY).bindAll(Map.of("company", company,
                "interest", CompanyInterestRepository.RELATIONSHIP)).fetch().all();
        var profiles = new ArrayList<Profile>(); var facts = new ArrayList<Evidence>();
        for (var row : rows) {
            long id = ((Number) row.get("candidateId")).longValue();
            profiles.add(new Profile(id, (String) row.get("name"), (String) row.get("professionalTitle")));
            for (Object value : (List<?>) row.get("evidence")) {
                var e = (Map<?, ?>) value;
                facts.add(new Evidence(id, ((Number)e.get("skillId")).longValue(), (String)e.get("skillName"),
                        SourceType.valueOf((String)e.get("sourceType")), ((Number)e.get("sourceId")).longValue()));
            }
        }
        return new SimilaritySnapshot(profiles, facts);
    }
    static final String QUERY = """
            MATCH (c:CANDIDATE)
            WHERE EXISTS { MATCH (c)-[interest]->(company:COMPANY)
                           WHERE id(company)=$company AND type(interest)=$interest }
            CALL {
                WITH c
                MATCH (c)-[:HAS]->(r:RESUME)-[:CandidateHasSkill]->(s:SKILL)
                RETURN id(s) AS skillId, s.name AS skillName, 'RESUME_DECLARATION' AS sourceType, id(r) AS sourceId
                UNION
                WITH c
                MATCH (c)-[:HAS]->(:RESUME)-[:HasExperience]->(e:EXPERIENCE)-[:DEVELOPS_SKILL]->(s:SKILL)
                RETURN id(s) AS skillId, s.name AS skillName, 'EXPERIENCE' AS sourceType, id(e) AS sourceId
                UNION
                WITH c
                MATCH (c)-[:HAS]->(:RESUME)-[:GRADUATED_IN]->(f:UNDERGRADUATE)-[:DEVELOPS_A]->(p:PROJECT)-[:DEVELOPS_SKILL]->(s:SKILL)
                WHERE NOT EXISTS {
                    MATCH (other:CANDIDATE)-[:HAS]->(:RESUME)-[:GRADUATED_IN]->(f) WHERE other <> c
                }
                RETURN id(s) AS skillId, s.name AS skillName, 'PROJECT' AS sourceType, id(p) AS sourceId
                UNION
                WITH c
                MATCH (c)-[:HAS]->(:RESUME)-[:POSTGRADUATED_IN]->(f:GRADUATE)-[:DEVELOPS_A]->(p:PROJECT)-[:DEVELOPS_SKILL]->(s:SKILL)
                WHERE NOT EXISTS {
                    MATCH (other:CANDIDATE)-[:HAS]->(:RESUME)-[:POSTGRADUATED_IN]->(f) WHERE other <> c
                }
                RETURN id(s) AS skillId, s.name AS skillName, 'PROJECT' AS sourceType, id(p) AS sourceId
                UNION
                WITH c
                RETURN null AS skillId, null AS skillName, null AS sourceType, null AS sourceId
            }
            RETURN id(c) AS candidateId, c.name AS name, c.professionalTitle AS professionalTitle,
                   collect(DISTINCT CASE WHEN skillId IS NOT NULL THEN
                     {skillId:skillId, skillName:skillName, sourceType:sourceType, sourceId:sourceId} END) AS evidence
            ORDER BY candidateId
            """;
}
