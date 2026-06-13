class_name UiKit
extends RefCounted

const Icons := preload("res://ui/Icons.gd")

# Quando uma tela embedded chama set_wallet com wallet=null, manda o warrior pro topbar do Shell.
# O Shell registra (UiKit.topbar_sink = update_topbar) sem criar ciclo de class_name. [PLANO_UI_SHELL_GODOT]
static var topbar_sink := Callable()
# ── Kit de UI "Stone & Ember" — padrão único das telas internas [PADRAO_UI_GODOT] ──
# Direção de arte: modelo Fable. Faz toda tela parecer parte do Hub. Tudo estático (igual
# StoneStyle), com caches. Uso típico no _ready() de uma tela:
#   var ui := UiKit.scaffold(self, "🔨 Forja", func(): go_back.emit(), func(): await _refresh(), UiKit.TINT_COMMERCE)
#   content = ui.content; status = ui.status; wallet = ui.wallet
# Doc: docs/PLANO_PADRAO_UI_GODOT.md

# ── Paleta (fonte única — substitui os RARITY_COL espalhados) ──────────────────────
const GOLD       := Color(0.96, 0.66, 0.26)   # títulos
const GOLD_SOFT  := Color(0.78, 0.65, 0.36)   # seções/réguas
const BRONZE     := Color(0.40, 0.32, 0.20)   # bordas padrão
const TEXT       := Color(0.87, 0.83, 0.74)   # corpo (pergaminho, nunca branco puro)
const TEXT_DIM   := Color(0.62, 0.58, 0.52)   # meta/sub
const OK         := Color(0.55, 0.80, 0.50)
const ERR        := Color(0.94, 0.42, 0.38)
const WARN       := Color(1.0, 0.76, 0.0)
const RARITY     := [Color(0.72,0.72,0.75), Color(0.45,0.85,0.45), Color(0.4,0.6,1.0), Color(0.78,0.45,0.95), Color(1.0,0.8,0.35)]

# Tints de fundo por categoria (cada tela passa o seu).
const TINT_DEFAULT   := Color(0.095, 0.09, 0.115)
const TINT_ADVENTURE := Color(0.085, 0.10, 0.09)
const TINT_BATTLE    := Color(0.115, 0.08, 0.075)
const TINT_COMMERCE  := Color(0.115, 0.095, 0.07)
const TINT_SOCIAL    := Color(0.08, 0.09, 0.11)

static func rarity_color(r: int) -> Color:
	return RARITY[clampi(r - 1, 0, 4)]

# Formata um valor em BRONZE (unidade-base) em ouro/prata/bronze com ícones. Ex.: 2500 → "25🥈".
# 100 bronze = 1 prata · 100 prata = 1 ouro. TODO preço/custo/recompensa do jogo é em bronze —
# use isto p/ não mostrar um valor de bronze com cara de ouro. [MOEDA]
static func coin_str(bronze: int) -> String:
	var g := bronze / 10000
	var s := (bronze % 10000) / 100
	var b := bronze % 100
	var parts: Array = []
	if g > 0: parts.append("%d🥇" % g)
	if s > 0: parts.append("%d🥈" % s)
	if b > 0 or parts.is_empty(): parts.append("%d🥉" % b)
	return " ".join(parts)

# [MOEDA] Mesma quebra do coin_str, mas renderiza com os ÍCONES pixel-art (Icons.gd, os
# mesmos da topbar) em vez de emoji — emoji mono fica tingido de dourado e some a distinção.
# Retorna um HBox [🥇 N] [🥈 N] [🥉 N]. num_color tinge só o número (ex.: Loja usa ERR quando
# não dá pra pagar). px = tamanho do ícone.
static func coin_box(bronze: int, px := 18, num_color := TEXT) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 3)
	var g := bronze / 10000
	var s := (bronze % 10000) / 100
	var b := bronze % 100
	var segs: Array = []
	if g > 0: segs.append(["gold", g])
	if s > 0: segs.append(["silver", s])
	if b > 0 or segs.is_empty(): segs.append(["bronze", b])
	for seg in segs:
		h.add_child(Icons.rect(str(seg[0]), px))
		var l := Label.new()
		l.text = str(int(seg[1]))
		l.add_theme_font_size_override("font_size", maxi(11, px - 4))
		l.add_theme_color_override("font_color", num_color)
		l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		l.mouse_filter = Control.MOUSE_FILTER_IGNORE
		h.add_child(l)
	return h

