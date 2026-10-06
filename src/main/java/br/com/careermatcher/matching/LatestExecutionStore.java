package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.MatchingRunResponse;
import org.springframework.stereotype.Component;

/** Process-local convenience view; durable history lives in Neo4j. */
@Component
public class LatestExecutionStore {
    public record Snapshot(MatchingRunResponse response) {}
    private final java.util.concurrent.ConcurrentMap<Long, Snapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    public void publish(long companyId, MatchingRunResponse response) { snapshots.put(companyId, new Snapshot(response)); }
    public Snapshot latest(long companyId) { return snapshots.get(companyId); }
}
