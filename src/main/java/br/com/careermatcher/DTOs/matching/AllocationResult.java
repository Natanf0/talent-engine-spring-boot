package br.com.careermatcher.DTOs.matching;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AllocationResult(
        Map<VacancyDTO, List<CandidateDTO>> shortlists,
        Set<String> unassignedCandidateIds,
        int totalAllocated
) {
    public AllocationResult {
        Map<VacancyDTO, List<CandidateDTO>> snapshot = new LinkedHashMap<>();
        shortlists.forEach((vacancy, candidates) -> snapshot.put(vacancy, List.copyOf(candidates)));
        shortlists = Collections.unmodifiableMap(snapshot);
        unassignedCandidateIds = Collections.unmodifiableSet(new LinkedHashSet<>(unassignedCandidateIds));
    }
}