# ── Fundo (ColorRect + shader cacheado, sem 3D) ────────────────────────────────────
const _BG_SHADER := """
shader_type canvas_item;
uniform vec3 tint = vec3(0.10, 0.085, 0.105);
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
void fragment() {
	vec2 uv = UV;
	float g = mix(0.55, 1.0, 1.0 - abs(uv.y - 0.38) * 1.4);
	vec3 col = tint * clamp(g, 0.45, 1.0);
	col += (hash(floor(uv * vec2(900.0, 500.0))) - 0.5) * 0.025;
	float d = length(uv - vec2(0.5, 0.46)) * 1.5;
	float vig = smoothstep(0.40, 1.05, d);          // 0 centro → 1 borda
	col *= 1.0 - vig * 0.55;                          // escurece a cor nas bordas
	float a = mix(0.50, 0.88, vig);                  // SCRIM: centro translúcido (o castelo 3D do App aparece atrás), borda opaca
	COLOR = vec4(col, a);
}
"""
static var _bg_shader: Shader

static func bg(screen: Control, tint := TINT_DEFAULT) -> void:
	if _bg_shader == null:
		_bg_shader = Shader.new()
		_bg_shader.code = _BG_SHADER
	var rect := ColorRect.new()
	rect.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var mat := ShaderMaterial.new()
	mat.shader = _bg_shader
	mat.set_shader_parameter("tint", Vector3(tint.r, tint.g, tint.b))
	rect.material = mat
	screen.add_child(rect)
	screen.move_child(rect, 0)

# ── Scaffold: header padrão + status + scroll/content ──────────────────────────────
static func scaffold(screen: Control, title_text: String, on_back: Callable, on_refresh: Callable, tint := TINT_DEFAULT) -> Dictionary:
	var embedded := screen.has_meta("embedded") and bool(screen.get_meta("embedded"))   # dentro do Shell? [PLANO_UI_SHELL_GODOT]
	screen.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	if not embedded:
		bg(screen, tint)   # no shell o fundo já vem do Shell
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	screen.add_child(root)

	# header
	var hm := MarginContainer.new()
	hm.add_theme_constant_override("margin_left", 16)
	hm.add_theme_constant_override("margin_right", 16)
	hm.add_theme_constant_override("margin_top", 12)
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	hm.add_child(header)
	root.add_child(hm)

	var back = null   # no shell a nav substitui o "←"
	if not embedded:
		back = icon_btn("←", on_back)
		back.custom_minimum_size = Vector2(48, 40)
		header.add_child(back)

	# ícone da tela no header: derivado do .tscn (ui/Character.tscn → "character.png"). Sem editar tela por tela.
	var icon_key := ""
	if screen != null and screen.scene_file_path != "":
		icon_key = screen.scene_file_path.get_file().get_basename().to_lower()
	if Icons.tex(icon_key) != null:
		var ti := Icons.rect(icon_key, 30)
		ti.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		header.add_child(ti)
		var sp := title_text.find(" ")   # tira o emoji do título (fica só "Personagem")
		if sp >= 0:
			title_text = title_text.substr(sp + 1).strip_edges()

	var ttl := Label.new()
	ttl.text = title_text
	ttl.add_theme_font_size_override("font_size", 24)
	ttl.add_theme_color_override("font_color", GOLD)
	ttl.add_theme_color_override("font_outline_color", Color(0.15, 0.04, 0.0))
	ttl.add_theme_constant_override("outline_size", 6)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	ttl.clip_text = true
	ttl.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	header.add_child(ttl)

	var wallet = null   # no shell a carteira mora no topbar (UiKit.set_wallet → topbar_sink)
	if not embedded:
		wallet = Label.new()
		wallet.add_theme_font_size_override("font_size", 13)
		wallet.add_theme_color_override("font_color", TEXT_DIM)
		wallet.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		header.add_child(wallet)

	var refresh := icon_btn("🔄", on_refresh)
	refresh.custom_minimum_size = Vector2(44, 40)
	header.add_child(refresh)

	# régua dourada (mesma linguagem das seções do Hub)
	var rm := MarginContainer.new()
	rm.add_theme_constant_override("margin_left", 16)
	rm.add_theme_constant_override("margin_right", 16)
	rm.add_theme_constant_override("margin_top", 8)
	var rule := ColorRect.new()
	rule.color = Color(0.78, 0.65, 0.36, 0.35)
	rule.custom_minimum_size = Vector2(0, 1)
	rm.add_child(rule)
	root.add_child(rm)

	# status (altura reservada → sem pulo de layout)
	var sm := MarginContainer.new()
	sm.add_theme_constant_override("margin_left", 16)
	sm.add_theme_constant_override("margin_top", 4)
	var status := Label.new()
	status.add_theme_font_size_override("font_size", 13)
	status.add_theme_color_override("font_color", TEXT_DIM)
	status.custom_minimum_size = Vector2(0, 22)
	sm.add_child(status)
	root.add_child(sm)

	# scroll → pad → content
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.follow_focus = true
	root.add_child(scroll)
	var pad := MarginContainer.new()
	pad.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pad.add_theme_constant_override("margin_left", 16)
	pad.add_theme_constant_override("margin_right", 16)
	pad.add_theme_constant_override("margin_top", 10)
	pad.add_theme_constant_override("margin_bottom", 20)
	scroll.add_child(pad)
	var content := VBoxContainer.new()
	content.add_theme_constant_override("separation", 10)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pad.add_child(content)

	# coluna máx 920 px centrada (legibilidade no desktop)
	var cap := func() -> void:
		var extra: int = maxi(0, int((screen.size.x - 920) / 2.0))
		pad.add_theme_constant_override("margin_left", 16 + extra)
		pad.add_theme_constant_override("margin_right", 16 + extra)
	screen.resized.connect(cap)
	cap.call()

	return {"content": content, "status": status, "header": header, "back": back, "refresh": refresh, "wallet": wallet, "scroll": scroll}

