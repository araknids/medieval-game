extends RefCounted
# ── Configurações de JOGO persistidas (user://settings.cfg) ─────────────────────────
# Separado do Lang (idioma). Hoje: GORE (sangue + desmembramento) — dá pra DESLIGAR p/
# alcançar mais público / classificação etária menor. Default = LIGADO (comportamento atual).
# Uso: const GameSettings := preload("res://GameSettings.gd"); if GameSettings.gore_enabled(): ... [CONFIG]

const CFG := "user://settings.cfg"
static var _loaded := false
static var _gore := true
static var _animated_bg := true   # [PLAYTEST_FIX] fundo 3D animado do menu (desligável)

static func _ensure() -> void:
	if _loaded:
		return
	_loaded = true
	var cf := ConfigFile.new()
	if cf.load(CFG) == OK:
		_gore = bool(cf.get_value("game", "gore", true))
		_animated_bg = bool(cf.get_value("game", "animated_bg", true))

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

# [PLAYTEST_FIX] Fundo 3D animado do menu ligado? (desligar p/ máquina fraca / preferência)
static func animated_bg_enabled() -> bool:
	_ensure()
	return _animated_bg

static func set_animated_bg(on: bool) -> void:
	_ensure()
	_animated_bg = on
	var cf := ConfigFile.new()
	cf.load(CFG)
	cf.set_value("game", "animated_bg", on)
	cf.save(CFG)
