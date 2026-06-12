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

## POST /api/world/{kingdom}/quests/{id}/collect — resolve. json.battleEvents se monsterEncountered. {} = não-interativa.
func quest_collect(kingdom: String, id: int) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/%d/collect" % [kingdom, id], {}, true)

## POST /api/world/{kingdom}/quests/{id}/luna/{action} — resolve a interrupção da Luna (ignore = retoma a missão).
func quest_luna(kingdom: String, id: int, action: String) -> Dictionary:
	return await _request(HTTPClient.METHOD_POST, "/api/world/%s/quests/%d/luna/%s" % [kingdom, id, action], {}, true)

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
