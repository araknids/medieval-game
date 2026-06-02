package com.medieval.game.controller;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.service.GuildService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/guild")
@RequiredArgsConstructor
public class GuildController {

    private final GuildService  guildService;
    private final PlayerService playerService;

    // ── Info da guilda do jogador ─────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> getMyGuild(Authentication auth) {
        Player player = getPlayer(auth);
        Guild guild = guildService.loadGuild(player);
        if (guild == null)
            return ResponseEntity.ok(Map.of("inGuild", false));
        return ResponseEntity.ok(toDetail(player, guild));
    }

    // ── Listar todas as guildas ───────────────────────────────────────────────
    @GetMapping("/list")
    public ResponseEntity<?> list(Authentication auth) {
        Player player = getPlayer(auth);
        Guild myGuild = guildService.loadGuild(player);
        Long myGuildId = myGuild != null ? myGuild.getId() : null;

        List<?> guilds = guildService.listAll().stream().map(g -> {
            int members = guildService.members(g).size();
            return Map.of(
                "id",          g.getId(),
                "name",        g.getName(),
                "description", g.getDescription() != null ? g.getDescription() : "",
                "level",       g.getLevel(),
                "gold",        g.getGold(),
                "members",     members,
                "maxMembers",  g.maxMembers(),
                "isMine",      g.getId().equals(myGuildId)
            );
        }).toList();

        return ResponseEntity.ok(guilds);
    }

    // ── Criar guilda ──────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            Guild guild = guildService.create(player, req.name(), req.description());
            return ResponseEntity.ok(toDetail(player, guild));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Entrar na guilda ──────────────────────────────────────────────────────
    @PostMapping("/join/{guildId}")
    public ResponseEntity<?> join(@PathVariable Long guildId, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            Guild guild = guildService.join(player, guildId);
            return ResponseEntity.ok(toDetail(player, guild));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Sair da guilda ────────────────────────────────────────────────────────
    @PostMapping("/leave")
    public ResponseEntity<?> leave(Authentication auth) {
        try {
            guildService.leave(getPlayer(auth));
            return ResponseEntity.ok(Map.of("message", "Você saiu da guilda.", "inGuild", false));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Expulsar membro ───────────────────────────────────────────────────────
    @PostMapping("/kick/{playerId}")
    public ResponseEntity<?> kick(@PathVariable Long playerId, Authentication auth) {
        try {
            guildService.kick(getPlayer(auth), playerId);
            return ResponseEntity.ok(Map.of("message", "Membro expulso."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Transferir liderança ──────────────────────────────────────────────────
    @PostMapping("/transfer/{playerId}")
    public ResponseEntity<?> transfer(@PathVariable Long playerId, Authentication auth) {
        try {
            Player leader = getPlayer(auth);
            guildService.transfer(leader, playerId);
            return ResponseEntity.ok(Map.of("message", "Liderança transferida."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Doar bronze para a guilda ─────────────────────────────────────────────
    @PostMapping("/donate")
    public ResponseEntity<?> donate(@RequestBody DonateRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            Guild guild = guildService.donate(player, req.amount());
            return ResponseEntity.ok(Map.of(
                "message",  "Doação realizada!",
                "guildGold", guild.getGold()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Subir nível da guilda ─────────────────────────────────────────────────
    @PostMapping("/levelup")
    public ResponseEntity<?> levelUp(Authentication auth) {
        try {
            Guild guild = guildService.levelUp(getPlayer(auth));
            return ResponseEntity.ok(Map.of(
                "message",    "Guilda subiu para nível " + guild.getLevel() + "!",
                "level",      guild.getLevel(),
                "maxMembers", guild.maxMembers(),
                "guildGold",  guild.getGold()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Dissolver guilda ──────────────────────────────────────────────────────
    @DeleteMapping
    public ResponseEntity<?> disband(Authentication auth) {
        try {
            guildService.disband(getPlayer(auth));
            return ResponseEntity.ok(Map.of("message", "Guilda dissolvida.", "inGuild", false));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> toDetail(Player player, Guild guild) {
        List<Player> members = guildService.members(guild);

        List<Map<String, Object>> memberList = members.stream()
                .map(m -> {
                    boolean isLeader = guild.getLeaderId().equals(m.getId());
                    return Map.<String, Object>of(
                        "playerId",    m.getId(),
                        "warriorName", guildService.warriorName(m),
                        "isLeader",    isLeader,
                        "isMe",        m.getId().equals(player.getId())
                    );
                }).toList();

        // Donation rank — sorted by guildDonatedBronze descending
        List<Map<String, Object>> donationRank = members.stream()
                .filter(m -> m.getGuildDonatedBronze() > 0)
                .sorted((a, b) -> Long.compare(b.getGuildDonatedBronze(), a.getGuildDonatedBronze()))
                .map(m -> Map.<String, Object>of(
                    "warriorName",   guildService.warriorName(m),
                    "donatedBronze", m.getGuildDonatedBronze(),
                    "isMe",          m.getId().equals(player.getId())
                )).toList();

        boolean isLeader = guild.getLeaderId().equals(player.getId());

        // Decompose treasury (stored in bronze) into bronze/silver/gold for display
        long rawBronze   = guild.getGold();
        long tGold       = rawBronze / 10000;
        long tSilver     = (rawBronze % 10000) / 100;
        long tBronze     = rawBronze % 100;

        long costBronze  = guild.levelUpCost();
        long cGold       = costBronze / 10000;
        long cSilver     = (costBronze % 10000) / 100;
        long cBronze     = costBronze % 100;

        return Map.ofEntries(
            Map.entry("inGuild",          true),
            Map.entry("id",               guild.getId()),
            Map.entry("name",             guild.getName()),
            Map.entry("description",      guild.getDescription() != null ? guild.getDescription() : ""),
            Map.entry("level",            guild.getLevel()),
            Map.entry("treasuryBronze",   rawBronze),
            Map.entry("treasury",         Map.of("bronze", tBronze, "silver", tSilver, "gold", tGold)),
            Map.entry("levelUpCost",      costBronze),
            Map.entry("levelUpCostFmt",   Map.of("bronze", cBronze, "silver", cSilver, "gold", cGold)),
            Map.entry("maxMembers",       guild.maxMembers()),
            Map.entry("members",          memberList),
            Map.entry("isLeader",         isLeader),
            Map.entry("xpBonus",          guild.xpBonus()),
            Map.entry("dropBonus",        guild.dropBonus()),
            Map.entry("bronzeBonus",      guild.bronzeBonus()),
            Map.entry("donationRank",     donationRank)
        );
    }

    record CreateRequest(String name, String description) {}
    record DonateRequest(long amount) {}
}
