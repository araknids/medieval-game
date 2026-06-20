package com.medieval.game.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.medieval.game.model.WorkSession;
import com.medieval.game.repository.WorkSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected WorkSessionRepository workSessionRepository;

    private static final AtomicInteger counter = new AtomicInteger(1);

    /**
     * [WORK_IDLE] O Trabalho tem timer REAL (instant-complete não o fura), então coletar logo após o
     * start é rejeitado. Para testar a COLETA, adiantamos o finishesAt p/ o passado, simulando o tempo
     * decorrido. Devolve a sessão atualizada.
     */
    protected WorkSession fastForwardWork(long sessionId) {
        WorkSession s = workSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("WorkSession não encontrada: " + sessionId));
        s.setFinishesAt(LocalDateTime.now().minusMinutes(1));
        return workSessionRepository.save(s);
    }

    /** Cria um usuário único e retorna o JWT token */
    protected String registerAndGetToken(String username) throws Exception {
        String email       = username + "@test.com";
        String password    = "senha123";
        // [NICK_LIMIT] warriorName respeita o @Size(max=20) do backend (username longo não estoura).
        String warriorName = "Guerreiro " + username;
        if (warriorName.length() > 20) warriorName = warriorName.substring(0, 20);

        String body = objectMapper.writeValueAsString(Map.of(
                "username",    username,
                "email",       email,
                "password",    password,
                "warriorName", warriorName
        ));

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Faz login e retorna o token */
    protected String loginAndGetToken(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password
        ));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Gera um username único por teste */
    protected static String uniqueUser(String prefix) {
        return prefix + counter.getAndIncrement();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}
