package br.com.careermatcher.ai;

import br.com.careermatcher.validations.ValidationException.FieldError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public final class AiExtractionDtos {
    private AiExtractionDtos() {}
    public record ExtractionResponse(ObjectNode data, List<FieldError> pendingFields, List<FieldError> warnings) {}
    record Message(String role, String content) {}
    record CompletionRequest(String model, List<Message> messages, Map<String, String> response_format,
                             Map<String, String> thinking, boolean stream, int max_tokens) {}
    record CompletionResult(JsonNode data, List<FieldError> warnings) {}
}
