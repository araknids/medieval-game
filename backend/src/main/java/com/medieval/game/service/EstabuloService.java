package com.medieval.game.service;

import com.medieval.game.enums.MountType;
import com.medieval.game.model.Mount;
import com.medieval.game.model.Player;
import com.medieval.game.repository.MountRepository;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Estábulo: compra/equipa montarias (coleção). A montaria equipada reduz a estamina das ações
 * (ver PlayerService.discountStamina). Ver docs/PLANO_ESTABULO.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstabuloService {

    private static final long GOLD_TO_BRONZE = 10_000L;

    private final MountRepository  mountRepository;
    private final PlayerRepository playerRepository;
    private final PlayerService    playerService;

    /** Linha do catálogo + estado para o jogador. */
    public record MountView(MountType type, boolean owned, boolean equipped) {}

    /** Catálogo completo (6) com estado owned/equipped do jogador. */
    public List<MountView> list(Player player) {
        Map<MountType, Mount> owned = mountRepository.findByPlayer(player).stream()
                .collect(Collectors.toMap(Mount::getMountType, m -> m, (a, b) -> a));
        return Arrays.stream(MountType.values()).map(t -> {
            Mount m = owned.get(t);
            return new MountView(t, m != null, m != null && m.isEquipped());
        }).toList();
    }

    /** Compra uma montaria: gold (Estábulo) ou SoulStones+VIP (Celestial). Bloqueia duplicata. */
    @Transactional
    public Mount buy(Player player, MountType type) {
        log.info("[EstabuloService] player={} action=buy mount={}", player.getId(), type);
        if (mountRepository.existsByPlayerAndMountType(player, type)) {
            log.warn("[EstabuloService] player={} REJECTED: já possui {}", player.getId(), type);
            throw new IllegalStateException("You already own this mount.");
        }

        if (type.vipOnly) {
            if (!player.isVip()) {
                log.warn("[EstabuloService] player={} REJECTED: {} é VIP-only", player.getId(), type);
                throw new IllegalStateException("This mount is VIP-only. Activate VIP first.");
            }
            if (player.getSoulStones() < type.priceSoulStones) {
                log.warn("[EstabuloService] player={} REJECTED: SoulStones {}/{}", player.getId(), player.getSoulStones(), type.priceSoulStones);
                throw new IllegalStateException("Not enough SoulStones. Required: " + type.priceSoulStones);
            }
            player.setSoulStones(player.getSoulStones() - type.priceSoulStones);
            playerRepository.save(player);
        } else {
            playerService.spendGold(player, type.priceGold * GOLD_TO_BRONZE); // gold → bronze
        }

        Mount mount = new Mount();
        mount.setPlayer(player);
        mount.setMountType(type);
        Mount saved = mountRepository.save(mount);
        log.info("[EstabuloService] player={} action=buy OK mount={} id={}", player.getId(), type, saved.getId());
        return saved;
    }

    /** Equipa uma montaria que o jogador possui (desequipa a anterior). */
    @Transactional
    public void equip(Player player, MountType type) {
        log.info("[EstabuloService] player={} action=equip mount={}", player.getId(), type);
        Mount mount = mountRepository.findByPlayer(player).stream()
                .filter(m -> m.getMountType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("You don't own this mount."));

        mountRepository.findByPlayerAndEquippedTrue(player).ifPresent(cur -> {
            cur.setEquipped(false);
            mountRepository.save(cur);
        });
        mount.setEquipped(true);
        mountRepository.save(mount);
        log.info("[EstabuloService] player={} action=equip OK mount={}", player.getId(), type);
    }

    /** Desequipa a montaria atual (volta a gastar estamina cheia). */
    @Transactional
    public void unequip(Player player) {
        mountRepository.findByPlayerAndEquippedTrue(player).ifPresent(cur -> {
            cur.setEquipped(false);
            mountRepository.save(cur);
            log.info("[EstabuloService] player={} action=unequip OK mount={}", player.getId(), cur.getMountType());
        });
    }
}
