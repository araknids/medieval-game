package com.medieval.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EmailService {

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.brevo.api-key:}")
    private String brevoApiKey;

    @Value("${app.brevo.from-email:}")
    private String fromEmail;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

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

    private void send(String to, String subject, String text) {
        if (!mailEnabled) {
            log.info("=== [DEV] EMAIL (não enviado) ===");
            log.info("Para: {}", to);
            log.info("Assunto: {}", subject);
            log.info("Mensagem:\n{}", text);
            log.info("=================================");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", brevoApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                "sender",      Map.of("name", "Medieval Game", "email", fromEmail),
                "to",          List.of(Map.of("email", to)),
                "subject",     subject,
                "textContent", text
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForObject("https://api.brevo.com/v3/smtp/email", request, String.class);
            log.info("Email enviado via Brevo para {}", to);
        } catch (Exception e) {
            log.error("Erro ao enviar email via Brevo para {}: {}", to, e.getMessage());
        }
    }
}
