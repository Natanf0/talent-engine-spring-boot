package br.com.careermatcher.similarity;

import java.util.List;
import java.util.Map;
import br.com.careermatcher.similarity.Fora.QualityStatus;
import br.com.careermatcher.similarity.SimilaritySnapshot.SourceType;

public record SimilarityResponse(String referenceCandidateId, int requestedK, int returnedCount,
                                 List<SourceSkill> sourceSkills, List<SimilarCandidate> candidates,
                                 Reason reason, QualityStatus qualityStatus, boolean approximationTargetMet) {
    public enum Reason { NO_SKILLS, NO_SIMILAR_CANDIDATES }
    public record SourceSkill(String skillId, String name, long frequency, double sigma, Map<SourceType, Long> evidenceCounts) {}
    public record SharedSkill(String skillId, String name, long referenceFrequency, long candidateFrequency,
                              Map<SourceType, Long> referenceEvidenceCounts, Map<SourceType, Long> candidateEvidenceCounts) {}
    public record SimilarCandidate(String id, String name, String professionalTitle, double score,
                                   List<SharedSkill> sharedSkills, boolean indirectAffinity) {}
}
