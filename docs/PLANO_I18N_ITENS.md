# Plano — i18n dos NOMES/LORE/AFIXOS de item (gerar em PT na criação) [I18N_ITENS]

## Contexto

O cliente Godot agora manda `Accept-Language` (commit `019518d`), então **todo texto
servido pelo backend que tem chave PT** (quests, flavor, diálogo, lore de reino, Torre,
combate, habilidades, conquistas, etc.) volta em Português. Ver `docs/PLANO_I18N_BACKEND.md`.

**O que o header NÃO resolve:** nomes de item, lore e afixos são **gerados em inglês e
gravados no banco** na hora da criação. Como já estão no DB, o locale do request não muda
nada. Decisão do dono (2026-06-15): **gerar em PT na criação** (itens NOVOS saem em PT;
antigos ficam como estão até o soft-wipe). Sem mudança de schema, sem migração.

## Restrição de segurança (por que é viável)

`InventoryItem.name` é **load-bearing** p/ ARMAS: `WeaponType.fromName(name)` infere o tipo
(e `make()` recalcula stats + categoria pelo nome). **`fromName` já entende EN+PT**
(`"axe"/"machado"`, `"bow"/"arco"`, `"greatsword"/"montante"/"espada longa"`, `"mace"/"marreta"`,
`"spear"/"lança"`, `"crossbow"/"besta"`). Por isso os nomes de forja (já em PT) funcionam.
→ **Regra:** toda tradução PT de palavra de ARMA precisa conter um keyword que o `fromName`
reconheça. Itens não-arma: tradução livre.

## Mecanismo

`Messages` (estático, locale do request via `LocaleContextHolder`):

- `Messages.word(en)` → traduz UMA palavra/fragmento (base, sufixo, afixo, nome curado) via
  chave `itemword.<SLUG(en)>` (SLUG = upper + `[^A-Z0-9]+`→`_`). Sem chave / locale EN → o
  próprio `en`. Dedup natural por slug ("Iron Sword" na loja e na lista de armas = 1 chave).
- Lore/origem/itens especiais: chaves dedicadas (`itemlore.*`, `itemorigin.*`, `item.*`).

Tudo em `messages_pt.properties` (só o PT precisa de arquivo; o EN é o default no código).
Sem contexto de request (testes/startup) → default EN → **suíte de testes intacta**.

## Superfície (o que é tocado)

| Fonte | Arquivo | Ação |
|-------|---------|------|
| Nome procedural de drop | `KingdomService.itemName`, `ExpeditionService.itemName` (duplicado) | `word(base)+" "+word(suffix)` |
| Afixo no NOME (prefixo) | `InventoryService` (`p.word + name`) | `word(p.word)` |
| Afixo no CARD | `InventoryController.AffixLine`, `AuctionService` | `word(affix.word)` |
| Lore + origem | `ItemLoreGenerator` | chaves `itemlore.*` / `itemorigin.*` |
| Troféu de zona / chefe | `ZoneService` ("Beast Trophy", "Tower Warden's") | template `item.*` |
| Pool da Loja | `ShopService` (COMMON/UNCOMMON + ALL_WEAPON_NAMES) | `word(name)` **após** o `fromName` |
| Arma inicial da Trial | `ClassChangeService` ("Hunting Bow"/"Worn Hatchet") | `word(...)` (mantém keyword) |

**Já em PT (não mexer):** forja (`SmithingService`, `w.pt()+" de "+m.pt()`), gear inicial
(`InventoryService.giveStarterItems`). Servem o jogador PT; o vazamento inverso (EN vê PT)
fica como follow-up de baixa prioridade.

## Limitação conhecida (gramática)

Nome procedural traduz palavra-a-palavra. `base + sufixo` lê bem em PT ("Espada de Ferro",
"Elmo do Dragão", "Armadura Encantada"). O **afixo-prefixo** fica imperfeito em gênero/posição
("Afiado Espada de Ferro" em vez de "Espada Afiada de Ferro") — aceitável p/ nome procedural
de fantasia; a linha de bônus do afixo no card é a fonte autoritativa. Não vale refatorar a
gramática agora.

## Teste

`mvn -o clean test` (H2) — assinaturas compartilhadas tocadas (`Messages`), build limpa pega o
que o incremental esconde. [feedback-verificar-clean-test]
