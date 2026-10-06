package br.com.careermatcher.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class AuthValidation {
    private AuthValidation() {}
    static String email(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw bad("Informe um e-mail válido.");
        return email;
    }
    static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 150 || name.chars().anyMatch(Character::isISOControl))
            throw bad("Informe um nome válido com até 150 caracteres.");
        return name;
    }
    static String password(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw bad("A senha deve ter pelo menos 8 caracteres e no máximo 72 bytes UTF-8.");
        return password;
    }
    static String tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidInvite();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ResponseStatusException invalidInvite() { return bad("Convite inválido, expirado ou já utilizado."); }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
