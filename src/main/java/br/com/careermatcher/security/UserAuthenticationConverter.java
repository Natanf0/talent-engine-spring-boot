package br.com.careermatcher.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class UserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;
    public UserAuthenticationConverter(UserRepository users) { this.users=users; }
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        var account = users.findById(jwt.getSubject()).orElseThrow(UserAuthenticationConverter::invalid);
        AuthUser user=account.user();
        if (!account.validLinks() || !user.cadastroFinalizado() || !user.role().name().equals(jwt.getClaimAsString("role"))
                || !sameId(user.companyId(),jwt.getClaim("companyId")) || !sameId(user.candidateId(),jwt.getClaim("candidateId"))) throw invalid();
        var result = new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_"+user.role().name())),user.id());
        result.setDetails(user);
        return result;
    }
    private static boolean sameId(Long expected, Object claim) {
        return expected==null ? claim==null : expected.toString().equals(Objects.toString(claim,null));
    }
    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"Sessão inválida.");
    }
}