# ── Carteira do header ─────────────────────────────────────────────────────────────
# w = WarriorResponse (json do /api/warrior). Mostra HP, estamina e as 3 moedas.
static func set_wallet(wallet, w: Dictionary) -> void:
	if w.is_empty():
		return
	if wallet == null:   # tela embedded → atualiza o topbar do Shell (sem ciclo de class_name)
		if topbar_sink.is_valid():
			topbar_sink.call(w)
		return
	var hp := int(w.get("hpPercent", w.get("currentHp", 100)))
	var stam := int(w.get("stamina", 0))
	var gold := int(w.get("gold", 0))
	var silver := int(w.get("silver", 0))
	var bronze := int(w.get("bronze", 0))
	wallet.text = "❤%d%%  ⚡%d   🥇%d 🥈%d 🥉%d" % [hp, stam, gold, silver, bronze]
	var col := TEXT_DIM
	if hp <= 0:
		col = ERR
	elif stam < 25:
		col = WARN
	wallet.add_theme_color_override("font_color", col)

# ── Feedback de status ─────────────────────────────────────────────────────────────
static func flash(status: Label, text: String, kind := 0) -> void:
	if status == null:
		return
	var col := Color(0.78, 0.74, 0.66)
	var prefix := ""
	if kind == 1:
		col = OK; prefix = "✔ "
	elif kind == 2:
		col = ERR; prefix = "✖ "
	if prefix != "" and text.length() > 0 and text.unicode_at(0) > 0x2000:
		prefix = ""   # já começa com emoji/símbolo
	status.add_theme_color_override("font_color", col)
	status.text = prefix + text

# Texto de erro a partir de uma resposta do BackendClient ({ok,status,json,raw,error}).
static func err_text(r) -> String:
	if r is Dictionary:
		if r.get("json") is Dictionary and r["json"].has("message"):
			return str(r["json"]["message"])
		if r.has("error") and str(r.get("error", "")) != "":
			return str(r["error"])
		if r.has("status"):
			return "Erro (%s)" % str(r["status"])
	return "Erro desconhecido"

# Atalho: mostra o erro de uma resposta no status.
static func show_error(status: Label, r) -> void:
	flash(status, err_text(r), 2)

