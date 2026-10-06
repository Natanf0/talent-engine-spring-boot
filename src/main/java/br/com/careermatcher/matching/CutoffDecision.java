package br.com.careermatcher.matching;

/** Mean and threshold refer to the accepted prefix BEFORE this decision. */
public record CutoffDecision(String vacancyId, String candidateId, Integer position, Double score,
                             double absoluteMinScore, double toleranceFactor, int acceptedCount,
                             Double clusterAverage, Double threshold, String code, boolean accepted,
                             String triggerCandidateId, String explanation) {}
