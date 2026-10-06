package br.com.careermatcher.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Arrays;

/** Offline administrator provisioning helper. Does not start Spring or connect to a database. */
public final class PasswordHash {
    private PasswordHash() {}
    public static void main(String[] args) {
        var console = System.console();
        if (console == null) throw new IllegalStateException("Execute em um terminal interativo; a senha não é aceita como argumento.");
        char[] first = console.readPassword("Senha (mínimo 8 caracteres): ");
        char[] confirmation = console.readPassword("Confirme a senha: ");
        try {
            if (first == null || confirmation == null || !Arrays.equals(first, confirmation))
                throw new IllegalArgumentException("As senhas não conferem.");
            String password = AuthValidation.password(new String(first));
            console.printf("%s%n", new BCryptPasswordEncoder(12).encode(password));
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
