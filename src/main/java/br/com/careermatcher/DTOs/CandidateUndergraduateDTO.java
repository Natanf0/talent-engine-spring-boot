package br.com.careermatcher.DTOs;

import br.com.careermatcher.enums.UNDERGRADUATETYPE;

public record CandidateUndergraduateDTO(String field,
                                        String institution,
                                        Integer conclusionYear,
                                        UNDERGRADUATETYPE undergraduatetype) {
}
