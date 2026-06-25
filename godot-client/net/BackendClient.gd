class_name BackendClient
extends Node
# ── Cliente HTTP do backend (Spring Boot) ──────────────────────────────────────
# Fala com a MESMA API REST + JWT que a web usa. Foundation da Fase 2 do GODOT_3D.
# Uso (async):
#   var c := BackendClient.new(); add_child(c)
#   var r = await c.login("user", "senha")
#   if r.ok: var w = await c.get_warrior()
# Plano: docs/PLANO_GODOT_3D.md (Fase 2)

## URL do servidor. Produção (Railway) por padrão; troque p/ "http://localhost:8080" no dev local.
## [LOCAL-TEST] apontado pro backend local — NÃO COMMITAR assim; reverter pro Railway antes de pushar.
var base_url := "http://localhost:8080"

## JWT obtido no login. Enviado como `Authorization: Bearer <token>` nas chamadas autenticadas.
var token := ""

## Nº de MUTAÇÕES feitas (POST/DELETE bem-sucedidos). O Shell usa pra revalidar telas cacheadas:
## se nada mudou desde a última visita, revisitar a tela NÃO refaz request. [PLANO_UI_SHELL_GODOT]
var mutation_count := 0

# ── Pool de conexões keep-alive ────────────────────────────────────────────────
# Em vez de abrir uma conexão TCP+TLS nova por request (handshake caro a cada chamada),
# mantemos um POOL de HTTPClient que ficam CONECTADOS e são reusados — igual ao pool
# keep-alive do navegador. Várias conexões = chamadas paralelas (uma por conexão livre).
# ⚠ Um único batch_get([...]) segura N conexões ao mesmo tempo (1 por endpoint) até terminar.
# Se N > POOL_MAX, o batch trava esperando uma conexão que ele mesmo segura (deadlock). Por isso
# POOL_MAX TEM de ser >= o maior batch da UI (hoje o World._open pede 5). 8 dá folga. [REDE_POOL]
const POOL_MAX := 8          # máx. de conexões simultâneas pro mesmo host
var _pool: Array = []        # Array[_Conn]
var _host := ""              # destino parseado de base_url (preenchido sob demanda)
var _port := 443
var _use_tls := true

# Uma conexão do pool: um HTTPClient persistente + flag de uso.
class _Conn:
	var http := HTTPClient.new()
	var busy := false

## POST /api/auth/login → guarda o token. Retorna {ok, status, json, raw, error}.
func login(username: String, password: String) -> Dictionary:
	var r := await _request(HTTPClient.METHOD_POST, "/api/auth/login",
			{"username": username, "password": password}, false)
	if r.get("ok") and r.get("json") is Dictionary and r["json"].has("token"):
		token = str(r["json"]["token"])
	return r

## POST /api/auth/register → cria conta E já loga (guarda o token). Campos: username (3-20),
## warriorName (≤30), email (válido), password (≥8), gender ("MALE"/"FEMALE", cosmético). [OUTFITS_FEMALE]
func register(username: String, warrior_name: String, email: String, password: String, gender := "MALE") -> Dictionary:
	var r := await _request(HTTPClient.METHOD_POST, "/api/auth/register",
			{"username": username, "warriorName": warrior_name, "email": email, "password": password, "gender": gender}, false)
	if r.get("ok") and r.get("json") is Dictionary and r["json"].has("token"):
		token = str(r["json"]["token"])
	return r

## GET /api/server-info (PÚBLICO, sem token). Identidade do servidor + trava de versão do cliente
## (minClientVersion/latestClientVersion/clientDownloadUrl). [SERVIDORES][DISTRIB_UPDATE]
func server_info() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/server-info", null, false)

## GET /api/warrior (autenticado). Retorna {ok, status, json, raw, error}.
func get_warrior() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/warrior", null, true)

