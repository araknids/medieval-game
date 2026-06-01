package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Gera lore e origem para itens — tudo em memória, sem banco de dados.
 * O mesmo item sempre terá o mesmo texto pois é salvo no DB na criação.
 */
@Component
public class ItemLoreGenerator {

    // ── Lore por raridade × tipo ──

    private static final String[][] COMMON_LORE = {
        // WEAPON
        {"Uma arma funcional, sem nada de especial. Cumpre seu papel nas batalhas do dia a dia.",
         "Forjada às pressas por um ferreiro do vilarejo. Não é bonita, mas corta.",
         "Desgastada pelo uso, mas ainda afiada o suficiente para a batalha."},
        // ARMOR (ARMOR, HELMET, SHIELD, GLOVES, BOOTS, PANTS, SHOULDER, NECKLACE, RING)
        {"Uma proteção básica, simples mas funcional. Já viu dias melhores.",
         "Costurada por mãos habilidosas mas com material modesto.",
         "Resistente o suficiente para aguentar as primeiras batalhas de um aventureiro."},
    };

    private static final String[][] UNCOMMON_LORE = {
        // WEAPON
        {"Forjada por um artesão habilidoso que aprendeu o ofício com um mestre. Já participou de várias batalhas.",
         "Tem um equilíbrio perfeito entre ataque e durabilidade. Não é obra de um leigo.",
         "Dizem que pertenceu a um soldado da guarda que a perdeu num confronto nas estradas."},
        // ARMOR
        {"Construída com esmero e materiais de qualidade superior. Oferece proteção real.",
         "Um equipamento digno de um soldado veterano. Resistiu a muitas batalhas.",
         "Fabricada com técnica refinada. Quem a usou antes sabia o que estava fazendo."},
    };

    private static final String[][] RARE_LORE = {
        // WEAPON
        {"Dizem que esta arma pertenceu a um guerreiro lendário que desapareceu misteriosamente nas Terras do Norte.",
         "Emite um leve brilho ao luar. Forjada com metais raros de uma mina hoje abandonada.",
         "Gravuras antigas decoram sua lâmina. Ninguém sabe ao certo o que significam."},
        // ARMOR
        {"Feita com técnicas antigas quase esquecidas. Emana uma aura de poder contido.",
         "As marcas de batalha nela são incontáveis. Sobreviveu onde outros pereceram.",
         "Criada por um armeiro de reputação lendária. Vale muito mais do que aparenta."},
    };

    private static final String[][] EPIC_LORE = {
        // WEAPON
        {"Um artefato de poder imensurável. Dizem que foi banhado no sangue de um dragão ancestral.",
         "O nome desta arma ecoa pelos séculos. Guerreiros caíram de joelhos só de vê-la.",
         "Criada numa forja esquecida pelos deuses, guarda dentro de si um fragmento de batalhas épicas."},
        // ARMOR
        {"Criada pelos próprios forjadores dos deuses, carrega a bênção dos antigos combatentes.",
         "Apenas os mais dignos são considerados merecedores de usá-la.",
         "Nenhum ferreiro vivo consegue reproduzir esta obra. É uma relíquia de outra era."},
    };

    // ── Origens ──

    public String generateLore(int rarity, ItemType type, Random rng) {
        String[][] pool = switch (rarity) {
            case 4 -> EPIC_LORE;
            case 3 -> RARE_LORE;
            case 2 -> UNCOMMON_LORE;
            default -> COMMON_LORE;
        };
        // índice 0 = armas, índice 1 = todo o resto
        int idx = (type == ItemType.WEAPON) ? 0 : 1;
        String[] texts = pool[idx];
        return texts[rng.nextInt(texts.length)];
    }

    public String originFromQuest(String questDisplayName) {
        return "Encontrado durante: " + questDisplayName + ".";
    }

    public String originFromShop(String merchantName) {
        return "Adquirido no Comércio de " + merchantName + ".";
    }

    public String originFromZone(String zoneName) {
        return "Encontrado em exploração na " + zoneName + ".";
    }

    public String originFromSmithing() {
        return "Forjado pelo próprio guerreiro na bigorna do Templo.";
    }

    public String originStarter() {
        return "Equipamento inicial fornecido pela guilda de aventureiros.";
    }

    public String originDrop(String bossOrEnemyName) {
        return "Obtido após derrotar " + bossOrEnemyName + ".";
    }
}
