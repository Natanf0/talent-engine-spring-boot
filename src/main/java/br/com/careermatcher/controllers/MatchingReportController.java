package br.com.careermatcher.controllers;

import br.com.careermatcher.reports.MatchingReportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MatchingReportController {
    private final MatchingReportService reports;
    public MatchingReportController(MatchingReportService reports) { this.reports = reports; }

    @PostMapping(value = "/api/matching/executions/{executionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String executionId) {
        byte[] pdf = reports.generate(executionId);
        String safeId = executionId.replaceAll("[^A-Za-z0-9_-]", "-");
        if (safeId.length() > 100) safeId = safeId.substring(0, 100);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("relatorio-matching-" + safeId + ".pdf").build().toString())
                .body(pdf);
    }
}
