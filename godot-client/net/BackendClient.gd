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
