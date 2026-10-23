package br.com.careermatcher.ai;

import br.com.careermatcher.controllers.CrudExceptionHandler.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(assignableTypes = AiExtractionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExtractionExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(exception.getStatusCode().value(), exception.getReason(), List.of()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(413).body(new ErrorResponse(413, vacancy(request) ? "O texto ou a requisição excede o limite permitido." : "O arquivo ou a requisição excede o limite permitido (PDF: até 5 MB).", List.of()));
    }
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class, MultipartException.class})
    ResponseEntity<ErrorResponse> malformed(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, vacancy(request) ? "Envie a descrição da vaga em texto no formato esperado." : "Envie o currículo PDF no formato esperado.", List.of()));
    }
    private boolean vacancy(HttpServletRequest request) { return request.getRequestURI().endsWith("/vagas/ai-extraction"); }
}
