package br.com.careermatcher.matching;

import br.com.careermatcher.controllers.TalentEngineController;
import br.com.careermatcher.DTOs.matching.*;
import br.com.careermatcher.enums.Role;
import br.com.careermatcher.interfaces.MatchingService;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchingSubmissionServiceTest {
    private final CurrentActor actor = new CurrentActor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphCrudRepository repository = mock(GraphCrudRepository.class);
    private final MatchingService matching = mock(MatchingService.class);
    private final MatchingSelectionValidator selections = new MatchingSelectionValidator(repository, mapper, actor, 200);
    private final ThreadPoolTaskExecutor executor = new MatchingAsyncConfiguration().matchingExecutor(1, 1);
    private final MatchingSubmissionService submissions = new MatchingSubmissionService(executor, selections, matching, actor);
    private final MatchingSelectionValidator.SelectedIds ids = new MatchingSelectionValidator.SelectedIds(List.of(1L), List.of(2L));
    private final MatchingRunResponse result = new MatchingRunResponse("RUN-test", Instant.EPOCH,
            new AllocationResponse(List.of(), Set.of(), 0));

    @BeforeEach void start() {
        executor.initialize();
        when(matching.generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble())).thenReturn(result);
    }
    @AfterEach void stop() throws InterruptedException {
        SecurityContextHolder.clearContext();
        executor.shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    private void authenticate(long companyId) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthUser("user-" + companyId, "User", "user@example.com", Role.GERENTE, companyId, null, true, null),
                null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @Test void postReturnsWhileWorkerIsBlockedAndRejectsOverflowWithoutCallerExecution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(2);
        when(repository.findByIds(eq(VACANCY), anyList(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(matching.generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble())).thenAnswer(call -> {
            completed.countDown();
            return result;
        });
        authenticate(10);
        var controller = new TalentEngineController(submissions, selections, new LatestExecutionStore(), actor::requireCompany);
        try {
            var response = controller.run(mapper.readTree("{\"candidateIds\":[1],\"vacancyIds\":[2]}"), 10, .65, 5);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNull();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(matching);
            submissions.submit(ids, 10, .65, 5);
            assertThatThrownBy(() -> submissions.submit(ids, 10, .65, 5))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        } finally {
            release.countDown();
        }
        assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test void capturesEachCompanyAndClearsWorkerContextAfterFailureAndSuccess() throws Exception {
        var observed = new CopyOnWriteArrayList<Long>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(repository.findByIds(eq(VACANCY), anyList(), anyInt())).thenAnswer(call -> {
            long companyId = actor.requireCompany();
            observed.add(companyId);
            if (companyId == 10) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return List.of();
        });
        var completed = new CountDownLatch(1);
        when(matching.generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble())).thenAnswer(call -> {
            observed.add(actor.requireCompany());
            completed.countDown();
            return result;
        });
        try {
            authenticate(10);
            submissions.submit(ids, 10, .65, 5);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            authenticate(20);
            submissions.submit(ids, 10, .65, 5);
            // Simulate HTTP completion/logout before queued work starts.
            SecurityContextHolder.clearContext();
            release.countDown();
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication())
                    .get(10, TimeUnit.SECONDS)).isNull();
            assertThat(observed).containsExactly(10L, 20L, 20L);
            verify(repository, times(1)).findByIds(eq(CANDIDATE), anyList(), anyInt());
            verify(matching, times(1)).generateShortlists(anyList(), anyList(), anyInt(), anyDouble(), anyDouble());
        } finally {
            release.countDown();
        }
    }

    @Test void stoppedExecutorRejectsNewSubmissions() {
        authenticate(10);
        executor.shutdown();
        assertThatThrownBy(() -> submissions.submit(ids, 10, .65, 5))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(repository, matching);
    }

    @Test void missingAuthenticationCannotSubmit() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> submissions.submit(ids, 10, .65, 5))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(repository, matching);
    }
}
