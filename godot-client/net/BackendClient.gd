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
var base_url := "https://medieval-game-production.up.railway.app"

## JWT obtido no login. Enviado como `Authorization: Bearer <token>` nas chamadas autenticadas.
var token := ""

## POST /api/auth/login → guarda o token. Retorna {ok, status, json, raw, error}.
func login(username: String, password: String) -> Dictionary:
	var r := await _request(HTTPClient.METHOD_POST, "/api/auth/login",
			{"username": username, "password": password}, false)
	if r.get("ok") and r.get("json") is Dictionary and r["json"].has("token"):
		token = str(r["json"]["token"])
	return r

## GET /api/warrior (autenticado). Retorna {ok, status, json, raw, error}.
func get_warrior() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/warrior", null, true)

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
func arena_fight() -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/arena/fight", {}, true)

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
func training_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/world/COMBAT/training", null, true)
func training_start(hours: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/COMBAT/training/start", {"hours": hours}, true)
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
func tower_current() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tower/current", null, true)
func tower_ranking() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tower/ranking", null, true)
func tower_arka(spare: bool) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tower/arka", {"spare": spare}, true)
func arena_rank() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/arena/rank", null, true)
func smithing_recipes() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/smithing/recipes", null, true)
func get_resources() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/gathering/resources", null, true)
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
func temple_protect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/protect/%d" % id, {}, true)
func temple_unprotect(id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/temple/unprotect/%d" % id, {}, true)
func tavern_status() -> Dictionary:
	return await _request(HTTPClient.METHOD_GET, "/api/tavern/status", null, true)
func tavern_drink(success: bool) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/tavern/drink", {"success": success}, true)
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

## Chamada genérica. method = HTTPClient.METHOD_*; body = Dictionary ou null; authed = manda o Bearer.
func _request(method: int, path: String, body: Variant = null, authed := false) -> Dictionary:
	var http := HTTPRequest.new()
	add_child(http)
	var headers := PackedStringArray(["Content-Type: application/json", "Accept: application/json"])
	if authed and token != "":
		headers.append("Authorization: Bearer " + token)
	var payload := ""
	if body != null:
		payload = JSON.stringify(body)
	var err := http.request(base_url + path, headers, method, payload)
	if err != OK:
		http.queue_free()
		return {"ok": false, "status": 0, "error": "request() falhou: %d" % err}
	var res: Array = await http.request_completed   # [result, code, headers, body]
	http.queue_free()
	var result: int = res[0]                         # HTTPRequest.Result (rede)
	var code: int = res[1]                           # HTTP status
	var text: String = (res[3] as PackedByteArray).get_string_from_utf8()
	var json: Variant = JSON.parse_string(text)
	if result != HTTPRequest.RESULT_SUCCESS:
		return {"ok": false, "status": code, "error": "falha de rede (result %d)" % result, "raw": text}
	var ok := code >= 200 and code < 300
	return {"ok": ok, "status": code, "json": json, "raw": text}
