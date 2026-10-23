package br.com.careermatcher.ai;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.validations.GraphValidations;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import static br.com.careermatcher.ai.AiExtractionDtos.*;

@Service
public class AiExtractionService {
    private final AiExtractionProperties properties;
    private final PdfTextExtractionService pdf;
    private final ExtractionPromptFactory prompts;
    private final DeepSeekClient client;
    private final GraphValidations validations;
    private final Semaphore permits;
    public AiExtractionService(AiExtractionProperties properties, PdfTextExtractionService pdf, ExtractionPromptFactory prompts,
                               DeepSeekClient client, GraphValidations validations) {
        this.properties = properties; this.pdf = pdf; this.prompts = prompts; this.client = client; this.validations = validations;
        this.permits = new Semaphore(properties.maxConcurrent());
    }
    public ExtractionResponse extract(Kind kind, MultipartFile file, String text) {
        if (kind == Kind.VACANCY) validateVacancyInput(file != null, text);
        if (!properties.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O preenchimento com IA está desabilitado.");
        if ((file == null) == (text == null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie exatamente um PDF ou um texto.");
        if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Há muitas solicitações de preenchimento em andamento. Tente novamente mais tarde.");
        long start = System.nanoTime();
        String outcome = "error";
        try {
            String source = file == null ? pdf.normalizeText(text) : pdf.extract(file);
            CompletionResult result = client.extract(prompts.messages(kind, source));
            GraphValidations.Draft draft;
            try { draft = validations.validateDraft(kind, (com.fasterxml.jackson.databind.node.ObjectNode) result.data()); }
            catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "A IA retornou uma estrutura incompatível. Tente novamente.");
            }
            var warnings = new ArrayList<>(result.warnings());
            warnings.addAll(draft.warnings());
            if (draft.data().isEmpty()) warnings.add(new br.com.careermatcher.validations.ValidationException.FieldError(
                    kind == Kind.VACANCY ? "text" : "document", kind == Kind.VACANCY
                    ? "Não foram encontrados dados da vaga no texto informado." : "Nenhum campo utilizável foi encontrado no documento."));
            outcome = "success";
            return new ExtractionResponse(draft.data(), draft.pendingFields(), warnings);
        } finally {
            permits.release();
            LoggerFactory.getLogger(AiExtractionService.class).info("ai_extraction kind={} outcome={} durationMs={}", kind, outcome,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }
    static void validateVacancyInput(boolean hasFile, String text) {
        if (hasFile) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "O preenchimento automático de vagas aceita apenas texto. Cole a descrição da vaga.");
        if (text == null || text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cole a descrição da vaga para preencher os campos automaticamente.");
    }

}
