package com.medieval.game.service;

import com.medieval.game.enums.PetType;
import com.medieval.game.model.Pet;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/** Pets equipáveis (igual ao Estábulo das montarias). Vêm de quest (Luna) ou do mercado VIP (gato). [PETS] */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetService {

    private final PetRepository    petRepository;
    private final com.medieval.game.repository.PlayerRepository playerRepository; // compra com SoulStone. [PETS]

    public record PetView(PetType type, String displayName, String icon, int hpBonusPercent,
                          int dexBonus, int soulStoneCost, boolean owned, boolean equipped) {}

    public boolean owns(Player player, PetType type) {
        return petRepository.existsByPlayerAndPetType(player, type);
    }

    public List<PetView> list(Player player) {
        List<Pet> owned = petRepository.findByPlayer(player);
        return Arrays.stream(PetType.values()).map(t -> {
            Pet p = owned.stream().filter(o -> o.getPetType() == t).findFirst().orElse(null);
            return new PetView(t, t.displayName, t.icon, t.hpBonusPercent, t.dexBonus, t.soulStoneCost,
                    p != null, p != null && p.isEquipped());
        }).toList();
    }

    /** Compra um pet com SoulStone (mercado VIP). soulStoneCost=0 → não comprável (vem de quest). [PETS] */
    @Transactional
    public Pet buy(Player player, PetType type) {
        if (type.soulStoneCost <= 0) throw new IllegalArgumentException(type.displayName + " is not for sale.");
        if (owns(player, type))      throw new com.medieval.game.config.LocalizedException("error.pet_owned", "You already own {0}.", type.displayName);
        if (player.getSoulStones() < type.soulStoneCost)
            throw new com.medieval.game.config.LocalizedException("error.soulstones_required", "Not enough SoulStones. Required: {0}", type.soulStoneCost);
        player.setSoulStones(player.getSoulStones() - type.soulStoneCost);
        playerRepository.save(player);
        log.info("[PetService] player={} action=buyPet type={} cost={}SS", player.getId(), type, type.soulStoneCost);
        return grant(player, type);
    }

    /** Concede um pet (idempotente) e auto-equipa se nenhum estiver equipado. [PETS] */
    @Transactional
    public Pet grant(Player player, PetType type) {
        var existing = petRepository.findByPlayerAndPetType(player, type);
        if (existing.isPresent()) return existing.get();

        boolean hasEquipped = petRepository.findByPlayerAndEquippedTrue(player).isPresent();
        Pet pet = new Pet();
        pet.setPlayer(player);
        pet.setPetType(type);
        pet.setEquipped(!hasEquipped); // o 1º pet já entra equipado
        Pet saved = petRepository.save(pet);
        log.info("[PetService] player={} action=grantPet type={} equipped={}", player.getId(), type, saved.isEquipped());
        return saved;
    }

    @Transactional
    public void equip(Player player, PetType type) {
        Pet pet = petRepository.findByPlayerAndPetType(player, type)
                .orElseThrow(() -> new IllegalStateException("You don't own that pet."));
        petRepository.findByPlayerAndEquippedTrue(player).ifPresent(cur -> { cur.setEquipped(false); petRepository.save(cur); });
        pet.setEquipped(true);
        petRepository.save(pet);
        log.info("[PetService] player={} action=equipPet type={}", player.getId(), type);
    }

    @Transactional
    public void unequip(Player player) {
        petRepository.findByPlayerAndEquippedTrue(player).ifPresent(cur -> { cur.setEquipped(false); petRepository.save(cur); });
        log.info("[PetService] player={} action=unequipPet", player.getId());
    }
}
