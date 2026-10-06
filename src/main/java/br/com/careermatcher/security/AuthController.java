package br.com.careermatcher.security;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static br.com.careermatcher.security.AuthDtos.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final CurrentActor actor;
    public AuthController(AuthService service,CurrentActor actor) { this.service=service; this.actor=actor; }
    @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest request) { return service.login(request); }
    @GetMapping("/me") public AuthUser me() { return actor.user(); }
    @PostMapping("/register/candidate") @ResponseStatus(HttpStatus.CREATED)
    public AuthUser register(@Valid @RequestBody RegisterRequest request) { return service.register(request); }
    @PostMapping("/invitations/validate")
    public InvitationView validateInvitation(@Valid @RequestBody TokenRequest request) { return service.validateInvitation(request.token()); }
    @PostMapping("/invitations/complete") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@Valid @RequestBody CompleteRequest request) { service.complete(request); }
}
