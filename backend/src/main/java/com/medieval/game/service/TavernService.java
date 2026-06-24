package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.model.TavernMessage;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TavernMessageRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [TAVERNA] Beber (1 bronze + minigame) → buff stackável; chat entre players; avisos globais.
 * Tempo real por polling (sem WebSocket/SSE). Feed por-servidor (1 banco por deploy [SERVIDORES]).
 * Números são placeholders pra tuning. Desenho: docs/PLANO_TAVERNA.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TavernService {

    public static final long DRINK_COST_BRONZE = 1;
    private static final int  BUFF_MINUTES     = 5;       // cada gole renova os 5 min INTEIROS (treadmill)
    private static final int  MSG_MAX_LEN      = 200;
    private static final int  NAME_MAX_LEN     = 60;
    private static final int  HISTORY_KEEP     = 200;     // mantém ~200 mensagens recentes
    private static final long CHAT_COOLDOWN_MS = 2500;    // anti-spam simples (~1 msg / 2.5s)
    private static final int  COOLDOWN_PRUNE_AT = 256;    // [VARREDURA] poda o map de cooldown qd passar disto
    private static final long COOLDOWN_STALE_MS = 60_000; // entrada > 1min é lixo (cooldown é 2.5s)
    private static final int[] BOTTLE_MILESTONES = {10, 25, 50, 100, 250, 500, 1000};
    // [TAVERNA_CHANCE] O SERVIDOR sorteia o acerto (anti-hack: o cliente não decide). A chance CAI conforme
    // os stacks → fica "mais difícil aos poucos"; reseta quando o buff expira. Números são placeholders.
    private static final double DRINK_BASE_CHANCE = 0.90;   // 90% sem stacks
    private static final double DRINK_CHANCE_STEP = 0.01;   // −1% por stack (encolhe devagar)
    private static final double DRINK_MIN_CHANCE  = 0.15;   // piso 15% (chegar perto do cap fica quase impossível)

    private final PlayerService           playerService;
    private final PlayerRepository        playerRepository;
    private final WarriorRepository       warriorRepository;
    private final TavernMessageRepository messageRepository;

    /** Cooldown de chat em memória (por-deploy). playerId → epochMillis do último post. */
    private final Map<Long, Long> lastChatAt = new ConcurrentHashMap<>();

    // ── Beber ──────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> drink(Player playerArg, boolean success) {
        Player player = playerRepository.findById(playerArg.getId()).orElseThrow();
        playerService.spendBronze(player, DRINK_COST_BRONZE); // 1 bronze SEMPRE (comprou a bebida)
        Warrior w = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        // [TAVERNA_CHANCE] O SERVIDOR sorteia o acerto (anti-hack: o `success` do cliente é IGNORADO, senão
        // daria p/ forjar buff de 100%). A chance cai conforme os stacks; errar = só o bronze.
        boolean active = w.tavernBuffActive();
        if (!active) {                          // [TAVERNA_RESET] buff expirou/inexistia → sessão nova: zera stacks E garrafas
            w.setTavernBuffStacks(0);
            player.setBottlesDrunk(0);
        }
        int curStacks = active ? w.getTavernBuffStacks() : 0;
        boolean ok = ThreadLocalRandom.current().nextDouble() < drinkSuccessChance(curStacks);
        int stacks = curStacks;
        int bottles = player.getBottlesDrunk();
        if (ok) {                               // acertou → +1 stack, renova os 5 min, conta a garrafa
            stacks = Math.min(Warrior.TAVERN_BUFF_CAP, curStacks + 1);
            w.setTavernBuffStacks(stacks);
            w.setTavernBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_MINUTES));
            bottles = player.getBottlesDrunk() + 1;
            player.setBottlesDrunk(bottles);
        }                                       // errou → não mexe em stacks/expiry (o buff segue decaindo)
        warriorRepository.save(w);
        playerRepository.save(player);
        if (ok) announceBottleMilestone(w, bottles);
        log.info("[TavernService] player={} drink ok={} stacks={} bottles={}", player.getId(), ok, stacks, bottles);
        Map<String, Object> res = new HashMap<>(status(player));
        res.put("success", ok);                 // o front mostra copo VAZIO (acerto) ou DERRAMADO (erro)
        return res;
    }

    /** [TAVERNA_CHANCE] Chance de acertar o gole; cai conforme os stacks (dificuldade que sobe), com piso. */
    private double drinkSuccessChance(int stacks) {
        return Math.max(DRINK_MIN_CHANCE, DRINK_BASE_CHANCE - stacks * DRINK_CHANCE_STEP);
    }

    // ── Status (buff + garrafas + custo) ─────────────────────────────────────────
    @Transactional(readOnly = true)   // [VARREDURA] leitura pura
    public Map<String, Object> status(Player playerArg) {
        Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        Warrior w = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        int stacks = w.activeTavernStacks();
        long secs = (stacks > 0 && w.getTavernBuffExpiresAt() != null)
                ? Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), w.getTavernBuffExpiresAt())) : 0;
        return Map.of(
            "drinkCost",       DRINK_COST_BRONZE,
            "bronze",          player.totalBronze(),
            "stacks",          stacks,
            "buffPct",         stacks * 0.01,
            "buffSecondsLeft", secs,
            "bottlesDrunk",    player.getBottlesDrunk(),
            "drinkChance",     drinkSuccessChance(stacks)   // [TAVERNA_CHANCE] barra verde = chance do próximo gole
        );
    }

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

    private void announceBottleMilestone(Warrior w, int bottles) {
        for (int milestone : BOTTLE_MILESTONES) {
            if (bottles == milestone) {
                announce("🍺 " + w.getName() + " just downed " + bottles + " bottles!");
                return;
            }
        }
    }

    private String senderDisplay(Player player) {
        String title = AchievementService.titleString(player); // estático e puro [TITULOS]
        String name  = warriorRepository.findByPlayer(player).map(Warrior::getName).orElse("Adventurer");
        return (title != null && !title.isBlank()) ? (title + " " + name) : name;
    }
}
