extends Node
# ── Teste do plumbing do backend (Fase 2) ──────────────────────────────────────
# Loga no backend, busca /api/warrior e imprime o JSON no painel "Saída".
# Credenciais vêm de res://net/credentials.cfg (NÃO commitado — copie do .example).
# Rode esta cena (BackendTest.tscn) com F6 e veja o resultado no painel Saída.

const CRED_PATH := "res://net/credentials.cfg"

func _ready() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(CRED_PATH) != OK:
		printerr("[BackendTest] FALTA ", CRED_PATH,
			" — copie net/credentials.cfg.example p/ net/credentials.cfg e preencha user/senha.")
		return
	var user := str(cfg.get_value("auth", "username", ""))
	var pwd := str(cfg.get_value("auth", "password", ""))
	if user == "" or pwd == "":
		printerr("[BackendTest] preencha username/password em ", CRED_PATH)
		return

	var client := BackendClient.new()
	add_child(client)
	print("[BackendTest] servidor: ", client.base_url)
	print("[BackendTest] logando como '", user, "' ...")

	var lr: Dictionary = await client.login(user, pwd)
	if not lr.get("ok"):
		printerr("[BackendTest] LOGIN FALHOU — status=", lr.get("status"),
			" err=", lr.get("error", ""), " raw=", lr.get("raw", ""))
		return
	print("[BackendTest] login OK ✓  token: ", client.token.substr(0, 16), "...")

	var wr: Dictionary = await client.get_warrior()
	if not wr.get("ok"):
		printerr("[BackendTest] /api/warrior FALHOU — status=", wr.get("status"),
			" raw=", wr.get("raw", ""))
		return
	print("[BackendTest] /api/warrior OK ✓  — dados do guerreiro:")
	print(JSON.stringify(wr.get("json"), "  "))