# ── [ONBOARDING] Chegada guiada + Deveres do Recruta ───────────────────────────────
## GET /api/warrior/onboarding → {seen: bool}. Se !seen, o Shell abre o briefing de boas-vindas.
func onboarding_status() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/warrior/onboarding", null, true)
## POST /api/warrior/onboarding/seen → marca o briefing como visto (idempotente).
func onboarding_seen() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/warrior/onboarding/seen", {}, true)
## GET /api/starter-quests → {quests:[{id,npc,flavor,need:{type,qty,have},reward:{xp,bronze,item},done}]}.
func starter_quests() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/starter-quests", null, true)
## POST /api/starter-quests/{which}/accept → aceita a quest (NPC → diário). [ONBOARDING v2]
func starter_quest_accept(which: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/starter-quests/%s/accept" % which, {}, true)
## POST /api/starter-quests/{guard|priest|shop}/turn-in → entrega o recurso pedido, recebe XP+gold.
func starter_quest_turn_in(which: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/starter-quests/%s/turn-in" % which, {}, true)

## POST /api/vip/change-gender/{MALE|FEMALE} — [GENDER] troca de sexo paga em SoulStone (só pela tela VIP).
func vip_change_gender(gender: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/vip/change-gender/%s" % gender.to_upper(), {}, true)

## POST /api/warrior/attributes/{ATTR} — gasta 1 ponto. Devolve o WarriorResponse atualizado. [MIGRACAO_GODOT]
func spend_attribute(attr: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/warrior/attributes/%s" % attr, {}, true)

## GET /api/inventory (autenticado). json = Array de itens (cada um com type, equipped, name, rarity...). [GODOT_PAPERDOLL]
func get_inventory() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/inventory", null, true)

## POST /api/inventory/{id}/equip — equipa (auto-desequipa o slot). Devolve o item. [MIGRACAO_GODOT]
func equip_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/inventory/%d/equip" % id, {}, true)

## POST /api/inventory/{id}/unequip — desequipa. Devolve o item.
func unequip_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/inventory/%d/unequip" % id, {}, true)

## POST /api/inventory/{id}/sell — vende. Devolve {message, goldEarned, gold}. Não vende equipado.
func sell_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/inventory/%d/sell" % id, {}, true)

## POST /api/arena/fight (autenticado) — resolve um duelo de arena e devolve o resultado completo.
## json.battleEvents = Array de eventos (spawn/attack/crit/miss/dodge/extra/volley/heal/.../victory),
## os MESMOS que o battleArena.js 2D toca. Usado pelo replay 3D (Fase 3). [BATALHA_ANIMADA]
func arena_fight(opponent_id := 0) -> Dictionary:   # [ARENA_ESCOLHA] 0 = matchmaking; >0 = card escolhido
	return await _request(HTTPClient.METHOD_POST, "/api/arena/fight", {"opponentId": opponent_id}, true)

# ── PvE (Fase 3): puxar luta de TORRE / QUEST que também devolve json.battleEvents ──────
## POST /api/tower/enter — cria/garante uma run da torre. Retorna runState (currentFloor…). Sem corpo.
func tower_enter() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tower/enter", {}, true)

## POST /api/tower/fight — luta o andar atual. json.battleEvents + {won, bossName, scene:"tower"}. Exige run ativa.
func tower_fight() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tower/fight", {}, true)

## GET /api/world/{kingdom}/quests — lista as quests do reino (id=questType, interactive, canStart, doneToday…).
func quest_list(kingdom: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/world/%s/quests" % kingdom, null, true)

## POST /api/world/{kingdom}/quests/start {questType} — inicia. Retorna {id, …} (questId p/ o collect).
func quest_start(kingdom: String, quest_type: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/start" % kingdom, {"questType": quest_type}, true)

## POST /api/world/{kingdom}/quests/{id}/collect — resolve. optionId só p/ quest interativa. json.battleEvents se monstro.
func quest_collect(kingdom: String, id: int, option_id := "") -> Dictionary:
	var body := {} if option_id == "" else {"optionId": option_id}
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/%d/collect" % [kingdom, id], body, true)

## POST /api/world/{kingdom}/quests/{id}/luna/{action} — resolve a interrupção da Luna (ignore = retoma a missão).
func quest_luna(kingdom: String, id: int, action: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/%d/luna/%s" % [kingdom, id, action], {}, true)

# -- [MIGRACAO_GODOT] endpoints das telas migradas (gerados do workflow) ----------
func shop_get() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/shop", null, true)
func shop_buy(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/shop/buy/%d" % id, {}, true)
func world_kingdoms() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/world", null, true)
func quest_active(kingdom: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/world/%s/quests/active" % kingdom, null, true)
func quest_abandon(kingdom: String, id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/%d/abandon" % [kingdom, id], {}, true)
## GET /api/quests/journal — diário unificado: {toPickUp, inProgress, completed}. [DIARIO_QUEST]
func quest_journal() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/quests/journal", null, true)
func training_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/world/COMBAT/training", null, true)
func training_start(hours: int, free := false) -> Dictionary:   # [TREINO_IDLE] free=true → treino idle grátis (timer real)
	return await _request(HTTPClient.METHOD_POST, "/api/world/COMBAT/training/start", {"hours": hours, "free": free}, true)
func training_collect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/COMBAT/training/%d/collect" % id, {}, true)
func training_cancel(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/COMBAT/training/%d/cancel" % id, {}, true)
func zone_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/zones/current", null, true)
func zone_enter(zone: String, role: String, skill_type, duration: int, kingdom: String, element) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/zones/enter", {"zone": zone, "role": role, "skillType": skill_type, "durationMinutes": duration, "kingdom": kingdom, "element": element}, true)
func zone_collect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/zones/%d/collect" % id, {}, true)
func zone_cancel(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/zones/%d/cancel" % id, {}, true)
## [ZONA_CHEFE] chefe errante: encarar (combate → battleEvents) / fugir (teste de stat). Mesma resposta do collect.
func zone_boss_fight(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/zones/%d/boss/fight" % id, {}, true)
func zone_boss_flee(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/zones/%d/boss/flee" % id, {}, true)

# ── Incursão / Delve (run roguelike de nós) [INCURSAO] ─────────────────────────────
## POST /api/expedition/start — inicia uma run. source=KINGDOM/ZONE; kingdom/zone/skillType/element opcionais.
func expedition_start(source: String, kingdom, zone, skill_type, element, tier: int) -> Dictionary:
	var body := {"source": source, "tier": tier}
	if kingdom != null and str(kingdom) != "": body["kingdom"] = kingdom
	if zone != null and str(zone) != "": body["zone"] = zone
	if skill_type != null and str(skill_type) != "": body["skillType"] = skill_type
	if element != null and str(element) != "": body["element"] = element
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/start", body, true)
## GET /api/expedition/current — run ativa (runState) ou {active:false}.
func expedition_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/expedition/current", null, true)
## POST /api/expedition/{id}/choose {nodeId} — escolhe um nó alcançável da camada atual.
func expedition_choose(id: int, node_id: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/%d/choose" % id, {"nodeId": node_id}, true)
## POST /api/expedition/{id}/node {optionId} — resolve o nó de EVENTO pendente.
func expedition_node(id: int, option_id: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/%d/node" % id, {"optionId": option_id}, true)
## POST /api/expedition/{id}/combat {pvp} — escolhe o combate do nó: pvp=true saqueia a vítima flagada. [INCURSAO_PVP]
func expedition_combat(id: int, pvp: bool) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/%d/combat" % id, {"pvp": pvp}, true)
## POST /api/expedition/{id}/extract — saca a bolsa (banca tudo) e encerra a run.
func expedition_extract(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/%d/extract" % id, {}, true)
## POST /api/expedition/{id}/abandon — desiste numa parada segura (perde a bolsa não-travada, sem KO).
func expedition_abandon(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/expedition/%d/abandon" % id, {}, true)
func tower_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tower/current", null, true)
func tower_ranking() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tower/ranking", null, true)
func tower_aravok(spare: bool) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tower/aravok", {"spare": spare}, true)
func arena_rank() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/arena/rank", null, true)
func smithing_recipes() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/smithing/recipes", null, true)
func get_resources() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/gathering/resources", null, true)
## POST /api/gathering/consume/{type} — consome 1 peixe (restaura estamina + vida). [RECURSOS]
func gathering_consume(res: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/gathering/consume/%s" % res, {}, true)
func smithing_refine(ore: String, qty: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/smithing/refine", {"oreType": ore, "quantity": qty}, true)
func smithing_craft(recipe_id: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/smithing/craft", {"recipeId": recipe_id}, true)
func smithing_gem(frag: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/smithing/gem", {"fragmentType": frag}, true)
func smithing_repair(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/smithing/repair/%d" % id, {}, true)
func smithing_reforge(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/smithing/reforge/%d" % id, {}, true)
func temple_info() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/temple", null, true)
func temple_heal() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/heal", {}, true)
func temple_vip_heal() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/vip-heal", {}, true)
func temple_soulstone_heal() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/soulstone-heal", {}, true)
func temple_apply_buff(buff: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/buff/%s" % buff, {}, true)
func temple_enchant_weapon(element: String) -> Dictionary:   # [ELEMENTOS] encanta arma 1h (essência + bronze)
	return await _request(HTTPClient.METHOD_POST, "/api/temple/enchant/weapon/%s" % element, {}, true)
func temple_enchant_armor(element: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/enchant/armor/%s" % element, {}, true)
func temple_protect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/protect/%d" % id, {}, true)
func temple_unprotect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/unprotect/%d" % id, {}, true)
func tavern_feed(since: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tavern/feed?since=%d" % since, null, true)
func tavern_chat(text: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tavern/chat", {"text": text}, true)
func get_achievements() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/achievements", null, true)
func select_title(id: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/achievements/title", {"id": id}, true)
func get_abilities() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/abilities", null, true)
func ability_learn(ability: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/abilities/learn/%s" % ability, {}, true)
func ability_respec() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/abilities/respec", {}, true)
func vip_status() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/vip/status", null, true)
func vip_buy() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/vip/buy", {}, true)
func daily_status() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/daily-reward/status", null, true)
func daily_claim() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/daily-reward/claim", {}, true)
func work_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/work/current", null, true)
func work_jobs() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/work/jobs", null, true)
func work_start(work_type: String, hours: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/work/start", {"workType": work_type, "hours": hours}, true)
func work_collect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/work/%d/collect" % id, {}, true)
func work_cancel(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/work/%d/cancel" % id, {}, true)
func mail_inbox() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/mail/inbox", null, true)
func mail_read(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/%d/read" % id, {}, true)
func mail_collect_gold(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/%d/collect" % id, {}, true)
func mail_claim_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/%d/claim-item" % id, {}, true)
func mail_claim_resource(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/%d/claim-resource" % id, {}, true)
func mail_claim_all() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/claim-all", {}, true)
func mail_delete_all() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/delete-all", {}, true)
func mail_delete(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_DELETE, "/api/mail/%d" % id, null, true)
func get_stash() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/stash", null, true)
func stash_deposit_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/stash/deposit/item/%d" % id, {}, true)
func stash_withdraw_item(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/stash/withdraw/item/%d" % id, {}, true)
func stash_deposit_resource(res: String, qty: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/stash/deposit/resource/%s" % res, {"quantity": qty}, true)
func stash_withdraw_resource(res: String, qty: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/stash/withdraw/resource/%s" % res, {"quantity": qty}, true)
func auction_browse() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/auction", null, true)
func auction_mine() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/auction/mine", null, true)
func auction_buy(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/auction/buy/%d" % id, {}, true)
func auction_cancel(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/auction/cancel/%d" % id, {}, true)
func auction_list(item_id: int, price: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/auction/list", {"itemId": item_id, "price": price}, true)
func guild_get() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/guild", null, true)
func guild_list() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/guild/list", null, true)
func guild_create(gname: String, desc: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild", {"name": gname, "description": desc}, true)
func guild_join(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/join/%d" % id, {}, true)
func guild_leave() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/leave", {}, true)
func guild_disband() -> Dictionary:
	return await _request(HTTPClient.METHOD_DELETE, "/api/guild", null, true)
func guild_kick(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/kick/%d" % id, {}, true)
func guild_transfer(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/transfer/%d" % id, {}, true)
func guild_donate(amount: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/donate", {"amount": amount}, true)
func guild_set_description(desc: String) -> Dictionary:   # editar descrição (líder, grátis)
	return await _request(HTTPClient.METHOD_POST, "/api/guild/description", {"description": desc}, true)
func guild_invite_by_name(warrior_name: String) -> Dictionary:   # convidar membro pelo nick (líder)
	return await _request(HTTPClient.METHOD_POST, "/api/guild-invites/invite-by-name", {"name": warrior_name}, true)

# ── Guerra de Guilda (guild-vs-guild) [GUERRA_GUILDA] ──────────────────────────────
## GET /api/guild/war → {atWar, warId, enemyGuildName, myKills, enemyKills, secondsLeft, enemies:[{playerId,warriorName,title,level,hpPercent,knockedOut,shielded}]}
func guild_war_status() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/guild/war", null, true)
## POST /api/guild/war-formation — líder posiciona os membros no tabuleiro 3×5. [GUERRA_FORMACAO]
## slots = Array de {playerId:int, lane:int(0-2), depth:int(0-4)}.
func guild_set_formation(slots: Array) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/war-formation", {"slots": slots}, true)
## GET /api/guild/war/targets → [{id, name, level}] (guildas elegíveis)
func guild_war_targets() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/guild/war/targets", null, true)
## POST /api/guild/war/declare/{guildId} (líder) → {message, warId}
func guild_war_declare(guild_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/war/declare/%d" % guild_id, {}, true)
## POST /api/guild/war/attack/{playerId} → {won, opponentName, loot, myKills, enemyKills, battleLog}
func guild_war_attack(player_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild/war/attack/%d" % player_id, {}, true)

# ── Guerra de Território [GUERRA_FORMACAO] ─────────────────────────────────────────
## GET /api/territory → [{territory, displayName, lore, controllingGuild, defenseStreak, debuffPercent, isNeutral, isMine, secsUntilBattle, exclusiveBonus, declaringGuilds:[], myGuildDeclared}]
func territory_list() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/territory", null, true)
## GET /api/territory/my → {hasTerritory, territory, displayName, defenseStreak, debuffPercent, xpBonus, bronzeBonus, ...}
func territory_my() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/territory/my", null, true)
## POST /api/territory/{territory}/declare (líder) → {message, territory, cycleId}
func territory_declare(territory: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/territory/%s/declare" % territory, {}, true)
## POST /api/territory/cancel → {message}
func territory_cancel() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/territory/cancel", {}, true)
## GET /api/territory/{territory}/history → [{attacker, defender, winner, resolvedAt, log}]
func territory_history(territory: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/territory/%s/history" % territory, null, true)
## GET /api/territory/{territory}/replay → {hasReplay, events:[WarEvent…], scene, winner, attacker, defender} [GUERRA_GAUNTLET]
func territory_replay(territory: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/territory/%s/replay" % territory, null, true)

# ── [LEADERBOARDS] Rankings + perfil/inspeção + social ─────────────────────────────
## GET /api/leaderboard/{category}?page= — category: level|arena|tower|hunter|slayer|wealth.
func leaderboard(category: String, page := 0) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/leaderboard/%s?page=%d" % [category, page], null, true)
## GET /api/leaderboard/guild/{subcat}?page= — subcat: power|territory|warkills|members.
func leaderboard_guild(subcat: String, page := 0) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/leaderboard/guild/%s?page=%d" % [subcat, page], null, true)
## GET /api/leaderboard/territory/{kingdom}?page= — top contribuintes de um reino.
func leaderboard_territory(kingdom: String, page := 0) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/leaderboard/territory/%s?page=%d" % [kingdom, page], null, true)
## GET /api/leaderboard/territories — lista de reinos p/ o picker.
func leaderboard_territories() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/leaderboard/territories", null, true)
## GET /api/players/{id}/profile — perfil read-only (atributos + combate + equipados).
func player_profile(player_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/players/%d/profile" % player_id, null, true)
## POST /api/mail/send {recipientWarriorName, message, goldAmount} — envia carta a outro jogador.
func mail_send(recipient: String, message: String, gold := 0) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/mail/send",
			{"recipientWarriorName": recipient, "message": message, "goldAmount": gold}, true)
# Amizade
func friends_list() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/friends", null, true)
func friend_request(player_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/friends/request/%d" % player_id, {}, true)
func friend_accept(req_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/friends/accept/%d" % req_id, {}, true)
func friend_decline(req_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/friends/decline/%d" % req_id, {}, true)
func friend_remove(player_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_DELETE, "/api/friends/%d" % player_id, null, true)
# Convite de guilda (só líder convida; aceitar entra via GuildService.join)
func guild_invites() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/guild-invites", null, true)
func guild_invite(player_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild-invites/invite/%d" % player_id, {}, true)
func guild_invite_accept(invite_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild-invites/%d/accept" % invite_id, {}, true)
func guild_invite_decline(invite_id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/guild-invites/%d/decline" % invite_id, {}, true)

## Chamada genérica. method = HTTPClient.METHOD_*; body = Dictionary ou null; authed = manda o Bearer.
## Reusa uma conexão keep-alive do pool; conexões diferentes rodam em paralelo. Mesmo contrato
## de retorno de antes: {ok, status, json, raw} ou {ok:false, status, error}.
func _request(method: int, path: String, body: Variant = null, authed := false) -> Dictionary:
	var headers := _headers(authed)
	var payload := ""
	if body != null:
		payload = JSON.stringify(body)

	var c: _Conn = await _acquire()
	var out := {}
	# 2 tentativas: a conexão keep-alive pode ter sido fechada pelo servidor enquanto ociosa;
	# nesse caso reconectamos do zero e refazemos o request uma vez.
	for attempt in 2:
		if attempt == 1:
			c.http.close()                    # força conexão nova
		if not await _ensure_connected(c):
			out = {"ok": false, "status": 0, "error": TranslationServer.translate("falha ao conectar em %s") % _host}
			continue
		out = await _do(c, method, path, headers, payload)
		if out.has("_retry"):                 # socket keep-alive estava morto
			out = {"ok": false, "status": 0, "error": TranslationServer.translate("conexão caiu")}
			continue
		break
	if method != HTTPClient.METHOD_GET and out.get("ok", false):
		mutation_count += 1   # mutação OK → invalida o cache das telas (revalidam na próxima visita)
	c.busy = false
	return out

# Headers padrão (+ Bearer se autenticado).
# [I18N] Accept-Language = idioma escolhido na UI (Lang.current() → "pt"/"en"). O backend
# (AcceptHeaderLocaleResolver + messages_pt.properties) devolve quests/flavor/lore/diálogo no
# idioma certo. Sem este header o backend caía no default ENGLISH → texto do servidor em inglês.
func _headers(authed: bool) -> PackedStringArray:
	var h := PackedStringArray(["Content-Type: application/json", "Accept: application/json", "Accept-Language: " + Lang.current()])
	if authed and token != "":
		h.append("Authorization: Bearer " + token)
	return h

## Roda VÁRIOS requests em PARALELO de verdade, multiplexando N conexões do pool numa ÚNICA
## coroutine (avança todas as HTTPClient a cada frame). NÃO dispara sub-coroutines, então não
## cai no erro "call async without await" do Godot 4. Cada spec = [method, path, body, authed].
## Retorna Array de {ok,status,json,raw} na MESMA ordem. Conexão keep-alive morta → retry seq.
func batch(specs: Array) -> Array:
	var n := specs.size()
	var results := []
	results.resize(n)
	if n == 0:
		return results
	_ensure_target()
	var conns := []
	conns.resize(n)
	var ph := []          # fase por conexão: 0=conectar 1=requisitando 2=lendo corpo 3=pronto
	ph.resize(n)
	var bufs := []
	bufs.resize(n)
	var codes := []
	codes.resize(n)
	for i in n:
		conns[i] = await _acquire()
		bufs[i] = PackedByteArray()
		codes[i] = 0
		ph[i] = 0

	var pending := n
	while pending > 0:
		for i in n:
			if ph[i] == 3:
				continue
			var c: _Conn = conns[i]
			c.http.poll()
			var st := c.http.get_status()
			if ph[i] == 0:                                   # conectando / disparar request
				if st == HTTPClient.STATUS_CONNECTED:
					var spec: Array = specs[i]
					var body = spec[2] if spec.size() > 2 else null
					var authed: bool = bool(spec[3]) if spec.size() > 3 else false
					var payload := JSON.stringify(body) if body != null else ""
					if c.http.request(spec[0], spec[1], _headers(authed), payload) == OK:
						ph[i] = 1
					else:
						results[i] = {"_retry": true}; ph[i] = 3; pending -= 1
				elif st == HTTPClient.STATUS_DISCONNECTED:
					var tls = TLSOptions.client() if _use_tls else null
					if c.http.connect_to_host(_host, _port, tls) != OK:
						results[i] = {"_retry": true}; ph[i] = 3; pending -= 1
				elif st != HTTPClient.STATUS_RESOLVING and st != HTTPClient.STATUS_CONNECTING:
					results[i] = {"_retry": true}; ph[i] = 3; pending -= 1
			elif ph[i] == 1:                                 # esperando headers da resposta
				if st == HTTPClient.STATUS_BODY or st == HTTPClient.STATUS_CONNECTED:
					codes[i] = c.http.get_response_code()
					ph[i] = 2
				elif st != HTTPClient.STATUS_REQUESTING:
					results[i] = {"_retry": true}; ph[i] = 3; pending -= 1
			elif ph[i] == 2:                                 # lendo o corpo
				if st == HTTPClient.STATUS_BODY:
					var chunk := c.http.read_response_body_chunk()
					if chunk.size() > 0:
						bufs[i].append_array(chunk)
				else:
					var text: String = bufs[i].get_string_from_utf8()
					var code: int = codes[i]
					results[i] = {"ok": code >= 200 and code < 300, "status": code, "json": JSON.parse_string(text), "raw": text}
					ph[i] = 3; pending -= 1
		await get_tree().process_frame

	for c in conns:
		c.busy = false
	# conexões keep-alive que morreram (raro) → refaz sequencial com reconexão (via _request)
	for i in n:
		if results[i] is Dictionary and results[i].has("_retry"):
			var spec: Array = specs[i]
			var body = spec[2] if spec.size() > 2 else null
			var authed: bool = bool(spec[3]) if spec.size() > 3 else false
			results[i] = await _request(spec[0], spec[1], body, authed)
	return results

## Açúcar p/ o caso comum: vários GET autenticados em paralelo. paths = Array de String.
func batch_get(paths: Array) -> Array:
	var specs := []
	for p in paths:
		specs.append([HTTPClient.METHOD_GET, p, null, true])
	return await batch(specs)

# Pega uma conexão livre do pool (ou cria até POOL_MAX). Espera um frame se todas ocupadas.
func _acquire() -> _Conn:
	while true:
		for c in _pool:
			if not c.busy:
				c.busy = true
				return c
		if _pool.size() < POOL_MAX:
			var c := _Conn.new()
			c.busy = true
			_pool.append(c)
			return c
		await get_tree().process_frame
	return null   # inalcançável (while true) — só p/ o analisador do GDScript

# Parseia base_url → host/porta/tls uma vez.
func _ensure_target() -> void:
	if _host != "":
		return
	var u := base_url
	if u.begins_with("https://"):
		_use_tls = true; _port = 443; u = u.substr(8)
	elif u.begins_with("http://"):
		_use_tls = false; _port = 80; u = u.substr(7)
	var slash := u.find("/")
	if slash != -1:
		u = u.substr(0, slash)               # tira qualquer path
	var colon := u.find(":")
	if colon != -1:
		_port = int(u.substr(colon + 1))     # host:porta explícita
		u = u.substr(0, colon)
	_host = u

# Garante que a conexão está CONECTADA (reusa se já estiver). Retorna false se não conectou.
func _ensure_connected(c: _Conn) -> bool:
	if c.http.get_status() == HTTPClient.STATUS_CONNECTED:
		return true
	_ensure_target()
	var tls = TLSOptions.client() if _use_tls else null
	if c.http.connect_to_host(_host, _port, tls) != OK:
		return false
	while true:
		c.http.poll()
		var st := c.http.get_status()
		if st == HTTPClient.STATUS_RESOLVING or st == HTTPClient.STATUS_CONNECTING:
			await get_tree().process_frame
			continue
		break
	return c.http.get_status() == HTTPClient.STATUS_CONNECTED

# Faz UM request numa conexão já conectada e lê a resposta inteira. {_retry:true} = socket morreu.
func _do(c: _Conn, method: int, path: String, headers: PackedStringArray, payload: String) -> Dictionary:
	if c.http.request(method, path, headers, payload) != OK:
		return {"_retry": true}
	while true:                               # espera os headers da resposta
		c.http.poll()
		var st := c.http.get_status()
		if st == HTTPClient.STATUS_REQUESTING:
			await get_tree().process_frame
			continue
		if st == HTTPClient.STATUS_BODY or st == HTTPClient.STATUS_CONNECTED:
			break
		return {"_retry": true}               # DISCONNECTED / CONNECTION_ERROR / TLS error
	var code := c.http.get_response_code()
	var buf := PackedByteArray()
	while c.http.get_status() == HTTPClient.STATUS_BODY:
		c.http.poll()
		var chunk := c.http.read_response_body_chunk()
		if chunk.size() > 0:
			buf.append_array(chunk)
		else:
			await get_tree().process_frame
	var text := buf.get_string_from_utf8()
	var json: Variant = JSON.parse_string(text)
	var ok := code >= 200 and code < 300
	return {"ok": ok, "status": code, "json": json, "raw": text}
