package br.com.careermatcher.security;

import br.com.careermatcher.interfaces.EmailService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.*;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.neo4j.uri=bolt://localhost:1","spring.neo4j.authentication.username=",
        "spring.neo4j.authentication.password=","spring.mail.username=","spring.mail.password=","auth.issuer=talentengine"})
@AutoConfigureMockMvc
@Import(AuthFlowIntegrationTest.LocalDatabase.class)
@DirtiesContext
class AuthFlowIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-02T12:00:00Z");
    private static final String PASSWORD="Senha-segura-123";
    private static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final Path KEYS=keys();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Neo4jClient client;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor matchingExecutor;
    @MockitoBean EmailService email;
    @MockitoBean Clock clock;
    private long companyA,companyB;
    private final AtomicReference<String> inviteToken=new AtomicReference<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.private-key",()->KEYS.resolve("private.pem").toUri().toString());
        registry.add("auth.public-key",()->KEYS.resolve("public.pem").toUri().toString());
    }
    private static Path keys() {
        try {
            Path directory=Files.createTempDirectory("talentengine-auth-test-");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
            Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",pair.getPublic().getEncoded()));
            return directory;
        } catch(Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static String pem(String type,byte[] value) { return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder().encodeToString(value)+"\n-----END "+type+"-----\n"; }
    @AfterAll static void cleanKeys() throws Exception {
        Files.deleteIfExists(KEYS.resolve("private.pem")); Files.deleteIfExists(KEYS.resolve("public.pem")); Files.deleteIfExists(KEYS);
    }
    @BeforeEach void prepare() {
        client.query("MATCH (n) DETACH DELETE n").run();
        when(clock.instant()).thenReturn(NOW);
        companyA=company("A"); companyB=company("B");
        seedStaff("admin-a","admin-a@example.com","ADMINISTRADOR",companyA);
        seedStaff("admin-b","admin-b@example.com","ADMINISTRADOR",companyB);
        seedStaff("recruiter","recruiter@example.com","RECRUTADOR",companyA);
        seedStaff("manager","manager@example.com","GERENTE",companyA);
        inviteToken.set(null);
        doAnswer(call->{ String body=call.getArgument(2); inviteToken.set(body.substring(body.indexOf("#token=")+7)); return null; })
                .when(email).sendEmail(anyString(),anyString(),anyString());
    }
    @Test void registersUniqueCandidateWithoutCredentialsOnCandidateAndRestrictsOwnProfile() throws Exception {
        var user=body(mvc.perform(post("/api/auth/register/candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana\",\"professionalTitle\":\"Developer\",\"email\":\" ANA@EXAMPLE.COM \",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CANDIDATO"))
                .andExpect(jsonPath("$.companyId").doesNotExist()).andExpect(jsonPath("$.email").value("ana@example.com")));
        String jwt=login("ana@example.com");
        mvc.perform(get("/api/me/candidate").header("Authorization","Bearer "+jwt)).andExpect(status().isOk()).andExpect(jsonPath("$.professionalTitle").value("Developer"));
        mvc.perform(patch("/api/me/candidate").header("Authorization","Bearer "+jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana Maria\",\"resume\":{\"skills\":[]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("ana@example.com"));
        mvc.perform(patch("/api/me/candidate").header("Authorization","Bearer "+jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"other@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+jwt)).andExpect(status().isForbidden());
        mvc.perform(get("/api/careermatcher/candidatos").header("Authorization","Bearer "+jwt)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register/candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Duplicate\",\"professionalTitle\":\"Dev\",\"email\":\"ANA@example.com\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isConflict());
        assertThat(count("MATCH (c:CANDIDATE) RETURN count(c) AS total")).isEqualTo(1);
        var data=client.query("MATCH (u:USER {id:$id})-[:IS]->(c) RETURN u.password AS hash,properties(c) AS candidate,u.name AS name")
                .bind(user.path("id").asText()).to("id").fetch().one().orElseThrow();
        assertThat(data.get("hash").toString()).startsWith("$2");
        assertThat(data.get("candidate").toString()).doesNotContain("password","email");
        assertThat(data.get("name")).isEqualTo("Ana Maria");
        String admin=login("admin-a@example.com");
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/careermatcher/candidatos/"+user.path("candidateId").asLong()).header("Authorization","Bearer "+admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
    }
    @Test void signsOneHourTokensAndRejectsExpiredForgedAndReportTokens() throws Exception {
        String jwt=login("admin-a@example.com");
        Jwt parsed=decoder.decode(jwt);
        assertThat(parsed.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(parsed.getClaimAsString("role")).isEqualTo("ADMINISTRADOR");
        assertThat(parsed.getClaimAsString("candidateId")).isNull();
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        String[] parts=jwt.split("\\.");
        String altered=parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"admin-b\"}".getBytes())+"."+parts[2];
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+altered)).andExpect(status().isUnauthorized());
        var claims=JwtClaimsSet.builder().issuer("talentengine").subject("admin-a").issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).audience(List.of("talentengine-reports")).claim("role","ADMINISTRADOR").claim("companyId",companyA).build();
        String service=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+service)).andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized());
    }
    @Test void inviteIsScopedSingleUseAndDeletionImmediatelyRevokesLogin() throws Exception {
        String admin=login("admin-a@example.com");
        JsonNode invited=invite(admin,"new@example.com","GERENTE");
        String token=inviteToken.get(),id=invited.path("user").path("id").asText();
        assertThat(invited.path("emailSent").asBoolean()).isTrue();
        var pending=client.query("MATCH (u:USER {id:$id}) RETURN properties(u) AS properties").bind(id).to("id").fetch().one().orElseThrow();
        assertThat(pending.toString()).doesNotContain(token,"password=","createdAt=","name=");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@example.com\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+login("admin-b@example.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete("/api/admin/users/"+id).header("Authorization","Bearer "+login("admin-b@example.com"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/invitations/complete").contentType(MediaType.APPLICATION_JSON).content(completeBody(token))).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/invitations/complete").contentType(MediaType.APPLICATION_JSON).content(completeBody(token))).andExpect(status().isBadRequest());
        String manager=login("new@example.com");
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+manager)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+admin).param("size","1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(delete("/api/admin/users/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+manager)).andExpect(status().isUnauthorized());
    }
    @Test void retryReplacesInviteAndSmtpFailureKeepsPendingUser() throws Exception {
        String admin=login("admin-a@example.com");
        invite(admin,"new@example.com","RECRUTADOR"); String old=inviteToken.get();
        invite(admin,"new@example.com","RECRUTADOR"); String current=inviteToken.get();
        assertThat(current).isNotEqualTo(old);
        mvc.perform(post("/api/auth/invitations/validate").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("token",old)))).andExpect(status().isBadRequest());
        doThrow(new MailSendException("offline")).when(email).sendEmail(anyString(),anyString(),anyString());
        JsonNode failure=invite(admin,"failure@example.com","GERENTE");
        assertThat(failure.path("emailSent").asBoolean()).isFalse();
        assertThat(count("MATCH (u:USER {email:'failure@example.com',cadastroFinalizado:false}) RETURN count(u) AS total")).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        mvc.perform(post("/api/auth/invitations/validate").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("token",current)))).andExpect(status().isBadRequest());
    }
    @Test void staffCannotAccessAnotherCompanyOrCandidatesWithoutInterest() throws Exception {
        String adminA=login("admin-a@example.com"),adminB=login("admin-b@example.com"),recruiter=login("recruiter@example.com");
        long vacancy=body(mvc.perform(post("/api/careermatcher/vagas").header("Authorization","Bearer "+adminA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Dev\",\"city\":\"Rio\",\"seniority\":\"PLENO\",\"modalidade\":\"REMOTO\"}")).andExpect(status().isCreated())).path("id").asLong();
        mvc.perform(get("/api/careermatcher/vagas/page").header("Authorization","Bearer "+adminB)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/careermatcher/vagas/"+vacancy).header("Authorization","Bearer "+adminB)).andExpect(status().isOk());
        mvc.perform(patch("/api/careermatcher/vagas/"+vacancy).header("Authorization","Bearer "+adminB).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Other\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/careermatcher/vagas/"+vacancy).header("Authorization","Bearer "+adminB)).andExpect(status().isNotFound());
        long candidate=client.query("CREATE (c:CANDIDATE {name:'Global',professionalTitle:'Dev'}) RETURN id(c) AS id")
                .fetchAs(Long.class).mappedBy((t,r)->r.get("id").asLong()).one().orElseThrow();
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header("Authorization","Bearer "+adminB)).andExpect(status().isNotFound());
        mvc.perform(post("/api/matching/run").header("Authorization","Bearer "+adminB).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("candidateIds",List.of(candidate),"vacancyIds",List.of(vacancy)))))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        matchingExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertThat(count("MATCH (e:MATCHING_EXECUTION) RETURN count(e) AS total")).isZero();
        mvc.perform(get("/api/matching/executions/latest").header("Authorization","Bearer "+adminB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+recruiter)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+login("manager@example.com"))).andExpect(status().isForbidden());
    }
    @Test void publishedVacancyIsPublicEvenWithInvalidOrExpiredBearerButOtherRoutesStayProtected() throws Exception {
        String jwt=login("admin-a@example.com");
        long vacancy=body(mvc.perform(post("/api/careermatcher/vagas").header("Authorization","Bearer "+jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Dev\",\"city\":\"Rio\",\"seniority\":\"PLENO\",\"modalidade\":\"REMOTO\",\"skillRequirements\":[{\"weight\":2,\"skill\":{\"name\":\"Java\"}}]}"))
                .andExpect(status().isCreated())).path("id").asLong();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        for (String authorization : List.of("", "Bearer invalid", "Bearer "+jwt)) {
            mvc.perform(get("/api/careermatcher/vagas/"+vacancy).header("Authorization",authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("Dev"))
                    .andExpect(jsonPath("$.skillRequirements[0].skill.name").value("Java"));
            for (String path : List.of("/api/careermatcher/vagas", "/api/careermatcher/vagas/page", "/api/careermatcher/candidatos")) {
                mvc.perform(get(path).header("Authorization",authorization)).andExpect(status().isUnauthorized());
            }
            mvc.perform(patch("/api/careermatcher/vagas/"+vacancy).header("Authorization",authorization)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Other\"}")).andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/careermatcher/vagas/"+vacancy).header("Authorization",authorization)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/careermatcher/vagas").header("Authorization",authorization)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        long orphan=client.query("CREATE (v:VACANCY {role:'Unpublished'}) RETURN id(v) AS id")
                .fetchAs(Long.class).mappedBy((t,r)->r.get("id").asLong()).one().orElseThrow();
        mvc.perform(get("/api/careermatcher/vagas/"+orphan)).andExpect(status().isNotFound());
        mvc.perform(get("/api/careermatcher/vagas/99999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/careermatcher/vagas/"+vacancy).param("source","share")).andExpect(status().isOk());
    }

    @Test void staffRolesCanReadAndDeleteButCannotCreateOrEditCandidates() throws Exception {
        for (String address : List.of("admin-a@example.com", "manager@example.com", "recruiter@example.com")) {
            String jwt=login(address);
            long id=client.query("CREATE (c:CANDIDATE {name:'Candidate',professionalTitle:'Dev'}) RETURN id(c) AS id")
                    .fetchAs(Long.class).mappedBy((t,r)->r.get("id").asLong()).one().orElseThrow();
            mvc.perform(post("/api/careermatcher/candidatos").header("Authorization","Bearer "+jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\",\"professionalTitle\":\"Dev\"}"))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(patch("/api/careermatcher/candidatos/"+id).header("Authorization","Bearer "+jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}")).andExpect(status().isMethodNotAllowed());
            mvc.perform(post("/api/careermatcher/candidato/"+id+"/create/resume").header("Authorization","Bearer "+jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
            mvc.perform(patch("/api/me/candidate").header("Authorization","Bearer "+jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}")).andExpect(status().isForbidden());
            client.query("MATCH (candidate:CANDIDATE), (company:COMPANY) WHERE id(candidate)=$candidate AND id(company)=$company MERGE (candidate)-[:`INTERESTED IN`]->(company)")
                    .bindAll(Map.of("candidate",id,"company",companyA)).run();
            mvc.perform(get("/api/careermatcher/candidatos/"+id).header("Authorization","Bearer "+jwt))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Candidate"));
            mvc.perform(delete("/api/careermatcher/candidatos/"+id).header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        }
    }

    @Test void interestsControlCompanyCatalogVacanciesAndStaffAccessWithoutDeletingAccount() throws Exception {
        var user=registerCandidate("interest@example.com");
        long candidate=user.path("candidateId").asLong();
        String jwt=login("interest@example.com"), adminA=login("admin-a@example.com"), adminB=login("admin-b@example.com");
        client.query("""
                MATCH (a:COMPANY), (b:COMPANY) WHERE id(a)=$a AND id(b)=$b
                CREATE (a)-[:PUBLISHES]->(:VACANCY {role:'Java', city:'Recife', seniority:'PLENO', modalidade:'REMOTO'})
                CREATE (b)-[:PUBLISHES]->(:VACANCY {role:'Python', city:'Curitiba', seniority:'JUNIOR', modalidade:'HIBRIDO'})
                """).bindAll(Map.of("a",companyA,"b",companyB)).run();
        mvc.perform(get("/api/me/companies").header("Authorization","Bearer "+jwt).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("A")).andExpect(jsonPath("$.content[0].interested").value(false));
        mvc.perform(get("/api/me/companies").header("Authorization","Bearer "+jwt).param("size","1").param("page","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("B"));
        for (String path : List.of("/api/me/vacancies", "/api/me/companies")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer "+adminA)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization","Bearer "+jwt).param("size","0")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/me/vacancies").header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header("Authorization","Bearer "+adminA)).andExpect(status().isNotFound());
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+adminA)).andExpect(status().isForbidden());
        mvc.perform(put("/api/me/companies/9999999/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNotFound());
        for (long company : List.of(companyA,companyA,companyB))
            mvc.perform(put("/api/me/companies/"+company+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isEqualTo(2);
        mvc.perform(get("/api/me/companies").header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].interested").value(true)).andExpect(jsonPath("$.content[1].interested").value(true));
        mvc.perform(get("/api/me/vacancies").header("Authorization","Bearer "+jwt).param("search","curitiba"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].role").value("Python"));
        mvc.perform(get("/api/me/vacancies").header("Authorization","Bearer "+jwt).param("sort","role").param("direction","desc").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].role").value("Python"));
        for (String staff : List.of(adminA, adminB, login("manager@example.com"), login("recruiter@example.com")))
            mvc.perform(get("/api/careermatcher/candidatos/page").header("Authorization","Bearer "+staff).param("search","Ana"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(delete("/api/careermatcher/candidatos/"+candidate).header("Authorization","Bearer "+adminA)).andExpect(status().isNoContent());
        mvc.perform(get("/api/careermatcher/candidato/"+candidate).header("Authorization","Bearer "+adminA)).andExpect(status().isNotFound());
        mvc.perform(get("/api/careermatcher/candidatos").header("Authorization","Bearer "+adminA)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header("Authorization","Bearer "+adminB)).andExpect(status().isOk());
        mvc.perform(get("/api/me/candidate").header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
        mvc.perform(get("/api/me/vacancies").header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].role").value("Python"));
        for (int i=0;i<2;i++) mvc.perform(delete("/api/me/companies/"+companyB+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header("Authorization","Bearer "+adminA)).andExpect(status().isOk());
        assertThat(login("interest@example.com")).isNotBlank();
    }

    @Test void concurrentInterestRequestsCreateOnlyOneRelationshipAndCannotTargetAnotherCandidate() throws Exception {
        var first=registerCandidate("first@example.com");
        var second=registerCandidate("second@example.com");
        String jwt=login("first@example.com");
        try (var pool=Executors.newFixedThreadPool(4)) {
            Callable<Integer> add=()->mvc.perform(put("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+jwt)
                    .param("candidateId",second.path("candidateId").asText())).andReturn().getResponse().getStatus();
            for (var result : pool.invokeAll(List.of(add,add,add,add))) assertThat(result.get()).isEqualTo(204);
        }
        var rows=client.query("MATCH (c:CANDIDATE)-[r:`INTERESTED IN`]->() RETURN id(c) AS id,count(r) AS total").fetch().all();
        assertThat(rows).containsExactly(Map.of("id",first.path("candidateId").asLong(),"total",1L));
    }

    @Test void matchingRejectsPreviouslySelectedCandidateAfterInterestRemoval() throws Exception {
        var user=registerCandidate("matching@example.com");
        String jwt=login("matching@example.com"),admin=login("admin-a@example.com");
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        long vacancy=body(mvc.perform(post("/api/careermatcher/vagas").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Dev\",\"city\":\"Rio\",\"seniority\":\"PLENO\",\"modalidade\":\"REMOTO\"}")).andExpect(status().isCreated())).path("id").asLong();
        mvc.perform(delete("/api/me/companies/"+companyA+"/interest").header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(post("/api/matching/run").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("candidateIds",List.of(user.path("candidateId").asLong()),"vacancyIds",List.of(vacancy)))))
                .andExpect(status().isAccepted());
        matchingExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertThat(count("MATCH (e:MATCHING_EXECUTION) RETURN count(e) AS total")).isZero();
    }

    private JsonNode registerCandidate(String email) throws Exception {
        return body(mvc.perform(post("/api/auth/register/candidate").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name","Ana", "professionalTitle","Developer", "email",email,"password",PASSWORD))))
                .andExpect(status().isCreated()));
    }

    @Test void concurrentInvitationCompletionSucceedsOnlyOnce() throws Exception {
        invite(login("admin-a@example.com"),"race@example.com","GERENTE");
        String request=completeBody(inviteToken.get());
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> complete=()->mvc.perform(post("/api/auth/invitations/complete").contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus();
            var outcomes=pool.invokeAll(List.of(complete,complete));
            assertThat(List.of(outcomes.get(0).get(),outcomes.get(1).get())).containsExactlyInAnyOrder(204,400);
        }
    }
    private JsonNode invite(String jwt,String address,String role) throws Exception {
        return body(mvc.perform(post("/api/admin/users").header("Authorization","Bearer "+jwt).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",address,"role",role)))).andExpect(status().isOk()));
    }
    private String completeBody(String token) throws Exception { return mapper.writeValueAsString(Map.of("token",token,"name","Novo usuário","password",PASSWORD)); }
    private String login(String email) throws Exception {
        return body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password",PASSWORD)))).andExpect(status().isOk())).path("accessToken").asText();
    }
    private JsonNode body(ResultActions action) throws Exception { return mapper.readTree(action.andReturn().getResponse().getContentAsString()); }
    private long company(String name) { return client.query("CREATE (c:COMPANY {name:$name}) RETURN id(c) AS id").bind(name).to("name").fetchAs(Long.class).mappedBy((t,r)->r.get("id").asLong()).one().orElseThrow(); }
    private long count(String query) { return client.query(query).fetchAs(Long.class).mappedBy((t,r)->r.get("total").asLong()).one().orElseThrow(); }
    private void seedStaff(String id,String email,String role,long company) {
        client.query("MATCH (c:COMPANY) WHERE id(c)=$company CREATE (:USER {id:$id,name:$id,email:$email,role:$role,password:$hash,cadastroFinalizado:true,createdAt:$now})-[:WORKS_FOR]->(c)")
                .bindAll(Map.of("company",company,"id",id,"email",email,"role",role,"hash",HASH,"now",NOW.toString())).run();
    }
    @TestConfiguration(proxyBeanMethods=false) static class LocalDatabase {
        @Bean(destroyMethod="close") Neo4j testDatabase() { return Neo4jBuilders.newInProcessBuilder().withDisabledServer().withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory,32L*1024*1024).build(); }
        @Bean(destroyMethod="close") Driver driver(Neo4j database) { return GraphDatabase.driver(database.boltURI(),AuthTokens.none()); }
    }
}
