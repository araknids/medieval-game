extends Control
# ── Tela HABILIDADES DE CLASSE ────────────────────────────────────────────────────
# Lê GET /api/abilities: árvore da classe (passivas + ativas), pontos de habilidade
# (1/level), custo de respec. Aprende um nível (POST /api/abilities/learn/{id}) e
# reseta tudo (POST /api/abilities/respec). Volta pro Personagem (sinal go_back).
# Padrão visual: UiKit [PADRAO_UI_GODOT]. [HABILIDADES]

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var data: Dictionary = {}
var warrior: Dictionary = {}     # /api/warrior (carteira do header)
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "✨ Habilidades", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/abilities", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	var pts := int(data.get("abilityPoints", 0))
	var abilities: Array = data.get("abilities", []) if data.get("abilities") is Array else []
	# Sem classe ainda (Recruta): só mostra os pontos guardados e o aviso.
	if abilities.is_empty():
		content.add_child(UiKit.section("Habilidades"))
		content.add_child(UiKit.empty(
			"Você tem %d ponto%s de habilidade guardado%s." % [pts, "" if pts == 1 else "s", "" if pts == 1 else "s"],
			"Escolha uma classe (Path Trial no Nv.10) para destravar as habilidades dela."))
		return
	# Cabeçalho da classe + pontos disponíveis
	content.add_child(UiKit.section("Habilidades — %s" % str(data.get("class", "?"))))
	if pts > 0:
		var pl := Label.new()
		pl.text = "⬆ %d ponto%s para gastar" % [pts, "" if pts == 1 else "s"]
		pl.add_theme_font_size_override("font_size", 14)
		pl.add_theme_color_override("font_color", UiKit.GOLD)
		content.add_child(pl)
	# cards em grid (2 col) p/ encurtar a tela longa
	content.add_child(UiKit.grid(self, abilities, func(a): return _ability_card(a, pts) if a is Dictionary else null))
	# Respec (pago, reseta os pontos) → confirma antes
	content.add_child(UiKit.spacer(6))
	content.add_child(UiKit.action_danger("🔄 Resetar habilidades (%s)" % _fmt_bronze(int(data.get("respecCost", 0))), _respec))

func _ability_card(a: Dictionary, pts: int) -> PanelContainer:
	var active := bool(a.get("active", false))
	var level := int(a.get("level", 0))
	var max_level := int(a.get("maxLevel", 0))
	var maxed := level >= max_level
	var col: Color = Color(0.48, 0.69, 1.0) if active else Color(0.6, 0.8, 0.6)
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	# ícone
	var icon := Label.new()
	icon.text = str(a.get("icon", "•"))
	icon.custom_minimum_size = Vector2(28, 0)
	icon.add_theme_font_size_override("font_size", 18)
	hb.add_child(icon)
	# esquerda: nome + tipo + descrição
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = str(a.get("displayName", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", col)
	left.add_child(nm)
	var kind_txt := ""
	if active:
		kind_txt = "⚡ Ativa"
		var cd := int(a.get("cooldown", 0))
		if cd > 0:
			kind_txt += " · CD %d rounds" % cd
	else:
		kind_txt = "🪨 Passiva"
	left.add_child(UiKit.dim(kind_txt))
	var desc := str(a.get("description", ""))
	if desc != "":
		left.add_child(UiKit.dim(desc))
	hb.add_child(left)
	# direita: nível + botão de aprender
	var right := VBoxContainer.new()
	right.add_theme_constant_override("separation", 4)
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var lvl := Label.new()
	lvl.text = "%d/%d" % [level, max_level]
	lvl.add_theme_font_size_override("font_size", 14)
	lvl.add_theme_color_override("font_color", UiKit.GOLD if maxed else UiKit.TEXT)
	lvl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	right.add_child(lvl)
	var learn := UiKit.small_btn("✖ No máx" if maxed else "+ Aprender", _learn.bind(str(a.get("id", ""))))
	learn.disabled = pts <= 0 or maxed
	right.add_child(learn)
	hb.add_child(right)
	return pc

# ── Ações (1 chamada → re-sincroniza a árvore) ────────────────────────────────────
func _learn(id: String) -> void:
	if busy or id == "": return
	busy = true
	await _do(await Api.ability_learn(id), "Aprimorado!")
	busy = false

func _respec() -> void:
	UiKit.confirm(self,
		"Resetar todas as habilidades por %s? Os pontos voltam para você." % _fmt_bronze(int(data.get("respecCost", 0))),
		"Resetar",
		func() -> void: await _do_respec())

func _do_respec() -> void:
	if busy: return
	busy = true
	await _do(await Api.ability_respec(), "Habilidades resetadas.")
	busy = false

# r = resultado JÁ resolvido; re-sincroniza e mostra o feedback.
func _do(r: Variant, default_msg: String) -> void:
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", default_msg))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── helpers de UI ─────────────────────────────────────────────────────────────────
func _fmt_bronze(n: int) -> String:
	return UiKit.coin_str(n)   # [MOEDA] ouro/prata/bronze por extenso (distinguível em texto)
