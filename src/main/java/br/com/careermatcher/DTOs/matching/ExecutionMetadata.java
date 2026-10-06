package br.com.careermatcher.DTOs.matching;

import br.com.careermatcher.models.MatchingExecution;
import java.time.Instant;

/** Public history representation; storage credentials and bucket are server configuration. */
public record ExecutionMetadata(Long id, String executionId, Instant createdAt, int vacanciesQuantity,
                                int candidateQuantity, String objectKey, String compression, int schemaVersion) {
    public static ExecutionMetadata from(MatchingExecution execution) {
        return new ExecutionMetadata(execution.getId(), execution.getExecutionId(), execution.getCreatedAt(),
                execution.getVacanciesQuantity(), execution.getCandidateQuantity(), execution.getObjectKey(),
                execution.getCompression(), execution.getSchemaVersion());
    }
}
