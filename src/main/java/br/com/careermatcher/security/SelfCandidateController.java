package br.com.careermatcher.security;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.services.CandidatoService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/candidate")
public class SelfCandidateController {
    private final CurrentActor actor;
    private final CandidatoService candidates;
    public SelfCandidateController(CurrentActor actor,CandidatoService candidates) { this.actor=actor; this.candidates=candidates; }
    @GetMapping public CandidateView get() { return candidates.findCandidate(actor.requireCandidate()); }
    @PatchMapping public CandidateView patch(@RequestBody CandidateWriteRequest request) { return candidates.patch(actor.requireCandidate(),request.fields()); }
}
