package br.com.careermatcher.crud;

import br.com.careermatcher.controllers.TalentEngineController;
import br.com.careermatcher.matching.*;
import br.com.careermatcher.matching.execution.*;
import br.com.careermatcher.rankers.*;
import br.com.careermatcher.repositories.*;
import br.com.careermatcher.services.*;
import br.com.careermatcher.validations.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.*;

/** Every test uses a fresh store: deleting nodes alone does not remove Neo4j schema tokens. */
class SeededGraphIntegrationTest {
    private Neo4j database;
    private Driver driver;
    private Neo4jClient client;
    private GraphCrudRepository graph;
    private CatalogRepository catalogs;
    private final TestActor actor = new TestActor();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Logger cypherLogger = (Logger) LoggerFactory.getLogger("org.springframework.data.neo4j.cypher");
    private ListAppender<ILoggingEvent> notifications;

    @BeforeEach void start() {
        database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory, 32L * 1024 * 1024).build();
        driver = GraphDatabase.driver(database.boltURI(), AuthTokens.none());
        client = Neo4jClient.create(driver);
        catalogs = new CatalogRepository(client);
        actor.staff(-1L);
        graph = new GraphCrudRepository(client, catalogs, mapper, actor);
        notifications = new ListAppender<>();
        notifications.start();
        cypherLogger.addAppender(notifications);
    }

    @AfterEach void stop() {
        cypherLogger.detachAppender(notifications);
        notifications.stop();
        driver.close();
        database.close();
    }

    @Test void readsAndDeletesAbsentOptionalRelationshipsAndPreservesExplicitListOrder() throws Exception {
        for (var kind : List.of(CANDIDATE, VACANCY)) {
            assertThat(graph.count(kind)).isZero();
            assertThat(graph.findAll(kind)).isEmpty();
            assertThat(graph.findPage(kind, 0, 10)).isEmpty();
        }
        assertNoUnknownNotifications();
        client.query("""
                CREATE (c:CANDIDATE {name:'Ana', professionalTitle:'Developer'})-[:HAS]->(r:RESUME)
                CREATE (r)-[:CandidateHasSkill {weight:3}]->(:SKILL {name:'Java', normalizedName:'java'})
                CREATE (company:COMPANY {name:'Test'})-[:PUBLISHES]->(:VACANCY {role:'Developer', city:'Recife', seniority:'PLENO', modalidade:'REMOTO'})
                CREATE (c)-[:`INTERESTED IN`]->(company)
                """).run();
        actor.staff(firstId("COMPANY"));
        assertThat(client.query("CALL db.labels() YIELD label RETURN label").fetch().all())
                .noneMatch(row -> row.get("label").equals("GRADUATE"));
        assertThat(client.query("CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey").fetch().all())
                .noneMatch(row -> row.get("propertyKey").equals("crudOrder"));

        long candidateId = firstId("CANDIDATE");
        long vacancyId = firstId("VACANCY");
        var candidate = graph.find(CANDIDATE, candidateId);
        var vacancy = graph.find(VACANCY, vacancyId);
        assertThat(candidate.path("resume").path("graduates").isEmpty()).isTrue();
        assertThat(vacancy.path("graduateRequirements").isEmpty()).isTrue();
        assertThat(vacancy.path("undergraduateRequirements").isEmpty()).isTrue();
        assertThat(candidate.path("resume").path("skills").get(0).path("skill").path("name").asText()).isEqualTo("Java");
        // Updating/deleting optional fields must also work before their relationship types exist.
        graph.update(VACANCY, vacancyId, vacancy, Set.of("graduateRequirements", "undergraduateRequirements"));
        candidate.put("name", "Ana Souza");
        actor.candidate(candidateId);
        graph.update(CANDIDATE, candidateId, candidate, Set.of("name"));
        assertThat(graph.find(CANDIDATE, candidateId).path("name").asText()).isEqualTo("Ana Souza");
        actor.staff(firstId("COMPANY"));
        graph.delete(CANDIDATE, candidateId);
        graph.delete(VACANCY, vacancyId);
        assertNoUnknownNotifications();

        var input = mapper.createObjectNode();
        input.put("name", "Bia").put("professionalTitle", "Developer");
        input.set("resume", mapper.readTree("""
                {"skills":[{"weight":1,"skill":{"name":"Zig"}},{"weight":2,"skill":{"name":"C"}}]}
                """));
        long ordered = graph.createCandidateForRegistration(input);
        actor.candidate(ordered);
        assertThat(graph.find(CANDIDATE, ordered).path("resume").path("skills").findValuesAsText("name"))
                .containsExactly("Zig", "C");
        // Make relationship ID order differ from explicit list order.
        client.query("""
                MATCH (c:CANDIDATE)-[:HAS]->(:RESUME)-[r:CandidateHasSkill]->()
                WHERE id(c)=$id SET r.crudOrder=1-r.crudOrder
                """).bind(ordered).to("id").run();
        assertThat(graph.find(CANDIDATE, ordered).path("resume").path("skills").findValuesAsText("name"))
                .containsExactly("C", "Zig");
        assertNoUnknownNotifications();
    }

    @Test void importsBothAuraSeedsAndRunsOneCandidateOneVacancyWithLocalObjectStorage() throws Exception {
        script("scripts/main_script.txt");
        actor.staff(companyId("13.R95.RRM/9JIP-17"));
        assertThat(graph.count(CANDIDATE)).isEqualTo(25);
        assertSeededWorkspaces();
        script("scripts/novos_candidatos_51_75.txt");
        long companyId = companyId("13.R95.RRM/9JIP-17");
        actor.staff(companyId);
        assertThat(graph.count(CANDIDATE)).isEqualTo(100);
        assertThat(graph.count(VACANCY)).isEqualTo(20);
        assertThat(client.query("MATCH (v:VACANCY) RETURN count(v) AS total")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("total").asLong()).one()).contains(25L);
        actor.staff(companyId("30.XLC.IZZ/QU9M-19"));
        assertThat(graph.count(CANDIDATE)).isEqualTo(25);
        assertThat(graph.count(VACANCY)).isEqualTo(5);
        actor.staff(companyId);
        assertNormalizedCatalogs();
        long javaId = client.query("MATCH (s:SKILL {normalizedName:'java'}) RETURN id(s) AS id")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        assertThat(catalogs.resolve(SKILL, " JAVA ")).isEqualTo(javaId);

        var validations = new GraphValidations(new TextSanitizer());
        var candidates = new CandidatoService(
                graph, new CandidatoValidations(validations), mapper);
        var vacancies = new VagaService(graph, new VagaValidations(validations), mapper);
        var candidate = candidates.findCandidate(firstId("CANDIDATE"));
        long vacancyId = client.query("MATCH (c:COMPANY)-[:PUBLISHES]->(v:VACANCY) "
                        + "WHERE id(c)=$companyId RETURN id(v) AS id ORDER BY id LIMIT 1")
                .bind(companyId).to("companyId").fetchAs(Long.class)
                .mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        var vacancy = vacancies.find(vacancyId);
        assertThat(candidate.resume().graduates()).isEmpty();
        assertThat(vacancy.graduateRequirements()).isEmpty();
        assertThat(vacancy.undergraduateRequirements()).isEmpty();

        var executions = new MatchingExecutionRepository(client);
        executions.initialize();
        var storage = new MemoryStorage();
        var latest = new LatestExecutionStore();
        var executionService = new MatchingExecutionService(() -> companyId, executions, storage,
                new CompressedExecutionWriter(mapper, List.of(new GzipExecutionCompressor()), "gzip"),
                new ExecutionDocumentMapper(), latest, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var service = new TalentEngineService(new RankerService(new SeniorityRanker(), new ExperienceRanker(),
                new SkillRanker(), new LanguageRanker(), new UndergraduateRanker(), new GraduateRanker(),
                new LocationRanker(), new ModalityRanker()), new HRMatchingAlgorithm(), new MatchingResponseMapper(),
                new DynamicCutoffService(), executionService, new SelectedParticipantsMapper());
        var validator = new MatchingSelectionValidator(graph, mapper, actor, 200);
        var tasks = new ArrayList<Runnable>();
        var controller = new TalentEngineController(new MatchingSubmissionService(tasks::add, validator, service, actor),
                validator, latest, () -> companyId);
        var body = mapper.valueToTree(Map.of("candidateIds", List.of(candidate.id()), "vacancyIds", List.of(vacancy.id())));
        var accepted = controller.run(body, 1, .65, 0);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(accepted.getBody()).isNull();
        assertThat(storage.objects).isEmpty();
        tasks.getFirst().run();
        var result = controller.latest();
        assertThat(result.allocation().shortlists()).hasSize(1);
        assertThat(result.allocation().totalAllocated()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        try (var gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(storage.objects.values().iterator().next()))) {
            assertThat(mapper.readTree(gzip).size()).isEqualTo(1);
        }
        assertThat(executions.find(companyId, result.executionId())).isPresent();
        assertThat(latest.latest(companyId).response()).isEqualTo(result);
        assertNoUnknownNotifications();
    }

    @Test void supplementalSeedIsStandaloneAndCreatesCandidatesWithoutAccounts() throws Exception {
        script("scripts/novos_candidatos_51_75.txt");
        assertNormalizedCatalogs();
        actor.staff(companyId("13.R95.RRM/9JIP-17"));
        assertThat(graph.count(CANDIDATE)).isEqualTo(75);
        actor.staff(companyId("30.XLC.IZZ/QU9M-19"));
        assertThat(graph.findAll(CANDIDATE)).hasSize(25).allSatisfy(candidate -> {
            assertThat(candidate.path("professionalTitle").asText()).isNotBlank();
            assertThat(candidate.has("email")).isFalse();
            assertThat(candidate.has("password")).isFalse();
        });
        assertThat(client.query("MATCH (n) WHERE 'USER' IN labels(n) RETURN count(n) AS total")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("total").asLong()).one()).contains(0L);
        assertNoUnknownNotifications();
    }

    private void assertSeededWorkspaces() {
        assertThat(client.query("MATCH (c:COMPANY) RETURN c.name AS name, c.cnpj AS cnpj").fetch().all())
                .containsExactlyInAnyOrder(
                        Map.of("name", "Tech Corp", "cnpj", "13.R95.RRM/9JIP-17"),
                        Map.of("name", "Google", "cnpj", "30.XLC.IZZ/QU9M-19"));
        long techId = companyId("13.R95.RRM/9JIP-17");
        long googleId = companyId("30.XLC.IZZ/QU9M-19");
        var admins = client.query("MATCH (u:USER) RETURN properties(u) AS properties").fetch().all();
        assertThat(admins).hasSize(2);
        assertSeededAdministrator("admin@techcorp.test", "Tech1234@", techId);
        assertSeededAdministrator("admin@google.test", "Google1234@", googleId);
        assertThat(admins).extracting(row -> ((Map<?, ?>) row.get("properties")).get("id"))
                .doesNotHaveDuplicates();
        assertThat(client.query("SHOW CONSTRAINTS YIELD name RETURN name").fetch().all())
                .extracting(row -> row.get("name"))
                .contains("user_id_unique", "user_email_unique", "user_invitation_unique");
        assertThat(client.query("MATCH (v:VACANCY) OPTIONAL MATCH (c:COMPANY)-[p:PUBLISHES]->(v) "
                        + "WITH v, count(p) AS owners WHERE owners <> 1 RETURN v.role AS role").fetch().all())
                .isEmpty();

        actor.staff(techId);
        var techVacancies = graph.findPage(VACANCY, 0, 100);
        assertThat(techVacancies).extracting(v -> v.path("role").asText()).containsExactlyInAnyOrder(
                "Java Developer", "React Frontend Engineer", "Data Scientist", "DevOps Engineer", "Product Owner",
                "QA Automation Engineer", "C# .NET Developer", "Data Engineer", "Mobile Flutter Developer",
                "Node.js Backend Developer", "UX/UI Designer", "Python Backend Developer", "Android Developer",
                "Cloud Security Architect", "Scrum Master", "Angular Frontend Developer", "Data Analyst",
                "Golang Software Engineer", "iOS Developer", "Business Analyst");
        var techCandidates = graph.findPage(CANDIDATE, 0, 100);
        assertThat(techCandidates).hasSize(25);
        long techVacancyId = techVacancies.getFirst().path("id").asLong();
        actor.staff(googleId);
        var googleVacancies = graph.findPage(VACANCY, 0, 100);
        assertThat(googleVacancies).extracting(v -> v.path("role").asText()).containsExactlyInAnyOrder(
                "Kubernetes Administrator", "React Native Developer", "Machine Learning Engineer",
                "Salesforce Consultant", "Site Reliability Engineer (SRE)");
        assertThat(graph.findPage(CANDIDATE, 0, 100)).isEmpty();
        assertWorkspaceCannotAccess(techVacancyId);
        actor.staff(techId);
        assertWorkspaceCannotAccess(googleVacancies.getFirst().path("id").asLong());
        assertThat(graph.find(VACANCY, techVacancyId).path("id").asLong()).isEqualTo(techVacancyId);
        actor.staff(googleId);
        assertThat(graph.find(VACANCY, googleVacancies.getFirst().path("id").asLong()).path("id").asLong())
                .isEqualTo(googleVacancies.getFirst().path("id").asLong());
        assertNoUnknownNotifications();
    }

    private void assertSeededAdministrator(String email, String password, long companyId) {
        var properties = (Map<?, ?>) client.query("MATCH (u:USER {email:$email}) RETURN properties(u) AS properties")
                .bind(email).to("email").fetch().one().orElseThrow().get("properties");
        assertThat(UUID.fromString(properties.get("id").toString()).version()).isEqualTo(4);
        assertThat(properties.get("name")).isInstanceOf(String.class);
        assertThat(properties.get("name").toString()).isNotBlank();
        assertThat(properties.get("role")).isEqualTo("ADMINISTRADOR");
        assertThat(properties.get("cadastroFinalizado")).isEqualTo(true);
        assertThat(Instant.parse(properties.get("createdAt").toString())).isBeforeOrEqualTo(Instant.now());
        String hash = properties.get("password").toString();
        assertThat(hash).startsWith("$2b$12$");
        assertThat(new BCryptPasswordEncoder(12).matches(password, hash)).isTrue();
        assertThat(client.query("MATCH (u:USER {email:$email})-[r]->(target) "
                        + "RETURN type(r) AS relationship, labels(target) AS labels, id(target) AS id")
                .bind(email).to("email").fetch().all())
                .containsExactly(Map.of("relationship", "WORKS_FOR", "labels", List.of("COMPANY"), "id", companyId));
    }

    private void assertWorkspaceCannotAccess(long vacancyId) {
        assertThatThrownBy(() -> graph.find(VACANCY, vacancyId)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> graph.update(VACANCY, vacancyId, mapper.createObjectNode().put("role", "Changed"), Set.of("role")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> graph.delete(VACANCY, vacancyId)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private long companyId(String cnpj) {
        return client.query("MATCH (c:COMPANY {cnpj:$cnpj}) RETURN id(c) AS id").bind(cnpj).to("cnpj")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
    }

    private void assertNormalizedCatalogs() {
        for (String label : List.of("SKILL", "LANGUAGE")) {
            var rows = client.query("MATCH (n:" + label + ") RETURN properties(n) AS properties").fetch().all();
            var keys = new ArrayList<String>();
            for (var row : rows) {
                var props = (Map<?, ?>) row.get("properties");
                String key = TextSanitizer.normalizeName(props.get("name").toString());
                assertThat(props.get("normalizedName")).isEqualTo(key);
                keys.add(key);
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
        assertThat(client.query("SHOW CONSTRAINTS YIELD name RETURN name").fetch().all())
                .extracting(row -> row.get("name")).contains("crud_skill_normalized", "crud_language_normalized");
    }

    private long firstId(String label) {
        return client.query("MATCH (n:" + label + ") RETURN id(n) AS id ORDER BY id LIMIT 1")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
    }

    private void script(String file) throws Exception {
        String cypher = Files.readString(Path.of(file)).replaceAll("(?m)^\\s*//.*$", "");
        for (String statement : cypher.split(";")) {
            if (statement.isBlank()) continue;
            for (var row : client.query(statement).fetch().all()) {
                row.forEach((key, value) -> {
                    if (key.endsWith("Ok")) assertThat(value).as("%s: %s", file, key).isEqualTo(true);
                    if (key.matches("(?i)(missing|invalid|duplicate).*")) {
                        if (value instanceof Number number) assertThat(number.longValue()).as("%s: %s", file, key).isZero();
                        else assertThat((List<?>) value).as("%s: %s", file, key).isEmpty();
                    }
                });
            }
        }
    }

    private void assertNoUnknownNotifications() {
        assertThat(notifications.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("Neo.ClientNotification.Statement.Unknown"));
    }

    private static class MemoryStorage implements ExecutionObjectStorage {
        final Map<String, byte[]> objects = new HashMap<>();
        public String bucket() { return "test"; }
        public void put(String bucket, String key, Path file, String contentType) {
            try { objects.put(key, Files.readAllBytes(file)); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        public void delete(String bucket, String key) { objects.remove(key); }
    }
}
