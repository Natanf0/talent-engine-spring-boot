package br.com.careermatcher.controllers;

import br.com.careermatcher.interfaces.MatchingService;
import br.com.careermatcher.matching.*;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TalentEngineControllerTest {
    private final GraphCrudRepository repository = mock(GraphCrudRepository.class);
    private final MatchingService matching = mock(MatchingService.class);
    private final CurrentActor actor = mock(CurrentActor.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MatchingSelectionValidator validator = new MatchingSelectionValidator(repository, mapper, actor, 200);
    private MockMvc mvc;
    private static final String BODY = "{\"candidateIds\":[1,2],\"vacancyIds\":[10]}";

    private MockMvc controller(TaskExecutor executor) {
        return standaloneSetup(new TalentEngineController(
                new MatchingSubmissionService(executor, validator, matching, actor),
                validator, mock(LatestExecutionStore.class), () -> 1)).build();
    }
    @BeforeEach void setup() {
        when(actor.requireCompany()).thenReturn(1L);
        when(matching.generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(new br.com.careermatcher.DTOs.matching.MatchingRunResponse("RUN-test", java.time.Instant.EPOCH,
                        new br.com.careermatcher.DTOs.matching.AllocationResponse(List.of(), Set.of(), 0)));
        mvc = controller(tasks::add);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/matching/run", "/api/careermatcher/run"})
    void acceptsWithoutReadingParticipantsOrWaitingForWork(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        assertThat(tasks).hasSize(1);
        verifyNoInteractions(repository, matching);
        tasks.getFirst().run();
        verify(repository).findByIds(br.com.careermatcher.crud.GraphSchema.Kind.VACANCY, List.of(10L), 200);
        verify(repository).findByIds(br.com.careermatcher.crud.GraphSchema.Kind.CANDIDATE, List.of(1L, 2L), 200);
        verify(matching).generateShortlists(anyList(), anyList(), eq(10), eq(.65), eq(5.0));
    }

    @Test void acceptsCustomParameters() throws Exception {
        mvc.perform(post("/api/matching/run?k=3&toleranceFactor=0.8&absoluteMinScore=7.5")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isAccepted());
        tasks.getFirst().run();
        verify(matching).generateShortlists(anyList(), anyList(), eq(3), eq(.8), eq(7.5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"k=0", "toleranceFactor=0", "toleranceFactor=NaN", "absoluteMinScore=-1"})
    void rejectsInvalidParametersBeforeSubmission(String query) throws Exception {
        mvc.perform(post("/api/matching/run?" + query).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        assertThat(tasks).isEmpty();
        verifyNoInteractions(repository, matching);
    }

    @Test void invalidSelectionDoesNotQueueWork() throws Exception {
        mvc.perform(post("/api/matching/run").contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("[1,2]", "[1,1]"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/matching/run").contentType(MediaType.APPLICATION_JSON)
                .content("{")).andExpect(status().isBadRequest());
        assertThat(tasks).isEmpty();
        verifyNoInteractions(repository, matching);
    }

    @Test void saturationReturns503InsteadOfAccepting() throws Exception {
        controller(task -> { throw new RejectedExecutionException(); })
                .perform(post("/api/matching/run").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(repository, matching);
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotChangeResponseOrEscapeWorker() throws Exception {
        when(matching.generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("storage unavailable"));
        mvc.perform(post("/api/matching/run").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        assertThatCode(() -> tasks.getFirst().run()).doesNotThrowAnyException();
    }

    @Test void getDoesNotRunMatchingAndLatestRemainsUnchanged() throws Exception {
        mvc.perform(get("/api/matching/run")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/matching/executions/latest")).andExpect(status().isNotFound());
        assertThat(tasks).isEmpty();
    }
}
