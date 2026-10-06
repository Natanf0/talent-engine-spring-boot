package br.com.careermatcher.DTOs.matching;

import java.time.Instant;

public record MatchingRunResponse(String executionId, Instant createdAt, AllocationResponse allocation) {}
