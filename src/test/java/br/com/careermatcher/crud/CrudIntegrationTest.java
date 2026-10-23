package br.com.careermatcher.crud;

import br.com.careermatcher.controllers.*;
import br.com.careermatcher.interfaces.*;
import br.com.careermatcher.repositories.*;
import br.com.careermatcher.services.*;
import br.com.careermatcher.validations.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.context.annotation.*;
import org.springframework.data.neo4j.config.AbstractNeo4jConfig;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrudIntegrationTest {
    private static Neo4j database;
    private AnnotationConfigApplicationContext context;
    private ObjectMapper mapper;
    private Neo4jClient client;
    private CandidatoService candidates;
    private VagaService vacancies;
    private MockMvc mvc;
    private long companyId;

    @BeforeAll void start() {
        database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory, 32L * 1024 * 1024).build();
        context = new AnnotationConfigApplicationContext(Config.class);
        mapper = context.getBean(ObjectMapper.class);
        client = context.getBean(Neo4jClient.class);
        candidates = context.getBean(CandidatoService.class);
        vacancies = context.getBean(VagaService.class);
        for (String label : List.of("SKILL", "LANGUAGE")) {
            client.query("CREATE CONSTRAINT crud_" + label.toLowerCase(Locale.ROOT)
                    + "_normalized IF NOT EXISTS FOR (n:" + label + ") REQUIRE n.normalizedName IS UNIQUE").run();
        }
        mvc = MockMvcBuilders.standaloneSetup(new CandidatoController(candidates), new VagaController(vacancies))
                .setControllerAdvice(new CrudExceptionHandler()).build();
    }
    @AfterAll void stop() { if (context != null) context.close(); if (database != null) database.close(); }
    @BeforeEach void clear() {
        client.query("MATCH (n) DETACH DELETE n").run();
        companyId = client.query("CREATE (c:COMPANY {name:'Test'}) RETURN id(c) AS id")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        context.getBean(TestActor.class).staff(companyId);
        reset(context.getBean(EmailService.class));
    }

    @Configuration @EnableTransactionManagement
    @Import({GraphCrudRepository.class, CatalogRepository.class, TextSanitizer.class, GraphValidations.class,
            CandidatoValidations.class, VagaValidations.class, CandidatoService.class, VagaService.class})
    static class Config extends AbstractNeo4jConfig {
        @Bean @Override public Driver driver() { return GraphDatabase.driver(database.boltURI(), AuthTokens.none()); }
        @Override protected Collection<String> getMappingBasePackages() { return List.of("br.com.careermatcher.models"); }
        @Bean public ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
        @Bean public TestActor actor() { return new TestActor(); }
        @Bean public EmailService email() { return mock(EmailService.class); }
    }


    @Test void paginatesRootSummariesAndFullLists() throws Exception {
        var first = registerInterested(fixture("candidate"));
        var second = registerInterested(object("{\"name\":\"Bia\",\"professionalTitle\":\"Dev\"}"));
        var sorted = java.util.stream.Stream.of(first, second).sorted(Comparator.comparingLong(item -> item.id())).toList();
        vacancies.create(fixture("vacancy"));
        mvc.perform(get("/api/careermatcher/candidatos/page").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sorted.getFirst().id()))
                .andExpect(jsonPath("$.content[0].resume").doesNotExist())
                .andExpect(jsonPath("$.content[0].password").doesNotExist());
        mvc.perform(get("/api/careermatcher/candidatos/page").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value(sorted.get(1).name()));
        mvc.perform(get("/api/careermatcher/candidatos/page").param("page", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/careermatcher/vagas/page"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].skillRequirements").doesNotExist())
                .andExpect(jsonPath("$.content[0].companyName").value("Test"));
        mvc.perform(get("/api/careermatcher/candidatos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$..resume").isNotEmpty());
        mvc.perform(get("/api/careermatcher/vagas"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].skillRequirements").exists());
    }

    @Test void searchesAllCandidatesBeforePaginationAndOnlyInSummaryFields() throws Exception {
        client.query("""
                UNWIND range(1,12) AS i
                CREATE (:CANDIDATE {name:'Pessoa '+i, professionalTitle:'Developer', about:'hidden-marker'})
                """).run();
        client.query("""
                CREATE (c:CANDIDATE {name:'Ana Silva', professionalTitle:'Analista QA', phone:'81988776655'})
                CREATE (:USER {role:'CANDIDATO', email:'private-marker@example.com'})-[:IS]->(c)
                """).run();
        linkAllCandidates();
        for (String search : List.of("  ANA sil  ", "analista", "887766")) {
            mvc.perform(get("/api/careermatcher/candidatos/page").param("size", "10").param("search", search))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Ana Silva"))
                    .andExpect(jsonPath("$.content[0].phone").value("81988776655"))
                    .andExpect(jsonPath("$.content[0].email").doesNotExist());
        }
        for (String search : List.of("hidden-marker", "private-marker", "nothing", "' OR true //")) {
            mvc.perform(get("/api/careermatcher/candidatos/page").param("search", search))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
        mvc.perform(get("/api/careermatcher/candidatos/page").param("search", "  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(13));
        mvc.perform(get("/api/careermatcher/candidatos/page").param("search", "developer").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].phone").doesNotExist());
    }

    @Test void sortsCandidatePagesOnServerWithStableTiesAndRejectsInvalidSorts() throws Exception {
        client.query("""
                UNWIND [{name:'Zoe', professionalTitle:'Analista'},
                        {name:'ana', professionalTitle:'Developer'},
                        {name:'Ana', professionalTitle:'Designer'}] AS data
                CREATE (c:CANDIDATE) SET c=data
                """).run();
        linkAllCandidates();
        var graph = context.getBean(GraphCrudRepository.class);
        var ascending = graph.findPage(GraphSchema.Kind.CANDIDATE, 0, 10, null, "name", "asc");
        assertThat(ascending).extracting(n -> n.path("name").asText()).containsExactly("ana", "Ana", "Zoe");
        assertThat(ascending.get(0).path("id").longValue()).isLessThan(ascending.get(1).path("id").longValue());
        mvc.perform(get("/api/careermatcher/candidatos/page").param("sort", "name").param("direction", "desc").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Zoe"));
        mvc.perform(get("/api/careermatcher/candidatos/page").param("sort", "name").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Ana"));
        for (String direction : List.of("asc", "desc")) {
            mvc.perform(get("/api/careermatcher/candidatos/page").param("sort", "professionalTitle").param("direction", direction))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].professionalTitle").value(direction.equals("asc") ? "Analista" : "Developer"));
        }
        for (String sort : List.of("email", "phone", "name DESC DELETE n")) {
            mvc.perform(get("/api/careermatcher/candidatos/page").param("sort", sort)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/careermatcher/candidatos/page").param("direction", "sideways")).andExpect(status().isBadRequest());
    }

    @Test void searchesVacancySummaryAndEnumLabelsBeforePaginationWithinCompany() throws Exception {
        long company = context.getBean(TestActor.class).requireCompany();
        client.query("""
                MATCH (company) WHERE id(company)=$company
                UNWIND range(1,12) AS i
                CREATE (company)-[:PUBLISHES]->(:VACANCY {role:'Vaga '+i, city:'Recife', seniority:'PLENO', modalidade:'REMOTO'})
                """).bind(company).to("company").run();
        client.query("""
                MATCH (company) WHERE id(company)=$company
                CREATE (company)-[:PUBLISHES]->(v:VACANCY {role:'Java Developer', city:'Curitiba', seniority:'JUNIOR', modalidade:'HIBRIDO', description:'hidden-marker'})
                CREATE (v)-[:NEEDS_SKILL]->(:SKILL {name:'private-skill'})
                CREATE (:COMPANY)-[:PUBLISHES]->(:VACANCY {role:'Java Developer', city:'Curitiba', seniority:'JUNIOR', modalidade:'HIBRIDO'})
                """).bind(company).to("company").run();
        for (String search : List.of("  CURITIBA  ", "Java", "Júnior", "JUNIOR", "Híbrido", "HIBRIDO", "íbr")) {
            mvc.perform(get("/api/careermatcher/vagas/page").param("search", search).param("size", "10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.content[0].city").value("Curitiba"))
                    .andExpect(jsonPath("$.content[0].seniority").value("JUNIOR"))
                    .andExpect(jsonPath("$.content[0].modalidade").value("HIBRIDO"))
                    .andExpect(jsonPath("$.content[0].skillRequirements").doesNotExist())
                .andExpect(jsonPath("$.content[0].companyName").value("Test"));
        }
        for (String search : List.of("hidden-marker", "private-skill", "missing", "' OR true //")) {
            mvc.perform(get("/api/careermatcher/vagas/page").param("search", search))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
        mvc.perform(get("/api/careermatcher/vagas/page").param("search", "  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(13));
        mvc.perform(get("/api/careermatcher/vagas/page").param("search", "recife").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test void sortsEveryVacancyColumnAlphabeticallyAndValidatesParameters() throws Exception {
        client.query("""
                MATCH (company) WHERE id(company)=$company
                UNWIND [{role:'Zeta', city:'Recife', seniority:'ESTAGIARIO', modalidade:'REMOTO'},
                        {role:'alpha', city:'Belem', seniority:'JUNIOR', modalidade:'PRESENCIAL'},
                        {role:'Alpha', city:'Belo Horizonte', seniority:'ARQUITETO', modalidade:'HIBRIDO'}] AS data
                CREATE (company)-[:PUBLISHES]->(v:VACANCY) SET v=data
                """).bind(context.getBean(TestActor.class).requireCompany()).to("company").run();
        var orders = Map.of("role", List.of("alpha", "Alpha", "Zeta"),
                "city", List.of("Belem", "Belo Horizonte", "Recife"),
                "seniority", List.of("ARQUITETO", "ESTAGIARIO", "JUNIOR"),
                "modalidade", List.of("HIBRIDO", "PRESENCIAL", "REMOTO"));
        for (var entry : orders.entrySet()) {
            for (String direction : List.of("asc", "desc")) {
                var result = mvc.perform(get("/api/careermatcher/vagas/page").param("sort", entry.getKey())
                        .param("direction", direction)).andExpect(status().isOk()).andReturn();
                var content = mapper.readTree(result.getResponse().getContentAsString()).path("content");
                List<String> actual = new ArrayList<>();
                content.forEach(row -> actual.add(row.path(entry.getKey()).asText()));
                List<String> expected = entry.getValue();
                if (direction.equals("desc")) expected = entry.getKey().equals("role")
                        ? List.of("Zeta", "alpha", "Alpha") : expected.reversed();
                assertThat(actual).containsExactlyElementsOf(expected);
            }
        }
        mvc.perform(get("/api/careermatcher/vagas/page").param("sort", "role").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].role").value("Alpha"));
        for (String sort : List.of("name", "id", "role DESC DELETE n"))
            mvc.perform(get("/api/careermatcher/vagas/page").param("sort", sort)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/careermatcher/vagas/page").param("direction", "invalid")).andExpect(status().isBadRequest());
    }

    @Test void validatesPaginationAndHandlesEmptyDatabase() throws Exception {
        for (String endpoint : List.of("candidatos", "vagas")) {
            String url = "/api/careermatcher/" + endpoint + "/page";
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
            for (String size : List.of("0", "-1", "101", "abc", "1.5"))
                mvc.perform(get(url).param("size", size)).andExpect(status().isBadRequest());
            for (String page : List.of("-1", "abc", "1.5", "2147483648"))
                mvc.perform(get(url).param("page", page)).andExpect(status().isBadRequest());
            mvc.perform(get(url).param("size", "100").param("page", "2147483647"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    @Test void createsReadsUpdatesAndDeletesEntireGraphsForMatching() throws Exception {
        var candidate = registerInterested(fixture("candidate"));
        var vacancy = vacancies.create(fixture("vacancy"));
        assertThat(candidate.resume().skills().getFirst().skill().id()).isEqualTo(vacancy.skillRequirements().getFirst().skill().id());
        assertThat(candidate.resume().languages().getFirst().language().id()).isEqualTo(vacancy.languageRequirements().getFirst().language().id());
        assertThat(candidate.resume().undergraduate().completionDate().toString()).isEqualTo("2030-12-01T00:00:00Z");
        assertThat(candidates.findAll()).hasSize(1);
        assertThat(vacancies.findAll()).hasSize(1);
        assertThat(mapper.writeValueAsString(candidate)).doesNotContain("password", "senha-exemplo");
        verifyNoInteractions(context.getBean(EmailService.class));

        var participantMapper = new br.com.careermatcher.matching.SelectedParticipantsMapper();
        var loadedCandidate = participantMapper.candidate(candidates.findCandidate(candidate.id()));
        var loadedVacancy = participantMapper.vacancy(vacancies.find(vacancy.id()));
        assertThat(loadedCandidate.getResume().getExperiences().getFirst().getDurationMonths()).isEqualTo(24);
        assertThat(loadedCandidate.getResume().getUndergraduate().getCompletionDate().toInstant().toString()).isEqualTo("2030-12-01T00:00:00Z");
        assertThat(loadedVacancy.getCompetencias().getFirst().getWeight()).isEqualTo(2.0);
        assertThat(loadedCandidate.getResume().getGraduates()).hasSize(1);
        assertThat(loadedVacancy.getGraduate()).hasSize(1);
        context.getBean(TestActor.class).candidate(candidate.id());
        var changedGraduate = candidates.patch(candidate.id(), object("{\"resume\":{\"graduates\":[{\"degree\":\"PHD\",\"field\":\"Computação\"}]}}"));
        assertThat(changedGraduate.resume().graduates().getFirst().degree().name()).isEqualTo("PHD");

        var patched = candidates.patch(candidate.id(), object("{\"professionalTitle\":\"Senior Developer\",\"phone\":null}"));
        assertThat(patched.name()).isEqualTo(candidate.name());
        assertThat(patched.phone()).isNull();
        assertThat(patched.resume().id()).isEqualTo(changedGraduate.resume().id());
        var changedResume = candidates.patch(candidate.id(), object("{\"resume\":{\"skills\":[]}}"));
        assertThat(changedResume.resume().skills()).isEmpty();
        assertThat(changedResume.resume().experiences()).hasSize(1);
        context.getBean(TestActor.class).staff(companyId);
        assertThat(vacancies.find(vacancy.id()).skillRequirements()).hasSize(1);
        vacancies.patch(vacancy.id(), object("{\"skillRequirements\":[],\"experienceRequirement\":null}"));
        assertThat(vacancies.find(vacancy.id()).experienceRequirement()).isNull();
        assertThat(vacancies.find(vacancy.id()).skillRequirements()).isEmpty();
        candidates.delete(candidate.id());
        vacancies.delete(vacancy.id());
        assertThat(count("MATCH (c:CANDIDATE) RETURN count(c) AS count")).isPositive();
        assertThat(count("MATCH (:CANDIDATE)-[:HAS]->(:RESUME) RETURN count(*) AS count")).isPositive();
        assertThat(count("MATCH ()-[:`INTERESTED IN`]->() RETURN count(*) AS count")).isZero();
        assertThat(count("MATCH (n:SKILL) RETURN count(n) AS count")).isEqualTo(2);
    }

    @Test void preservesSharedResumeAfterBothInterestsAreRemoved() throws Exception {
        var first = registerInterested(fixture("candidate"));
        var second = registerInterested(object("{\"name\":\"Bia\",\"professionalTitle\":\"Dev\"}"));
        client.query("MATCH (a:CANDIDATE)-[:HAS]->(r:RESUME), (b:CANDIDATE) WHERE id(a)=$a AND id(b)=$b CREATE (b)-[:HAS]->(r)")
                .bindAll(Map.of("a", first.id(), "b", second.id())).run();
        context.getBean(TestActor.class).candidate(first.id());
        candidates.patch(first.id(), object("{\"resume\":{\"experiences\":[]}}"));
        context.getBean(TestActor.class).staff(companyId);
        assertThat(candidates.findCandidate(second.id()).resume().experiences()).hasSize(1);
        candidates.delete(first.id());
        assertThat(candidates.findCandidate(second.id()).resume().id()).isEqualTo(first.resume().id());
        candidates.delete(second.id());
        assertThat(count("MATCH (c:CANDIDATE) RETURN count(c) AS count")).isPositive();
        assertThat(count("MATCH (:CANDIDATE)-[:HAS]->(:RESUME) RETURN count(*) AS count")).isPositive();
        assertThat(count("MATCH ()-[:`INTERESTED IN`]->() RETURN count(*) AS count")).isZero();
    }

    @Test void reusesNormalizedCatalogsConcurrently() throws Exception {
        long id = client.query("CREATE (s:SKILL {name:'Java', normalizedName:'java'}) RETURN id(s) AS id")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        var first = registerInterested(fixture("candidate"));
        assertThat(first.resume().skills().getFirst().skill().id()).isEqualTo(Long.toString(id));
        assertThat(count("MATCH (s:SKILL {normalizedName:'java'}) RETURN count(s) AS count")).isEqualTo(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> registerInterested(object("{\"name\":\"Um\",\"professionalTitle\":\"Dev\",\"resume\":{\"skills\":[{\"weight\":1,\"skill\":{\"name\":\"New  Skill\"}}]}}")));
            var two = pool.submit(() -> registerInterested(object("{\"name\":\"Dois\",\"professionalTitle\":\"Dev\",\"resume\":{\"skills\":[{\"weight\":2,\"skill\":{\"name\":\" new skill \"}}]}}")));
            assertThat(one.get(30, TimeUnit.SECONDS).resume().skills().getFirst().skill().id())
                    .isEqualTo(two.get(30, TimeUnit.SECONDS).resume().skills().getFirst().skill().id());
        }
        assertThat(count("MATCH (s:SKILL {normalizedName:'new skill'}) RETURN count(s) AS count")).isEqualTo(1);
    }

    @Test void reusesSeededCatalogAndTreatsCypherAsData() throws Exception {
        client.query("CREATE (:SKILL {name:'Java', normalizedName:'java'})").run();
        var input = fixture("candidate");
        input.put("name", "Ana'}) MATCH (n) DETACH DELETE n //");
        var candidate = registerInterested(input);
        assertThat(candidate.name()).isEqualTo(input.path("name").asText());
        assertThat(count("MATCH (s:SKILL) WHERE toLower(trim(s.name))='java' RETURN count(s) AS count")).isEqualTo(1);
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences()).hasSize(1);
    }

    @Test void concurrentPatchesPreserveUnrelatedChanges() throws Exception {
        long id = registerInterested(fixture("candidate")).id();
        context.getBean(TestActor.class).candidate(id);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> candidates.patch(id, object("{\"name\":\"Novo nome\"}")));
            var two = pool.submit(() -> candidates.patch(id, object("{\"professionalTitle\":\"Novo cargo\"}")));
            one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
        }
        assertThat(candidates.findCandidate(id).name()).isEqualTo("Novo nome");
        assertThat(candidates.findCandidate(id).professionalTitle()).isEqualTo("Novo cargo");
    }

    @Test void validatesBeforePersistingAndRollsBackWholeAggregate() throws Exception {
        var original = registerInterested(fixture("candidate"));
        context.getBean(TestActor.class).candidate(original.id());
        long before = count("MATCH (n) RETURN count(n) AS count");
        assertThatThrownBy(() -> candidates.patch(original.id(), object("{\"name\":\"Mudou\",\"resume\":{\"experiences\":[{\"role\":\"Dev\",\"durationMonths\":-1,\"seniority\":\"PLENO\"}]}}")))
                .isInstanceOf(ValidationException.class);
        assertThat(candidates.findCandidate(original.id())).isEqualTo(original);
        assertThat(count("MATCH (n) RETURN count(n) AS count")).isEqualTo(before);
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        ObjectNode valid = fixture("candidate");
        assertThatThrownBy(() -> transaction.execute(status -> {
            registerInterested(valid);
            throw new IllegalStateException("Force rollback after saving complete graph");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("MATCH (n) RETURN count(n) AS count")).isEqualTo(before);
    }

    @Test void httpRoutesReturnDtosErrorsAndAliasesWithoutLeakingPasswords() throws Exception {
        long id = registerInterested(fixture("candidate")).id();
        mvc.perform(post("/api/careermatcher/candidatos").contentType(MediaType.APPLICATION_JSON).content(fixture("candidate").toString()))
                .andExpect(status().isMethodNotAllowed());
        for (String route : List.of("/api/careermatcher/candidatos", "/api/careermatcher/candidatos/"))
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(jsonPath("$[0].password").doesNotExist());
        mvc.perform(get("/api/careermatcher/candidato/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.resume.experiences[0].durationMonths").value(24));
        mvc.perform(patch("/api/careermatcher/candidatos/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"professionalTitle\":null}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/careermatcher/candidato/" + id + "/create/resume").contentType(MediaType.APPLICATION_JSON).content("{\"skills\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/careermatcher/candidatos/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/careermatcher/candidatos/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/careermatcher/candidatos/abc")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/careermatcher/candidatos").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isMethodNotAllowed());
        var vacancy = mvc.perform(post("/api/careermatcher/vagas/").contentType(MediaType.APPLICATION_JSON).content(fixture("vacancy").toString()))
                .andExpect(status().isCreated()).andReturn();
        long vacancyId = mapper.readTree(vacancy.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(get("/api/careermatcher/vagas/" + vacancyId)).andExpect(status().isOk());
        mvc.perform(get("/api/careermatcher/vagas")).andExpect(status().isOk());
        mvc.perform(patch("/api/careermatcher/vagas/" + vacancyId).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Natal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.city").value("Natal"));
        mvc.perform(delete("/api/careermatcher/vagas/" + vacancyId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/careermatcher/vagas/" + vacancyId)).andExpect(status().isNotFound());
    }

    @Test void staffCannotCreateOrUpdateCandidateEvenThroughRepository() throws Exception {
        var graph = context.getBean(GraphCrudRepository.class);
        var candidate = registerInterested(fixture("candidate"));
        assertThatThrownBy(() -> graph.create(GraphSchema.Kind.CANDIDATE, fixture("candidate")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> candidates.patch(candidate.id(), object("{\"name\":\"Changed\"}")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> graph.update(GraphSchema.Kind.CANDIDATE, candidate.id(), fixture("candidate"), Set.of("name")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        assertThat(candidates.findCandidate(candidate.id()).name()).isEqualTo(candidate.name());
        context.getBean(TestActor.class).candidate(candidate.id() + 10000);
        assertThatThrownBy(() -> candidates.patch(candidate.id(), object("{\"name\":\"Changed\"}")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test void assistedImportOnlyPersistsAfterReviewAndSave() throws Exception {
        var actor = context.getBean(TestActor.class);
        var candidate = registerInterested(object("{\"name\":\"Ana original\",\"professionalTitle\":\"Dev\"}"));
        var defaults = new br.com.careermatcher.ai.AiExtractionProperties(true, 5242880, 20, 60000, 2,
                new br.com.careermatcher.ai.AiExtractionProperties.Deepseek("test-key", "https://deepseek.test", "deepseek-flash",
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60), 8192));
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://deepseek.test");
        var provider = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var extraction = new br.com.careermatcher.ai.AiExtractionService(defaults,
                new br.com.careermatcher.ai.PdfTextExtractionService(defaults),
                new br.com.careermatcher.ai.ExtractionPromptFactory(mapper),
                new br.com.careermatcher.ai.DeepSeekClient(builder.build(), defaults, mapper), context.getBean(GraphValidations.class));
        var api = MockMvcBuilders.standaloneSetup(new br.com.careermatcher.ai.AiExtractionController(actor, extraction),
                        new br.com.careermatcher.security.SelfCandidateController(actor, candidates), new VagaController(vacancies))
                .setControllerAdvice(new br.com.careermatcher.ai.AiExtractionExceptionHandler(), new CrudExceptionHandler()).build();
        var imported = object("""
                {"name":"Ana importada","professionalTitle":"Desenvolvedora Java",
                 "resume":{"skills":[{"skill":{"name":"Java"}}]}}
                """);
        var vacancy = fixture("vacancy").put("additionalInfo", "<p>Benefícios extraídos</p>");
        for (ObjectNode draft : List.of(imported, vacancy)) {
            var content = mapper.createObjectNode(); content.set("data", draft);
            var response = mapper.createObjectNode();
            var choice = response.putArray("choices").addObject(); choice.put("finish_reason", "stop");
            choice.putObject("message").put("content", content.toString());
            provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://deepseek.test/chat/completions"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        }
        byte[] pdf;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); document.addPage(page);
            try (var text = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                text.beginText();
                text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(50, 700); text.showText("Ana importada - Desenvolvedora Java - Competencias Java"); text.endText();
            }
            document.save(out); pdf = out.toByteArray();
        }
        actor.candidate(candidate.id());
        long before = count("MATCH (n) RETURN count(n) AS count");
        var suggestion = api.perform(multipart("/api/me/candidate/ai-extraction")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "linkedin.pdf", "application/pdf", pdf)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingFields[0].field").value("resume.skills[0].weight"))
                .andReturn();
        assertThat(count("MATCH (n) RETURN count(n) AS count")).isEqualTo(before);
        assertThat(candidates.findCandidate(candidate.id()).name()).isEqualTo("Ana original");
        ObjectNode reviewed = (ObjectNode) mapper.readTree(suggestion.getResponse().getContentAsString()).path("data");
        ((ObjectNode) reviewed.at("/resume/skills/0")).put("weight", 2);
        api.perform(patch("/api/me/candidate").contentType(MediaType.APPLICATION_JSON).content(reviewed.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ana importada"))
                .andExpect(jsonPath("$.resume.skills[0].weight").value(2));
        actor.staff(companyId);
        before = count("MATCH (n) RETURN count(n) AS count");
        var vacancySuggestion = api.perform(multipart("/api/careermatcher/vagas/ai-extraction").param("text", "Vaga Java remota com benefícios"))
                .andExpect(status().isOk()).andReturn();
        assertThat(count("MATCH (n) RETURN count(n) AS count")).isEqualTo(before);
        JsonNode vacancyData = mapper.readTree(vacancySuggestion.getResponse().getContentAsString()).path("data");
        var saved = api.perform(post("/api/careermatcher/vagas").contentType(MediaType.APPLICATION_JSON).content(vacancyData.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.additionalInfo").value("<p>Benefícios extraídos</p>"))
                .andReturn();
        long id = mapper.readTree(saved.getResponse().getContentAsString()).path("id").asLong();
        assertThat(vacancies.find(id).additionalInfo()).isEqualTo("<p>Benefícios extraídos</p>");
        provider.verify();
    }

    private br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView registerInterested(ObjectNode input) {
        var candidate = candidates.registerCandidate(input);
        client.query("MATCH (c:CANDIDATE), (company:COMPANY) WHERE id(c)=$candidate AND id(company)=$company "
                + "MERGE (c)-[:`INTERESTED IN`]->(company)")
                .bindAll(Map.of("candidate", candidate.id(), "company", companyId)).run();
        return candidate;
    }

    private void linkAllCandidates() {
        client.query("MATCH (c:CANDIDATE), (company:COMPANY) WHERE id(company)=$company MERGE (c)-[:`INTERESTED IN`]->(company)")
                .bind(companyId).to("company").run();
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/crud/" + name + ".json")) { return (ObjectNode) mapper.readTree(stream); }
    }
    private ObjectNode object(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }
    private long count(String query) { return client.query(query).fetchAs(Long.class).mappedBy((types, record) -> record.get("count").asLong()).one().orElseThrow(); }
}
