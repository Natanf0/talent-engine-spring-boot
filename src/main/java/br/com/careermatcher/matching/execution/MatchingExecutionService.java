package br.com.careermatcher.matching.execution;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.DTOs.matching.*;
import br.com.careermatcher.matching.LatestExecutionStore;
import br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView;
import br.com.careermatcher.models.MatchingExecution;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import br.com.careermatcher.repositories.GraphCrudRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Service
public class MatchingExecutionService {
    private static final Logger log = LoggerFactory.getLogger(MatchingExecutionService.class);
    private final CurrentCompany company;
    private final MatchingExecutionRepository repository;
    private final ExecutionObjectStorage storage;
    private final CompressedExecutionWriter writer;
    private final ExecutionDocumentMapper documents;
    private final LatestExecutionStore latest;
    private final Clock clock;

    public record Context(long companyId, String executionId, Instant createdAt) {}

    public MatchingExecutionService(CurrentCompany company, MatchingExecutionRepository repository,
                                    ExecutionObjectStorage storage, CompressedExecutionWriter writer,
                                    ExecutionDocumentMapper documents, LatestExecutionStore latest, Clock clock) {
        this.company = company; this.repository = repository; this.storage = storage; this.writer = writer;
        this.documents = documents; this.latest = latest; this.clock = clock;
    }

    public Context begin() {
        long companyId = company.id();
        try { repository.requireCompany(companyId); }
        catch (DataAccessException failure) { throw unavailable(); }
        return new Context(companyId, "RUN-" + UUID.randomUUID(), clock.instant());
    }

    public MatchingRunResponse complete(Context context, AllocationResponse allocation,
                                        List<CandidateView> candidates, int vacanciesQuantity) {
        long startR2Compressao= System.currentTimeMillis();
        Path temporary = null;
        try {
            String bucket = storage.bucket();
            String key = "companies/" + context.companyId() + "/executions/" + context.executionId() + writer.extension();
            temporary = writer.write(documents.map(allocation, candidates));
            upload(context, bucket, key, temporary);
            var metadata = new MatchingExecution(null, context.executionId(), context.createdAt(), vacanciesQuantity,
                    candidates.size(), bucket, key, writer.format(), 2);
            persist(context.companyId(), metadata);
            var response = new MatchingRunResponse(context.executionId(), context.createdAt(), allocation);
            latest.publish(context.companyId(), response);
            log.info("Matching stored: executionId={}, companyId={}", context.executionId(), context.companyId());
            System.out.println("R2 bucket - compressaoo " + (startR2Compressao - System.currentTimeMillis())/1000 + " s");
            return response;
        } catch (IOException | RuntimeException failure) {
            log.warn("Matching storage failed: executionId={}, failureType={}", context.executionId(), failure.getClass().getSimpleName());
            throw unavailable();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { log.warn("Temporary cleanup failed: executionId={}", context.executionId()); }
            }
        }

    }

    public PageView<ExecutionMetadata> history(int page, int size) {
        GraphCrudRepository.validatePage(page, size);
        try {
            var results = repository.findPage(company.id(), page, size);
            return new PageView<>(results.content().stream().map(ExecutionMetadata::from).toList(),
                    results.page(), results.size(), results.totalElements(), results.totalPages());
        } catch (DataAccessException failure) { throw unavailable(); }
    }

    private void upload(Context context, String bucket, String key, Path file) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                storage.put(bucket, key, file, writer.contentType());
                return;
            } catch (RuntimeException failure) {
                log.warn("Matching upload failed: executionId={}, attempt={}", context.executionId(), attempt);
                if (attempt == 2) {
                    cleanup(bucket, key, context.executionId());
                    throw unavailable();
                }
                try { Thread.sleep(500); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cleanup(bucket, key, context.executionId());
                    throw unavailable();
                }
            }
        }
    }

    private void persist(long companyId, MatchingExecution metadata) {
        try { repository.create(companyId, metadata); }
        catch (RuntimeException failure) {
            log.warn("Matching metadata commit failed: executionId={}, failureType={}",
                    metadata.getExecutionId(), failure.getClass().getSimpleName());
            // Commit acknowledgement may be lost. Never delete an object until absence is confirmed.
            Optional<MatchingExecution> confirmed;
            try { confirmed = repository.find(companyId, metadata.getExecutionId()); }
            catch (RuntimeException unknown) {
                log.warn("Matching commit unresolved; object retained: executionId={}", metadata.getExecutionId());
                throw unavailable();
            }
            if (confirmed.isPresent()) {
                var saved = confirmed.get();
                if (saved.getBucket().equals(metadata.getBucket()) && saved.getObjectKey().equals(metadata.getObjectKey())
                        && saved.getCreatedAt().equals(metadata.getCreatedAt())
                        && saved.getVacanciesQuantity() == metadata.getVacanciesQuantity()
                        && saved.getCandidateQuantity() == metadata.getCandidateQuantity()
                        && saved.getCompression().equals(metadata.getCompression())
                        && saved.getSchemaVersion() == metadata.getSchemaVersion()) return;
                log.warn("Matching metadata conflict; object retained: executionId={}", metadata.getExecutionId());
            } else cleanup(metadata.getBucket(), metadata.getObjectKey(), metadata.getExecutionId());
            throw unavailable();
        }
    }

    private void cleanup(String bucket, String key, String executionId) {
        try { storage.delete(bucket, key); }
        catch (RuntimeException failure) { log.warn("Matching object cleanup failed: executionId={}, objectKey={}", executionId, key); }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível armazenar ou consultar a execução. Tente novamente.");
    }
}
