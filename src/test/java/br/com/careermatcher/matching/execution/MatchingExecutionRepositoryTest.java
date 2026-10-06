package br.com.careermatcher.matching.execution;

import br.com.careermatcher.models.MatchingExecution;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.context.annotation.*;
import org.springframework.data.neo4j.config.AbstractNeo4jConfig;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchingExecutionRepositoryTest {
    private static Neo4j database;
    private AnnotationConfigApplicationContext context;
    private Neo4jClient client;
    private MatchingExecutionRepository repository;
    private long companyId;

    @BeforeAll void start() {
        database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory, 32L * 1024 * 1024).build();
        context = new AnnotationConfigApplicationContext(Config.class);
        client = context.getBean(Neo4jClient.class);
        repository = context.getBean(MatchingExecutionRepository.class);
        repository.initialize();
        repository.initialize();
    }
    @AfterAll void close() { if (context != null) context.close(); if (database != null) database.close(); }
    @BeforeEach void clear() {
        client.query("MATCH (n) DETACH DELETE n").run();
        companyId = createCompany();
    }

    @Configuration @EnableTransactionManagement
    @Import(MatchingExecutionRepository.class)
    static class Config extends AbstractNeo4jConfig {
        @Bean @Override public Driver driver() { return GraphDatabase.driver(database.boltURI(), AuthTokens.none()); }
        @Override protected Collection<String> getMappingBasePackages() { return List.of("br.com.careermatcher.models"); }
    }

    @Test void historyIsPersistedPaginatedOrderedAndScopedToCompany() {
        long other = createCompany();
        var time = Instant.parse("2026-09-20T16:06:00Z");
        repository.create(companyId, metadata("RUN-a", time));
        repository.create(companyId, metadata("RUN-b", time));
        repository.create(companyId, metadata("RUN-c", time.plusSeconds(1)));
        repository.create(other, metadata("RUN-other", time.plusSeconds(10)));
        var page = repository.findPage(companyId, 0, 2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(MatchingExecution::getExecutionId).containsExactly("RUN-c", "RUN-b");
        assertThat(page.content().getFirst().getId()).isNotNull();
        assertThat(page.content().getFirst().getCreatedAt()).isEqualTo(time.plusSeconds(1));
        var loaded = repository.find(companyId, "RUN-b").orElseThrow();
        assertThat(loaded.getVacanciesQuantity()).isEqualTo(23);
        assertThat(loaded.getCandidateQuantity()).isEqualTo(70);
        assertThat(loaded.getCompression()).isEqualTo("gzip");
        assertThat(loaded.getBucket()).isEqualTo("reports");
        assertThat(loaded.getSchemaVersion()).isEqualTo(1);
        assertThat(repository.find(companyId, "RUN-other")).isEmpty();
        assertThat(repository.findPage(companyId, 1, 2).content()).extracting(MatchingExecution::getExecutionId)
                .containsExactly("RUN-a");
        assertThat(repository.findPage(companyId, 10, 2).content()).isEmpty();
        // A new repository instance reads durable data without any process-local snapshot.
        assertThat(new MatchingExecutionRepository(client).findPage(companyId, 0, 20).totalElements()).isEqualTo(3);
    }

    @Test void preservesCompanyPropertiesAndExistingRelationships() {
        client.query("MATCH (c:COMPANY) WHERE id(c)=$id CREATE (c)-[:PUBLISHES]->(:VACANCY {role:'Existing'})")
                .bind(companyId).to("id").run();
        repository.create(companyId, metadata("RUN-new", Instant.EPOCH));
        assertThat(count("MATCH (:COMPANY)-[:PUBLISHES]->(:VACANCY) RETURN count(*) AS count")).isEqualTo(1);
        assertThat(count("MATCH (:COMPANY {name:'Company'})-[:RELATED_TO]->(:MATCHING_EXECUTION) RETURN count(*) AS count")).isEqualTo(1);
    }

    @Test void missingCompanyCreatesNeitherNodeNorRelationship() {
        assertThatThrownBy(() -> repository.create(Long.MAX_VALUE, metadata("RUN-missing", Instant.EPOCH)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(count("MATCH (e:MATCHING_EXECUTION) RETURN count(e) AS count")).isZero();
    }

    @Test void transactionRollbackRemovesMetadataAndRelationshipTogether() {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            repository.create(companyId, metadata("RUN-rollback", Instant.EPOCH));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(companyId, "RUN-rollback")).isEmpty();
        assertThat(count("MATCH ()-[r:RELATED_TO]->() RETURN count(r) AS count")).isZero();
    }

    @Test void executionIdConstraintPreventsDuplicateHistory() {
        repository.create(companyId, metadata("RUN-unique", Instant.EPOCH));
        assertThatThrownBy(() -> repository.create(companyId, metadata("RUN-unique", Instant.EPOCH)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findPage(companyId, 0, 20).totalElements()).isEqualTo(1);
    }

    private long createCompany() {
        return client.query("CREATE (c:COMPANY {name:'Company'}) RETURN id(c) AS count")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("count").asLong()).one().orElseThrow();
    }
    private long count(String query) {
        return client.query(query).fetchAs(Long.class).mappedBy((types, row) -> row.get("count").asLong()).one().orElseThrow();
    }
    private MatchingExecution metadata(String id, Instant time) {
        return new MatchingExecution(null, id, time, 23, 70, "reports", "companies/" + companyId + "/executions/" + id + ".json.gz", "gzip", 1);
    }
}
