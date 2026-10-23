package br.com.careermatcher.ai;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.validations.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiExtractionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private AiExtractionService service(AiExtractionProperties properties) {
        return new AiExtractionService(properties, new PdfTextExtractionService(properties), new ExtractionPromptFactory(mapper),
                client, new GraphValidations(new TextSanitizer()));
    }
    private AiExtractionDtos.CompletionResult result() {
        return new AiExtractionDtos.CompletionResult(mapper.createObjectNode().put("role", "Dev"), List.of());
    }
    @Test void rejectsInputAndDisabledFeatureBeforePaidCall() {
        var service = service(AiTestSupport.properties());
        assertThatThrownBy(() -> service.extract(Kind.VACANCY, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.extract(Kind.VACANCY, PdfTextExtractionServiceTest.file(new byte[]{1}), "text"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.extract(Kind.CANDIDATE, PdfTextExtractionServiceTest.file("not a pdf".getBytes()), null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service(AiTestSupport.properties(false, 5242880, 20, 60000)).extract(Kind.VACANCY, null, "vaga"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(client);
    }
    @Test void rejectsVacancyPdfBeforeParserAndPaidCallEvenWhenInvokedDirectly() {
        var properties = AiTestSupport.properties();
        var parser = mock(PdfTextExtractionService.class);
        var service = new AiExtractionService(properties, parser, new ExtractionPromptFactory(mapper),
                client, new GraphValidations(new TextSanitizer()));
        for (String text : new String[]{null, "Vaga Java"})
            assertThatThrownBy(() -> service.extract(Kind.VACANCY, PdfTextExtractionServiceTest.file(new byte[]{1}), text))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode().value()).isEqualTo(400);
                        assertThat(exception.getReason()).contains("apenas texto");
                    });
        for (String text : new String[]{null, "", "  "})
            assertThatThrownBy(() -> service.extract(Kind.VACANCY, null, text))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getReason()).startsWith("Cole a descrição da vaga"));
        verifyNoInteractions(parser, client);
    }
    @Test void handlesVacancyTextLimitAndEmptyResultWithVacancyMessages() {
        var service = service(AiTestSupport.properties());
        assertThatThrownBy(() -> service.extract(Kind.VACANCY, null, "a".repeat(60001)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(413));
        verifyNoInteractions(client);
        when(client.extract(anyList())).thenReturn(new AiExtractionDtos.CompletionResult(mapper.createObjectNode(), List.of()));
        assertThat(service.extract(Kind.VACANCY, null, "Texto sem dados de uma vaga").warnings())
                .extracting(ValidationException.FieldError::message).contains("Não foram encontrados dados da vaga no texto informado.");
    }
    @Test void extractsCandidatePdfAndVacancyTextAsPartialDrafts() throws Exception {
        when(client.extract(anyList())).thenReturn(new AiExtractionDtos.CompletionResult(mapper.createObjectNode().put("name", "Ana"), List.of()));
        var candidate = service(AiTestSupport.properties()).extract(Kind.CANDIDATE,
                PdfTextExtractionServiceTest.file(PdfTextExtractionServiceTest.pdf("Ana Silva desenvolvedora de sistemas Java", 1, false)), null);
        assertThat(candidate.data().path("name").asText()).isEqualTo("Ana");
        assertThat(candidate.pendingFields()).extracting(ValidationException.FieldError::field).contains("professionalTitle");
        doReturn(result()).when(client).extract(anyList());
        var vacancy = service(AiTestSupport.properties()).extract(Kind.VACANCY, null, "Vaga Java remota");
        assertThat(vacancy.data().path("role").asText()).isEqualTo("Dev");
        assertThat(vacancy.pendingFields()).extracting(ValidationException.FieldError::field).contains("city", "modalidade", "seniority");
    }
    @Test void limitsConcurrentWorkAndReleasesPermitOnFailure() throws Exception {
        var defaults = AiTestSupport.properties();
        var service = service(new AiExtractionProperties(true, 5242880, 20, 60000, 1, defaults.deepseek()));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.extract(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> service.extract(Kind.VACANCY, null, "vaga"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.extract(Kind.VACANCY, null, "vaga"))
                        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(429));
            } finally { release.countDown(); }
            assertThatThrownBy(() -> active.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        }
        doReturn(result()).when(client).extract(anyList());
        assertThat(service.extract(Kind.VACANCY, null, "vaga").data().has("role")).isTrue();
    }
    @Test void requiresKeyOnlyWhenEnabled() {
        var defaults = AiTestSupport.properties();
        var provider = new AiExtractionProperties.Deepseek("", "https://api.deepseek.com", "deepseek-flash",
                defaults.deepseek().connectTimeout(), defaults.deepseek().readTimeout(), 8192);
        assertThatThrownBy(() -> new AiExtractionProperties(true, 5242880, 20, 60000, 2, provider))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AiExtractionProperties(false, 5242880, 20, 60000, 2, provider)).doesNotThrowAnyException();
    }
}
