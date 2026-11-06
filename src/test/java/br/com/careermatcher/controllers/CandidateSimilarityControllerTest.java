package br.com.careermatcher.controllers;

import br.com.careermatcher.enums.Role;
import br.com.careermatcher.security.*;
import br.com.careermatcher.similarity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CandidateSimilarityControllerTest {
    private static final String URL = "/api/careermatcher/candidatos/similares";
    private final SimilaritySnapshotRepository repository = mock(SimilaritySnapshotRepository.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = standaloneSetup(new CandidateSimilarityController(new CandidateSimilarityService(
                new CurrentActor(), repository, new SimilaritySettings())))
                .setControllerAdvice(new CrudExceptionHandler()).build();
        when(repository.load()).thenReturn(new SimilaritySnapshot(List.of(
                new SimilaritySnapshot.Profile(123,"Reference","Dev"),
                new SimilaritySnapshot.Profile(124,"Similar","Dev")), List.of(
                new SimilaritySnapshot.Evidence(123,1,"Java",SimilaritySnapshot.SourceType.EXPERIENCE,2),
                new SimilaritySnapshot.Evidence(124,1,"Java",SimilaritySnapshot.SourceType.EXPERIENCE,3))));
    }
    void login(Role role) {
        var user = new AuthUser("staff","Staff","staff@test.local",role,7L,null,true,"Company");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @ParameterizedTest @EnumSource(value=Role.class,names={"ADMINISTRADOR","RECRUTADOR","GERENTE"})
    void acceptsRootDtoAndReturnsRankedPublicCandidates(Role role) throws Exception {
        login(role);
        mvc.perform(post(URL).contentType("application/json").content("{\"id\":\"123\",\"name\":\"FORGED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.referenceCandidateId").value("123"))
                .andExpect(jsonPath("$.requestedK").value(10)).andExpect(jsonPath("$.candidates[0].id").value("124"))
                .andExpect(jsonPath("$.candidates[0].name").value("Similar"))
                .andExpect(jsonPath("$.candidates[0].password").doesNotExist());
        mvc.perform(post(URL+"?k=1").contentType("application/json").content("{\"id\":\"123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedK").value(1));
    }
    @Test void rejectsUnauthenticatedAndCandidateUsers() throws Exception {
        mvc.perform(post(URL).contentType("application/json").content("{\"id\":\"123\"}"))
                .andExpect(status().isUnauthorized());
        login(Role.CANDIDATO);
        mvc.perform(post(URL).contentType("application/json").content("{\"id\":\"123\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }
    @Test void validatesBodyAndQueryWithoutReadingGraph() throws Exception {
        login(Role.GERENTE);
        for(String body:List.of("", "null", "{", "{}", "{\"id\":\"-1\"}", "{\"candidate\":{\"id\":\"123\"}}"))
            mvc.perform(post(URL).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(String k:List.of("0","101","-1","abc"))
            mvc.perform(post(URL+"?k="+k).contentType("application/json").content("{\"id\":\"123\"}"))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void returns404ForInvisibleReferenceAndEmptyReasonForNoSkills() throws Exception {
        login(Role.RECRUTADOR);
        mvc.perform(post(URL).contentType("application/json").content("{\"id\":\"999\"}"))
                .andExpect(status().isNotFound());
        when(repository.load()).thenReturn(new SimilaritySnapshot(List.of(new SimilaritySnapshot.Profile(123,"A","Dev")),List.of()));
        mvc.perform(post(URL).contentType("application/json").content("{\"id\":\"123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("NO_SKILLS"));
    }
}
