package br.com.careermatcher.security;

import br.com.careermatcher.controllers.CrudExceptionHandler;
import br.com.careermatcher.validations.ValidationException.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice(basePackageClasses=AuthController.class)
public class AuthExceptionHandler extends CrudExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400,"Preencha os campos corretamente.",exception.getBindingResult().getFieldErrors().stream()
                .map(error->new FieldError(error.getField(),"Valor inválido.")).toList()));
    }
}
