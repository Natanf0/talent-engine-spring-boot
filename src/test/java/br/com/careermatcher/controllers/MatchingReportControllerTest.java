package br.com.careermatcher.controllers;

import br.com.careermatcher.reports.MatchingReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MatchingReportControllerTest {
    private final MatchingReportService service = mock(MatchingReportService.class);
    private final MatchingReportController controller = new MatchingReportController(service);
    private final MockMvc mvc = standaloneSetup(controller).build();

    @Test void returnsPdfAttachmentWithoutCaching() throws Exception {
        byte[] pdf = "%PDF-1.7\nreport\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        when(service.generate("RUN-1")).thenReturn(pdf);
        mvc.perform(post("/api/matching/executions/RUN-1/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relatorio-matching-RUN-1.pdf\""))
                .andExpect(content().bytes(pdf));
        verify(service).generate("RUN-1");
    }

    @Test void sanitizesAndBoundsDownloadFilename() {
        String executionId = "RUN\r\n\"" + "x".repeat(150);
        when(service.generate(executionId)).thenReturn(new byte[0]);
        var disposition = controller.pdf(executionId).getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals("relatorio-matching-RUN---" + "x".repeat(94) + ".pdf", disposition.getFilename());
    }

    @Test void preservesNotFoundAndGatewayErrors() throws Exception {
        when(service.generate("missing")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(service.generate("unavailable")).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        mvc.perform(post("/api/matching/executions/missing/pdf")).andExpect(status().isNotFound());
        mvc.perform(post("/api/matching/executions/unavailable/pdf")).andExpect(status().isBadGateway());
    }
}
