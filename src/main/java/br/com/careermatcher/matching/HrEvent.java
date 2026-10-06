package br.com.careermatcher.matching;

/** Ranks are one-based positions in the preferences supplied to HR. */
public record HrEvent(long sequence, String type, String candidateId, String vacancyId,
                      String causeCandidateId, Integer candidatePreferenceRank, Integer vacancyPreferenceRank,
                      Integer causeVacancyRank, int occupancy, int capacity, String explanation) {}
