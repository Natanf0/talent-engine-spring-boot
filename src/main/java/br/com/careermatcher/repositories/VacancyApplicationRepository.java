package br.com.careermatcher.repositories;

import br.com.careermatcher.DTOs.VacancyApplicationDtos.ApplicationResponse;
import br.com.careermatcher.DTOs.VacancyApplicationDtos.ApplicationStatus;
import br.com.careermatcher.security.CurrentActor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@Transactional
public class VacancyApplicationRepository {
    private final Neo4jClient client;
    private final CurrentActor actor;

    @Transactional(readOnly = true)
    public ApplicationStatus status(long vacancyId) {
        long candidateId = actor.requireCandidate();
        return client.query("""
                MATCH (candidate:CANDIDATE) WHERE id(candidate)=$candidateId
                MATCH (vacancy:VACANCY) WHERE id(vacancy)=$vacancyId
                  AND EXISTS { MATCH (:COMPANY)-[:PUBLISHES]->(vacancy) }
                RETURN EXISTS { MATCH (candidate)-[:`APPLIED TO`]->(vacancy) } AS applied
                """).bindAll(Map.of("candidateId", candidateId, "vacancyId", vacancyId))
                .fetchAs(ApplicationStatus.class)
                .mappedBy((types, row) -> new ApplicationStatus(row.get("applied").asBoolean()))
                .one().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vaga ou candidato não encontrado."));
    }

    public ApplicationResponse apply(long vacancyId, BigDecimal income) {
        long candidateId = actor.requireCandidate();
        // Share the candidate lock with interest toggles and staff talent-pool removals.
        if (client.query("""
                MATCH (candidate:CANDIDATE) WHERE id(candidate)=$candidateId
                SET candidate.__crudLock=true REMOVE candidate.__crudLock
                RETURN id(candidate) AS id
                """).bind(candidateId).to("candidateId").fetch().one().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidato não encontrado.");

        return client.query("""
                MATCH (candidate:CANDIDATE) WHERE id(candidate)=$candidateId
                MATCH (company:COMPANY)-[:PUBLISHES]->(vacancy:VACANCY) WHERE id(vacancy)=$vacancyId
                MERGE (candidate)-[:`INTERESTED IN`]->(company)
                MERGE (candidate)-[application:`APPLIED TO`]->(vacancy)
                SET application.income=$income
                RETURN id(vacancy) AS vacancyId, id(company) AS companyId,
                       coalesce(company.name, '') AS companyName, application.income AS income
                """).bindAll(Map.of("candidateId", candidateId, "vacancyId", vacancyId, "income", income.doubleValue()))
                .fetchAs(ApplicationResponse.class).mappedBy((types, row) -> {
                    String companyName = row.get("companyName").asString();
                    return new ApplicationResponse(row.get("vacancyId").asLong(), row.get("companyId").asLong(),
                            companyName, row.get("income").asDouble(),
                            "Parabéns, sua candidatura foi enviada com sucesso a " + companyName + ".");
                }).one().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vaga não encontrada."));
    }
}
