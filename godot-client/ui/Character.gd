extends Control
# ── Tela PERSONAGEM (home) ───────────────────────────────────────────────────────
# Lê GET /api/warrior e mostra stats/HP/estamina/atributos. Gasta ponto de atributo
# (POST /api/warrior/attributes/{ATTR}). Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back
signal go_battle
signal go_inventory
signal logout

# atributo: chave no JSON, sigla, ícone
const ATTRS := [
	["strength", "STR", "⚔"], ["constitution", "CON", "❤"], ["dexterity", "DEX", "🎯"],
	["agility", "AGI", "💨"], ["luck", "LUK", "🍀"], ["intellect", "INT", "📚"],
]
# efeito de cada atributo (mostrado p/ contexto — [REBALANCE])
const ATTR_EFFECT := {
	"STR": "dano corpo-a-corpo", "CON": "+8 HP por ponto", "DEX": "acerto · dano de arco",
	"AGI": "golpe extra · esquiva", "LUK": "crítico", "INT": "reservado (Mago)",
}

var w: Dictionary = {}
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "👤 Personagem", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var r = await Api.get_warrior()
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	w = r["json"]
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, w)
	# ── Identidade ──
	var idr := UiKit.card(UiKit.GOLD_SOFT)
	var idbox: VBoxContainer = idr[1]
	var title := str(w.get("title", ""))
	var head := Label.new()
	head.text = (title + "  " if title != "" else "") + str(w.get("name", "?"))
	head.add_theme_font_size_override("font_size", 28)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	idbox.add_child(head)
	var sub := Label.new()
	sub.text = "%s · Nível %d" % [str(w.get("warriorClass", "Recruta")), int(w.get("level", 1))]
	sub.add_theme_font_size_override("font_size", 14)
	sub.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	idbox.add_child(sub)
	if bool(w.get("isKnockedOut", false)):
		var ko := Label.new()
		ko.text = "💀 NOCAUTEADO (em recuperação)"
		ko.add_theme_color_override("font_color", UiKit.ERR)
		idbox.add_child(ko)
	content.add_child(idr[0])
	# ── Barras: XP, HP, Estamina ──
	var xp := int(w.get("experience", 0))
	var xp_need := int(w.get("expNeeded", 0))
	content.add_child(UiKit.bar("XP", xp, xp + maxi(1, xp_need), Color(0.42, 0.50, 0.85), "%d  (faltam %d)" % [xp, xp_need]))
	var hp := int(w.get("hpPercent", 100))
	content.add_child(UiKit.bar("HP", hp, 100, Color(0.70, 0.22, 0.20), "%d%%" % hp))
	var stam := int(w.get("stamina", 0))
	var stam_txt := "%d%%" % stam
	if stam < 100:
		stam_txt += "  (cheia em %d min)" % int(w.get("minutesToFullStamina", 0))
	content.add_child(UiKit.bar("Estamina", stam, 100, Color(0.36, 0.65, 0.38), stam_txt))
	# ── Atributos ──
	var pts := int(w.get("availablePoints", 0))
	var attr_title := "Atributos"
	if pts > 0:
		attr_title += "  (%d livre%s)" % [pts, "" if pts == 1 else "s"]
	content.add_child(UiKit.section(attr_title))
	for a in ATTRS:
		content.add_child(_attr_row(a, pts > 0))
	# ── Combate ──
	content.add_child(UiKit.section("Combate"))
	content.add_child(UiKit.kv("Ataque", str(w.get("combatAttack", w.get("totalAttack", 0)))))
	content.add_child(UiKit.kv("Defesa", str(w.get("combatDefense", w.get("totalDefense", 0)))))
	content.add_child(UiKit.kv("Vida máx", str(w.get("combatHealth", w.get("totalHealth", 0)))))
	content.add_child(UiKit.kv("Rank (arena)", str(w.get("rankPoints", 0))))
	content.add_child(UiKit.kv("SoulStones", str(int(w.get("soulStones", 0)))))
	# ── Ações ──
	content.add_child(UiKit.spacer(8))
	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation", 10)
	content.add_child(actions)
	var fight := UiKit.action_big("⚔ Lutar", func() -> void: go_battle.emit())
	fight.disabled = bool(w.get("isKnockedOut", false))
	actions.add_child(fight)
	actions.add_child(UiKit.action("🎒 Inventário", func() -> void: go_inventory.emit()))
	actions.add_child(UiKit.action_danger("Sair", func() -> void: logout.emit()))
	if not fight.disabled:
		fight.call_deferred("grab_focus")

# linha de atributo: ícone+sigla · valor · efeito · botão + (se há ponto livre)
func _attr_row(a: Array, can_add: bool) -> HBoxContainer:
	var key := str(a[0])
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	var nm := Label.new()
	nm.text = "%s %s" % [str(a[2]), str(a[1])]
	nm.custom_minimum_size = Vector2(90, 0)
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	row.add_child(nm)
	var val := Label.new()
	val.text = str(w.get(key, 0))
	val.custom_minimum_size = Vector2(40, 0)
	val.add_theme_font_size_override("font_size", 15)
	val.add_theme_color_override("font_color", UiKit.GOLD)
	row.add_child(val)
	var eff := Label.new()
	eff.text = str(ATTR_EFFECT.get(str(a[1]), ""))
	eff.add_theme_font_size_override("font_size", 12)
	eff.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	eff.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(eff)
	if can_add:
		var plus := UiKit.icon_btn("+", func() -> void: await _spend(key))
		plus.custom_minimum_size = Vector2(36, 36)
		row.add_child(plus)
	return row

func _spend(key: String) -> void:
	if busy: return
	busy = true
	var r = await Api.spend_attribute(key.to_upper())
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		w = r["json"]
		_render()
		UiKit.flash(status, "Ponto aplicado", 1)
	else:
		UiKit.show_error(status, r)
