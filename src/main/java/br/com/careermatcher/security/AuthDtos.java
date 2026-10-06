package br.com.careermatcher.security;

import br.com.careermatcher.enums.Role;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() {}
    public record LoginRequest(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=256) String password) {}
    public record RegisterRequest(@NotBlank @Size(max=150) String name,
                                  @NotBlank @Size(max=150) String professionalTitle,
                                  @NotBlank @Size(max=254) String email,
                                  @NotBlank @Size(max=256) String password) {}
    public record TokenRequest(@NotBlank @Size(max=256) String token) {}
    public record CompleteRequest(@NotBlank @Size(max=256) String token,
                                  @NotBlank @Size(max=150) String name,
                                  @NotBlank @Size(max=256) String password) {}
    public record InviteRequest(@NotBlank @Size(max=254) String email, @NotNull Role role) {}
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, AuthUser user) {}
    public record InvitationView(String email, Role role) {}
    public record InvitationResponse(AuthUser user, boolean emailSent) {}
}
