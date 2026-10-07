package br.com.careermatcher.security;

import br.com.careermatcher.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthUser(String id, String name, String email, Role role,
                       Long companyId, Long candidateId, boolean cadastroFinalizado, String companyName) {}
