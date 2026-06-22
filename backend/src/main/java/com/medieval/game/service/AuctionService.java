package com.medieval.game.service;

import com.medieval.game.model.AuctionListing;
import com.medieval.game.model.AuctionListing.Status;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Casa de Leilão (buyout, preço fixo). Taxa 5% ao postar (queima) + 15% na venda → vendedor recebe 80%.
 * 2 dias, máx 10 listagens. Item fica flagado `listed` (sai da bag) e só muda de dono na venda. [LEILAO]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final double FEE_PCT      = 0.05; // taxa adiantada (queima, perde sempre)
    private static final double SALE_CUT_PCT = 0.15; // corte na venda → vendedor recebe 85% do preço
    private static final int    MAX_DAYS     = 2;
    private static final int    MAX_ACTIVE   = 10;

    private final AuctionListingRepository listingRepo;
    private final InventoryItemRepository  inventoryRepository;
    private final PlayerRepository         playerRepository;
    private final WarriorRepository        warriorRepository;
    private final SocketedGemRepository    gemRepository;
    private final ItemAffixRepository      affixRepository;
    private final PlayerService            playerService;
    private final InventoryService         inventoryService;
    private final MailService              mailService;

    public record AuctionView(Long listingId, long price, long sellerPayout, String sellerName,
                              long secondsLeft, boolean isMine,
                              Long itemId, String name, String type, String typeDisplay,
                              int rarity, String rarityName, int attackBonus, int defenseBonus, int healthBonus,
                              int sockets, int durability, int itemLevel,
                              List<String> affixes, List<String> gems, String outfitTheme) {}

    // ── Postar ──────────────────────────────────────────────────────────────────
    @Transactional
    public AuctionListing list(Player playerArg, Long itemId, long price) {
        log.info("[AuctionService] player={} action=list itemId={} price={}", playerArg.getId(), itemId, price);
        if (price < 1) throw new IllegalArgumentException("Price must be at least 1 bronze.");
        Player player = playerRepository.findById(playerArg.getId()).orElseThrow();

        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found."));
        if (!item.getPlayer().getId().equals(player.getId())) throw new IllegalStateException("This item does not belong to you.");
        if (item.isEquipped())  throw new IllegalStateException("Unequip the item before listing it.");
        if (item.isStashed())   throw new IllegalStateException("Withdraw the item from the stash first.");
        if (item.isGuarded())   throw new IllegalStateException("Item is protected by the Temple — un-guard it first.");
        if (item.isPvpLocked()) throw new IllegalStateException("Item is PvP-locked — can't list it now.");
        if (item.isListed())    throw new IllegalStateException("Item is already listed.");
        if (item.isConsigned()) throw new IllegalStateException("Item is consigned with the Blue Merchant."); // [MERCADO_STEAM]
        if (item.isRunPending()) throw new IllegalStateException("Item is in a Delve run (not yet extracted)."); // [INCURSAO]
        // [BALANCE_ECON] Só Raro+ é negociável — Comum/Incomum = soulbound de mercado (não inunda o market).
        if (item.getRarity() < InventoryService.MIN_TRADE_RARITY)
            throw new com.medieval.game.config.LocalizedException("error.item_soulbound",
                    "Only Rare or better items can be traded on the market.");

        if (listingRepo.countBySellerAndStatus(player, Status.ACTIVE) >= MAX_ACTIVE)
            throw new com.medieval.game.config.LocalizedException("error.auction_max_active", "You already have {0} active listings.", MAX_ACTIVE);

        long fee = Math.round(price * FEE_PCT);
        playerService.spendBronze(player, fee); // 5% queimado (lança se não tiver saldo)

        item.setListed(true);
        inventoryRepository.save(item);

        AuctionListing l = new AuctionListing();
        l.setItem(item);
        l.setSeller(player);
        l.setPrice(price);
        l.setListedAt(LocalDateTime.now());
        l.setEndsAt(LocalDateTime.now().plusDays(MAX_DAYS));
        AuctionListing saved = listingRepo.save(l);
        log.info("[AuctionService] player={} listed itemId={} as listing={} fee={}", player.getId(), itemId, saved.getId(), fee);
        return saved;
    }

    // ── Comprar (buyout) ─────────────────────────────────────────────────────────
    @Transactional
    public AuctionView buy(Player buyerArg, Long listingId) {
        log.info("[AuctionService] player={} action=buy listing={}", buyerArg.getId(), listingId);
        AuctionListing l = listingRepo.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found."));
        if (l.getStatus() != Status.ACTIVE) throw new IllegalStateException("This listing is no longer active.");
        if (l.isExpired()) { expire(l); throw new IllegalStateException("This auction has expired."); }

        Player buyer = playerRepository.findById(buyerArg.getId()).orElseThrow();
        if (l.getSeller().getId().equals(buyer.getId())) throw new IllegalStateException("You can't buy your own listing.");
        if (inventoryService.bagSpaceLeft(buyer) < 1) throw new IllegalStateException("You need a free bag slot to buy.");
        if (buyer.totalBronze() < l.getPrice()) throw new IllegalStateException("Not enough bronze.");

        playerService.spendBronze(buyer, l.getPrice());
        long payout = Math.round(l.getPrice() * (1 - SALE_CUT_PCT)); // 85% (15% queimado)
        Player seller = playerRepository.findById(l.getSeller().getId()).orElseThrow();
        seller.addBronzeAmount(payout);
        playerRepository.save(seller);

        InventoryItem item = l.getItem();
        item.setPlayer(buyer);   // transfere (joias/afixos vão junto via FK)
        item.setListed(false);
        inventoryRepository.save(item);

        l.setStatus(Status.SOLD);
        l.setBuyerId(buyer.getId());
        listingRepo.save(l);

        mailService.sendSystemMail(seller, "🏪 Your '" + item.getName() + "' sold for " + l.getPrice()
                + " bronze (you received " + payout + " after the 15% fee).");
        mailService.sendSystemMail(buyer, "🏪 You bought '" + item.getName() + "' for " + l.getPrice() + " bronze.");
        log.info("[AuctionService] listing={} SOLD to player={} payout={}", l.getId(), buyer.getId(), payout);
        return toViews(List.of(l), buyer.getId()).get(0);
    }

    // ── Cancelar (sem reembolso da taxa) ─────────────────────────────────────────
    @Transactional
    public void cancel(Player player, Long listingId) {
        AuctionListing l = listingRepo.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found."));
        if (l.getStatus() != Status.ACTIVE) throw new IllegalStateException("This listing is no longer active.");
        if (!l.getSeller().getId().equals(player.getId())) throw new IllegalStateException("That listing isn't yours.");
        InventoryItem item = l.getItem();
        item.setListed(false); // volta pra bag (a taxa de 5% não é reembolsada)
        inventoryRepository.save(item);
        l.setStatus(Status.CANCELLED);
        listingRepo.save(l);
        log.info("[AuctionService] player={} cancelled listing={}", player.getId(), listingId);
    }

    // ── Expiração ────────────────────────────────────────────────────────────────
    @Transactional
    public void expire(AuctionListing l) {
        // [VARREDURA] Claim atômico (ACTIVE→EXPIRED): substitui o check-then-act `if status != ACTIVE`.
        // O perdedor sai com rowcount 0 (sem lançar) → não envenena o batch de expiração.
        if (listingRepo.claimStatus(l.getId(), Status.ACTIVE, Status.EXPIRED) == 0) return;
        InventoryItem item = l.getItem();
        item.setListed(false); // volta pro vendedor (permitido mesmo com a bag cheia — é item dele)
        inventoryRepository.save(item);
        playerRepository.findById(l.getSeller().getId()).ifPresent(s ->
                mailService.sendSystemMail(s, "🏪 Your '" + item.getName() + "' didn't sell in 2 days and was returned to your bag."));
        log.info("[AuctionService] listing={} EXPIRED (returned to seller)", l.getId());
    }

    @Transactional
    public void expireDueAuctions() {
        List<AuctionListing> due = listingRepo.findActiveDue(LocalDateTime.now());
        for (AuctionListing l : due) {
            try { expire(l); } catch (Exception e) { log.error("Error expiring auction {}: {}", l.getId(), e.getMessage(), e); }
        }
        if (!due.isEmpty()) log.info("[AuctionService] expired {} due listing(s)", due.size());
    }

    // ── Consultas ────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AuctionView> browse(Player me) {
        // [AUDITORIA_2 A5] capa em 200 (mais recentes) — não serializa o livro inteiro de uma vez
        List<AuctionListing> all = listingRepo.findTop200ByStatusOrderByListedAtDesc(Status.ACTIVE)
                .stream().filter(l -> !l.isExpired()).toList();
        return toViews(all, me.getId());
    }

    @Transactional(readOnly = true)
    public List<AuctionView> mine(Player me) {
        Player p = playerRepository.findById(me.getId()).orElseThrow();
        List<AuctionListing> all = listingRepo.findBySellerAndStatus(p, Status.ACTIVE).stream().filter(l -> !l.isExpired()).toList();
        return toViews(all, me.getId());
    }

    // ── Helpers ──
    private List<AuctionView> toViews(List<AuctionListing> listings, Long myId) {
        if (listings.isEmpty()) return List.of();
        List<InventoryItem> items = listings.stream().map(AuctionListing::getItem).toList();
        Map<Long, List<SocketedGem>> gemsByItem = gemRepository.findAllByItemIn(items).stream()
                .collect(Collectors.groupingBy(g -> g.getItem().getId()));
        Map<Long, List<ItemAffix>> affByItem = affixRepository.findAllByItemIn(items).stream()
                .collect(Collectors.groupingBy(a -> a.getItem().getId()));
        // [AUDITORIA_2 A5] nomes dos vendedores em 1 query (em vez de findByPlayer por listagem)
        Map<Long, String> sellerNames = warriorRepository.findByPlayerIn(
                        listings.stream().map(AuctionListing::getSeller).toList()).stream()
                .collect(Collectors.toMap(w -> w.getPlayer().getId(), Warrior::getName, (a, b) -> a));

        List<AuctionView> out = new ArrayList<>();
        for (AuctionListing l : listings) {
            InventoryItem it = l.getItem();
            String sellerName = sellerNames.getOrDefault(l.getSeller().getId(), "?");
            long secs = Math.max(0, Duration.between(LocalDateTime.now(), l.getEndsAt()).getSeconds());
            List<String> affixes = affByItem.getOrDefault(it.getId(), List.of()).stream()
                    .map(a -> Messages.word(a.getAffix().word) + " (+" + a.getMagnitude() + " " + a.getAffix().stat.name() + ")").toList(); // [I18N_ITENS]
            List<String> gems = gemsByItem.getOrDefault(it.getId(), List.of()).stream()
                    .map(g -> g.getGemType().displayName).toList();
            out.add(new AuctionView(
                    l.getId(), l.getPrice(), Math.round(l.getPrice() * (1 - SALE_CUT_PCT)), sellerName, secs,
                    l.getSeller().getId().equals(myId),
                    it.getId(), it.getName(), it.getType().name(), it.getType().displayName,
                    it.getRarity(), rarityName(it.getRarity()),
                    it.getAttackBonus(), it.getDefenseBonus(), it.getHealthBonus(),
                    it.getSockets(), it.getDurability(), it.getItemLevel(),
                    affixes, gems,
                    it.getOutfitTheme() != null ? it.getOutfitTheme() : InventoryService.outfitThemeFor(it.getName()))); // [OUTFITS_CLASSE]
        }
        return out;
    }

    private static String rarityName(int r) {
        return switch (r) {
            case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; case 5 -> "Lendário"; default -> "Comum";
        };
    }
}
