package br.com.careermatcher.repositories;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.models.MatchingExecution;
import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@Repository
public class MatchingExecutionRepository {
    private final Neo4jClient client;
    public MatchingExecutionRepository(Neo4jClient client) { this.client = client; }

    public void initialize() {
        client.query("CREATE CONSTRAINT matching_execution_id IF NOT EXISTS FOR (e:MATCHING_EXECUTION) REQUIRE e.executionId IS UNIQUE").run();
    }

    public void requireCompany(long companyId) {
        boolean exists = client.query("MATCH (c:COMPANY) WHERE id(c)=$companyId RETURN id(c) AS id")
                .bind(companyId).to("companyId").fetch().one().isPresent();
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
    }

    @Transactional
    public MatchingExecution create(long companyId, MatchingExecution execution) {
        return client.query("""
                MATCH (c:COMPANY) WHERE id(c)=$companyId
                CREATE (e:MATCHING_EXECUTION) SET e=$properties
                CREATE (c)-[:RELATED_TO]->(e)
                RETURN id(e) AS id, properties(e) AS properties
                """)
                .bindAll(Map.of("companyId", companyId, "properties", Map.of(
                        "executionId", execution.getExecutionId(), "createdAt", execution.getCreatedAt().atOffset(ZoneOffset.UTC),
                        "vacanciesQuantity", execution.getVacanciesQuantity(), "candidateQuantity", execution.getCandidateQuantity(),
                        "bucket", execution.getBucket(), "objectKey", execution.getObjectKey(),
                        "compression", execution.getCompression(), "schemaVersion", execution.getSchemaVersion())))
                .fetchAs(MatchingExecution.class).mappedBy((types, row) -> map(row)).one()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }

    public Optional<MatchingExecution> find(long companyId, String executionId) {
        return client.query("""
                MATCH (c:COMPANY)-[:RELATED_TO]->(e:MATCHING_EXECUTION {executionId:$executionId})
                WHERE id(c)=$companyId RETURN id(e) AS id, properties(e) AS properties
                """).bindAll(Map.of("companyId", companyId, "executionId", executionId))
                .fetchAs(MatchingExecution.class).mappedBy((types, row) -> map(row)).one();
    }

    @Transactional(readOnly = true)
    public PageView<MatchingExecution> findPage(long companyId, int page, int size) {
        GraphCrudRepository.validatePage(page, size);
        requireCompany(companyId);
        long count = client.query("MATCH (c:COMPANY)-[:RELATED_TO]->(e:MATCHING_EXECUTION) WHERE id(c)=$companyId RETURN count(e) AS total")
                .bind(companyId).to("companyId").fetchAs(Long.class)
                .mappedBy((types, row) -> row.get("total").asLong()).one().orElse(0L);
        var content = client.query("""
                MATCH (c:COMPANY)-[:RELATED_TO]->(e:MATCHING_EXECUTION) WHERE id(c)=$companyId
                RETURN id(e) AS id, properties(e) AS properties
                ORDER BY e.createdAt DESC, e.executionId DESC SKIP $skip LIMIT $limit
                """).bindAll(Map.of("companyId", companyId, "skip", (long) page * size, "limit", size))
                .fetchAs(MatchingExecution.class).mappedBy((types, row) -> map(row)).all();
        return new PageView<>(java.util.List.copyOf(content), page, size, count, (count + size - 1) / size);
    }

    private static MatchingExecution map(Record row) {
        var properties = row.get("properties");
        return new MatchingExecution(row.get("id").asLong(), properties.get("executionId").asString(),
                properties.get("createdAt").asZonedDateTime().toInstant(), properties.get("vacanciesQuantity").asInt(),
                properties.get("candidateQuantity").asInt(), properties.get("bucket").asString(),
                properties.get("objectKey").asString(), properties.get("compression").asString(), properties.get("schemaVersion").asInt());
    }
}