# ── Modal de confirmação (procedural) ──────────────────────────────────────────────
static func confirm(host: Control, text: String, confirm_label: String, on_yes: Callable, danger := true) -> void:
	var dim_rect := ColorRect.new()
	dim_rect.color = Color(0, 0, 0, 0.62)
	dim_rect.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim_rect.mouse_filter = Control.MOUSE_FILTER_STOP
	host.add_child(dim_rect)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim_rect.add_child(center)
	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(420, 0)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.09, 0.10, 0.97)
	sb.set_border_width_all(2)
	sb.border_color = Color(0.40, 0.32, 0.20)
	sb.set_corner_radius_all(3)
	sb.set_content_margin_all(18)
	panel.add_theme_stylebox_override("panel", sb)
	center.add_child(panel)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 14)
	panel.add_child(v)
	var lbl := body(text)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(lbl)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	v.add_child(row)
	var close := func() -> void: dim_rect.queue_free()
	row.add_child(_btn("Cancelar", close, Vector2(110, 40), 15))
	var yes_cb := func() -> void:
		dim_rect.queue_free()
		on_yes.call()
	var yes := _btn(confirm_label, yes_cb, Vector2(140, 40), 15)
	if danger:
		yes.add_theme_color_override("font_color", Color(0.92, 0.55, 0.48))
		yes.add_theme_color_override("font_hover_color", Color(1.0, 0.62, 0.50))
	row.add_child(yes)
	# clicar fora do painel = cancelar
	dim_rect.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			close.call())
	yes.call_deferred("grab_focus")

# ── Botões (tudo pedra) ────────────────────────────────────────────────────────────
static func _btn(text: String, cb: Callable, size: Vector2, font := 15) -> Button:
	var b := Button.new()
	b.text = text
	StoneStyle.apply(b)
	b.add_theme_font_size_override("font_size", font)
	b.custom_minimum_size = size
	if cb.is_valid():
		b.pressed.connect(UiKit._debounce.bind(b))   # [SEGURANCA] desabilita 0.4s ao clicar (anti clique-duplo + feedback)
		b.pressed.connect(cb)
	return b

# Feedback imediato + trava de clique-duplo: desabilita o botão por 0.4s (some no busy da tela também).
# Se a tela re-renderizar e liberar o botão, o is_instance_valid no timer evita tocar num nó morto.
static func _debounce(b: Button) -> void:
	b.disabled = true
	if b.is_inside_tree():
		b.get_tree().create_timer(0.4).timeout.connect(func() -> void:
			if is_instance_valid(b):
				b.disabled = false)

static func action(text: String, cb: Callable) -> Button:
	return _btn(text, cb, Vector2(130, 40), 15)

static func action_big(text: String, cb: Callable) -> Button:
	return _btn(text, cb, Vector2(160, 48), 18)

static func action_danger(text: String, cb: Callable) -> Button:
	var b := _btn(text, cb, Vector2(130, 40), 15)
	b.add_theme_color_override("font_color", Color(0.92, 0.55, 0.48))
	b.add_theme_color_override("font_hover_color", Color(1.0, 0.62, 0.50))
	return b

static func icon_btn(text: String, cb: Callable) -> Button:
	return _btn(text, cb, Vector2(44, 40), 18)

# Botão pequeno p/ ações de linha (item_row etc.).
static func small_btn(text: String, cb: Callable, danger := false) -> Button:
	var b := _btn(text, cb, Vector2(120, 36), 13)
	if danger:
		b.add_theme_color_override("font_color", Color(0.92, 0.55, 0.48))
		b.add_theme_color_override("font_hover_color", Color(1.0, 0.62, 0.50))
	return b

# Campo de texto no estilo do kit (fundo escuro + borda bronze; foco = borda dourada).
static func input(placeholder := "") -> LineEdit:
	var le := LineEdit.new()
	le.placeholder_text = placeholder
	le.add_theme_font_size_override("font_size", 14)
	le.add_theme_color_override("font_color", TEXT)
	le.add_theme_color_override("font_placeholder_color", TEXT_DIM)
	le.add_theme_color_override("caret_color", GOLD)
	var normal := StyleBoxFlat.new()
	normal.bg_color = Color(0.05, 0.045, 0.06)
	normal.set_border_width_all(1)
	normal.border_color = Color(0.40, 0.32, 0.20, 0.6)
	normal.set_corner_radius_all(2)
	normal.content_margin_left = 10
	normal.content_margin_right = 10
	normal.content_margin_top = 8
	normal.content_margin_bottom = 8
	var focus: StyleBoxFlat = normal.duplicate()
	focus.border_color = GOLD_SOFT
	le.add_theme_stylebox_override("normal", normal)
	le.add_theme_stylebox_override("focus", focus)
	le.add_theme_stylebox_override("read_only", normal)
	return le

