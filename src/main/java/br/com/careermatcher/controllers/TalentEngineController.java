package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.matching.MatchingRunResponse;
import br.com.careermatcher.matching.MatchingSubmissionService;
import br.com.careermatcher.matching.DynamicCutoffService;
import br.com.careermatcher.matching.LatestExecutionStore;
import br.com.careermatcher.matching.MatchingSelectionValidator;
import br.com.careermatcher.validations.ValidationException;
import br.com.careermatcher.validations.ValidationException.FieldError;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


@RestController
public class TalentEngineController {
    private final MatchingSubmissionService submissions;
    private final MatchingSelectionValidator selectionValidator;
    private final LatestExecutionStore latestExecutionStore;
    private final br.com.careermatcher.matching.execution.CurrentCompany company;

    public TalentEngineController(MatchingSubmissionService submissions, MatchingSelectionValidator selectionValidator,
                                  LatestExecutionStore latestExecutionStore, br.com.careermatcher.matching.execution.CurrentCompany company) {
        this.submissions = submissions;
        this.selectionValidator = selectionValidator;
        this.latestExecutionStore = latestExecutionStore;
        this.company = company;
    }

    @PostMapping({"/api/careermatcher/run", "/api/matching/run"})
    public ResponseEntity<Void> run(@RequestBody JsonNode body,
            @RequestParam(defaultValue = "10") int k,
            @RequestParam(defaultValue = "0.65") double toleranceFactor,
            @RequestParam(defaultValue = "5.0") double absoluteMinScore
    ) {
        if (k <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O tamanho do shortlist (K) deve ser maior que zero.");
        }
        try {
            DynamicCutoffService.validateParameters(toleranceFactor, absoluteMinScore);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        submissions.submit(selectionValidator.parse(body), k, toleranceFactor, absoluteMinScore);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/matching/executions/latest")
    public MatchingRunResponse latest() {
        var snapshot = latestExecutionStore.latest(company.id());
        if (snapshot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhuma execução disponível.");
        return snapshot.response();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CrudExceptionHandler.ErrorResponse> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new CrudExceptionHandler.ErrorResponse(
                exception.getStatusCode().value(), exception.getReason(), java.util.List.of()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<CrudExceptionHandler.ErrorResponse> validation(ValidationException exception) {
        return ResponseEntity.badRequest().body(new CrudExceptionHandler.ErrorResponse(400,
                exception.getMessage(), exception.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CrudExceptionHandler.ErrorResponse> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new CrudExceptionHandler.ErrorResponse(400,
                "Requisição inválida.", java.util.List.of(new FieldError("body", "JSON inválido."))));
    }
}
