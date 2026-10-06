package br.com.careermatcher.interfaces;


public interface EmailService {
    void sendEmail(String to, String subject, String body);
    void sendAdmEmail(String details);
}
