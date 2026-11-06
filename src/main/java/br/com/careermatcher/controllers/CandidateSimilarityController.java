package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.similarity.CandidateSimilarityService;
import br.com.careermatcher.similarity.SimilarityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/careermatcher/candidatos/similares")
@RequiredArgsConstructor
public class CandidateSimilarityController {
    private final CandidateSimilarityService service;

    @PostMapping
    public SimilarityResponse findSimilar(@RequestBody CandidateDTO candidate,
                                          @RequestParam(required = false) Integer k) {
        return service.findSimilar(candidate, k);
    }
}
