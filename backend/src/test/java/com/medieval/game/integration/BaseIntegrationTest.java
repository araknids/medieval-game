package com.medieval.game.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    private static final AtomicInteger counter = new AtomicInteger(1);

    /** Cria um usuário único e retorna o JWT token */
    protected String registerAndGetToken(String username) throws Exception {
        String email       = username + "@test.com";
        String password    = "senha123";
        String warriorName = "Guerreiro " + username;

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
