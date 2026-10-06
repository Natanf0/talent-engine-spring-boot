package br.com.careermatcher.DTOs.matching;

/** Candidate details exposed by matching results, without authentication data. */
public record CandidateDTO(
        String id,
        String name,
        String about,
        String professionalTitle,
        String phone,
        String email
) {
}
