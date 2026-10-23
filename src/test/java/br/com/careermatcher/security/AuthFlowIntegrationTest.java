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
        "spring.neo4j.authentication.password=","spring.mail.username=","spring.mail.password=","auth.issuer=talentengine",
        "auth.allowed-origins=http://localhost:4200"})
@AutoConfigureMockMvc
@Import(AuthFlowIntegrationTest.LocalDatabase.class)
@DirtiesContext
class AuthFlowIntegrationTest {
    private static final String FRONTEND_ORIGIN="http://localhost:4200";
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
    @Test void companyNamesAreReturnedInLoginAndRestoredSession() throws Exception {
        for (String emailAddress : List.of("admin-a@example.com", "recruiter@example.com", "manager@example.com", "admin-b@example.com")) {
            String expected=emailAddress.equals("admin-b@example.com")?"B":"A";
            var response=body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("email",emailAddress,"password",PASSWORD))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.user.companyName").value(expected)));
            String token=response.path("accessToken").asText();
            mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.companyName").value(expected));
            assertThat(decoder.decode(token).getClaims()).doesNotContainKey("companyName");
        }
    }
    @Test void registersUniqueCandidateWithoutCredentialsOnCandidateAndRestrictsOwnProfile() throws Exception {
        var user=body(mvc.perform(post("/api/auth/register/candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana\",\"professionalTitle\":\"Developer\",\"email\":\" ANA@EXAMPLE.COM \",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CANDIDATO"))
                .andExpect(jsonPath("$.companyName").doesNotExist()).andExpect(jsonPath("$.companyId").doesNotExist()).andExpect(jsonPath("$.email").value("ana@example.com")));
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

    @Test void interestPreflightAllowsConfiguredFrontendWithoutAuthentication() throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            var response=mvc.perform(options("/api/me/companies/"+companyA+"/interest")
                            .header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,method)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,"Authorization, Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,FRONTEND_ORIGIN))
                    .andReturn().getResponse();
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).split(",\\s*")).contains(method);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsIgnoringCase("Authorization").containsIgnoringCase("Content-Type");
        }
    }

    @Test void interestRejectsUntrustedOriginsAndUnauthorizedActors() throws Exception {
        registerCandidate("cors@example.com");
        String jwt=login("cors@example.com"),path="/api/me/companies/"+companyA+"/interest";
        mvc.perform(options(path).header(HttpHeaders.ORIGIN,"https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,"Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(put(path).header(HttpHeaders.ORIGIN,"https://untrusted.example")
                        .header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized());
        for (String staff : List.of("admin-a@example.com","manager@example.com","recruiter@example.com"))
            mvc.perform(put(path).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN)
                            .header(HttpHeaders.AUTHORIZATION,"Bearer "+login(staff)))
                    .andExpect(status().isForbidden());
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();
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
        mvc.perform(get("/api/me/companies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("A")).andExpect(jsonPath("$.content[0].interested").value(false));
        mvc.perform(get("/api/me/companies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt).param("size","1").param("page","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("B"));
        for (String path : List.of("/api/me/vacancies", "/api/me/companies")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt).param("size","0")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isNotFound());
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isForbidden());
        mvc.perform(put("/api/me/companies/9999999/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isNotFound());
        for (long company : List.of(companyA,companyA))
            mvc.perform(put("/api/me/companies/"+company+"/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isEqualTo(1);
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("Java"))
                .andExpect(jsonPath("$.content[0].companyName").value("A"));
        mvc.perform(get("/api/careermatcher/candidatos/page").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidate));
        mvc.perform(get("/api/careermatcher/candidatos/page").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminB))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/me/companies/"+companyB+"/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt))
                .andExpect(status().isNoContent());
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isEqualTo(2);
        mvc.perform(get("/api/me/companies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].interested").value(true)).andExpect(jsonPath("$.content[1].interested").value(true));
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt).param("search","curitiba"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].role").value("Python"))
                .andExpect(jsonPath("$.content[0].companyName").value("B"));
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt).param("sort","role").param("direction","desc").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].role").value("Python"))
                .andExpect(jsonPath("$.content[0].companyName").value("B"));
        for (String staff : List.of(adminA, adminB, login("manager@example.com"), login("recruiter@example.com")))
            mvc.perform(get("/api/careermatcher/candidatos/page").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+staff).param("search","Ana"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(delete("/api/careermatcher/candidatos/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isNoContent());
        mvc.perform(get("/api/careermatcher/candidato/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isNotFound());
        mvc.perform(get("/api/careermatcher/candidatos").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminB)).andExpect(status().isOk());
        mvc.perform(get("/api/me/candidate").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].role").value("Python"));
        for (int i=0;i<2;i++) mvc.perform(delete("/api/me/companies/"+companyB+"/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/vacancies").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/careermatcher/candidatos/page").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(put("/api/me/companies/"+companyA+"/interest").header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+jwt)).andExpect(status().isNoContent());
        mvc.perform(get("/api/careermatcher/candidatos/"+candidate).header(HttpHeaders.ORIGIN,FRONTEND_ORIGIN).header("Authorization","Bearer "+adminA)).andExpect(status().isOk());
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

    @Test void applyingToPublicVacancyRegistersSalaryAndAddsCandidateToPublishingCompanyOnly() throws Exception {
        var user = registerCandidate("applicant@example.com");
        String jwt = login("applicant@example.com");
        long vacancy = publishedVacancy(companyA);
        String path = "/api/me/vacancies/" + vacancy + "/applications";
        mvc.perform(get("/api/careermatcher/vagas/" + vacancy)).andExpect(status().isOk());
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();

        for (double income : List.of(5500.75, 6200.50)) {
            mvc.perform(post(path).header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("income", income))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vacancyId").value(vacancy))
                    .andExpect(jsonPath("$.companyId").value(companyA))
                    .andExpect(jsonPath("$.companyName").value("A"))
                    .andExpect(jsonPath("$.income").value(income))
                    .andExpect(jsonPath("$.message").value("Parabéns, sua candidatura foi enviada com sucesso a A."));
        }
        var rows = client.query("""
                MATCH (c:CANDIDATE)-[r:`APPLIED TO`]->(v:VACANCY)
                RETURN id(c) AS candidate, id(v) AS vacancy, r.income AS income
                """).fetch().all();
        assertThat(rows).containsExactly(Map.of("candidate", user.path("candidateId").asLong(),
                "vacancy", vacancy, "income", 6200.50));
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isEqualTo(1);
        mvc.perform(get("/api/careermatcher/candidatos/page").header("Authorization", "Bearer " + login("admin-a@example.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(user.path("candidateId").asLong()));
        mvc.perform(get("/api/careermatcher/candidatos/page").header("Authorization", "Bearer " + login("admin-b@example.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        long secondVacancy = publishedVacancy(companyA);
        mvc.perform(post("/api/me/vacancies/" + secondVacancy + "/applications").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"income\":7000}"))
                .andExpect(status().isOk());
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isEqualTo(2);
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isEqualTo(1);
    }

    @Test void applicationRequiresValidSalaryAndPublishedVacancyWithoutPartialWrites() throws Exception {
        registerCandidate("salary@example.com");
        String jwt = login("salary@example.com");
        long vacancy = publishedVacancy(companyA);
        String path = "/api/me/vacancies/" + vacancy + "/applications";
        for (String payload : List.of("{}", "{\"income\":null}", "{\"income\":0}", "{\"income\":-1}",
                "{\"income\":12.345}", "{\"income\":1e309}", "{\"income\":\"NaN\"}", "{\"income\":\"abc\"}",
                "{\"income\":\"5.000,00\"}", "{\"income\":true}")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        long unpublished = client.query("CREATE (v:VACANCY {role:'Unpublished'}) RETURN id(v) AS id")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("id").asLong()).one().orElseThrow();
        for (long id : List.of(unpublished, Long.MAX_VALUE)) {
            mvc.perform(post("/api/me/vacancies/" + id + "/applications").header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"income\":5000}"))
                    .andExpect(status().isNotFound());
        }
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isZero();
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();
    }

    @Test void applicationRequiresActiveCandidateSessionAndSupportsFrontendPreflight() throws Exception {
        registerCandidate("session@example.com");
        String jwt = login("session@example.com");
        long vacancy = publishedVacancy(companyA);
        String path = "/api/me/vacancies/" + vacancy + "/applications";
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"income\":5000}"))
                .andExpect(status().isUnauthorized());
        for (String address : List.of("admin-a@example.com", "manager@example.com", "recruiter@example.com")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + login(address))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"income\":5000}"))
                    .andExpect(status().isForbidden());
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(3601));
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"income\":5000}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/careermatcher/vagas/" + vacancy).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isZero();
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();
    }

    @Test void concurrentApplicationsReuseExistingInterestAndCannotTargetAnotherCandidateOrCompany() throws Exception {
        var first = registerCandidate("apply-first@example.com");
        var second = registerCandidate("apply-second@example.com");
        String jwt = login("apply-first@example.com");
        long vacancy = publishedVacancy(companyA);
        mvc.perform(put("/api/me/companies/" + companyA + "/interest").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Integer> apply = () -> mvc.perform(post("/api/me/vacancies/" + vacancy + "/applications")
                            .header("Authorization", "Bearer " + jwt)
                            .param("candidateId", second.path("candidateId").asText()).param("companyId", Long.toString(companyB))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"income\":5500.75}"))
                    .andReturn().getResponse().getStatus();
            for (var result : pool.invokeAll(List.of(apply, apply, apply, apply))) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(client.query("MATCH (c:CANDIDATE)-[r:`APPLIED TO`]->() RETURN id(c) AS candidate, count(r) AS total")
                .fetch().all()).containsExactly(Map.of("candidate", first.path("candidateId").asLong(), "total", 1L));
        assertThat(client.query("MATCH (c:CANDIDATE)-[r:`INTERESTED IN`]->(company) RETURN id(c) AS candidate, id(company) AS company, count(r) AS total")
                .fetch().all()).containsExactly(Map.of("candidate", first.path("candidateId").asLong(), "company", companyA, "total", 1L));
    }

    @Test void applicationStatusUsesOnlyCurrentCandidateAndSelectedVacancyWithoutChangingGraph() throws Exception {
        var first = registerCandidate("status-first@example.com");
        String jwt = login("status-first@example.com");
        long vacancy = publishedVacancy(companyA);
        long otherVacancy = publishedVacancy(companyA);
        String path = "/api/me/vacancies/" + vacancy + "/application-status";
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(content().json("{\"applied\":false}"));
        mvc.perform(put("/api/me/companies/" + companyA + "/interest").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isZero();
        mvc.perform(post("/api/me/vacancies/" + vacancy + "/applications").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"income\":5500.75}"))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(content().json("{\"applied\":true}"));
        mvc.perform(get("/api/me/vacancies/" + otherVacancy + "/application-status")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));

        registerCandidate("status-second@example.com");
        String secondJwt = login("status-second@example.com");
        mvc.perform(get(path).header("Authorization", "Bearer " + secondJwt)
                        .param("candidateId", first.path("candidateId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        // Membership can be removed independently; the existing application still counts.
        mvc.perform(delete("/api/me/companies/" + companyA + "/interest").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isEqualTo(1);
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();
    }

    @Test void applicationStatusRequiresCandidateSessionAndPublishedVacancy() throws Exception {
        registerCandidate("status-access@example.com");
        String jwt = login("status-access@example.com");
        long vacancy = publishedVacancy(companyA);
        String path = "/api/me/vacancies/" + vacancy + "/application-status";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String address : List.of("admin-a@example.com", "manager@example.com", "recruiter@example.com"))
            mvc.perform(get(path).header("Authorization", "Bearer " + login(address)))
                    .andExpect(status().isForbidden());
        long unpublished = client.query("CREATE (v:VACANCY) RETURN id(v) AS id")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("id").asLong()).one().orElseThrow();
        for (long id : List.of(unpublished, Long.MAX_VALUE))
            mvc.perform(get("/api/me/vacancies/" + id + "/application-status").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isNotFound());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3601));
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
        assertThat(count("MATCH ()-[r:`APPLIED TO`]->() RETURN count(r) AS total")).isZero();
        assertThat(count("MATCH ()-[r:`INTERESTED IN`]->() RETURN count(r) AS total")).isZero();
    }

    private long publishedVacancy(long companyId) {
        return client.query("""
                MATCH (company:COMPANY) WHERE id(company)=$companyId
                CREATE (company)-[:PUBLISHES]->(v:VACANCY {role:'Developer', city:'Recife', seniority:'PLENO', modalidade:'REMOTO'})
                RETURN id(v) AS id
                """).bind(companyId).to("companyId").fetchAs(Long.class)
                .mappedBy((t, r) -> r.get("id").asLong()).one().orElseThrow();
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
