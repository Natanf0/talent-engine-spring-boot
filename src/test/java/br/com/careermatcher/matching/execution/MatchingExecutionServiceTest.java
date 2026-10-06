package br.com.careermatcher.matching.execution;

import br.com.careermatcher.DTOs.matching.*;
import br.com.careermatcher.matching.LatestExecutionStore;
import br.com.careermatcher.models.*;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchingExecutionServiceTest {
    @TempDir Path directory;
    private final MatchingExecutionRepository repository = mock(MatchingExecutionRepository.class);
    private final ExecutionObjectStorage storage = mock(ExecutionObjectStorage.class);
    private final LatestExecutionStore latest = new LatestExecutionStore();
    private final Instant instant = Instant.parse("2026-09-20T16:06:00Z");
    private final AllocationResponse allocation = new AllocationResponse(List.of(), Set.of("7"), 0);
    private final MatchingRunResponse previous = new MatchingRunResponse("RUN-previous", Instant.EPOCH, allocation);
    private MatchingExecutionService service;
    private CompressedExecutionWriter writer;
    private final List<br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView> candidates = List.of(br.com.careermatcher.matching.ExecutionTestSupport.view(candidate()));

    @BeforeEach void setup() {
        writer = new CompressedExecutionWriter(new ObjectMapper().findAndRegisterModules(),
                List.of(new GzipExecutionCompressor()), "gzip", directory);
        service = new MatchingExecutionService(() -> 1, repository, storage, writer, new ExecutionDocumentMapper(),
                latest, Clock.fixed(instant, ZoneOffset.UTC));
        when(storage.bucket()).thenReturn("private-reports");
        latest.publish(1, previous);
    }

    @Test void retriesSameObjectAndPublishesOnlyAfterCommit() throws Exception {
        var context = service.begin();
        var sent = new AtomicReference<byte[]>();
        var path = new AtomicReference<Path>();
        doAnswer(call -> {
            path.set(call.getArgument(2));
            sent.set(Files.readAllBytes(path.get()));
            assertThat(latest.latest(1).response()).isSameAs(previous);
            throw new IllegalStateException("lost upload acknowledgement");
        }).doAnswer(call -> {
            assertThat((Path) call.getArgument(2)).isEqualTo(path.get());
            assertThat(Files.readAllBytes(call.getArgument(2))).isEqualTo(sent.get());
            return null;
        }).when(storage).put(anyString(), anyString(), any(), anyString());
        when(repository.create(eq(1L), any())).thenAnswer(call -> {
            assertThat(latest.latest(1).response()).isSameAs(previous);
            return call.getArgument(1);
        });
        var response = service.complete(context, allocation, candidates, 23);
        assertThat(response.executionId()).isEqualTo(context.executionId());
        assertThat(response.createdAt()).isEqualTo(instant);
        assertThat(latest.latest(1).response()).isSameAs(response);
        var metadata = ArgumentCaptor.forClass(MatchingExecution.class);
        verify(repository).create(eq(1L), metadata.capture());
        assertThat(metadata.getValue().getCandidateQuantity()).isEqualTo(1);
        assertThat(metadata.getValue().getVacanciesQuantity()).isEqualTo(23);
        assertThat(metadata.getValue().getCompression()).isEqualTo("gzip");
        assertThat(metadata.getValue().getSchemaVersion()).isEqualTo(2);
        verify(storage, times(2)).put(eq("private-reports"),
                eq("companies/1/executions/" + context.executionId() + ".json.gz"), eq(path.get()), eq("application/gzip"));
        var order = inOrder(repository, storage);
        order.verify(repository).requireCompany(1);
        order.verify(storage).bucket();
        order.verify(storage, times(2)).put(anyString(), anyString(), any(), anyString());
        order.verify(repository).create(eq(1L), any());
        assertThat(directory).isEmptyDirectory();
    }

    @Test void twoFailedUploadsReturn503WithoutPublishingMetadata() {
        doThrow(new IllegalStateException("upload")).when(storage).put(anyString(), anyString(), any(), anyString());
        assertUnavailable(() -> service.complete(service.begin(), allocation, candidates, 0));
        verify(storage, times(2)).put(anyString(), anyString(), any(), anyString());
        verify(repository, never()).create(anyLong(), any());
        assertThat(latest.latest(1).response()).isSameAs(previous);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void failedCommitDeletesObjectOnlyAfterConfirmingAbsence() {
        when(repository.create(anyLong(), any())).thenThrow(new IllegalStateException("commit"));
        when(repository.find(anyLong(), anyString())).thenReturn(Optional.empty());
        assertUnavailable(() -> service.complete(service.begin(), allocation, candidates, 0));
        var order = inOrder(repository, storage);
        order.verify(repository).requireCompany(1);
        order.verify(storage).bucket();
        order.verify(storage).put(anyString(), anyString(), any(), anyString());
        order.verify(repository).create(eq(1L), any());
        order.verify(repository).find(eq(1L), anyString());
        order.verify(storage).delete(eq("private-reports"), anyString());
        assertThat(latest.latest(1).response()).isSameAs(previous);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void lostCommitAcknowledgementCanStillCompleteSuccessfully() {
        var committed = new AtomicReference<MatchingExecution>();
        when(repository.create(anyLong(), any())).thenAnswer(call -> {
            committed.set(call.getArgument(1));
            throw new IllegalStateException("ack lost");
        });
        when(repository.find(anyLong(), anyString())).thenAnswer(call -> Optional.of(committed.get()));
        var response = service.complete(service.begin(), allocation, candidates, 0);
        assertThat(latest.latest(1).response()).isSameAs(response);
        verify(storage, never()).delete(anyString(), anyString());
        assertThat(directory).isEmptyDirectory();
    }

    @Test void unknownCommitRetainsObjectAndPreviousLatest() {
        when(repository.create(anyLong(), any())).thenThrow(new IllegalStateException("commit"));
        when(repository.find(anyLong(), anyString())).thenThrow(new IllegalStateException("database unavailable"));
        assertUnavailable(() -> service.complete(service.begin(), allocation, candidates, 0));
        verify(storage, never()).delete(anyString(), anyString());
        assertThat(latest.latest(1).response()).isSameAs(previous);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void conflictingConfirmationDoesNotDeleteObjectOrPublishSuccess() {
        when(repository.create(anyLong(), any())).thenThrow(new IllegalStateException("commit"));
        when(repository.find(anyLong(), anyString())).thenReturn(Optional.of(new MatchingExecution(
                1L, "RUN-other", instant, 0, 1, "private-reports", "different-key", "gzip", 1)));
        assertUnavailable(() -> service.complete(service.begin(), allocation, candidates, 0));
        verify(storage, never()).delete(anyString(), anyString());
        assertThat(latest.latest(1).response()).isSameAs(previous);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void cleanupFailureDoesNotHide503OrLeaveTemporaryFile() {
        when(repository.create(anyLong(), any())).thenThrow(new IllegalStateException("commit"));
        when(repository.find(anyLong(), anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("cleanup failed")).when(storage).delete(anyString(), anyString());
        assertUnavailable(() -> service.complete(service.begin(), allocation, candidates, 0));
        assertThat(latest.latest(1).response()).isSameAs(previous);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void absentCompanyFailsBeforeAnyUpload() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.")).when(repository).requireCompany(1);
        assertThatThrownBy(service::begin).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(storage);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void serializationFailureDoesNotUploadOrChangeLatest() throws Exception {
        var broken = mock(CompressedExecutionWriter.class);
        when(broken.write(anyList())).thenThrow(new IOException("serialization"));
        var failing = new MatchingExecutionService(() -> 1, repository, storage, broken, new ExecutionDocumentMapper(),
                latest, Clock.systemUTC());
        assertUnavailable(() -> failing.complete(failing.begin(), allocation, candidates, 0));
        verify(storage, never()).put(anyString(), anyString(), any(), anyString());
        verify(repository, never()).create(anyLong(), any());
        assertThat(latest.latest(1).response()).isSameAs(previous);
    }

    @Test void concurrentExecutionsUseIndependentKeysAndMetadata() throws Exception {
        var contexts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        doAnswer(call -> { contexts.add(call.getArgument(1)); return null; })
                .when(storage).put(anyString(), anyString(), any(), anyString());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<MatchingRunResponse>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> service.complete(service.begin(), allocation, candidates, 0)));
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(new HashSet<>(contexts)).hasSize(8);
        verify(repository, times(8)).create(eq(1L), any());
        assertThat(directory).isEmptyDirectory();
    }

    private static Candidate candidate() {
        var candidate = new Candidate("Ana", "About", "Developer", "phone");
        candidate.setId(7L);
        return candidate;
    }
    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(503);
            assertThat(error.getReason()).contains("Tente novamente");
        });
    }
}
