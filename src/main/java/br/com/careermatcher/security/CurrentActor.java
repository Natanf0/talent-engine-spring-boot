package br.com.careermatcher.security;

import br.com.careermatcher.enums.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentActor {
    public AuthUser user() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getPrincipal() instanceof AuthUser user) return user;
            if (authentication.getDetails() instanceof AuthUser user) return user;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Autenticação necessária.");
    }

    public void requireStaff() {
        if (!user().role().isStaff()) throw forbidden();
    }

    public long requireCompany() {
        AuthUser user = user();
        if (!user.role().isStaff() || user.companyId() == null) throw forbidden();
        return user.companyId();
    }

    public long requireCandidate() {
        AuthUser user = user();
        if (user.role() != Role.CANDIDATO || user.candidateId() == null) throw forbidden();
        return user.candidateId();
    }

    public long requireAdministrator() {
        if (user().role() != Role.ADMINISTRADOR) throw forbidden();
        return requireCompany();
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Operação não permitida para este perfil.");
    }
}
