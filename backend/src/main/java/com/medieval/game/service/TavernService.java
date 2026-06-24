package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.model.TavernMessage;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.TavernMessageRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [TAVERNA] Chat entre players + avisos globais. Tempo real por polling (sem WebSocket/SSE).
 * Feed por-servidor (1 banco por deploy [SERVIDORES]). Desenho: docs/PLANO_TAVERNA.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TavernService {

    private static final int  MSG_MAX_LEN      = 200;
    private static final int  NAME_MAX_LEN     = 60;
    private static final int  HISTORY_KEEP     = 200;     // mantém ~200 mensagens recentes
    private static final long CHAT_COOLDOWN_MS = 2500;    // anti-spam simples (~1 msg / 2.5s)
    private static final int  COOLDOWN_PRUNE_AT = 256;    // [VARREDURA] poda o map de cooldown qd passar disto
    private static final long COOLDOWN_STALE_MS = 60_000; // entrada > 1min é lixo (cooldown é 2.5s)

    private final WarriorRepository       warriorRepository;
    private final TavernMessageRepository messageRepository;

    /** Cooldown de chat em memória (por-deploy). playerId → epochMillis do último post. */
    private final Map<Long, Long> lastChatAt = new ConcurrentHashMap<>();

    // ── Chat ─────────────────────────────────────────────────────────────────────
    @Transactional
    public TavernMessage postMessage(Player player, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Message cannot be empty.");
        text = text.strip();
        if (text.length() > MSG_MAX_LEN) text = text.substring(0, MSG_MAX_LEN);

        long now = System.currentTimeMillis();
        Long last = lastChatAt.get(player.getId());
        if (last != null && now - last < CHAT_COOLDOWN_MS)
            throw new com.medieval.game.config.LocalizedException(
                    "error.chat_cooldown", "Slow down — wait a moment before posting again.");
        lastChatAt.put(player.getId(), now);
        // [VARREDURA] anti-leak: o map guardava 1 entrada por player p/ sempre. Poda as obsoletas quando
        // crescer (cooldown=2.5s → qualquer entrada >1min já não importa). Custo O(n) só além do limiar.
        if (lastChatAt.size() > COOLDOWN_PRUNE_AT) {
            long cutoff = now - COOLDOWN_STALE_MS;
            lastChatAt.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        return save("CHAT", player.getId(), senderDisplay(player), text);
    }

    // ── Aviso global (genérico — reusável pelos gatilhos futuros: level-up, drop lendário…) ──
    @Transactional
    public TavernMessage announce(String text) {
        return save("ANNOUNCEMENT", 0L, "📢", text);
    }

    // ── Feed (polling) ───────────────────────────────────────────────────────────
    @Transactional(readOnly = true)   // [VARREDURA] leitura pura
    public List<TavernMessage> feed(Long sinceId) {
        if (sinceId == null || sinceId <= 0) {
            List<TavernMessage> last = messageRepository.findTop50ByOrderByIdDesc();
            java.util.Collections.reverse(last); // ordem crescente p/ a UI
            return last;
        }
        return messageRepository.findByIdGreaterThanOrderByIdAsc(sinceId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private TavernMessage save(String type, Long senderId, String name, String text) {
        if (name.length() > NAME_MAX_LEN) name = name.substring(0, NAME_MAX_LEN);
        TavernMessage m = new TavernMessage();
        m.setType(type); m.setSenderPlayerId(senderId); m.setSenderName(name); m.setText(text);
        TavernMessage saved = messageRepository.save(m);
        if (saved.getId() != null && saved.getId() > HISTORY_KEEP)
            messageRepository.deleteByIdLessThan(saved.getId() - HISTORY_KEEP); // prune
        return saved;
    }

    private String senderDisplay(Player player) {
        String title = AchievementService.titleString(player); // estático e puro [TITULOS]
        String name  = warriorRepository.findByPlayer(player).map(Warrior::getName).orElse("Adventurer");
        return (title != null && !title.isBlank()) ? (title + " " + name) : name;
    }
}
