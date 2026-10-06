package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.DTOs.matching.ExecutionMetadata;
import br.com.careermatcher.matching.execution.MatchingExecutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MatchingExecutionController {
    private final MatchingExecutionService executions;
    public MatchingExecutionController(MatchingExecutionService executions) { this.executions = executions; }

    @GetMapping("/api/matching/executions")
    public PageView<ExecutionMetadata> history(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return executions.history(page, size);
    }
}
