package br.com.careermatcher.services;

import br.com.careermatcher.interfaces.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImp implements EmailService {
    private final JavaMailSender mailSender;
    private final String from;

    public EmailServiceImp(JavaMailSender mailSender, @Value("${spring.mail.username}") String from){
        this.mailSender=mailSender;
        this.from=from;
    }

    public void sendEmail(String to, String subject, String body){
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        // The caller owns retry/reporting. Recursive fallback would resend secrets indefinitely on SMTP failure.
        mailSender.send(message);
    }

    public void sendAdmEmail(String details){
        this.sendEmail("natan.ferreira@talentengine.com.br",
                "Erro ao enviar email",
                "Ocorreu um erro ao enviar um email. Verifique os detalhes abaixo\n" + details);
    }


}
