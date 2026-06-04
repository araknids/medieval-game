package com.medieval.game.integration;

import com.medieval.game.model.PasswordResetToken;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PasswordResetTokenRepository;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// M6 — reset de senha invalida tokens JWT emitidos antes do reset.
@DisplayName("Auditoria M6 | Reset de senha invalida tokens JWT antigos")
class JwtInvalidationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository             playerRepository;
    @Autowired PasswordResetTokenRepository resetTokenRepository;

    @Test
    @DisplayName("Token antigo → 401 após reset; novo login → 200")
    void oldTokenInvalidatedAfterReset() throws Exception {
        String user   = uniqueUser("m6");
        String token1 = registerAndGetToken(user);

        // token antigo funciona
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token1)))
                .andExpect(status().isOk());

        // garante que o iat (em segundos) do token1 seja anterior ao instante do reset
        Thread.sleep(1100);

        // cria um link de reset válido direto no banco e reseta a senha
        Player player = playerRepository.findByUsername(user).orElseThrow();
        PasswordResetToken prt = new PasswordResetToken();
        prt.setToken("m6-reset-" + System.nanoTime());
        prt.setPlayer(player);
        prt.setExpiresAt(LocalDateTime.now().plusHours(1));
        prt.setUsed(false);
        resetTokenRepository.save(prt);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("token", prt.getToken(), "password", "novaSenha1"))))
                .andExpect(status().isOk());

        // token antigo agora é rejeitado
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token1)))
                .andExpect(status().isUnauthorized());

        // login com a senha nova gera um token que funciona
        String token2 = loginAndGetToken(user, "novaSenha1");
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token2)))
                .andExpect(status().isOk());
    }
}
