package br.com.careermatcher.controllers;

import br.com.careermatcher.validations.ValidationException;
import br.com.careermatcher.validations.ValidationException.FieldError;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestControllerAdvice(assignableTypes = {CandidatoController.class, VagaController.class, MatchingExecutionController.class})
public class CrudExceptionHandler {
    public record ErrorResponse(int status, String message, List<FieldError> errors) {}
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, exception.getMessage(), exception.errors()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception exception) {
        String field = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : "body";
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Requisição inválida.",
                List.of(new FieldError(field, "JSON ou tipo de dado inválido."))));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(exception.getStatusCode().value(),
                exception.getReason(), List.of()));
    }
    @ExceptionHandler({DataIntegrityViolationException.class, TransientDataAccessException.class})
    public ResponseEntity<ErrorResponse> conflict(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(409,
                "Conflito de persistência. Recarregue os dados e tente novamente.", List.of()));
    }
}
