extends Control
# ── Tela PERSONAGEM (home) ───────────────────────────────────────────────────────
# Lê GET /api/warrior e mostra stats/HP/estamina/moeda/atributos. Gasta ponto de atributo
# (POST /api/warrior/attributes/{ATTR}). Botões: Lutar (→ batalha) e Sair. [MIGRACAO_GODOT]

signal go_battle
signal logout

# atributo: chave no JSON, sigla, ícone
const ATTRS := [
	["strength", "STR", "⚔"], ["constitution", "CON", "❤"], ["dexterity", "DEX", "🎯"],
	["agility", "AGI", "💨"], ["luck", "LUK", "🍀"], ["intellect", "INT", "📚"],
]

var w: Dictionary = {}
var content: VBoxContainer
var status: Label
var busy := false

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.09, 0.08, 0.11)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var scroll := ScrollContainer.new()
	scroll.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(scroll)
	var margin := MarginContainer.new()
	margin.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 22)
	scroll.add_child(margin)
	content = VBoxContainer.new()
	content.add_theme_constant_override("separation", 8)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	margin.add_child(content)
	status = Label.new()
	content.add_child(status)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	var r = await Api.get_warrior()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	w = r["json"]
	_render()

func _render() -> void:
	for c in content.get_children():
		if c != status:
			c.queue_free()
	status.text = ""
	# ── Cabeçalho: título + nome + classe + nível ──
	var title := str(w.get("title", ""))
	var nm := str(w.get("name", "?"))
	var head := Label.new()
	head.text = (title + "  " if title != "" else "") + nm
	head.add_theme_font_size_override("font_size", 32)
	content.add_child(head)
	var sub := Label.new()
	sub.text = "%s · Nível %d" % [str(w.get("warriorClass", "Recruta")), int(w.get("level", 1))]
	sub.modulate = Color(1, 0.9, 0.6)
	content.add_child(sub)
	if bool(w.get("isKnockedOut", false)):
		var ko := Label.new(); ko.text = "💀 NOCAUTEADO (em recuperação)"; ko.modulate = Color(1, 0.5, 0.5)
		content.add_child(ko)
	# ── Barras: XP, HP, Estamina ──
	var xp := int(w.get("experience", 0))
	var xp_need := int(w.get("expNeeded", 0))
	content.add_child(_bar("XP", xp, xp + max(1, xp_need), Color(0.5, 0.6, 1.0), "%d  (faltam %d)" % [xp, xp_need]))
	content.add_child(_bar("HP", int(w.get("hpPercent", 100)), 100, Color(0.85, 0.3, 0.3), "%d%%" % int(w.get("hpPercent", 100))))
	var stam := int(w.get("stamina", 0))
	var stam_txt := "%d%%" % stam
	if stam < 100:
		stam_txt += "  (cheia em %d min)" % int(w.get("minutesToFullStamina", 0))
	content.add_child(_bar("Estamina", stam, 100, Color(0.4, 0.8, 0.5), stam_txt))
	# ── Moeda ──
	content.add_child(_section("Moeda"))
	content.add_child(_kv("Ouro/Prata/Bronze", "%d 🥇  %d 🥈  %d 🥉" % [int(w.get("gold", 0)), int(w.get("silver", 0)), int(w.get("bronze", 0))]))
	content.add_child(_kv("SoulStones", "%d 💎" % int(w.get("soulStones", 0))))
	# ── Combate ──
	content.add_child(_section("Combate"))
	content.add_child(_kv("Ataque", str(w.get("combatAttack", w.get("totalAttack", 0)))))
	content.add_child(_kv("Defesa", str(w.get("combatDefense", w.get("totalDefense", 0)))))
	content.add_child(_kv("Vida máx", str(w.get("combatHealth", w.get("totalHealth", 0)))))
	content.add_child(_kv("Rank (arena)", str(w.get("rankPoints", 0))))
	# ── Atributos (com + se houver pontos) ──
	var pts := int(w.get("availablePoints", 0))
	var attr_title := "Atributos"
	if pts > 0:
		attr_title += "   (%d %s livre%s)" % [pts, "ponto" if pts == 1 else "pontos", "" if pts == 1 else "s"]
	content.add_child(_section(attr_title))
	for a in ATTRS:
		content.add_child(_attr_row(a[2], a[1], a[0], pts > 0))
	# ── Ações ──
	content.add_child(_spacer(10))
	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation", 10)
	content.add_child(actions)
	var fight := Button.new()
	fight.text = "⚔ Lutar"
	fight.custom_minimum_size = Vector2(140, 44)
	fight.disabled = bool(w.get("isKnockedOut", false))
	fight.pressed.connect(func() -> void: go_battle.emit())
	actions.add_child(fight)
	var refresh := Button.new()
	refresh.text = "↻ Atualizar"
	refresh.pressed.connect(func() -> void: await _refresh())
	actions.add_child(refresh)
	var out := Button.new()
	out.text = "Sair"
	out.pressed.connect(func() -> void: logout.emit())
	actions.add_child(out)

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _section(t: String) -> Label:
	var l := Label.new()
	l.text = t
	l.add_theme_font_size_override("font_size", 20)
	l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _kv(k: String, v: String) -> HBoxContainer:
	var row := HBoxContainer.new()
	var lk := Label.new(); lk.text = k; lk.custom_minimum_size = Vector2(170, 0); lk.modulate = Color(1, 1, 1, 0.7)
	var lv := Label.new(); lv.text = v
	row.add_child(lk); row.add_child(lv)
	return row

func _bar(bname: String, value: int, maxv: int, col: Color, txt: String) -> VBoxContainer:
	var box := VBoxContainer.new()
	var lbl := Label.new(); lbl.text = "%s   %s" % [bname, txt]; box.add_child(lbl)
	var pb := ProgressBar.new()
	pb.min_value = 0; pb.max_value = max(1, maxv); pb.value = clampi(value, 0, maxv)
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(0, 14)
	var sb := StyleBoxFlat.new(); sb.bg_color = col; sb.set_corner_radius_all(3)
	pb.add_theme_stylebox_override("fill", sb)
	box.add_child(pb)
	return box

func _attr_row(icon: String, sigla: String, key: String, can_add: bool) -> HBoxContainer:
	var row := HBoxContainer.new()
	var l := Label.new()
	l.text = "%s %s" % [icon, sigla]
	l.custom_minimum_size = Vector2(110, 0)
	row.add_child(l)
	var v := Label.new(); v.text = str(w.get(key, 0)); v.custom_minimum_size = Vector2(40, 0)
	row.add_child(v)
	if can_add:
		var plus := Button.new(); plus.text = "+"; plus.custom_minimum_size = Vector2(34, 0)
		plus.pressed.connect(func() -> void: await _spend(key))
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

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
