package br.com.careermatcher.crud;

import br.com.careermatcher.repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.util.*;

import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchGraphReadIntegrationTest {
    private Neo4j database;
    private Driver driver;
    private Neo4jClient client;
    private GraphCrudRepository graph;
    private final TestActor actor = new TestActor();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private long companyId;

    @BeforeAll void start() {
        database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory, 32L * 1024 * 1024).build();
        driver = GraphDatabase.driver(database.boltURI(), AuthTokens.none());
        client = spy(Neo4jClient.create(driver));
        graph = new GraphCrudRepository(client, new CatalogRepository(client), mapper, actor);
    }

    @AfterAll void stop() { driver.close(); database.close(); }

    @BeforeEach void resetData() {
        client.query("MATCH (n) DETACH DELETE n").run();
        companyId = ((Number) client.query("CREATE (c:COMPANY {name:'Test'}) RETURN id(c) AS id")
                .fetch().one().orElseThrow().get("id")).longValue();
        actor.staff(companyId);
        linkAllCandidates();
        clearInvocations(client);
    }

    @ParameterizedTest
    @CsvSource({"1,200", "200,200", "201,200", "401,200", "9,7"})
    void batchesRootsRelationshipsAndEmailsAndPreservesOrder(int count, int batchSize) {
        var candidates = ids("""
                UNWIND range(1,$count) AS i
                CREATE (c:CANDIDATE {name:'Candidate '+i})-[:HAS]->(:RESUME)
                CREATE (:USER {role:'CANDIDATO', email:'c'+i+'@example.com', password:'never-return'})-[:IS]->(c)
                RETURN id(c) AS id ORDER BY id DESC
                """, Map.of("count", count));
        int batches = (count + batchSize - 1) / batchSize;
        linkAllCandidates();
        clearInvocations(client);
        var result = graph.findByIds(CANDIDATE, candidates, batchSize);
        assertThat(result).extracting(n -> n.path("id").longValue()).containsExactlyElementsOf(candidates);
        assertThat(result).allSatisfy(n -> {
            assertThat(n.path("email").asText()).endsWith("@example.com");
            assertThat(n.has("password")).isFalse();
            assertThat(n.path("resume").path("skills").isEmpty()).isTrue();
        });
        // Root, candidate edges, resume edges, and account email: one query each per batch.
        assertThat(queryCount()).isEqualTo(4 * batches);

        var vacancies = ids("""
                MATCH (company) WHERE id(company)=$company
                UNWIND range(1,$count) AS i
                CREATE (company)-[:PUBLISHES]->(v:VACANCY {role:'Vacancy '+i})
                RETURN id(v) AS id ORDER BY id DESC
                """, Map.of("company", companyId, "count", count));
        linkAllCandidates();
        clearInvocations(client);
        assertThat(graph.findByIds(VACANCY, vacancies, batchSize))
                .extracting(n -> n.path("id").longValue()).containsExactlyElementsOf(vacancies);
        assertThat(queryCount()).isEqualTo(2 * batches);
    }

    @Test void batchesLargeDescendantSetsAndLoadsSharedNodesOnlyOnce() {
        var candidates = ids("""
                CREATE (a:CANDIDATE {name:'A'})-[:HAS]->(resume:RESUME)
                CREATE (b:CANDIDATE {name:'B'})-[:HAS]->(resume)
                CREATE (skill:SKILL {name:'Java'})
                WITH a,b,resume,skill
                UNWIND range(1,201) AS i
                CREATE (resume)-[:HasExperience {crudOrder:202-i}]->(e:EXPERIENCE {role:'Role '+i})
                CREATE (e)-[:DEVELOPS_SKILL {weight:2}]->(skill)
                WITH DISTINCT a,b UNWIND [a,b] AS c RETURN id(c) AS id
                """, Map.of());
        linkAllCandidates();
        clearInvocations(client);
        var result = graph.findByIds(CANDIDATE, candidates, 200);
        assertThat(queryCount()).isEqualTo(6);
        assertThat(result).allSatisfy(n -> {
            var experiences = n.path("resume").path("experiences");
            assertThat(experiences.size()).isEqualTo(201);
            assertThat(experiences.get(0).path("role").asText()).isEqualTo("Role 201");
            assertThat(experiences.get(200).path("skills").get(0).path("skill").path("name").asText()).isEqualTo("Java");
        });
    }

    @Test void matchesIndividualReadsIncludingDatesOptionalEdgesWeightsAndExplicitOrdering() throws Exception {
        ObjectNode candidate = fixture("candidate");
        candidate.withObject("resume").withArray("skills").add(mapper.readTree("""
                {"weight":7,"skill":{"name":"Kotlin"}}
                """));
        long first = graph.createCandidateForRegistration(candidate);
        long second = graph.createCandidateForRegistration(fixture("candidate"));
        long empty = graph.createCandidateForRegistration(mapper.createObjectNode().put("name", "Empty"));
        long vacancy = graph.create(VACANCY, fixture("vacancy"));
        client.query("""
                MATCH (c) WHERE id(c)=$id
                CREATE (:USER {role:'CANDIDATO', email:'account@example.com', password:'secret'})-[:IS]->(c)
                CREATE (c)-[:HAS]->(:SECRET {password:'secret'})
                """).bind(first).to("id").run();
        client.query("MATCH ()-[r:CandidateHasSkill]->() SET r.crudOrder=10-r.crudOrder").run();
        client.query("MATCH (p:PROJECT) SET p.startDate=datetime(p.startDate)").run();
        client.query("MATCH (u:UNDERGRADUATE) WHERE u.completionDate IS NOT NULL SET u.completionDate=1922313600000").run();
        client.query("MATCH (g:GRADUATE) WHERE g.completionDate IS NOT NULL SET g.completionDate=datetime(g.completionDate)").run();
        var selected = List.of(second, empty, first);
        linkAllCandidates();
        var expected = selected.stream().map(id -> graph.find(CANDIDATE, id)).toList();
        assertThat(graph.findByIds(CANDIDATE, selected, 2)).containsExactlyElementsOf(expected);
        assertThat(graph.findByIds(VACANCY, List.of(vacancy), 200)).containsExactly(graph.find(VACANCY, vacancy));
        assertThat(expected.get(2).toString()).doesNotContain("password", "secret");
        assertThat(expected.get(2).path("resume").path("skills").get(0).path("skill").path("name").asText())
                .isEqualTo("Kotlin");
    }

    @Test void authorizesEveryRootBeforeReadingDescendantsAndPreservesCandidateScope() {
        long own = graph.create(VACANCY, mapper.createObjectNode().put("role", "Own"));
        long foreign = ids("CREATE (:COMPANY)-[:PUBLISHES]->(v:VACANCY) RETURN id(v) AS id", Map.of()).getFirst();
        linkAllCandidates();
        clearInvocations(client);
        assertNotFound(() -> graph.findByIds(VACANCY, List.of(own, foreign), 1));
        assertThat(queryCount()).isEqualTo(2); // Only scoped root queries; no graph traversal.
        assertNotFound(() -> graph.findByIds(VACANCY, List.of(own, Long.MAX_VALUE), 200));
        assertNotFound(() -> graph.findByIds(CANDIDATE, List.of(Long.MAX_VALUE), 200));
        long first = graph.createCandidateForRegistration(mapper.createObjectNode().put("name", "First"));
        long second = graph.createCandidateForRegistration(mapper.createObjectNode().put("name", "Second"));
        actor.candidate(first);
        assertThat(graph.findByIds(CANDIDATE, List.of(first), 200)).hasSize(1);
        assertNotFound(() -> graph.findByIds(CANDIDATE, List.of(first, second), 200));
    }

    @Test void comparesTwentyVacanciesAndThirtyCandidatesWithoutRemoteServices() {
        var candidates = ids("""
                CREATE (skill:SKILL {name:'Java'})
                WITH skill UNWIND range(1,30) AS i
                CREATE (c:CANDIDATE {name:'Candidate '+i})-[:HAS]->(resume:RESUME)
                CREATE (resume)-[:CandidateHasSkill {weight:2}]->(skill)
                CREATE (resume)-[:HasExperience]->(e:EXPERIENCE {role:'Developer', durationMonths:24})
                CREATE (e)-[:DEVELOPS_SKILL {weight:2}]->(skill)
                RETURN id(c) AS id
                """, Map.of());
        var vacancies = ids("""
                MATCH (company) WHERE id(company)=$company
                MATCH (skill:SKILL {name:'Java'})
                UNWIND range(1,20) AS i
                CREATE (company)-[:PUBLISHES]->(v:VACANCY {role:'Developer '+i})
                CREATE (v)-[:NEEDS_SKILL {weight:2, required:true}]->(skill)
                CREATE (v)-[:NEEDS_EXPERIENCE {required:false}]->(e:EXPERIENCE {role:'Developer', durationMonths:12})
                CREATE (e)-[:DEVELOPS_SKILL {weight:1}]->(skill)
                RETURN id(v) AS id
                """, Map.of("company", companyId));
        linkAllCandidates();
        // Warm both implementations before the illustrative local timing.
        graph.find(CANDIDATE, candidates.getFirst());
        graph.find(VACANCY, vacancies.getFirst());
        graph.findByIds(CANDIDATE, candidates, 200);
        graph.findByIds(VACANCY, vacancies, 200);
        linkAllCandidates();
        clearInvocations(client);
        long start = System.nanoTime();
        var oldCandidates = candidates.stream().map(id -> graph.find(CANDIDATE, id)).toList();
        var oldVacancies = vacancies.stream().map(id -> graph.find(VACANCY, id)).toList();
        long oldNanos = System.nanoTime() - start;
        long oldQueries = queryCount();
        linkAllCandidates();
        clearInvocations(client);
        start = System.nanoTime();
        var newCandidates = graph.findByIds(CANDIDATE, candidates, 200);
        var newVacancies = graph.findByIds(VACANCY, vacancies, 200);
        long newNanos = System.nanoTime() - start;
        long newQueries = queryCount();
        assertThat(newCandidates).containsExactlyElementsOf(oldCandidates);
        assertThat(newVacancies).containsExactlyElementsOf(oldVacancies);
        // Vacancy experiences no longer need a query for nested skills.
        assertThat(newQueries).isEqualTo(7).isLessThan(oldQueries / 10);
        System.out.printf("Batch read comparison (20 vacancies, 30 candidates): individual=%d queries / %.1f ms; batch=%d queries / %.1f ms%n",
                oldQueries, oldNanos / 1_000_000.0, newQueries, newNanos / 1_000_000.0);
    }

    @ParameterizedTest @ValueSource(ints = {1, 10, 25})
    void pagesOnlySummaryFieldsInTwoQueriesRegardlessOfPageSize(int size) {
        client.query("""
                UNWIND range(1,30) AS i
                CREATE (c:CANDIDATE {name:'Person '+i, professionalTitle:'Developer', phone:'81999999999'})
                CREATE (:USER {role:'CANDIDATO', email:'private@example.com'})-[:IS]->(c)
                """).run();
        linkAllCandidates();
        clearInvocations(client);
        assertThat(graph.count(CANDIDATE, " Developer ")).isEqualTo(30);
        var page = graph.findPage(CANDIDATE, 0, size, " Developer ", "name", "asc");
        assertThat(page).hasSize(size).allSatisfy(item -> {
            assertThat(item.path("phone").asText()).isEqualTo("81999999999");
            assertThat(item.has("email")).isFalse();
            assertThat(item.has("resume")).isFalse();
        });
        assertThat(queryCount()).isEqualTo(2);
    }

    @Test void companyPagesReadInterestStatesInTwoQueries() {
        long candidate = graph.createCandidateForRegistration(mapper.createObjectNode().put("name", "Ana"));
        client.query("UNWIND range(1,30) AS i CREATE (:COMPANY {name:'Company '+i})").run();
        actor.candidate(candidate);
        var companies = new CompanyInterestRepository(client, actor);
        for (int size : List.of(1, 10, 25)) {
            clearInvocations(client);
            var page = companies.findPage(0, size);
            assertThat(page.content()).hasSize(size).allSatisfy(company -> assertThat(company.interested()).isFalse());
            assertThat(page.totalElements()).isEqualTo(31);
            assertThat(queryCount()).isEqualTo(2);
        }
    }

    @Test void staffBatchReadRejectsCandidatesWithoutInterestBeforeHydration() {
        long candidate = graph.createCandidateForRegistration(mapper.createObjectNode().put("name", "Ana"));
        clearInvocations(client);
        assertNotFound(() -> graph.findByIds(CANDIDATE, List.of(candidate), 200));
        assertThat(queryCount()).isEqualTo(1);
    }

    private void linkAllCandidates() {
        client.query("MATCH (c:CANDIDATE), (company:COMPANY) WHERE id(company)=$company MERGE (c)-[:`INTERESTED IN`]->(company)")
                .bind(companyId).to("company").run();
    }

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("src/test/resources/crud/" + name + ".json")));
    }

    @ParameterizedTest @ValueSource(ints = {1, 10, 25})
    void searchesAndSortsVacancyPagesInTwoQueries(int size) {
        client.query("""
                MATCH (company) WHERE id(company)=$company
                UNWIND range(1,30) AS i
                CREATE (company)-[:PUBLISHES]->(:VACANCY {role:'Role '+i, city:'Recife', seniority:'SENIOR', modalidade:'HIBRIDO'})
                """).bind(companyId).to("company").run();
        linkAllCandidates();
        clearInvocations(client);
        assertThat(graph.count(VACANCY, "Híbrido")).isEqualTo(30);
        assertThat(graph.findPage(VACANCY, 0, size, "Híbrido", "seniority", "asc")).hasSize(size);
        assertThat(queryCount()).isEqualTo(2);
    }

    private List<Long> ids(String query, Map<String, Object> parameters) {
        return client.query(query).bindAll(parameters).fetch().all().stream()
                .map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private long queryCount() {
        // query(String) delegates internally to query(Supplier); count each statement only once.
        return mockingDetails(client).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query") && i.getArgument(0) instanceof String).count();
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }
}
