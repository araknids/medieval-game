package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ZoneService {

    private final ZoneActivityRepository   activityRepository;
    private final WarriorRepository        warriorRepository;
    private final InventoryItemRepository  inventoryRepository;
    private final PlayerRepository         playerRepository;
    private final GatheringService         gatheringService;
    private final BattleSimulator          battleSimulator;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // ── Entrar na zona ──

    @Transactional
    public ZoneActivity enter(Player player, Zone zone, ActivityRole role,
                              SkillType skillType, int durationMinutes) {

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));

        if (warrior.isOnMission())
            throw new IllegalStateException("Seu guerreiro já está ocupado");
        if (warrior.isKnockedOut())
            throw new IllegalStateException("Seu guerreiro está inconsciente. Visite o Templo para curar!");

        if (activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS).isPresent())
            throw new IllegalStateException("Você já está em uma expedição");

        if (warrior.getLevel() < zone.minLevel)
            throw new IllegalStateException("Nível insuficiente. Necessário: " + zone.minLevel);

        if (durationMinutes < 30 || durationMinutes > 720)
            throw new IllegalArgumentException("Duração deve ser entre 30 min e 12h");

        // Valida skill para gatherer
        if (role == ActivityRole.GATHERING && skillType == null)
            throw new IllegalArgumentException("Escolha uma habilidade para coletar");

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        ZoneActivity activity = new ZoneActivity();
        activity.setPlayer(player);
        activity.setZone(zone);
        activity.setRole(role);
        activity.setSkillType(skillType);
        activity.setDurationMinutes(durationMinutes);
        activity.setStartedAt(LocalDateTime.now());
        activity.setEndsAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusMinutes(durationMinutes));
        return activityRepository.save(activity);
    }

    // ── Coleta da expedição ──

    public record CollectResult(ZoneActivity activity,
                                List<GatheringService.ResourceDrop> drops,
                                boolean wasAttacked, boolean survived,
                                String lostItemName) {}

    @Transactional
    public CollectResult collect(Player player, Long activityId) {
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedição não encontrada"));

        if (!activity.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Esta expedição não é sua");

        if (activity.getStatus() == ZoneActivityStatus.COMPLETED ||
            activity.getStatus() == ZoneActivityStatus.DEFEATED)
            throw new IllegalStateException("Expedição já finalizada");

        if (!activity.isReadyToCollect() && activity.getStatus() == ZoneActivityStatus.IN_PROGRESS) {
            long secs = java.time.Duration.between(
                    LocalDateTime.now(), activity.getEndsAt()).getSeconds();
            throw new IllegalStateException("Expedição ainda em andamento. Faltam " + secs + "s");
        }

        List<GatheringService.ResourceDrop> drops = new ArrayList<>();
        boolean wasAttacked = false;
        boolean survived    = true;
        String  lostItem    = null;

        // ── Zona Segura: só coleta ──
        if (activity.getZone() == Zone.SAFE || activity.getRole() == ActivityRole.HUNTING) {
            drops = resolveGathering(player, activity);
            activity.setStatus(ZoneActivityStatus.COMPLETED);

        } else {
            // ── PvP/Alto Risco: verifica encontros ──
            PvpResult pvp = resolveEncounters(player, activity);
            wasAttacked = pvp.wasAttacked();
            survived    = pvp.survived();

            if (!survived) {
                // Derrota — perde parte dos recursos e stamina vai a 0
                activity.setStatus(ZoneActivityStatus.DEFEATED);
                activity.setAttacked(true);
                activity.setSurvivedAttack(false);
                activity.setBronzeLost(pvp.bronzeLost());
                activity.setAttackerWarriorName(pvp.attackerName());
                activity.setBattleLog(String.join("\n", pvp.battleLog()));
                activity.setResolvedAt(LocalDateTime.now());

                // HP = 0 + perde buff; stamina = 0
                player.setCurrentStamina(0);
                player.setStaminaUpdatedAt(LocalDateTime.now());
                warriorRepository.findByPlayer(player).ifPresent(w -> {
                    w.applyDamagePercent(100);
                    w.clearBuff();
                    warriorRepository.save(w);
                });

                // Alto Risco: 10% de perder item equipado
                if (activity.getZone() == Zone.HIGH_RISK) {
                    lostItem = maybeDropEquippedItem(player);
                    activity.setLostEquippedItem(lostItem);
                }

                playerRepository.save(player);
            } else {
                // Sobreviveu (com ou sem ataque): coleta normalmente
                drops = resolveGathering(player, activity);
                activity.setStatus(ZoneActivityStatus.COMPLETED);
                if (wasAttacked) {
                    activity.setAttacked(true);
                    activity.setSurvivedAttack(true);
                    activity.setBattleLog(String.join("\n", pvp.battleLog()));
                    activity.setAttackerWarriorName(pvp.attackerName());
                    activity.setResolvedAt(LocalDateTime.now());
                }
            }
        }

        // Aplica drops ao inventário
        for (GatheringService.ResourceDrop drop : drops) {
            gatheringService.addResource(player, drop.type(), drop.quantity());
        }

        // Aplica XP de skill (se gatherer)
        if (activity.getRole() == ActivityRole.GATHERING && activity.getSkillType() != null) {
            SkillLevel skill = gatheringService.getOrCreateSkill(player, activity.getSkillType());
            long xp = (long)(activity.getXpGained());
            gatheringService.addSkillXp(skill, (int) xp);
        }

        // Libera o guerreiro
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        activityRepository.save(activity);
        return new CollectResult(activity, drops, wasAttacked, survived, lostItem);
    }

    // ── Abandona expedição ──

    @Transactional
    public void cancel(Player player, Long activityId) {
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedição não encontrada"));
        if (!activity.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Não é sua");
        if (activity.getStatus() != ZoneActivityStatus.IN_PROGRESS)
            throw new IllegalStateException("Já finalizada");

        activity.setStatus(ZoneActivityStatus.CANCELLED);
        activityRepository.save(activity);

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });
    }

    // ── Verifica expedição ativa ──

    public Optional<ZoneActivity> getCurrentActivity(Player player) {
        return activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS);
    }

    // ── Histórico ──

    public List<ZoneActivity> getHistory(Player player) {
        return activityRepository.findAllByPlayerAndStatusInOrderByStartedAtDesc(
                player, List.of(ZoneActivityStatus.COMPLETED,
                                ZoneActivityStatus.DEFEATED,
                                ZoneActivityStatus.CANCELLED));
    }

    // ── Privados: cálculo de recursos ──

    private List<GatheringService.ResourceDrop> resolveGathering(Player player, ZoneActivity activity) {
        if (activity.getRole() != ActivityRole.GATHERING || activity.getSkillType() == null) {
            return List.of();
        }

        SkillLevel skill = gatheringService.getOrCreateSkill(player, activity.getSkillType());
        double mult = activity.getZone().multiplier;
        int    durationMin = activity.getDurationMinutes();
        int    xpBase = durationMin * (skill.getLevel() / 10 + 2);

        // Simula múltiplas rodadas de coleta
        List<GatheringService.ResourceDrop> allDrops = new ArrayList<>();
        int rounds = Math.max(1, durationMin / 10); // 1 rodada a cada 10 min
        for (int i = 0; i < rounds; i++) {
            allDrops.addAll(gatheringService.collectGatheringDropsOnly(activity.getSkillType(),
                    skill.getLevel(), 10));
        }

        // Aplica multiplicador de zona
        List<GatheringService.ResourceDrop> scaled = new ArrayList<>();
        for (GatheringService.ResourceDrop d : allDrops) {
            scaled.add(new GatheringService.ResourceDrop(d.type(),
                    Math.max(1, Math.round(d.quantity() * mult))));
        }

        long xp = Math.round(xpBase * mult);
        activity.setXpGained(xp);
        return scaled;
    }

    // ── Privados: resolução de PvP ──

    record PvpResult(boolean wasAttacked, boolean survived, long bronzeLost,
                     String attackerName, List<String> battleLog) {}

    private PvpResult resolveEncounters(Player player, ZoneActivity activity) {
        Zone   zone  = activity.getZone();
        int    hours = Math.max(1, activity.getDurationMinutes() / 60);
        Random rng   = new Random();

        Warrior defender = warriorRepository.findByPlayer(player).orElse(null);
        if (defender == null) return new PvpResult(false, true, 0, null, List.of());

        int[] defStats = getWarriorStats(defender, player);

        for (int h = 0; h < hours; h++) {

            // ── Encontro com player (PvP) ──
            if (zone.encounterChancePerHour > 0 && rng.nextInt(100) < zone.encounterChancePerHour) {
                Warrior attacker = findHunterInZone(zone, player, rng);
                if (attacker != null) {
                    int[] atkStats = getWarriorStats(attacker, attacker.getPlayer());
                    String atkName = attacker.getName();

                    List<String> log = battleSimulator.simulate(
                            atkName,         atkStats[0], atkStats[1], atkStats[2], atkStats[3],
                            defender.getName(), defStats[0], defStats[1], defStats[2], defStats[3]);

                    boolean defWon = removeWinnerTag(log, defender.getName());

                    if (!defWon) {
                        long bronzeLost = applyDefeatPenalty(player, attacker.getPlayer());
                        return new PvpResult(true, false, bronzeLost, atkName + " (jogador)", log);
                    }
                    return new PvpResult(true, true, 0, atkName + " (jogador)", log);
                }
            }

            // ── Encontro com NPC ──
            if (rng.nextInt(100) < zone.npcEncounterChancePerHour) {
                int npcLevel = defender.getLevel() + rng.nextInt(4); // até +3
                String npcName = npcName(zone, rng);
                int[] npcStats = npcStatsByLevel(npcLevel, rng);

                List<String> log = battleSimulator.simulate(
                        npcName,           npcStats[0], npcStats[1], npcStats[2], npcStats[3],
                        defender.getName(), defStats[0], defStats[1], defStats[2], defStats[3]);

                boolean defWon = removeWinnerTag(log, defender.getName());

                if (!defWon) {
                    long bronzeLost = applyDefeatPenalty(player, null);
                    return new PvpResult(true, false, bronzeLost, npcName, log);
                }
                return new PvpResult(true, true, 0, npcName, log);
            }
        }

        return new PvpResult(false, true, 0, null, List.of());
    }

    /** Remove a tag WINNER: do final do log e retorna se o defender venceu */
    private boolean removeWinnerTag(List<String> log, String defenderName) {
        if (log.isEmpty()) return true;
        String last = log.get(log.size() - 1);
        boolean defWon = last.contains("WINNER:" + defenderName);
        log.remove(log.size() - 1);
        return defWon;
    }

    /** Aplica penalidade de derrota: perde 15% bronze; hunter (se player) ganha 50% do perdido */
    private long applyDefeatPenalty(Player player, Player hunter) {
        long bronzeLost = Math.round(player.totalBronze() * 0.15);
        if (bronzeLost > 0) {
            player.addBronzeAmount(-bronzeLost);
            playerRepository.save(player);
            if (hunter != null) {
                hunter.addBronzeAmount(bronzeLost / 2);
                playerRepository.save(hunter);
            }
        }
        return bronzeLost;
    }

    // ── Geração de NPCs ──

    private static final String[][] NPC_NAMES = {
        // SAFE
        {"Lobo Selvagem", "Bandoleiro", "Saqueador da Estrada", "Urso Enfurecido", "Javali Gigante"},
        // PVP
        {"Mercenário Corrupto", "Orc Guerreiro", "Cavaleiro Renegado", "Golem de Pedra", "Troll da Montanha"},
        // HIGH_RISK
        {"Demônio Menor", "Lich das Trevas", "Dragão Jovem", "Campeão Infernal", "Espectro da Morte"},
    };

    private String npcName(Zone zone, Random rng) {
        String[] pool = switch (zone) {
            case SAFE      -> NPC_NAMES[0];
            case PVP       -> NPC_NAMES[1];
            case HIGH_RISK -> NPC_NAMES[2];
        };
        return pool[rng.nextInt(pool.length)];
    }

    /** Stats do NPC baseados no nível (até +3 do guerreiro) */
    private int[] npcStatsByLevel(int level, Random rng) {
        int atk = 4 + level * 3 + rng.nextInt(4);
        int def = 2 + level * 2 + rng.nextInt(3);
        int hp  = 70 + level * 20 + rng.nextInt(30);
        int eva = Math.min(8 + level, 25);
        return new int[]{atk, def, hp, eva};
    }

    private Warrior findHunterInZone(Zone zone, Player exclude, Random rng) {
        List<ZoneActivity> hunters = activityRepository.findAllByZoneAndRoleAndStatusAndPlayerNot(
                zone, ActivityRole.HUNTING, ZoneActivityStatus.IN_PROGRESS, exclude);
        if (hunters.isEmpty()) return null;
        ZoneActivity hunterActivity = hunters.get(rng.nextInt(hunters.size()));
        return warriorRepository.findByPlayer(hunterActivity.getPlayer()).orElse(null);
    }

    private int[] getWarriorStats(Warrior w, Player player) {
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player)
                .stream().filter(InventoryItem::isEquipped).toList();
        int atk = w.getTotalBaseAttack()  + equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
        int def = w.getTotalBaseDefense() + equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
        int hp  = w.getTotalBaseHealth()  + equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();
        return new int[]{atk, def, hp, w.getEvasionChance()};
    }

    // Método legado mantido para compatibilidade
    private String npcHunterName(Random rng) {
        String[] names = {"Ladrão das Sombras","Mercenário Solitário","Caçador de Recompensas"};
        return names[rng.nextInt(names.length)];
    }

    private String maybeDropEquippedItem(Player player) {
        Random rng = new Random();
        if (rng.nextDouble() >= 0.10) return null; // 10% chance

        // Itens protegidos pelo Templo não caem
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player)
                .stream().filter(i -> i.isEquipped() && !i.isGuarded()).toList();
        if (equipped.isEmpty()) return null;

        InventoryItem item = equipped.get(rng.nextInt(equipped.size()));
        String name = item.getName();
        inventoryRepository.delete(item);
        return name;
    }
}