# ── Cartões / seções / texto ───────────────────────────────────────────────────────
# Retorna [PanelContainer, VBoxContainer] — adicione o conteúdo no VBox.
static func card(border := BRONZE, enabled := true) -> Array:
	var p := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.115, 0.10, 0.12, 0.92)
	sb.set_border_width_all(1)
	sb.border_color = Color(border.r, border.g, border.b, 0.65)
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(12)
	sb.shadow_color = Color(0, 0, 0, 0.45)
	sb.shadow_size = 4
	sb.shadow_offset = Vector2(0, 2)
	if not enabled:
		sb.border_color = Color(0.3, 0.3, 0.3, 0.5)
		p.modulate = Color(1, 1, 1, 0.55)
	p.add_theme_stylebox_override("panel", sb)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 4)
	p.add_child(v)
	return [p, v]

static func section(text: String) -> Control:
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	v.add_child(spacer(8))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	var lbl := Label.new()
	lbl.text = text.to_upper()
	lbl.add_theme_font_size_override("font_size", 15)
	lbl.add_theme_color_override("font_color", GOLD_SOFT)
	row.add_child(lbl)
	var rule := ColorRect.new()
	rule.color = Color(0.78, 0.65, 0.36, 0.35)
	rule.custom_minimum_size = Vector2(0, 1)
	rule.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	rule.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rule)
	v.add_child(row)
	return v

static func kv(key: String, value: String, value_col := TEXT) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 8)
	var k := Label.new()
	k.text = key
	k.custom_minimum_size = Vector2(170, 0)
	k.add_theme_font_size_override("font_size", 14)
	k.add_theme_color_override("font_color", TEXT_DIM)
	h.add_child(k)
	var val := Label.new()
	val.text = value
	val.add_theme_font_size_override("font_size", 14)
	val.add_theme_color_override("font_color", value_col)
	val.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(val)
	return h

static func body(text: String) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 14)
	l.add_theme_color_override("font_color", TEXT)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

static func dim(text: String) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", TEXT_DIM)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

# ── Barra de progresso (HP/Stamina/XP…) ────────────────────────────────────────────
static func bar(label: String, value: int, maxv: int, fill: Color, suffix := "") -> VBoxContainer:
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 2)
	var lbl := Label.new()
	lbl.text = label if suffix == "" else "%s   %s" % [label, suffix]
	lbl.add_theme_font_size_override("font_size", 13)
	lbl.add_theme_color_override("font_color", TEXT)
	v.add_child(lbl)
	var cap := maxi(maxv, 1)
	var pb := ProgressBar.new()
	pb.min_value = 0
	pb.max_value = cap
	pb.value = clampi(value, 0, cap)
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(0, 16)
	var bgs := StyleBoxFlat.new()
	bgs.bg_color = Color(0.05, 0.045, 0.06)
	bgs.set_border_width_all(1)
	bgs.border_color = Color(0.40, 0.32, 0.20, 0.6)
	bgs.set_corner_radius_all(2)
	var fgs := StyleBoxFlat.new()
	fgs.bg_color = fill
	fgs.set_corner_radius_all(2)
	pb.add_theme_stylebox_override("background", bgs)
	pb.add_theme_stylebox_override("fill", fgs)
	v.add_child(pb)
	return v

# ── Linha de item (mochila/loja/leilão/baú) ────────────────────────────────────────
# it = item (name, rarity, itemLevel, type, statsLine?…); actions = [[label, cb, danger?], …].
# Ícone do TIPO de item (slot_*) como TextureRect pronto, ou null se o tipo não tem ícone.
# Usado pelo item_row e pelos cards próprios (Leilão) → ícone de item consistente em todo o projeto.
static func item_icon(item_type: String, px := 48) -> TextureRect:
	var t := Icons.item_tex(item_type)
	if t == null:
		return null
	var tr := TextureRect.new()
	tr.texture = t
	tr.custom_minimum_size = Vector2(px, px)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tr.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return tr

