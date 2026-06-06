package com.medieval.game.controller;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.service.GuildService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TerritoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/guild")
@RequiredArgsConstructor
public class GuildController {

    private final GuildService    guildService;
    private final PlayerService   playerService;
    private final TerritoryService territoryService; // p/ currentCycleId() do cansaço. [GUERRA_ROSTER]

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
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        Guild guild = guildService.create(player, req.name(), req.description());
        return ResponseEntity.ok(toDetail(player, guild));
    }

    // ── Entrar na guilda ──────────────────────────────────────────────────────
    @PostMapping("/join/{guildId}")
    public ResponseEntity<?> join(@PathVariable Long guildId, Authentication auth) {
        Player player = getPlayer(auth);
        Guild guild = guildService.join(player, guildId);
        return ResponseEntity.ok(toDetail(player, guild));
    }

    // ── Sair da guilda ────────────────────────────────────────────────────────
    @PostMapping("/leave")
    public ResponseEntity<?> leave(Authentication auth) {
        guildService.leave(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", "You left the guild.", "inGuild", false));
    }

    // ── Expulsar membro ───────────────────────────────────────────────────────
    @PostMapping("/kick/{playerId}")
    public ResponseEntity<?> kick(@PathVariable Long playerId, Authentication auth) {
        guildService.kick(getPlayer(auth), playerId);
        return ResponseEntity.ok(Map.of("message", "Member kicked."));
    }

    // ── Transferir liderança ──────────────────────────────────────────────────
    @PostMapping("/transfer/{playerId}")
    public ResponseEntity<?> transfer(@PathVariable Long playerId, Authentication auth) {
        Player leader = getPlayer(auth);
        guildService.transfer(leader, playerId);
        return ResponseEntity.ok(Map.of("message", "Leadership transferred."));
    }

    // ── Doar bronze para a guilda (sobe o nível automaticamente) ──────────────── [GUILD_LEVEL_GOLD]
    @PostMapping("/donate")
    public ResponseEntity<?> donate(@Valid @RequestBody DonateRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        GuildService.DonateResult res = guildService.donate(player, req.amount());
        Guild guild = res.guild();
        return ResponseEntity.ok(Map.of(
            "message",   "Donation successful!",
            "guildGold", guild.getGold(),
            "level",     res.newLevel(),
            "leveledUp", res.leveledUp()
        ));
    }

    // ── Dissolver guilda ──────────────────────────────────────────────────────
    @DeleteMapping
    public ResponseEntity<?> disband(Authentication auth) {
        guildService.disband(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", "Guild disbanded.", "inGuild", false));
    }

    // ── Roster de guerra (líder escolhe até 15 p/ a batalha de território) ────── [GUERRA_ROSTER]
    @PostMapping("/roster")
    public ResponseEntity<?> setRoster(@Valid @RequestBody RosterRequest req, Authentication auth) {
        guildService.setWarRoster(getPlayer(auth), req.memberIds());
        return ResponseEntity.ok(Map.of("message", "Battle roster saved."));
    }

    // ── Formação 3×5 da guerra (líder posiciona os membros) ────── [GUERRA_FORMACAO]
    @PostMapping("/war-formation")
    public ResponseEntity<?> setFormation(@Valid @RequestBody FormationRequest req, Authentication auth) {
        var slots = (req.slots() == null ? List.<SlotDto>of() : req.slots()).stream()
                .map(s -> new com.medieval.game.service.GuildService.FormationSlot(s.playerId(), s.lane(), s.depth()))
                .toList();
        guildService.setWarFormation(getPlayer(auth), slots);
        return ResponseEntity.ok(Map.of("message", "War formation saved."));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> toDetail(Player player, Guild guild) {
        List<Player> members = guildService.members(guild);
        long cycle = territoryService.currentCycleId(); // p/ exibir o cansaço da próxima batalha. [GUERRA_ROSTER]

        List<Map<String, Object>> memberList = members.stream()
                .map(m -> {
                    boolean isLeader = guild.getLeaderId().equals(m.getId());
                    return Map.<String, Object>of(
                        "playerId",    m.getId(),
                        "warriorName", guildService.warriorName(m),
                        "title",       com.medieval.game.service.AchievementService.titleString(m), // [TITULOS]
                        "isLeader",    isLeader,
                        "isMe",        m.getId().equals(player.getId()),
                        "inWarRoster", m.isInWarRoster(),                              // [GUERRA_ROSTER]
                        "warLane",     m.getWarLane(),                                 // [GUERRA_FORMACAO]
                        "warDepth",    m.getWarDepth(),
                        "fatiguePct",  guildService.warriorFatiguePct(m, cycle)        // [GUERRA_ROSTER]
                    );
                }).toList();

        // Donation rank — sorted by guildDonatedBronze descending
        List<Map<String, Object>> donationRank = members.stream()
                .filter(m -> m.getGuildDonatedBronze() > 0)
                .sorted((a, b) -> Long.compare(b.getGuildDonatedBronze(), a.getGuildDonatedBronze()))
                .map(m -> Map.<String, Object>of(
                    "warriorName",   guildService.warriorName(m),
                    "title",         com.medieval.game.service.AchievementService.titleString(m), // [TITULOS]
                    "donatedBronze", m.getGuildDonatedBronze(),
                    "isMe",          m.getId().equals(player.getId())
                )).toList();

        boolean isLeader = guild.getLeaderId().equals(player.getId());

        // Decompose treasury (stored in bronze) into bronze/silver/gold for display
        long rawBronze   = guild.getGold();
        long tGold       = rawBronze / 10000;
        long tSilver     = (rawBronze % 10000) / 100;
        long tBronze     = rawBronze % 100;

        return Map.ofEntries(
            Map.entry("inGuild",          true),
            Map.entry("id",               guild.getId()),
            Map.entry("name",             guild.getName()),
            Map.entry("description",      guild.getDescription() != null ? guild.getDescription() : ""),
            Map.entry("level",            guild.getLevel()),
            Map.entry("maxLevel",         Guild.MAX_LEVEL),
            Map.entry("treasuryBronze",   rawBronze),
            Map.entry("treasury",         Map.of("bronze", tBronze, "silver", tSilver, "gold", tGold)),
            // [GUILD_LEVEL_GOLD] nível derivado do gold acumulado (progresso pro próximo nível)
            Map.entry("lifetimeGold",     guild.getLifetimeGold()),
            Map.entry("nextLevelGold",    guild.goldForNextLevel()),   // -1 se já no nível máximo
            Map.entry("goldToNextLevel",  guild.goldToNextLevel()),
            Map.entry("levelProgressPct", guild.levelProgressPct()),
            Map.entry("maxMembers",       guild.maxMembers()),
            Map.entry("members",          memberList),
            Map.entry("isLeader",         isLeader),
            Map.entry("xpBonus",          guild.xpBonus()),
            Map.entry("dropBonus",        guild.dropBonus()),
            Map.entry("bronzeBonus",      guild.bronzeBonus()),
            Map.entry("donationRank",     donationRank)
        );
    }

    // Nome da guild com charset restrito (defesa em profundidade contra XSS — aparece p/ outros).
    // A descrição é texto livre e é escapada na exibição. [XSS]
    record CreateRequest(
            @NotBlank @Size(min = 3, max = 30)
            @Pattern(regexp = "[\\p{L}\\p{N} ._'-]+", message = "Guild name has invalid characters")
            String name,
            @Size(max = 200) String description) {}
    record DonateRequest(@Min(1) long amount) {}
    // Roster de guerra: lista de playerIds escolhidos (≤15 validado no service). [GUERRA_ROSTER]
    record RosterRequest(@Size(max = 15) List<Long> memberIds) {}

    // [GUERRA_FORMACAO] Formação 3×5: cada membro numa célula (lane 0-2, depth 0-4).
    record SlotDto(Long playerId, int lane, int depth) {}
    record FormationRequest(@Size(max = 15) List<SlotDto> slots) {}
}
