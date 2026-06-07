package com.medieval.game.service;

import com.medieval.game.model.Consignment;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.ConsignmentRepository;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.steam.SteamItemMapping;
import com.medieval.game.service.steam.SteamMarketProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [MERCADO_STEAM] Mercador Azul: o jogador "consigna" um item (sai da bag → escrow) e o Mercador
 * tenta exportá-lo pro inventário Steam (Community Market) via {@link SteamMarketProvider}. Com a Steam
 * desligada o item fica em HELD e dá pra cancelar (devolve). A venda real (HELD/LINKED → SOLD) acontece
 * fora, na Steam — tratada na F1+ (poll/webhook). Ver docs/PLANO_MERCADO_STEAM.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlueMerchantService {

    private final InventoryItemRepository inventoryRepository;
    private final ConsignmentRepository   consignmentRepository;
    private final PlayerRepository         playerRepository;
    private final SteamMarketProvider      steamProvider;

    /** Entrega um item ao Mercador Azul. Vai pra HELD; se a Steam estiver ligada + conta linkada, vira LINKED. */
    @Transactional
    public Consignment consign(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalStateException("Item not found."));
        if (!item.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This item does not belong to you.");
        if (item.isEquipped())  throw new IllegalStateException("Unequip the item first.");
        if (item.isStashed())   throw new IllegalStateException("Withdraw it from the stash first.");
        if (item.isListed())    throw new IllegalStateException("Item is listed in the Auction House.");
        if (item.isGuarded())   throw new IllegalStateException("Un-guard the item at the Temple first.");
        if (item.isConsigned()) throw new IllegalStateException("Item is already with the Blue Merchant.");
        if (item.isPvpLocked() && player.isPvpFlagged())
            throw new IllegalStateException("Item is PvP-locked right now.");
        if (item.isBroken())    throw new IllegalStateException("Repair the item first.");
        // TODO(decisão): porta de elegibilidade (ex.: só raridade alta) — placeholder: aceita qualquer gear.

        item.setConsigned(true); // sai da bag (igual ao `listed` do Leilão)
        inventoryRepository.save(item);

        Consignment c = new Consignment();
        c.setItem(item);
        c.setPlayer(player);
        c.setSteamItemDef(SteamItemMapping.itemDefFor(item));
        c.setStatus(Consignment.Status.HELD);

        // Exporta pro inventário Steam se a integração estiver ligada e a conta linkada.
        if (steamProvider.isEnabled() && player.getSteamId() != null) {
            SteamMarketProvider.GrantResult r = steamProvider.grantItem(player.getSteamId(), c.getSteamItemDef(), steamProps(item));
            if (r.success()) {
                c.setStatus(Consignment.Status.LINKED);
                c.setSteamItemInstance(r.steamItemInstanceId());
            } else {
                log.warn("[BlueMerchant] grant falhou player={} item={} msg={}", player.getId(), itemId, r.message());
            }
        }
        c.setUpdatedAt(LocalDateTime.now());
        Consignment saved = consignmentRepository.save(c);
        log.info("[BlueMerchant] player={} consign itemId={} status={}", player.getId(), itemId, saved.getStatus());
        return saved;
    }

    /** Cancela uma consignação ativa e devolve o item pra bag. */
    @Transactional
    public void cancel(Player player, Long consignmentId) {
        Consignment c = consignmentRepository.findById(consignmentId)
                .orElseThrow(() -> new IllegalStateException("Consignment not found."));
        if (!c.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Not your consignment.");
        if (c.getStatus() == Consignment.Status.SOLD)
            throw new IllegalStateException("Already sold on Steam — can't return it.");
        if (c.getStatus() == Consignment.Status.RETURNED)
            throw new IllegalStateException("Already returned.");
        // TODO(F1): se LINKED na Steam real, revogar a instância do inventário Steam antes de devolver.

        InventoryItem item = c.getItem();
        item.setConsigned(false);
        inventoryRepository.save(item);
        c.setStatus(Consignment.Status.RETURNED);
        c.setUpdatedAt(LocalDateTime.now());
        consignmentRepository.save(c);
        log.info("[BlueMerchant] player={} cancel consignment={} returned itemId={}", player.getId(), consignmentId, item.getId());
    }

    /** Linka a conta Steam (SteamID64). TODO(F1): validar via auth ticket do cliente Godot. */
    @Transactional
    public void linkSteam(Player player, String steamId) {
        if (steamId == null || steamId.isBlank()) throw new IllegalStateException("Steam ID required.");
        player.setSteamId(steamId.trim());
        playerRepository.save(player);
        log.info("[BlueMerchant] player={} linked steam account", player.getId());
    }

    @Transactional(readOnly = true)
    public List<Consignment> mine(Player player) {
        return consignmentRepository.findByPlayerOrderByCreatedAtDesc(player);
    }

    public boolean steamEnabled() { return steamProvider.isEnabled(); }

    // ── DTOs pra UI (montados DENTRO da transação — open-in-view=false em prod) ──
    public record ConsignmentView(Long id, String itemName, int rarity, String type, String status,
                                  String steamItemDef, String steamItemInstance) {}
    public record ItemView(Long itemId, String name, int rarity, String type, int atk, int def, int hp) {}
    public record MerchantState(boolean steamEnabled, boolean steamLinked, String steamId,
                                List<ConsignmentView> consignments, List<ItemView> consignable) {}

    /** Estado completo do Mercador Azul pra UI: status Steam + consignações + itens consignáveis (bag). */
    @Transactional(readOnly = true)
    public MerchantState state(Player player) {
        List<ConsignmentView> cons = consignmentRepository.findByPlayerOrderByCreatedAtDesc(player).stream()
                .map(c -> new ConsignmentView(c.getId(), c.getItem().getName(), c.getItem().getRarity(),
                        c.getItem().getType().name(), c.getStatus().name(), c.getSteamItemDef(), c.getSteamItemInstance()))
                .toList();
        List<ItemView> bag = inventoryRepository.findAllByPlayer(player).stream()
                .filter(i -> !i.isEquipped() && !i.isStashed() && !i.isListed() && !i.isConsigned() && !i.isBroken())
                .map(i -> new ItemView(i.getId(), i.getName(), i.getRarity(), i.getType().name(),
                        i.getAttackBonus(), i.getDefenseBonus(), i.getHealthBonus()))
                .toList();
        return new MerchantState(steamProvider.isEnabled(), player.getSteamId() != null, player.getSteamId(), cons, bag);
    }

    private Map<String, String> steamProps(InventoryItem item) {
        return Map.of(
                "atk", String.valueOf(item.getAttackBonus()),
                "def", String.valueOf(item.getDefenseBonus()),
                "hp",  String.valueOf(item.getHealthBonus()),
                "rarity", String.valueOf(item.getRarity()),
                "itemLevel", String.valueOf(item.getItemLevel()));
    }
}
