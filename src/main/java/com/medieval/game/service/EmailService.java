package com.medieval.game.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public void sendWelcomeEmail(String to, String username, String warriorName) {
        String subject = "⚔ Bem-vindo ao Medieval Game!";
        String body = String.format(
            "Olá, %s!\n\n" +
            "Sua conta foi criada com sucesso.\n" +
            "Seu guerreiro %s está pronto para a batalha!\n\n" +
            "Acesse o jogo em: %s\n\n" +
            "Boa sorte nas batalhas!\n" +
            "— Equipe Medieval Game",
            username, warriorName, baseUrl
        );
        send(to, subject, body);
    }

    public void sendPasswordResetEmail(String to, String resetToken) {
        String resetUrl = baseUrl + "/?reset=" + resetToken;
        String subject  = "🔑 Redefinição de senha — Medieval Game";
        String body = String.format(
            "Olá!\n\n" +
            "Recebemos uma solicitação para redefinir sua senha.\n\n" +
            "Clique no link abaixo (válido por 30 minutos):\n%s\n\n" +
            "Se não foi você, ignore este email.\n\n" +
            "— Equipe Medieval Game",
            resetUrl
        );
        send(to, subject, body);
    }

    private void send(String to, String subject, String body) {
        if (!mailEnabled) {
            log.info("=== [DEV] EMAIL NÃO ENVIADO ===");
            log.info("Para: {}", to);
            log.info("Assunto: {}", subject);
            log.info("Corpo:\n{}", body);
            log.info("================================");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email enviado para {}", to);
        } catch (Exception e) {
            log.error("Erro ao enviar email para {}: {}", to, e.getMessage());
        }
    }
}
