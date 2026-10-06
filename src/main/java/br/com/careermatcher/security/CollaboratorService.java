package br.com.careermatcher.security;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.enums.Role;
import br.com.careermatcher.interfaces.EmailService;
import br.com.careermatcher.repositories.GraphCrudRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static br.com.careermatcher.security.AuthDtos.*;

@Service
public class CollaboratorService {
    private final UserRepository users;
    private final CurrentActor actor;
    private final EmailService email;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String frontend;
    private final Duration ttl;
    private final SecureRandom random=new SecureRandom();
    public CollaboratorService(UserRepository users,CurrentActor actor,EmailService email,Clock clock,
                               PlatformTransactionManager manager,@Value("${auth.frontend-url:http://localhost:5500}") String frontend,
                               @Value("${auth.invitation-ttl:24h}") Duration ttl) {
        this.users=users; this.actor=actor; this.email=email; this.clock=clock;
        this.transactions=new TransactionTemplate(manager); this.frontend=frontend.replaceAll("/+$",""); this.ttl=ttl;
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Validade de convite deve ser positiva.");
    }
    @Transactional(readOnly=true)
    public PageView<AuthUser> list(int page,int size) {
        long companyId=actor.requireAdministrator(); GraphCrudRepository.validatePage(page,size);
        return users.collaborators(companyId,page,size);
    }
    public InvitationResponse invite(InviteRequest request) {
        long companyId=actor.requireAdministrator();
        if (request.role()!=Role.GERENTE && request.role()!=Role.RECRUTADOR) throw AuthValidation.bad("Selecione Gerente ou Recrutador.");
        String address=AuthValidation.email(request.email());
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AuthUser user=transactions.execute(status->{
            var existing=users.findByEmail(address);
            AuthUser result;
            if (existing.isPresent()) {
                users.lock(existing.get().user().id());
                var account=users.findById(existing.get().user().id()).orElseThrow(AuthService::conflict);
                result=account.user();
                if (!account.validLinks() || result.cadastroFinalizado() || !Objects.equals(result.companyId(),companyId) || result.role()!=request.role()) throw AuthService.conflict();
            } else result=users.createInvitation(address,request.role(),companyId);
            users.setInvitation(result.id(),AuthValidation.tokenHash(token),clock.instant().plus(ttl));
            return result;
        });
        boolean sent=true;
        try { email.sendEmail(address,"Bem-vindo ao TalentEngine", "Você recebeu um convite para participar do TalentEngine.\n"
                + "Finalize seu cadastro pelo link abaixo, válido por "+ttl.toHours()+" horas:\n"+frontend+"/finalizar-cadastro#token="+token); }
        catch (MailException failure) { sent=false; }
        return new InvitationResponse(user,sent);
    }
    @Transactional public void delete(String id) { users.deleteCollaborator(actor.requireAdministrator(),id); }
}