static func item_row(it: Dictionary, name_text: String, sub_text: String, stats_text: String, actions: Array) -> PanelContainer:
	var rar := int(it.get("rarity", 1))
	var res := card(rarity_color(rar))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	if rar >= 4:   # épico/lendário: borda mais grossa
		var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
		sb.set_border_width_all(2)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 12)
	box.add_child(row)
	var ic := item_icon(str(it.get("type", "")))   # ícone do tipo (slot_*), consistente em todo o projeto
	if ic:
		row.add_child(ic)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(left)
	var nm := Label.new()
	nm.text = name_text
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", rarity_color(rar))
	left.add_child(nm)
	if sub_text != "":
		left.add_child(dim(sub_text))
	if stats_text != "":
		var st := Label.new()
		st.text = stats_text
		st.add_theme_font_size_override("font_size", 12)
		st.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		st.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		left.add_child(st)
	var rcol := VBoxContainer.new()
	rcol.add_theme_constant_override("separation", 6)
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rcol)
	for a in actions:
		var danger: bool = bool(a[2]) if a.size() > 2 else false
		rcol.add_child(small_btn(str(a[0]), a[1], danger))
	return pc

# ── Estado vazio (com dica de onde conseguir) ──────────────────────────────────────
static func empty(text: String, hint := "") -> Control:
	var res := card(Color(0.3, 0.3, 0.3, 0.5))
	var v: VBoxContainer = res[1]
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 14)
	l.add_theme_color_override("font_color", TEXT_DIM)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(l)
	if hint != "":
		var h := Label.new()
		h.text = hint
		h.add_theme_font_size_override("font_size", 12)
		h.add_theme_color_override("font_color", GOLD_SOFT)
		h.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		h.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		v.add_child(h)
	return res[0]

# ── Grid responsivo + linha de filtros ─────────────────────────────────────────────
const RARITY_NAMES := ["Comum", "Incomum", "Raro", "Épico", "Lendário"]

# Põe os cards num GridContainer responsivo (encurta telas longas). builder = func(item) -> Control.
# host = a tela (p/ ler a largura do viewport). compact=true → cards pequenos cabem em 3 colunas.
static func grid(host: Control, items: Array, builder: Callable, compact := false) -> GridContainer:
	var w := 900.0
	if host != null and host.is_inside_tree():
		w = host.get_viewport().get_visible_rect().size.x
	var cols := 1
	if compact:
		cols = 3 if w >= 820.0 else (2 if w >= 540.0 else 1)
	else:
		cols = 2 if w >= 640.0 else 1
	var g := GridContainer.new()
	g.columns = cols
	g.add_theme_constant_override("h_separation", 8)
	g.add_theme_constant_override("v_separation", 8)
	g.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for it in items:
		var card: Control = builder.call(it)
		if card != null:
			card.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			g.add_child(card)
	return g

# Linha de chips de filtro (HFlow → quebra em telas estreitas). options = Array de
# {label, value, color?}. active = valor selecionado. on_pick = func(value) -> void (re-renderiza).
static func filter_row(options: Array, active, on_pick: Callable) -> Control:
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	for o in options:
		if not (o is Dictionary):
			continue
		var b := small_btn(str(o.get("label", "?")), on_pick.bind(o.get("value")))
		b.custom_minimum_size = Vector2(0, 32)
		b.add_theme_font_size_override("font_size", 12)
		var col: Color = o.get("color", GOLD)
		b.add_theme_color_override("font_color", col)
		if active == o.get("value"):
			# chip ATIVO: fundo preenchido + borda na cor da opção (destaque claro, não só opacidade)
			var sb := StyleBoxFlat.new()
			sb.bg_color = Color(col.r, col.g, col.b, 0.22)
			sb.set_border_width_all(2); sb.border_color = col; sb.set_corner_radius_all(6)
			sb.content_margin_left = 10; sb.content_margin_right = 10
			sb.content_margin_top = 4; sb.content_margin_bottom = 4
			b.add_theme_stylebox_override("normal", sb)
			b.add_theme_stylebox_override("hover", sb)
			b.add_theme_stylebox_override("pressed", sb)
			b.add_theme_stylebox_override("focus", sb)
		else:
			b.modulate = Color(1, 1, 1, 0.45)        # inativo = apagado
		row.add_child(b)
	return row

# Atalho: filtro de raridade padrão (0=Todas, 1-5). on_pick recebe o int da raridade.
static func rarity_filter(active: int, on_pick: Callable) -> Control:
	var opts := [{"label": "Todas", "value": 0, "color": GOLD}]
	for r in range(1, 6):
		opts.append({"label": RARITY_NAMES[r - 1], "value": r, "color": rarity_color(r)})
	return filter_row(opts, active, on_pick)

static func spacer(h := 8) -> Control:
	var s := Control.new()
	s.custom_minimum_size = Vector2(0, h)
	return s
