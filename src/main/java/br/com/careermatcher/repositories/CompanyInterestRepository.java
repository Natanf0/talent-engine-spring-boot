package br.com.careermatcher.repositories;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.security.CurrentActor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@Transactional
public class CompanyInterestRepository {
    public static final String RELATIONSHIP = "INTERESTED IN";
    public record PartnerCompany(long id, String name, boolean interested) {}
    private final Neo4jClient client;
    private final CurrentActor actor;

    @Transactional(readOnly = true)
    public PageView<PartnerCompany> findPage(int page, int size) {
        long candidateId = actor.requireCandidate();
        GraphCrudRepository.validatePage(page, size);
        long total = client.query("MATCH (company:COMPANY) RETURN count(company) AS total")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("total").asLong()).one().orElse(0L);
        var content = client.query("""
                MATCH (company:COMPANY)
                WITH company ORDER BY toLower(coalesce(company.name, '')), id(company) SKIP $skip LIMIT $limit
                RETURN id(company) AS id, coalesce(company.name, '') AS name,
                    EXISTS { MATCH (candidate:CANDIDATE)-[interest]->(company)
                             WHERE id(candidate)=$candidateId AND type(interest)=$relationship } AS interested
                """).bindAll(Map.of("skip", (long) page * size, "limit", size,
                        "candidateId", candidateId, "relationship", RELATIONSHIP))
                .fetchAs(PartnerCompany.class).mappedBy((types, row) -> new PartnerCompany(
                        row.get("id").asLong(), row.get("name").asString(), row.get("interested").asBoolean()))
                .all().stream().toList();
        return new PageView<>(content, page, size, total, (total + size - 1) / size);
    }

    public void setInterest(long companyId, boolean interested) {
        long candidateId = actor.requireCandidate();
        var parameters = Map.<String, Object>of("candidateId", candidateId, "companyId", companyId);
        // Serialize toggles on the candidate, including staff removals, to avoid duplicate edges.
        if (client.query("""
                MATCH (candidate:CANDIDATE), (company:COMPANY)
                WHERE id(candidate)=$candidateId AND id(company)=$companyId
                SET candidate.__crudLock=true REMOVE candidate.__crudLock
                RETURN id(candidate) AS id
                """).bindAll(parameters).fetch().one().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidato ou empresa não encontrado.");
        if (interested) {
            client.query("""
                    MATCH (candidate:CANDIDATE), (company:COMPANY)
                    WHERE id(candidate)=$candidateId AND id(company)=$companyId
                    MERGE (candidate)-[:`INTERESTED IN`]->(company)
                    """).bindAll(parameters).run();
        } else {
            client.query("""
                    MATCH (candidate:CANDIDATE)-[interest]->(company:COMPANY)
                    WHERE id(candidate)=$candidateId AND id(company)=$companyId AND type(interest)=$relationship
                    DELETE interest
                    """).bindAll(parameters).bind(RELATIONSHIP).to("relationship").run();
        }
    }
}
