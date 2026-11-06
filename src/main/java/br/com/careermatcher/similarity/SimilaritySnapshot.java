package br.com.careermatcher.similarity;

import java.util.*;

/** Immutable persisted facts. IDs are Neo4j generated numeric IDs, as in the existing CRUD. */
public record SimilaritySnapshot(List<Profile> profiles, List<Evidence> evidence) {
    public SimilaritySnapshot { profiles = List.copyOf(profiles); evidence = List.copyOf(evidence); }
    public record Profile(long id, String name, String professionalTitle) {}
    public enum SourceType { RESUME_DECLARATION, EXPERIENCE, PROJECT }
    public record Evidence(long candidateId, long skillId, String skillName, SourceType sourceType, long sourceId) {}
}
