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
        mvc = MockMvcBuilders.standaloneSetup(new CandidatoController(candidates), new VagaController(vacancies),
                new CompanyController(context.getBean(CompanyService.class)),
                new ResumeFormConfigurationController(context.getBean(ResumeSkillPolicy.class)),
                new CatalogController(context.getBean(CatalogRepository.class), context.getBean(TextSanitizer.class)))
                .setControllerAdvice(new CrudExceptionHandler()).build();
    }
    @Test void talentPoolExcludesOnlyCurrentConfirmedEmployeesOfItsWorkspaceAndPreservesProfileAccess() throws Exception {
        client.query("""
            UNWIND [
              {name:'External',confirmed:false,current:true,company:$company},
              {name:'Internal',confirmed:true,current:true,company:$company},
              {name:'Former',confirmed:true,current:false,company:$company},
              {name:'Other employer',confirmed:true,current:true,company:-999}
            ] AS item
            MATCH (company:COMPANY) WHERE id(company)=$company
            CREATE (candidate:CANDIDATE {name:item.name,professionalTitle:'Dev'})-[:`INTERESTED IN`]->(company)
            CREATE (candidate)-[:HAS]->(:RESUME)-[:HasExperience]->(:EXPERIENCE {confirmed:item.confirmed,currentJob:item.current,confirmedCompanyId:item.company})
            """).bind(companyId).to("company").run();
        client.query("""
            MATCH(company:COMPANY) WHERE id(company)=$company
            CREATE (c:CANDIDATE {name:'Split signals',professionalTitle:'Dev'})-[:`INTERESTED IN`]->(company)
            CREATE(c)-[:HAS]->(r:RESUME)
            CREATE(r)-[:HasExperience]->(:EXPERIENCE {confirmed:true,currentJob:false,confirmedCompanyId:$company})
            CREATE(r)-[:HasExperience]->(:EXPERIENCE {confirmed:false,currentJob:true,confirmedCompanyId:$company})
            """).bind(companyId).to("company").run();
        var graph=context.getBean(GraphCrudRepository.class);
        var expected=List.of("External","Former","Other employer","Split signals");
        assertThat(candidates.findPage(0,10).content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary::name).containsExactlyElementsOf(expected);
        assertThat(candidates.findAll()).extracting(c->c.name()).containsExactlyElementsOf(expected);
        assertThat(graph.findPage(GraphSchema.Kind.CANDIDATE,0,10)).extracting(n->n.path("name").asText()).containsExactlyElementsOf(expected);
        assertThat(context.getBean(br.com.careermatcher.relocation.RelocationService.class).list(0,10,"").content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary::name).containsExactly("Internal");
        long internal=client.query("MATCH(c:CANDIDATE {name:'Internal'}) RETURN id(c) AS id").fetchAs(Long.class).one().orElseThrow();
        assertThat(candidates.findCandidate(internal).name()).isEqualTo("Internal");
        assertThatThrownBy(()->graph.findTalentPoolByIds(List.of(internal),200)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(graph.talentPoolIds()).hasSize(4).doesNotContain(internal);
    }

    @Test void allSelectionIncludesEveryEligibleCandidateBeyondCacheWindowAndRejectsInternalManualSelection() throws Exception {
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,305);
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL,5);
        assertThat(candidates.findPage(0,100).totalElements()).isEqualTo(305);
        var vacancy=vacancies.create(fixture("vacancy"));
        var graph=context.getBean(GraphCrudRepository.class);
        var validator=new br.com.careermatcher.matching.MatchingSelectionValidator(graph,mapper,context.getBean(TestActor.class),200);
        var selection=validator.validate(mapper.valueToTree(Map.of("allCandidates",true,"vacancyIds",List.of(vacancy.id()))));
        assertThat(selection.candidates()).hasSize(305);
        assertThat(selection.candidates()).extracting(c->c.id()).containsExactlyElementsOf(graph.talentPoolIds());
        long excluded=selection.candidates().get(304).id();
        var partial=validator.validate(mapper.valueToTree(Map.of("allCandidates",true,"excludedCandidateIds",List.of(excluded),"vacancyIds",List.of(vacancy.id()))));
        assertThat(partial.candidates()).hasSize(304);
        assertThat(partial.candidates()).extracting(c->c.id()).doesNotContain(excluded);
        long internal=client.query("MATCH(c:CANDIDATE)-[:HAS]->(:RESUME)-[:HasExperience]->(:EXPERIENCE {confirmed:true,currentJob:true}) RETURN id(c) AS id LIMIT 1").fetchAs(Long.class).one().orElseThrow();
        assertThatThrownBy(()->validator.validate(mapper.valueToTree(Map.of("candidateIds",List.of(internal),"vacancyIds",List.of(vacancy.id())))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        context.getBean(TestActor.class).staff(companyId+999);
        assertThat(graph.talentPoolIds()).isEmpty();
    }

    @Test void sharesSummaryCacheAcrossCompanyRolesAndReauthorizesEveryHit() throws Exception {
        var candidate=registerInterested(fixture("candidate"));
        var actor=context.getBean(TestActor.class);
        assertThat(candidates.findPage(0,10).content().getFirst().name()).isEqualTo(candidate.name());
        client.query("MATCH (c:CANDIDATE) WHERE id(c)=$id SET c.name='External change'").bind(candidate.id()).to("id").run();
        for(var role:List.of(br.com.careermatcher.enums.Role.RECRUTADOR,br.com.careermatcher.enums.Role.GERENTE,br.com.careermatcher.enums.Role.ADMINISTRADOR)) {
            actor.staff(companyId,role);
            assertThat(candidates.findPage(0,10).content().getFirst().name()).isEqualTo(candidate.name());
        }
        actor.staff(companyId+999);
        assertThat(candidates.findPage(0,10).content()).isEmpty();
        actor.candidate(candidate.id());
        assertThatThrownBy(()->candidates.findPage(0,10)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(br.com.careermatcher.listings.WorkspaceListings.Listing.class)
    void cachesOnlyFirst300SummariesWithoutTruncatingDeepPagesSearchOrSorting(br.com.careermatcher.listings.WorkspaceListings.Listing type) {
        seedListing(type,305);
        var spyClient=spy(client);
        var listings=testListings(spyClient,()->0L);
        var first=listingPage(listings,type,0,100,"",null,"asc");
        assertThat(first.content()).hasSize(100); assertThat(first.totalElements()).isEqualTo(305);
        verify(spyClient,times(2)).query(anyString()); clearInvocations(spyClient);
        assertThat(listingPage(listings,type,2,100,"",null,"asc").content()).hasSize(100);
        verifyNoInteractions(spyClient);
        String label=type==br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES?"VACANCY":"CANDIDATE";
        String field=type==br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES?"role":"professionalTitle";
        client.query("MATCH (n:"+label+") WHERE n.seedIndex=304 SET n."+field+"='Beyond cached window'").run();
        var deep=listingPage(listings,type,3,100,"",null,"asc");
        assertThat(deep.content()).hasSize(5);
        assertThat(mapper.valueToTree(deep.content()).toString()).contains("Beyond cached window");
        verify(spyClient,times(2)).query(anyString()); clearInvocations(spyClient);
        var found=listingPage(listings,type,0,10,"beyond cached window",null,"asc");
        assertThat(found.totalElements()).isEqualTo(1); assertThat(found.content()).hasSize(1);
        verify(spyClient,times(2)).query(anyString()); clearInvocations(spyClient);
        var sorted=listingPage(listings,type,0,10,"",type==br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES?"role":"name","desc");
        assertThat(sorted.totalElements()).isEqualTo(305);
        verify(spyClient,times(2)).query(anyString());
    }

    @Test void expiresPeopleAfter10MinutesAndVacanciesAfter2Minutes() {
        var ticks=new java.util.concurrent.atomic.AtomicLong();
        var listings=testListings(client,ticks::get);
        for(var type:br.com.careermatcher.listings.WorkspaceListings.Listing.values())
            assertThat(listingPage(listings,type,0,10,"",null,"asc").totalElements()).isZero();
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL,1);
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,1);
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES,1);
        ticks.set(java.time.Duration.ofMinutes(2).toNanos()-1);
        assertThat(listingPage(listings,br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES,0,10,"",null,"asc").totalElements()).isZero();
        ticks.incrementAndGet();
        assertThat(listingPage(listings,br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES,0,10,"",null,"asc").totalElements()).isEqualTo(1);
        for(var type:List.of(br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL))
            assertThat(listingPage(listings,type,0,10,"",null,"asc").totalElements()).isZero();
        ticks.set(java.time.Duration.ofMinutes(10).toNanos());
        for(var type:List.of(br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL))
            assertThat(listingPage(listings,type,0,10,"",null,"asc").totalElements()).isEqualTo(1);
    }

    @Test void vacancyWritesRefreshSharedListAfterCommitAndRollbackDoesNotPublishUncommittedRows() throws Exception {
        assertThat(vacancies.findPage(0,10).totalElements()).isZero();
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.execute(status->{
            try { vacancies.create(fixture("vacancy")); } catch(Exception e) { throw new RuntimeException(e); }
            assertThat(context.getBean(br.com.careermatcher.listings.WorkspaceListings.class).vacancies(0,10,"",null,"asc").totalElements()).isEqualTo(1);
            status.setRollbackOnly(); return null;
        });
        assertThat(vacancies.findPage(0,10).totalElements()).isZero();
        var vacancy=vacancies.create(fixture("vacancy"));
        assertThat(vacancies.findPage(0,10).content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.VacancySummary::id).containsExactly(vacancy.id());
        vacancies.patch(vacancy.id(),object("{\"city\":\"Cidade nova\"}"));
        assertThat(vacancies.findPage(0,10,"Cidade nova",null,"asc").totalElements()).isEqualTo(1);
        vacancies.delete(vacancy.id());
        assertThat(vacancies.findPage(0,10).totalElements()).isZero();
    }

    @Test void interestApplicationAndProfileChangesInvalidateAffectedCompanyListings() throws Exception {
        var candidate=registerInterested(fixture("candidate"));
        var vacancy=vacancies.create(fixture("vacancy"));
        assertThat(candidates.findPage(0,10).totalElements()).isEqualTo(1);
        var actor=context.getBean(TestActor.class); actor.candidate(candidate.id());
        context.getBean(CompanyInterestRepository.class).setInterest(companyId,false);
        actor.staff(companyId); assertThat(candidates.findPage(0,10).totalElements()).isZero();
        actor.candidate(candidate.id());
        context.getBean(VacancyApplicationRepository.class).apply(vacancy.id(),java.math.BigDecimal.valueOf(1000));
        actor.staff(companyId); assertThat(candidates.findPage(0,10).totalElements()).isEqualTo(1);
        long secondCompany=client.query("CREATE (co:COMPANY {name:'Second workspace'}) RETURN id(co) AS id").fetchAs(Long.class).one().orElseThrow();
        client.query("MATCH (c:CANDIDATE),(co:COMPANY) WHERE id(c)=$candidate AND id(co)=$company MERGE (c)-[:`INTERESTED IN`]->(co)")
                .bind(candidate.id()).to("candidate").bind(secondCompany).to("company").run();
        actor.staff(secondCompany); assertThat(candidates.findPage(0,10).totalElements()).isEqualTo(1);
        actor.candidate(candidate.id());
        candidates.patch(candidate.id(),object("{\"professionalTitle\":\"Arquiteta atualizada\"}"));
        for(long company:List.of(companyId,secondCompany)) {
            actor.staff(company);
            assertThat(candidates.findPage(0,10).content().getFirst().professionalTitle()).isEqualTo("Arquiteta atualizada");
        }
    }

    @Test void completeCachedSnapshotsUseSameSearchAndLocalizedSortingAsDatabase() {
        seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES,3);
        var spyClient=spy(client); var listings=testListings(spyClient,()->0L);
        assertThat(listings.vacancies(0,10,"",null,"asc").totalElements()).isEqualTo(3);
        clearInvocations(spyClient);
        assertThat(listings.vacancies(0,10,"pleno",null,"asc").totalElements()).isEqualTo(3);
        assertThat(listings.vacancies(0,10,"remoto","seniority","desc").totalElements()).isEqualTo(3);
        assertThat(listings.vacancies(0,1,"vaga 002","role","asc").content().getFirst().role()).isEqualTo("Vaga 002");
        verifyNoInteractions(spyClient);
    }

    private br.com.careermatcher.listings.WorkspaceListings testListings(Neo4jClient client,com.github.benmanes.caffeine.cache.Ticker ticker) {
        return new br.com.careermatcher.listings.WorkspaceListings(client,context.getBean(TestActor.class),context.getBean(PlatformTransactionManager.class),
                java.time.Duration.ofMinutes(10),java.time.Duration.ofMinutes(2),300,64,ticker);
    }
    private br.com.careermatcher.DTOs.crud.CrudDtos.PageView<?> listingPage(br.com.careermatcher.listings.WorkspaceListings listings,
            br.com.careermatcher.listings.WorkspaceListings.Listing type,int page,int size,String query,String sort,String direction) {
        return type==br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES?listings.vacancies(page,size,query,sort,direction)
                :listings.candidates(type,page,size,query,sort,direction);
    }
    private void seedListing(br.com.careermatcher.listings.WorkspaceListings.Listing type,int count) {
        String query=type==br.com.careermatcher.listings.WorkspaceListings.Listing.VACANCIES
                ? "MATCH(co:COMPANY) WHERE id(co)=$company UNWIND range(0,$count-1) AS i CREATE(co)-[:PUBLISHES]->(:VACANCY {seedIndex:i,role:'Vaga '+right('000'+toString(i),3),city:'Recife',seniority:'PLENO',modalidade:'REMOTO'})"
                : "MATCH(co:COMPANY) WHERE id(co)=$company UNWIND range(0,$count-1) AS i CREATE(c:CANDIDATE {seedIndex:i,name:'Pessoa '+right('000'+toString(i),3),professionalTitle:'Dev',phone:'1199000'})-[:`INTERESTED IN`]->(co) "
                +(type==br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL?"CREATE(c)-[:HAS]->(:RESUME)-[:HasExperience]->(:EXPERIENCE {confirmed:true,currentJob:true,confirmedCompanyId:$company})":"");
        client.query(query).bind(companyId).to("company").bind(count).to("count").run();
    }

    @Test void exposesPrimarySkillLimitForBothForms() throws Exception {
        for (String path : List.of("/api/me/candidate/form-configuration", "/api/careermatcher/candidatos/form-configuration"))
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.maxSkills").value(3));
    }

    @Test void searchesSharedCatalogsWithNormalizedQueriesAndBoundedResults() throws Exception {
        var catalog = context.getBean(CatalogRepository.class);
        catalog.resolve(GraphSchema.Kind.SKILL, "Java");
        catalog.resolve(GraphSchema.Kind.SKILL, "JavaScript");
        catalog.resolve(GraphSchema.Kind.SKILL, "SQL");
        catalog.resolve(GraphSchema.Kind.LANGUAGE, "Javanês");
        mvc.perform(get("/api/catalog/skills").param("query", " JAVA ").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Java"));
        mvc.perform(get("/api/catalog/skills").param("query", "ava"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/catalog/languages").param("query", "java"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Javanês"));
        mvc.perform(get("/api/catalog/skills").param("query", "' MATCH (n) DETACH DELETE n"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/catalog/skills").param("limit", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/skills").param("query", "a".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test void paginatesEntireCachedSkillCatalogAndFiltersBeforePagination() throws Exception {
        var names = java.util.stream.IntStream.range(0, 205).mapToObj(i -> "Skill %03d".formatted(i)).toList();
        client.query("UNWIND $names AS name CREATE (:SKILL {name:name})").bind(names).to("names").run();
        var catalog = context.getBean(CatalogRepository.class);
        var collected = new ArrayList<String>();
        for (int offset = 0; offset < 220; offset += 20)
            collected.addAll(catalog.search(GraphSchema.Kind.SKILL, "", 20, offset).stream().map(CatalogRepository.Option::name).toList());
        assertThat(collected).containsExactlyElementsOf(names);
        // Later pages and searches read the same cached snapshot, even after an external deletion.
        client.query("MATCH (n:SKILL) DELETE n").run();
        mvc.perform(get("/api/catalog/skills").param("offset", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].name").value("Skill 200"));
        mvc.perform(get("/api/catalog/skills").param("query", "204"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Skill 204"));
        mvc.perform(get("/api/catalog/skills").param("offset", "220"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/catalog/skills").param("offset", "-1")).andExpect(status().isBadRequest());
    }

    @Test void seedsClosedRankedLanguageCatalogIdempotentlyAndReusesSkillVariants() throws Exception {
        var catalog = context.getBean(CatalogRepository.class);
        long java = catalog.resolve(GraphSchema.Kind.SKILL, "Java");
        for (String variant : List.of(" JAVA ", "java", "Ｊａｖａ"))
            assertThat(catalog.resolve(GraphSchema.Kind.SKILL, variant)).isEqualTo(java);
        assertThat(catalog.search(GraphSchema.Kind.SKILL, "ＪＡＶＡ", 20)).extracting(CatalogRepository.Option::name).containsExactly("Java");
        assertThat(catalog.resolve(GraphSchema.Kind.SKILL, "JavaScript")).isNotEqualTo(java);
        catalog.seedLanguages(); catalog.seedLanguages();
        var languages = catalog.search(GraphSchema.Kind.LANGUAGE, "", 20);
        assertThat(languages).hasSize(50);
        assertThat(languages.getFirst().id()).isEqualTo(1);
        assertThat(languages.getFirst().name()).isEqualTo("Inglês");
        assertThat(languages.getLast().id()).isEqualTo(50);
        assertThat(catalog.resolve(GraphSchema.Kind.LANGUAGE, "English")).isEqualTo(catalog.resolve(GraphSchema.Kind.LANGUAGE, "INGLES"));
        assertThatThrownBy(() -> catalog.resolve(GraphSchema.Kind.LANGUAGE, "Inventado"))
                .isInstanceOf(ValidationException.class);
        assertThat(count("MATCH (n:LANGUAGE) RETURN count(n) AS count")).isEqualTo(50);
        catalog.search(GraphSchema.Kind.SKILL, "Rust", 20);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
            catalog.resolve(GraphSchema.Kind.SKILL, "Rust");
            assertThat(catalog.search(GraphSchema.Kind.SKILL, "Rust", 20)).isEmpty();
            return null;
        });
        assertThat(catalog.search(GraphSchema.Kind.SKILL, "Rust", 20)).hasSize(1);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
            catalog.resolve(GraphSchema.Kind.SKILL, "RollbackOnly"); status.setRollbackOnly(); return null;
        });
        assertThat(catalog.search(GraphSchema.Kind.SKILL, "RollbackOnly", 20)).isEmpty();
    }

    @Test void cachesBothCatalogsAndReloadsOnlyAfterExpiry() {
        var catalog = context.getBean(CatalogRepository.class);
        var immediateExpiry = new CatalogRepository(client, java.time.Duration.ofNanos(1), java.time.Duration.ofNanos(1));
        catalog.resolve(GraphSchema.Kind.SKILL, "Java");
        catalog.seedLanguages();
        for (var repository : List.of(catalog, immediateExpiry)) {
            assertThat(repository.search(GraphSchema.Kind.SKILL, "", 50)).hasSize(1);
            assertThat(repository.search(GraphSchema.Kind.LANGUAGE, "", 50)).hasSize(50);
        }
        // External changes are visible only after expiry; cache hits do not query Neo4j.
        client.query("CREATE (:SKILL {name:'Kotlin'})").run();
        client.query("MATCH (n:LANGUAGE {catalogId:50}) REMOVE n.catalogId").run();
        assertThat(catalog.search(GraphSchema.Kind.SKILL, "kotlin", 50)).isEmpty();
        assertThat(catalog.search(GraphSchema.Kind.LANGUAGE, "", 50)).hasSize(50);
        assertThat(immediateExpiry.search(GraphSchema.Kind.SKILL, "kotlin", 50)).hasSize(1);
        assertThat(immediateExpiry.search(GraphSchema.Kind.LANGUAGE, "", 50)).hasSize(49);
    }

    @Test void seedsLanguagesWithoutReplacingLegacyNodesOrRelationships() {
        long existing = client.query("CREATE (r:RESUME)-[:SPEAKS]->(n:LANGUAGE {name:'English'}) RETURN id(n) AS id")
                .fetchAs(Long.class).one().orElseThrow();
        var catalog = context.getBean(CatalogRepository.class);
        catalog.seedLanguages();
        assertThat(catalog.resolve(GraphSchema.Kind.LANGUAGE, "Inglês")).isEqualTo(existing);
        assertThat(count("MATCH (:RESUME)-[:SPEAKS]->(:LANGUAGE {catalogId:1}) RETURN count(*) AS count")).isEqualTo(1);
        assertThat(count("MATCH (n:LANGUAGE) RETURN count(n) AS count")).isEqualTo(50);
    }

    @Test void verifiesMultipleEmployeesWithSharedCompanyLinkAndPreservesItOnProfileSave() throws Exception {
        var service = context.getBean(br.com.careermatcher.relocation.RelocationService.class);
        var actor = context.getBean(TestActor.class);
        var input = fixture("candidate");
        ((ObjectNode)input.at("/resume/experiences/0")).put("startDate","2024-01-01").put("currentJob",true);
        var candidate = registerInterested(input);
        assertThat(candidate.resume().experiences().getFirst().confirmed()).isFalse();
        var link = service.generate();
        assertThat(service.list(0,20,"").totalElements()).isZero();
        assertThat(candidates.findPage(0,10).totalElements()).isEqualTo(1);
        actor.candidate(candidate.id());
        assertThat(service.preview(link.token()).companyName()).isEqualTo("Test");
        long experience = Long.parseLong(candidate.resume().experiences().getFirst().id());
        service.confirm(link.token(), experience);
        service.confirm(link.token(), experience); // same candidate may retry safely
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().confirmed()).isTrue();
        candidates.patch(candidate.id(), object("{\"resume\":{\"skills\":[]}}"));
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().confirmed()).isTrue();
        var forged = input.deepCopy();
        ((ObjectNode)forged.at("/resume/experiences/0")).put("confirmed", true);
        assertThatThrownBy(() -> candidates.patch(candidate.id(), forged)).isInstanceOf(ValidationException.class);
        actor.staff(companyId);
        assertThat(service.list(0,20,"ana").totalElements()).isEqualTo(1);
        assertThat(candidates.findPage(0,10).totalElements()).isZero();
        long otherCompany = client.query("CREATE (c:COMPANY {name:'Other'}) RETURN id(c) AS id").fetchAs(Long.class).one().orElseThrow();
        actor.staff(otherCompany);
        assertThat(service.list(0,20,"").totalElements()).isZero();
        actor.staff(companyId);
        var other = registerInterested(input);
        actor.candidate(other.id());
        assertThat(service.preview(link.token()).companyName()).isEqualTo("Test");
        service.confirm(link.token(), Long.parseLong(other.resume().experiences().getFirst().id()));
        assertThat(candidates.findCandidate(other.id()).resume().experiences().getFirst().confirmed()).isTrue();
        actor.staff(companyId);
        assertThat(service.list(0,20,"").totalElements()).isEqualTo(2);
        actor.candidate(candidate.id());
        ((ObjectNode)input.at("/resume/experiences/0")).put("currentJob",false).put("endDate","2025-01-01");
        candidates.patch(candidate.id(), input);
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().confirmed()).isFalse();
        actor.staff(companyId);
        assertThat(service.list(0,20,"").totalElements()).isEqualTo(1);
        assertThat(candidates.findPage(0,10).totalElements()).isEqualTo(1);
    }

    @Test void internalCollaboratorListSearchesAllVisibleColumnsAndSortsBeforePagination() throws Exception {
        client.query("""
            UNWIND [
              {name:'Bruno',title:'Qualidade',phone:'119111',confirmed:true,current:true,company:$company},
              {name:'Ana',title:'Design',phone:'219222',confirmed:true,current:true,company:$company},
              {name:'Carla',title:'Qualidade',phone:'319333',confirmed:true,current:true,company:$company},
              {name:'Oculto',title:'Qualidade',phone:'419444',confirmed:false,current:true,company:$company},
              {name:'Ex-colaborador',title:'Qualidade',phone:'519555',confirmed:true,current:false,company:$company},
              {name:'Outra empresa',title:'Qualidade',phone:'619666',confirmed:true,current:true,company:-999}
            ] AS item
            CREATE (c:CANDIDATE {name:item.name,professionalTitle:item.title,phone:item.phone})-[:HAS]->(r:RESUME)
            CREATE (r)-[:HasExperience]->(:EXPERIENCE {confirmed:item.confirmed,currentJob:item.current,confirmedCompanyId:item.company})
            """).bind(companyId).to("company").run();
        var service=context.getBean(br.com.careermatcher.relocation.RelocationService.class);
        assertThat(service.list(0,10,"Qualidade").totalElements()).isEqualTo(2);
        assertThat(service.list(0,10,"219222").content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary::name).containsExactly("Ana");
        assertThat(service.list(0,1,"","name","asc").content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary::name).containsExactly("Ana");
        var page=service.list(1,1,"","name","desc");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).extracting(br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary::name).containsExactly("Bruno");
        assertThat(service.list(0,10,"","professionalTitle","asc").content().getFirst().name()).isEqualTo("Ana");
        assertThatThrownBy(() -> service.list(0,10,"","phone","asc")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(0,10,"","name","invalid")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void sharedVerificationExpiresExactlyAfter24Hours() throws Exception {
        var actor=context.getBean(TestActor.class);
        var input=fixture("candidate");
        ((ObjectNode)input.at("/resume/experiences/0")).put("startDate","2024-01-01").put("currentJob",true);
        var candidate=registerInterested(input);
        var start=java.time.Instant.parse("2026-11-05T12:00:00Z");
        var service=new br.com.careermatcher.relocation.RelocationService(client,actor,java.time.Clock.fixed(start,java.time.ZoneOffset.UTC),context.getBean(br.com.careermatcher.listings.WorkspaceListings.class));
        var link=service.generate();
        assertThat(link.expiresAt()).isEqualTo(start.plus(java.time.Duration.ofHours(24)));
        actor.candidate(candidate.id());
        var before=new br.com.careermatcher.relocation.RelocationService(client,actor,java.time.Clock.fixed(link.expiresAt().minusMillis(1),java.time.ZoneOffset.UTC),context.getBean(br.com.careermatcher.listings.WorkspaceListings.class));
        assertThat(before.preview(link.token()).companyName()).isEqualTo("Test");
        var expired=new br.com.careermatcher.relocation.RelocationService(client,actor,java.time.Clock.fixed(link.expiresAt(),java.time.ZoneOffset.UTC),context.getBean(br.com.careermatcher.listings.WorkspaceListings.class));
        assertThatThrownBy(() -> expired.preview(link.token())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> expired.confirm(link.token(),Long.parseLong(candidate.resume().experiences().getFirst().id())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void verificationRejectsExpiredLinksAndOtherCandidatesExperiences() throws Exception {
        var service = context.getBean(br.com.careermatcher.relocation.RelocationService.class);
        var actor = context.getBean(TestActor.class);
        var input = fixture("candidate");
        ((ObjectNode)input.at("/resume/experiences/0")).put("startDate","2024-01-01").put("currentJob",true);
        var first = registerInterested(input);
        var second = registerInterested(input);
        var link = service.generate();
        actor.candidate(first.id());
        assertThatThrownBy(() -> service.confirm(link.token(),Long.parseLong(second.resume().experiences().getFirst().id())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        client.query("MATCH (v:EMPLOYEE_VERIFICATION) SET v.expiresAt=0").run();
        assertThatThrownBy(() -> service.confirm(link.token(),Long.parseLong(first.resume().experiences().getFirst().id())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.preview("invalid"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @AfterAll void stop() { if (context != null) context.close(); if (database != null) database.close(); }
    @BeforeEach void clear() {
        client.query("MATCH (n) DETACH DELETE n").run();
        context.getBean(CatalogRepository.class).clearCaches();
        context.getBean(br.com.careermatcher.listings.WorkspaceListings.class).clearCaches();
        companyId = client.query("CREATE (c:COMPANY {name:'Test'}) RETURN id(c) AS id")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        context.getBean(TestActor.class).staff(companyId);
        reset(context.getBean(EmailService.class));
    }

    @Configuration @EnableTransactionManagement
    @Import({br.com.careermatcher.listings.WorkspaceListings.class, CompanyInterestRepository.class, VacancyApplicationRepository.class, br.com.careermatcher.relocation.RelocationService.class, ResumeSkillPolicy.class, GraphCrudRepository.class, CatalogRepository.class, TextSanitizer.class, GraphValidations.class,
            CandidatoValidations.class, VagaValidations.class, CandidatoService.class, VagaService.class,
            CompanyService.class})
    static class Config extends AbstractNeo4jConfig {
        @Bean @Override public Driver driver() { return GraphDatabase.driver(database.boltURI(), AuthTokens.none()); }
        @Override protected Collection<String> getMappingBasePackages() { return List.of("br.com.careermatcher.models"); }
        @Bean public ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
        @Bean public java.time.Clock clock() { return java.time.Clock.systemUTC(); }
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

    @Test void roundTripsExperienceDatesAndCurrentJobThroughCandidateAndBatchReads() throws Exception {
        var input = fixture("candidate");
        var experience = (ObjectNode)input.at("/resume/experiences/0");
        experience.remove("durationMonths");
        experience.put("startDate", "2023-01-15").put("endDate", "2025-01-15").put("currentJob", false);
        var candidate = registerInterested(input);
        var saved = candidate.resume().experiences().getFirst();
        assertThat(saved.startDate()).isEqualTo(java.time.LocalDate.of(2023, 1, 15));
        assertThat(saved.endDate()).isEqualTo(java.time.LocalDate.of(2025, 1, 15));
        assertThat(saved.durationMonths()).isEqualTo(24);
        mvc.perform(get("/api/careermatcher/candidato/" + candidate.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.resume.experiences[0].startDate").value("2023-01-15"));
        var graph = context.getBean(GraphCrudRepository.class);
        assertThat(graph.findByIds(GraphSchema.Kind.CANDIDATE, List.of(candidate.id()), 10).getFirst())
                .isEqualTo(graph.find(GraphSchema.Kind.CANDIDATE, candidate.id()));
        context.getBean(TestActor.class).candidate(candidate.id());
        experience.put("currentJob", true);
        var patch = object("{}");
        patch.set("resume", input.path("resume"));
        var updated = candidates.patch(candidate.id(), patch).resume().experiences().getFirst();
        assertThat(updated.endDate()).isNull();
        assertThat(updated.currentJob()).isTrue();
        assertThat(updated.durationMonths()).isEqualTo(br.com.careermatcher.models.ExperiencePeriod.months(saved.startDate(), java.time.LocalDate.now(java.time.ZoneOffset.UTC)));
        // Date-only Neo4j properties are also supported on read.
        client.query("MATCH (e:EXPERIENCE) WHERE id(e)=$id SET e.startDate=date(e.startDate)")
                .bind(Long.parseLong(updated.id())).to("id").run();
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().startDate()).isEqualTo(saved.startDate());
    }

    @Test void omitsLegacyVacancyExperienceSkillsWhilePreservingCandidateSkills() throws Exception {
        var candidate = registerInterested(fixture("candidate"));
        var vacancy = vacancies.create(fixture("vacancy"));
        long experienceId = Long.parseLong(candidate.resume().experiences().getFirst().id());
        client.query("""
                MATCH (v:VACANCY)-[r:NEEDS_EXPERIENCE]->(), (e:EXPERIENCE)
                WHERE id(v)=$vacancy AND id(e)=$experience
                DELETE r CREATE (v)-[:NEEDS_EXPERIENCE {required:true}]->(e)
                """).bindAll(Map.of("vacancy", vacancy.id(), "experience", experienceId)).run();

        mvc.perform(get("/api/careermatcher/vagas/" + vacancy.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experienceRequirement.experience.skills").doesNotExist())
                .andExpect(jsonPath("$.skillRequirements[0].skill.name").exists());
        mvc.perform(get("/api/careermatcher/vagas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].experienceRequirement.experience.skills").doesNotExist());
        var graph = context.getBean(GraphCrudRepository.class);
        var single = graph.find(GraphSchema.Kind.VACANCY, vacancy.id());
        var batch = graph.findByIds(GraphSchema.Kind.VACANCY, List.of(vacancy.id()), 10).getFirst();
        assertThat(batch).isEqualTo(single);
        assertThat(batch.path("experienceRequirement").path("experience").has("skills")).isFalse();

        mvc.perform(patch("/api/careermatcher/vagas/" + vacancy.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experienceRequirement\":{\"experience\":{\"durationMonths\":36}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experienceRequirement.experience.durationMonths").value(36))
                .andExpect(jsonPath("$.experienceRequirement.experience.skills").doesNotExist())
                .andExpect(jsonPath("$.skillRequirements[0].skill.name").exists());
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().skills())
                .isEqualTo(candidate.resume().experiences().getFirst().skills());
        assertThat(candidates.findCandidate(candidate.id()).resume().experiences().getFirst().durationMonths())
                .isEqualTo(24);
        assertThat(count("MATCH (:VACANCY)-[:NEEDS_EXPERIENCE]->(:EXPERIENCE)-[:DEVELOPS_SKILL]->() RETURN count(*) AS count"))
                .isZero();
    }

    @Test void rejectsVacancyExperienceSkillsThroughHttpOnCreateAndPatch() throws Exception {
        ObjectNode input = fixture("vacancy");
        ((ObjectNode) input.path("experienceRequirement").path("experience"))
                .set("skills", mapper.readTree("[{\"weight\":1,\"skill\":{\"name\":\"Java\"}}]"));
        mvc.perform(post("/api/careermatcher/vagas").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isBadRequest());
        assertThat(vacancies.findAll()).isEmpty();
        var vacancy = vacancies.create(fixture("vacancy"));
        mvc.perform(patch("/api/careermatcher/vagas/" + vacancy.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(input.toString()))
                .andExpect(status().isBadRequest());
        assertThat(vacancies.find(vacancy.id())).isEqualTo(vacancy);
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

    @Test void managesCompanyAboutAndPublishesVacancyInformation() throws Exception {
        mvc.perform(get("/api/admin/company"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"))
                .andExpect(jsonPath("$.about").doesNotExist());
        mvc.perform(patch("/api/admin/company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"about\":\"<p>Valores <strong>claros</strong><script>bad()</script></p>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.about").value("<p>Valores <strong>claros</strong></p>"));

        ObjectNode input = fixture("vacancy").put("additionalInfo", "<p>Benefícios e horário flexível.</p>");
        var vacancy = vacancies.create(input);
        mvc.perform(get("/api/careermatcher/vagas/" + vacancy.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.additionalInfo").value("<p>Benefícios e horário flexível.</p>"))
                .andExpect(jsonPath("$.companyAbout").value("<p>Valores <strong>claros</strong></p>"));
        mvc.perform(patch("/api/admin/company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"about\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.about").doesNotExist());
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
        assertThat(vacancyData.path("additionalInfo").asText()).isEqualTo("Benefícios extraídos");
        var saved = api.perform(post("/api/careermatcher/vagas").contentType(MediaType.APPLICATION_JSON).content(vacancyData.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.additionalInfo").value("Benefícios extraídos"))
                .andReturn();
        long id = mapper.readTree(saved.getResponse().getContentAsString()).path("id").asLong();
        assertThat(vacancies.find(id).additionalInfo()).isEqualTo("Benefícios extraídos");
        provider.verify();
    }

    private br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView registerInterested(ObjectNode input) {
        var candidate = candidates.registerCandidate(input);
        client.query("MATCH (c:CANDIDATE), (company:COMPANY) WHERE id(c)=$candidate AND id(company)=$company "
                + "MERGE (c)-[:`INTERESTED IN`]->(company)")
                .bindAll(Map.of("candidate", candidate.id(), "company", companyId)).run();
        context.getBean(br.com.careermatcher.listings.WorkspaceListings.class).invalidateAfterCommit(companyId,
                br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,
                br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL);
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
