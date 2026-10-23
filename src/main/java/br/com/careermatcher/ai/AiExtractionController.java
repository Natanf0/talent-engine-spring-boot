package br.com.careermatcher.ai;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.security.CurrentActor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import static br.com.careermatcher.ai.AiExtractionDtos.ExtractionResponse;

@RestController
public class AiExtractionController {
    private final CurrentActor actor;
    private final AiExtractionService service;
    public AiExtractionController(CurrentActor actor, AiExtractionService service) { this.actor = actor; this.service = service; }
    @PostMapping(value = "/api/me/candidate/ai-extraction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionResponse candidate(@RequestParam("file") MultipartFile file) {
        actor.requireCandidate();
        return service.extract(Kind.CANDIDATE, file, null);
    }
    @PostMapping(value = "/api/careermatcher/vagas/ai-extraction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionResponse vacancy(MultipartHttpServletRequest request,
                                      @RequestParam(value = "text", required = false) String text) {
        actor.requireCompany();
        AiExtractionService.validateVacancyInput(!request.getFileMap().isEmpty(), text);
        return service.extract(Kind.VACANCY, null, text);
    }
}
