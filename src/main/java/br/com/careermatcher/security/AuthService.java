package br.com.careermatcher.security;

import br.com.careermatcher.services.CandidatoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static br.com.careermatcher.security.AuthDtos.*;

@Service
public class AuthService {
    private final UserRepository users;
    private final CandidatoService candidates;
    private final ObjectMapper mapper;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String dummyHash;
    public AuthService(UserRepository users,CandidatoService candidates,ObjectMapper mapper,PasswordEncoder passwords,
                       JwtEncoder encoder,Clock clock,@Value("${auth.issuer:talentengine}") String issuer) {
        this.users=users; this.candidates=candidates; this.mapper=mapper; this.passwords=passwords;
        this.encoder=encoder; this.clock=clock; this.issuer=issuer;
        this.dummyHash=passwords.encode(java.util.UUID.randomUUID().toString());
    }
    @Transactional(readOnly=true)
    public LoginResponse login(LoginRequest request) {
        var account=users.findByEmail(AuthValidation.email(request.email())).orElse(null);
        String supplied=request.password();
        boolean acceptable=supplied!=null && supplied.getBytes(StandardCharsets.UTF_8).length<=72;
        boolean matched=passwords.matches(acceptable?supplied:"invalid-login",account==null || account.password()==null?dummyHash:account.password());
        if (!acceptable || !matched || account==null || !account.validLinks() || !account.user().cadastroFinalizado())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"E-mail ou senha inválidos, ou cadastro não finalizado.");
        AuthUser user=account.user();
        Instant now=clock.instant().truncatedTo(ChronoUnit.SECONDS), expiry=now.plusSeconds(3600);
        var claims=JwtClaimsSet.builder().issuer(issuer).subject(user.id()).audience(List.of("talentengine-api"))
                .issuedAt(now).expiresAt(expiry).claim("role",user.role().name());
        if (user.companyId()!=null) claims.claim("companyId",user.companyId());
        if (user.candidateId()!=null) claims.claim("candidateId",user.candidateId());
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims.build())).getTokenValue();
        return new LoginResponse(token,"Bearer",expiry,user);
    }
    @Transactional
    public AuthUser register(RegisterRequest request) {
        String email=AuthValidation.email(request.email()),name=AuthValidation.name(request.name());
        String hash=passwords.encode(AuthValidation.password(request.password()));
        if (users.findByEmail(email).isPresent()) throw conflict();
        var candidate=candidates.registerCandidate(mapper.createObjectNode().put("name",name).put("professionalTitle",request.professionalTitle()));
        return users.createCandidate(email,candidate.name(),hash,candidate.id(),clock.instant());
    }
    @Transactional(readOnly=true)
    public InvitationView validateInvitation(String token) {
        var account=validInvitation(AuthValidation.tokenHash(token));
        return new InvitationView(account.user().email(),account.user().role());
    }
    @Transactional
    public void complete(CompleteRequest request) {
        String hash=AuthValidation.tokenHash(request.token());
        String name=AuthValidation.name(request.name()), password=passwords.encode(AuthValidation.password(request.password()));
        var initial=validInvitation(hash);
        users.lock(initial.user().id());
        // Re-read after the lock: another request may have consumed/replaced the token while waiting.
        var account=validInvitation(hash);
        users.complete(account.user().id(),hash,name,password,clock.instant());
    }
    private UserRepository.Account validInvitation(String hash) {
        var account=users.findByInvitation(hash).orElseThrow(AuthValidation::invalidInvite);
        if (!account.validLinks() || account.user().cadastroFinalizado() || account.user().role()==br.com.careermatcher.enums.Role.CANDIDATO
                || account.invitationExpiresAt()==null || !account.invitationExpiresAt().isAfter(clock.instant())) throw AuthValidation.invalidInvite();
        return account;
    }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"E-mail já cadastrado."); }
}
