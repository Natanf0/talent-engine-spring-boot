package br.com.careermatcher.validations;

import java.util.List;

public class ValidationException extends RuntimeException {
    public record FieldError(String field, String message) {}
    private final List<FieldError> errors;
    public ValidationException(List<FieldError> errors) {
        super("Dados inválidos.");
        this.errors = List.copyOf(errors);
    }
    public List<FieldError> errors() { return errors; }
}
