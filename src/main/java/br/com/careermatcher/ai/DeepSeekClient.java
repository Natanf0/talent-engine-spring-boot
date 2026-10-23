package br.com.careermatcher.ai;

import br.com.careermatcher.validations.ValidationException.FieldError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.*;
import static br.com.careermatcher.ai.AiExtractionDtos.*;

@Component
public class DeepSeekClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private final RestClient client;
    private final AiExtractionProperties properties;
    private final ObjectMapper mapper;
    public DeepSeekClient(@Qualifier("deepSeekRestClient") RestClient client, AiExtractionProperties properties, ObjectMapper mapper) {
        this.client = client; this.properties = properties; this.mapper = mapper;
    }
    CompletionResult extract(List<Message> messages) {
        var settings = properties.deepseek();
        var request = new CompletionRequest(settings.model(), messages, Map.of("type", "json_object"),
                Map.of("type", "disabled"), false, settings.maxOutputTokens());
        try {
            // Read a bounded response before JSON parsing; never log prompts, documents, keys or upstream bodies.
            String body = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(settings.apiKey())).body(request)
                    .exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A IA está ocupada. Tente novamente mais tarde.");
                        if (status >= 400) throw unavailable();
                        byte[] bytes = response.getBody().readNBytes(1_048_577);
                        if (bytes.length > 1_048_576) throw unavailable();
                        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    });
            JsonNode envelope = mapper.readTree(body);
            JsonNode choice = envelope.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) throw unavailable();
            JsonNode result = mapper.readTree(content.asText());
            if (!result.isObject() || !result.path("data").isObject()) throw unavailable();
            List<FieldError> warnings = new ArrayList<>();
            if (result.path("warnings").isArray()) {
                for (JsonNode warning : result.path("warnings")) {
                    // Use plain text in the UI; bound untrusted diagnostic content too.
                    if (warnings.size() == 20) break;
                    if (warning.path("field").isTextual() && warning.path("message").isTextual())
                        warnings.add(new FieldError(limit(warning.path("field").asText(), 200), limit(warning.path("message").asText(), 500)));
                }
            }
            log.info("ai_extraction_usage inputTokens={} outputTokens={}", envelope.path("usage").path("prompt_tokens").asLong(),
                    envelope.path("usage").path("completion_tokens").asLong());
            return new CompletionResult(result.path("data"), warnings);
        } catch (JsonProcessingException exception) {
            throw unavailable();
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "A IA demorou para responder. Tente novamente.");
            }
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }
    private static String limit(String value, int size) { return value.substring(0, Math.min(size, value.length())); }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "A IA não retornou sugestões válidas. Tente novamente ou preencha manualmente.");
    }
}
