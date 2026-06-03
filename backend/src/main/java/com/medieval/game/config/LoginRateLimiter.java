package com.medieval.game.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter em memória, sem dependências externas, para os fluxos de auth
 * (login / forgot-password). Janela fixa por chave (IP + usuário/email).
 * Suficiente para deploy single-instance no Railway. [AUDITORIA A10]
 *
 * Conta tentativas dentro de uma janela; bloqueia quando excede o limite.
 * Em login, registramos FALHAS (sucesso limpa a chave); em forgot-password,
 * registramos toda requisição (anti-spam de email).
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ENTRIES = 50_000; // guarda contra crescimento ilimitado

    // [0] = início da janela (epoch ms), [1] = contagem
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    /** true se a chave já estourou o limite dentro da janela atual. */
    public synchronized boolean isBlocked(String key, int maxAttempts, long windowMs) {
        long[] w = windows.get(key);
        if (w == null) return false;
        if (System.currentTimeMillis() - w[0] > windowMs) return false; // janela expirada
        return w[1] >= maxAttempts;
    }

    /** Registra uma tentativa (falha de login ou requisição de reset) na chave. */
    public synchronized void recordAttempt(String key, long windowMs) {
        long now = System.currentTimeMillis();
        long[] w = windows.get(key);
        if (w == null || now - w[0] > windowMs) {
            if (windows.size() > MAX_ENTRIES) windows.clear();
            windows.put(key, new long[]{now, 1});
        } else {
            w[1]++;
        }
    }

    /** Limpa a chave (ex.: login bem-sucedido). */
    public synchronized void reset(String key) {
        windows.remove(key);
    }
}
