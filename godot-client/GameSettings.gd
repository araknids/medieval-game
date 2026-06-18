extends RefCounted
# ── Configurações de JOGO persistidas (user://settings.cfg) ─────────────────────────
# Separado do Lang (idioma). Hoje: GORE (sangue + desmembramento) — dá pra DESLIGAR p/
# alcançar mais público / classificação etária menor. Default = LIGADO (comportamento atual).
# Uso: const GameSettings := preload("res://GameSettings.gd"); if GameSettings.gore_enabled(): ... [CONFIG]

const CFG := "user://settings.cfg"
static var _loaded := false
static var _gore := true

static func _ensure() -> void:
	if _loaded:
		return
	_loaded = true
	var cf := ConfigFile.new()
	if cf.load(CFG) == OK:
		_gore = bool(cf.get_value("game", "gore", true))

# Sangue + desmembramento ligados? (lazy-load do disco na 1ª chamada)
static func gore_enabled() -> bool:
	_ensure()
	return _gore

static func set_gore(on: bool) -> void:
	_ensure()
	_gore = on
	var cf := ConfigFile.new()
	cf.load(CFG)   # preserva outras chaves, se houver
	cf.set_value("game", "gore", on)
	cf.save(CFG)
