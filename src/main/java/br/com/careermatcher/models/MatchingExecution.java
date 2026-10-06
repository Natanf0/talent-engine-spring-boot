package br.com.careermatcher.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import java.time.Instant;

@Node("MATCHING_EXECUTION")
@Getter
@NoArgsConstructor
public class MatchingExecution {
    @Id @GeneratedValue private Long id;
    private String executionId;
    private Instant createdAt;
    private int vacanciesQuantity;
    private int candidateQuantity;
    private String bucket;
    private String objectKey;
    private String compression;
    private int schemaVersion;

    public MatchingExecution(Long id, String executionId, Instant createdAt, int vacanciesQuantity,
                             int candidateQuantity, String bucket, String objectKey,
                             String compression, int schemaVersion) {
        this.id = id;
        this.executionId = executionId;
        this.createdAt = createdAt;
        this.vacanciesQuantity = vacanciesQuantity;
        this.candidateQuantity = candidateQuantity;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.compression = compression;
        this.schemaVersion = schemaVersion;
    }
}
