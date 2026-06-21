class_name UiKit
extends RefCounted

const Icons := preload("res://ui/Icons.gd")
const Weapons := preload("res://Weapons.gd")   # [SLOT_WEAPON_IMG] render 2D da arma p/ ícone de item
static var _weapons_helper = null              # instância p/ weapon_kind (lazy)

# Quando uma tela embedded chama set_wallet com wallet=null, manda o warrior pro topbar do Shell.
# O Shell registra (UiKit.topbar_sink = update_topbar) sem criar ciclo de class_name. [PLANO_UI_SHELL_GODOT]
static var topbar_sink := Callable()
# Inventory chama após equipar/desequipar → Shell re-busca inventário (índice de comparação + busto 3D),
# SÓ quando o equip muda (não a cada navegação). Evita request à toa. [PLANO_UI_SHELL_GODOT]
static var equip_changed_sink := Callable()

# [LOADING] overlay central de carregamento (1 por vez) — substitui a mensagem "Carregando…" do topo
static var _loading_overlay: Control = null
# [MENU_FUNDO] App liga isto → ao trocar de equip, o duelo do fundo re-monta com o seu gear novo.
static var duel_refresh_sink := Callable()
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

# Formata um valor em BRONZE (unidade-base) em TEXTO ouro/prata/bronze. Ex.: 2500 → "25 prata".
# 100 bronze = 1 prata · 100 prata = 1 ouro. P/ ÍCONES pixel-art use coin_box (nó). TODO preço/custo/
# recompensa do jogo é em bronze — use isto p/ não mostrar um valor de bronze com cara de ouro. [MOEDA]
static func coin_str(bronze: int) -> String:
	var g := bronze / 10000
	var s := (bronze % 10000) / 100
	var b := bronze % 100
	# Em TEXTO os emojis 🥇🥈🥉 ficam todos dourados (fonte mono) → indistinguíveis. Usa PALAVRAS.
	# Onde dá pra usar nó (cards/linhas), prefira coin_box (ícones pixel-art). [MOEDA]
	var parts: Array = []
	if g > 0: parts.append(Lang.t("%d ouro") % g)
	if s > 0: parts.append(Lang.t("%d prata") % s)
	if b > 0 or parts.is_empty(): parts.append(Lang.t("%d bronze") % b)
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
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED   # conteúdo nunca rola na horizontal (sem estouro/corte)
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
	content.add_theme_constant_override("separation", 7)   # [SEM_SCROLL] espaçamento menor entre blocos → telas mais curtas
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
	# [ICON_TOOLTIP] carteira é emoji concatenado → 1 tooltip de legenda explica os símbolos.
	wallet.tooltip_text = Lang.t("❤ Vida · ⚡ Estamina · 🥇 Ouro · 🥈 Prata · 🥉 Bronze")
	wallet.mouse_filter = Control.MOUSE_FILTER_PASS
	var col := TEXT_DIM
	if hp <= 0:
		col = ERR
	elif stam < 25:
		col = WARN
	wallet.add_theme_color_override("font_color", col)

# ── Feedback de status ─────────────────────────────────────────────────────────────
static func flash(status: Label, text: String, kind := 0) -> void:
	hide_loading()   # [LOADING] qualquer mensagem de status encerra o dialog de carregamento (unificado)
	if status == null:
		return
	var col := Color(0.78, 0.74, 0.66)
	if kind == 1:
		col = OK
	elif kind == 2:
		col = ERR
	status.add_theme_color_override("font_color", col)
	# [SEM_WEB_EMOJI] toast = Label (texto puro, não renderiza ícone inline) → tira QUALQUER emoji de web;
	# a COR (verde/vermelho) já comunica sucesso/erro. Nada de ✔/✖/💨 de web na UI. [me respeita]
	status.text = strip_web_emoji(text)

# Tira emoji/dingbat/símbolo de web de QUALQUER posição. Mantém texto/acentos (<0x2000) + pontuação
# tipográfica (travessão, reticências, aspas, bullet). Colapsa espaços que sobram. [SEM_WEB_EMOJI]
const _KEEP_SYM := [0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026]
static func strip_web_emoji(text: String) -> String:
	var out := ""
	for i in text.length():
		var c := text.unicode_at(i)
		if c < 0x2000 or c in _KEEP_SYM:
			out += text[i]
	while out.find("  ") != -1:
		out = out.replace("  ", " ")
	out = out.replace(" )", ")").replace("( ", "(")   # emoji inline em "(1 🥉)" → "(1)" sem buraco
	return out.strip_edges()

# Texto de erro a partir de uma resposta do BackendClient ({ok,status,json,raw,error}).
static func err_text(r) -> String:
	if r is Dictionary:
		# Erros do backend vêm como {"error": "..."} (GlobalExceptionHandler + controllers);
		# alguns endpoints usam {"message": "..."}. Lê os dois ANTES de cair no genérico "Erro (status)".
		if r.get("json") is Dictionary:
			var j: Dictionary = r["json"]
			if str(j.get("error", "")) != "":
				return str(j["error"])
			if str(j.get("message", "")) != "":
				return str(j["message"])
		if r.has("error") and str(r.get("error", "")) != "":   # falha de conexão (sem json)
			return str(r["error"])
		if r.has("status"):
			return Lang.t("Erro (%s)") % str(r["status"])
	return Lang.t("Erro desconhecido")

# Atalho: mostra o erro de uma resposta no status.
static func show_error(status: Label, r) -> void:
	hide_loading()   # [LOADING] um erro encerra o carregamento → tira o overlay
	flash(status, err_text(r), 2)

# [LOADING] Overlay CENTRAL de carregamento (dark backdrop + card + engrenagem girando + texto).
# Substitui a antiga mensagem "Carregando…" no topo (que ficava escondida atrás do header). É o MESMO
# padrão em todas as telas: o _refresh chama show_loading(self); o _render/erro chama hide_loading().
# Idempotente (1 overlay por vez) e bloqueia cliques enquanto carrega.
static func show_loading(host: Control, text := "") -> void:
	hide_loading()
	if host == null:
		return
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.5)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	host.add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := card(GOLD_SOFT)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	box.add_theme_constant_override("separation", 10)
	var spin := TextureRect.new()
	spin.texture = Icons.tex("settings")   # engrenagem girando = carregando
	spin.custom_minimum_size = Vector2(44, 44)
	spin.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	spin.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	spin.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	spin.pivot_offset = Vector2(22, 22)
	box.add_child(spin)
	var lbl := Label.new()
	lbl.text = text if text != "" else Lang.t("Carregando…")
	lbl.add_theme_font_size_override("font_size", 16)
	lbl.add_theme_color_override("font_color", GOLD)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(lbl)
	center.add_child(pc)
	_loading_overlay = overlay
	if spin.texture != null:   # gira a engrenagem (loop infinito) — sem ícone, só o texto
		var tw := spin.create_tween().set_loops()
		tw.tween_property(spin, "rotation", TAU, 1.1).from(0.0)

static func hide_loading() -> void:
	if _loading_overlay != null and is_instance_valid(_loading_overlay):
		_loading_overlay.queue_free()
	_loading_overlay = null

# [ERRO_VISIVEL] Modal centralizado de aviso/erro: overlay escuro + card + OK. Usar quando o `status`
# (que mora no header) ficaria longe da ação ou seria apagado por um _refresh logo em seguida — ex.:
# falha ao equipar no fim de uma lista longa. host = a tela (Control). Sai no OK ou clicando fora.
static func notify(host: Control, text: String, is_error := false) -> void:
	if host == null or text.strip_edges() == "":
		return
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	host.add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := card(GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	if is_error:
		sb.border_color = ERR
	vb.add_theme_constant_override("separation", 12)
	center.add_child(panel)
	var lbl := body(text)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.custom_minimum_size = Vector2(420, 0)
	vb.add_child(lbl)
	var ok := action("OK", func() -> void: overlay.queue_free())
	ok.custom_minimum_size = Vector2(420, 40)
	vb.add_child(ok)
	# clicar fora do card (no overlay) também fecha
	overlay.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed:
			overlay.queue_free())
	ok.call_deferred("grab_focus")

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

# Relatório de batalha (modal) — estilo da Torre: borda win/loss + título + recompensas + log
# colapsável + OK. Reusado p/ TODAS as batalhas (quest/zona) terem o mesmo desfecho. [BATTLE_REPORT]
# reward_rows = Array de Control (kv/kv_node/dim já montados pelo chamador). log = Array de String.
static func show_battle_report(host: Control, won: bool, title: String, reward_rows: Array, log: Array, on_close := Callable()) -> void:
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	host.add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var border: Color = OK if won else ERR
	var res := card(border)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	vb.add_theme_constant_override("separation", 10)
	center.add_child(panel)
	# [ICONES_MARCADOR] título com ícone PixelLab quando começa com emoji-marcador (⚔/💀/🔒/🏆…), centrado.
	var tsplit := Icons.split_emoji(title)
	if tsplit[0] != "":
		var th := HBoxContainer.new()
		th.alignment = BoxContainer.ALIGNMENT_CENTER
		th.add_theme_constant_override("separation", 8)
		var tic := Icons.rect(tsplit[0], 24); tic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		th.add_child(tic)
		var tl := Label.new(); tl.text = tsplit[1]
		tl.add_theme_font_size_override("font_size", 18)
		tl.add_theme_color_override("font_color", border)
		th.add_child(tl)
		vb.add_child(th)
	else:
		var h := Label.new()
		h.text = title
		h.add_theme_font_size_override("font_size", 18)
		h.add_theme_color_override("font_color", border)
		h.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		vb.add_child(h)
	for row in reward_rows:
		if row is Control:
			vb.add_child(row)
	# log da batalha (colapsável — pode ter muitas linhas; descarta a tag interna WINNER:)
	var clean: Array = []
	if log is Array:
		for line in log:
			var s := str(line)
			if s.begins_with("WINNER:"):
				continue
			clean.append(s)
	if not clean.is_empty():
		var log_box := VBoxContainer.new()
		# [FIX] Dentro do ScrollContainer o VBox colapsava p/ largura ~0 → os labels (autowrap) quebravam
		# 1 caractere por linha. Largura explícita (≤ a do scroll) faz o autowrap funcionar normal.
		log_box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		log_box.custom_minimum_size = Vector2(420, 0)
		for s in clean:
			log_box.add_child(dim(str(s)))
		# scroll: cresce até caber log curto; trava em ~300px e rola quando é grande
		var log_scroll := ScrollContainer.new()
		log_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
		log_scroll.custom_minimum_size = Vector2(440, mini(clean.size() * 22 + 8, 300))
		log_scroll.visible = false
		log_scroll.add_child(log_box)
		var toggle := small_btn("📜 Ver log", Callable())
		toggle.pressed.connect(func() -> void:
			log_scroll.visible = not log_scroll.visible
			toggle.text = "📜 Ocultar log" if log_scroll.visible else "📜 Ver log")
		vb.add_child(toggle)
		vb.add_child(log_scroll)
	vb.add_child(spacer(4))
	var ok := action("OK", func() -> void:
		overlay.queue_free()
		if on_close.is_valid():
			on_close.call())
	ok.custom_minimum_size = Vector2(440, 40)
	vb.add_child(ok)
	ok.call_deferred("grab_focus")

# [CARD_BOTAO] Botão de escolha ICON-PRIMARY: ícone grande em cima + rótulo pequeno embaixo. P/ modais
# de escolha binária (Encarar/Fugir, Ajudar/Terminar) e chips de nó da Incursão — bem menor que o
# botão de texto 460×40. Fallback no emoji se o ícone PixelLab ainda não foi importado.
static func icon_choice_btn(icon_key: String, emoji: String, label: String, cb: Callable, accent := GOLD_SOFT, compact := false, flat := false) -> Button:
	var icon_px := 26 if compact else 40
	var lbl_font := 11 if compact else 13
	var b := Button.new()
	if flat:
		_apply_flat_node(b)   # [INCURSAO] ícone "no mapa" sem moldura (estilo Slay-the-Spire) — só hover suave
	else:
		DarkButtonStyle.apply(b)
	b.custom_minimum_size = Vector2(72, 54) if compact else Vector2(112, 84)
	b.focus_mode = Control.FOCUS_NONE
	if cb.is_valid():
		b.pressed.connect(_debounce.bind(b))
		b.pressed.connect(cb)
	var v := VBoxContainer.new()
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	v.add_theme_constant_override("separation", 1 if compact else 4)
	v.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	b.add_child(v)
	var ic := Icons.tex(icon_key)
	if ic != null:
		var tr := _tex_rect(ic, icon_px)
		tr.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		v.add_child(tr)
		Icons.anim_rect(b, tr, icon_key)   # [HOVER_ICON_ANIM] o botão-pai dispara o frame-cycle da rect interna
	else:
		var el := Label.new(); el.text = emoji
		el.add_theme_font_size_override("font_size", 20 if compact else 30)
		el.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		el.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.add_child(el)
	if label != "":
		var ll := Label.new(); ll.text = label
		ll.add_theme_font_size_override("font_size", lbl_font)
		ll.add_theme_color_override("font_color", accent)
		ll.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		ll.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.add_child(ll)
	return b

# [INCURSAO] Botão "sem moldura" p/ os nós sobre o mapa: fundo transparente + hover/press suaves.
static func _apply_flat_node(b: Button) -> void:
	b.add_theme_stylebox_override("normal", StyleBoxEmpty.new())
	b.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	var hover := StyleBoxFlat.new()
	hover.bg_color = Color(1, 1, 1, 0.10); hover.set_corner_radius_all(8)
	b.add_theme_stylebox_override("hover", hover)
	var press := StyleBoxFlat.new()
	press.bg_color = Color(1, 1, 1, 0.16); press.set_corner_radius_all(8)
	b.add_theme_stylebox_override("pressed", press)

# [TOAST] Toast de recompensa NÃO-bloqueante: aparece no topo, fade-in e some sozinho (~2.6s) — sem
# botão OK. Usado p/ desfecho SIMPLES (coleta/loot sem batalha). title pode ter emoji-marcador (vira
# ícone). chips = Array; cada item é um Control (ex.: coin_box) OU [icon_key, text]. mouse IGNORE em
# tudo → o jogador continua clicando a tela por baixo.
static func reward_toast(host: Control, title: String, chips: Array) -> void:
	if host == null or (title.strip_edges() == "" and chips.is_empty()):
		return
	var root := Control.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	host.add_child(root)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	center.offset_top = 64
	center.offset_bottom = 240
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(center)
	var panel := PanelContainer.new()
	panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.09, 0.10, 0.96)
	sb.set_border_width_all(1)
	sb.border_color = GOLD_SOFT
	sb.set_corner_radius_all(6)
	sb.set_content_margin_all(12)
	sb.shadow_color = Color(0, 0, 0, 0.5); sb.shadow_size = 6
	panel.add_theme_stylebox_override("panel", sb)
	center.add_child(panel)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 6)
	vb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	panel.add_child(vb)
	if title.strip_edges() != "":
		var tnode := icon_text(title, 15, GOLD, 20)
		tnode.mouse_filter = Control.MOUSE_FILTER_IGNORE
		vb.add_child(tnode)
	if not chips.is_empty():
		var flow := HFlowContainer.new()
		flow.add_theme_constant_override("h_separation", 12)
		flow.add_theme_constant_override("v_separation", 4)
		flow.mouse_filter = Control.MOUSE_FILTER_IGNORE
		vb.add_child(flow)
		for c in chips:
			if c is Control:
				c.mouse_filter = Control.MOUSE_FILTER_IGNORE
				flow.add_child(c)
			elif c is Array and c.size() >= 2:
				var hh := HBoxContainer.new(); hh.add_theme_constant_override("separation", 4)
				hh.mouse_filter = Control.MOUSE_FILTER_IGNORE
				var cic := Icons.rect(str(c[0]), 16); cic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
				hh.add_child(cic)
				var lb := Label.new(); lb.text = str(c[1])
				lb.add_theme_font_size_override("font_size", 13)
				lb.add_theme_color_override("font_color", TEXT)
				lb.mouse_filter = Control.MOUSE_FILTER_IGNORE
				hh.add_child(lb)
				flow.add_child(hh)
	root.modulate = Color(1, 1, 1, 0)
	var tw := root.create_tween()
	tw.tween_property(root, "modulate:a", 1.0, 0.18)
	tw.tween_interval(2.2)
	tw.tween_property(root, "modulate:a", 0.0, 0.4)
	tw.tween_callback(root.queue_free)

# ── Botões (tudo pedra) ────────────────────────────────────────────────────────────
static func _btn(text: String, cb: Callable, size: Vector2, font := 15, tier := 1) -> Button:
	var b := Button.new()
	_btn_label(b, text)   # [SEM_WEB_EMOJI] emoji-líder vira ÍCONE do projeto; resto sem emoji de web
	DarkButtonStyle.apply(b, tier)   # [BOTAO_DARK] 0=PRIMARY (CTA c/ borda dourada), 1=SECONDARY (traço bronze)
	b.add_theme_font_size_override("font_size", font)
	b.custom_minimum_size = size
	if cb.is_valid():
		b.pressed.connect(UiKit._debounce.bind(b))   # [SEGURANCA] desabilita 0.4s ao clicar (anti clique-duplo + feedback)
		b.pressed.connect(cb)
	return b

# Feedback imediato + trava de clique-duplo: desabilita o botão por 0.4s (some no busy da tela também).
# [LAMBDA_FREED] Captura o instance_id (int), NÃO o nó: se o clique navega e a tela/botão é liberada antes
# do timer de 0.4s, capturar o nó faria o engine logar "Lambda capture at index 0 was freed" quando o
# SceneTreeTimer (dono = a árvore) dispara. Com o id, o capture nunca é um objeto liberado → sem warning;
# instance_from_id volta null se já morreu → seguro.
static func _debounce(b: Button) -> void:
	b.disabled = true
	if b.is_inside_tree():
		var bid := b.get_instance_id()
		b.get_tree().create_timer(0.4).timeout.connect(func() -> void:
			var btn := instance_from_id(bid) as Button
			if is_instance_valid(btn):
				btn.disabled = false)

# [SEM_WEB_EMOJI] Texto do botão sem emoji de web: emoji-líder mapeado → ÍCONE do projeto + resto;
# emoji solto (ícone próprio) → ícone; senão tira o emoji do texto. Nunca esvazia o botão (fallback).
static func _btn_label(b: Button, text: String) -> void:
	var t := text.strip_edges()
	var parts := Icons.split_emoji(t)            # [icon_key, resto] se o líder mapeia p/ ícone EXISTENTE
	if str(parts[0]) != "":
		Icons.set_icon(b, str(parts[0]))
		b.add_theme_constant_override("icon_max_width", 22)   # ícone pequeno ao lado do texto (não estoura)
		b.text = strip_web_emoji(str(parts[1]))
		return
	var head := t.replace("️", "")               # texto = SÓ um emoji (sem espaço)? tenta ícone próprio
	if head.find(" ") < 0 and Icons.EMOJI_ICON.has(head) and Icons.tex(Icons.EMOJI_ICON[head]) != null:
		Icons.set_icon(b, str(Icons.EMOJI_ICON[head]))
		b.add_theme_constant_override("icon_max_width", 26)
		b.text = ""
		return
	var stripped := strip_web_emoji(t)
	b.text = t if (stripped == "" and b.icon == null) else stripped   # não deixa botão vazio/invisível

static func action(text: String, cb: Callable) -> Button:
	return _btn(text, cb, Vector2(130, 40), 15)

static func action_big(text: String, cb: Callable) -> Button:
	return _btn(text, cb, Vector2(160, 48), 18, DarkButtonStyle.PRIMARY)   # [BOTAO_DARK] CTA = borda dourada

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
	return card_styled(PanelContainer.new(), border, enabled)

# [ITEM_TOOLTIP] Aplica o visual de card num PanelContainer JÁ EXISTENTE (ex.: um ItemTooltipCard) +
# adiciona o VBox de conteúdo. Retorna [pc, vbox]. card() chama isto com um PanelContainer novo.
static func card_styled(p: PanelContainer, border := BRONZE, enabled := true) -> Array:
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

# [ITEM_TOOLTIP] Painel RICO de um item (hover). opts: {equipped:bool}. Fonte única p/ mochila + slots.
# Reusa rarity_color/item_icon_for/coin_box/compare_line/Icons. Lazy: chamado só quando o tooltip aparece.
static func item_tooltip_panel(it: Dictionary, opts := {}) -> PanelContainer:
	var rar := int(it.get("rarity", 1))
	var rc := rarity_color(rar)
	var is_eq := bool(opts.get("equipped", false))
	var root := PanelContainer.new()
	root.custom_minimum_size = Vector2(300, 0)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.09, 0.08, 0.10, 0.98)
	sb.set_border_width_all(2 if rar >= 3 else 1)
	sb.border_color = Color(rc.r, rc.g, rc.b, 1.0 if rar >= 5 else 0.9)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(11)
	sb.shadow_color = Color(rc.r, rc.g, rc.b, 0.30)
	sb.shadow_size = 10 if rar >= 5 else 8
	root.add_theme_stylebox_override("panel", sb)
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 5)
	root.add_child(v)
	# header: ícone + nome (cor da raridade, com contorno) numa barra escura
	var hbar := PanelContainer.new()
	var hsb := StyleBoxFlat.new(); hsb.bg_color = Color(0, 0, 0, 0.28); hsb.set_corner_radius_all(6)
	hsb.content_margin_left = 5; hsb.content_margin_right = 5; hsb.content_margin_top = 3; hsb.content_margin_bottom = 3
	hbar.add_theme_stylebox_override("panel", hsb)
	var hrow := HBoxContainer.new(); hrow.add_theme_constant_override("separation", 8)
	hbar.add_child(hrow)
	var icon := item_icon_for(it, 28)
	if icon != null:
		hrow.add_child(icon)
	var nm := Label.new(); nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 17)
	nm.add_theme_color_override("font_color", rc)
	nm.add_theme_color_override("font_outline_color", Color(0.12, 0.03, 0.0, 1))
	nm.add_theme_constant_override("outline_size", 4)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hrow.add_child(nm)
	v.add_child(hbar)
	v.add_child(item_subline(it, 0))
	# pills (tags) — só as verdadeiras
	var tags := HFlowContainer.new(); tags.add_theme_constant_override("h_separation", 5); tags.add_theme_constant_override("v_separation", 3)
	if is_eq: tags.add_child(_tag_pill("Equipado", GOLD, ""))
	if bool(it.get("selfCrafted", false)): tags.add_child(_tag_pill("Forjado por você", GOLD, "forge"))
	if bool(it.get("pvpLocked", false)): tags.add_child(_tag_pill("Travado no PvP", ERR, "locked"))
	if bool(it.get("guarded", false)): tags.add_child(_tag_pill("Protegido", OK, "locked"))
	if str(it.get("weaponCategory", "")) == "RANGED": tags.add_child(_tag_pill("À distância", TEXT_DIM, ""))
	if tags.get_child_count() > 0: v.add_child(tags)
	# stats (ícone pixel + label + valor dourado)
	var statbox := VBoxContainer.new(); statbox.add_theme_constant_override("separation", 1)
	for s in [["attackBonus", "ATK", "stat_atk"], ["defenseBonus", "DEF", "slot_shield"], ["healthBonus", "HP", "hp"], ["strBonus", "STR", "attr_strength"], ["dexBonus", "DEX", "attr_dexterity"], ["lukBonus", "LUK", "attr_luck"]]:
		var val := int(it.get(s[0], 0))
		if val != 0:
			statbox.add_child(_stat_row(str(s[2]), str(s[1]), val))
	if statbox.get_child_count() > 0:
		v.add_child(_tt_divider(rc))
		v.add_child(statbox)
	# afixos
	var affixes = it.get("affixes", [])
	if affixes is Array:
		for a in affixes:
			if a is Dictionary:
				var al := Label.new()
				al.text = "• %s (%s %+d)" % [str(a.get("word", "")), str(a.get("stat", "")), int(a.get("magnitude", 0))]
				al.add_theme_font_size_override("font_size", 12)
				al.add_theme_color_override("font_color", GOLD if rar < 4 else rc)
				v.add_child(al)
	# soquetes/gemas
	var sockets := int(it.get("sockets", 0))
	if sockets > 0:
		var gnames: Array = []
		var gems = it.get("gems", [])
		if gems is Array:
			for g in gems:
				if g is Dictionary: gnames.append(str(g.get("gemName", g.get("displayName", g.get("type", "")))))
		var sr := HBoxContainer.new(); sr.add_theme_constant_override("separation", 5)
		if Icons.tex("gem") != null: sr.add_child(Icons.rect("gem", 14))
		var sl := Label.new()
		sl.text = "%d/%d%s" % [gnames.size(), sockets, ("  " + ", ".join(gnames)) if not gnames.is_empty() else ""]
		sl.add_theme_font_size_override("font_size", 12); sl.add_theme_color_override("font_color", TEXT_DIM)
		sr.add_child(sl); v.add_child(sr)
	# durabilidade (só sub-máxima)
	var dur := int(it.get("durability", -1))
	if dur >= 0 and dur < 100:
		var dl := Label.new(); dl.text = Lang.t("Durabilidade: %d") % dur
		dl.add_theme_font_size_override("font_size", 11)
		dl.add_theme_color_override("font_color", ERR if dur < 25 else TEXT_DIM)
		v.add_child(dl)
	# resumo de comparação (só p/ item não-equipado e comparável; self-suprime)
	if not is_eq:
		var cmp := compare_line(it)
		if cmp != null: v.add_child(cmp)
	# lore + origem
	var desc := str(it.get("description", ""))
	if desc != "":
		v.add_child(_tt_divider(rc))
		if desc.length() > 170: desc = desc.substr(0, 167) + "…"
		var lore := Label.new(); lore.text = "\"%s\"" % desc
		lore.add_theme_font_size_override("font_size", 12); lore.add_theme_color_override("font_color", TEXT_DIM)
		lore.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; lore.custom_minimum_size = Vector2(276, 0)
		v.add_child(lore)
	var origin := str(it.get("origin", ""))
	if origin != "":
		var orow := HBoxContainer.new(); orow.add_theme_constant_override("separation", 5)
		if Icons.tex("world") != null: orow.add_child(Icons.rect("world", 14))
		var ol := Label.new(); ol.text = Lang.t("Obtido em: %s") % origin
		ol.add_theme_font_size_override("font_size", 11); ol.add_theme_color_override("font_color", GOLD_SOFT)
		ol.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; ol.custom_minimum_size = Vector2(250, 0)
		ol.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		orow.add_child(ol); v.add_child(orow)
	# vender (coin_box = ícones reais de moeda)
	var price := int(it.get("sellPrice", 0))
	if price > 0:
		var prow := HBoxContainer.new(); prow.add_theme_constant_override("separation", 5)
		var pl := Label.new(); pl.text = Lang.t("Vende por")
		pl.add_theme_font_size_override("font_size", 11); pl.add_theme_color_override("font_color", TEXT_DIM)
		prow.add_child(pl); prow.add_child(coin_box(price, 14, TEXT_DIM))
		v.add_child(prow)
	if is_eq:
		var fl := Label.new(); fl.text = Lang.t("(clique para desequipar)")
		fl.add_theme_font_size_override("font_size", 11); fl.add_theme_color_override("font_color", TEXT_DIM)
		v.add_child(fl)
	return root

static func _tag_pill(text: String, col: Color, icon_key := "") -> Control:
	var p := PanelContainer.new()
	var sb := StyleBoxFlat.new(); sb.bg_color = Color(col.r, col.g, col.b, 0.18); sb.set_corner_radius_all(4)
	sb.content_margin_left = 5; sb.content_margin_right = 5; sb.content_margin_top = 1; sb.content_margin_bottom = 1
	p.add_theme_stylebox_override("panel", sb)
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 3)
	if icon_key != "" and Icons.tex(icon_key) != null:
		h.add_child(Icons.rect(icon_key, 12))
	var l := Label.new(); l.text = Lang.t(text); l.add_theme_font_size_override("font_size", 10); l.add_theme_color_override("font_color", col)
	h.add_child(l); p.add_child(h)
	return p

static func _tt_divider(col: Color) -> Control:
	var d := ColorRect.new(); d.color = Color(col.r, col.g, col.b, 0.25); d.custom_minimum_size = Vector2(0, 1)
	return d

static func _stat_row(icon_key: String, label: String, val: int) -> Control:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	if Icons.tex(icon_key) != null:
		h.add_child(Icons.rect(icon_key, 16))
	var nm := Label.new(); nm.text = label
	nm.add_theme_font_size_override("font_size", 12); nm.add_theme_color_override("font_color", TEXT_DIM)
	nm.custom_minimum_size = Vector2(48, 0)
	h.add_child(nm)
	var vl := Label.new(); vl.text = "%+d" % val
	vl.add_theme_font_size_override("font_size", 13); vl.add_theme_color_override("font_color", GOLD)
	h.add_child(vl)
	return h

# [CARD_BOTAO] Card clicável INTEIRO (o card É o botão — sem botão de texto embaixo). Retorna
# [PanelContainer, VBoxContainer] como card(): o chamador enche o VBox com o conteúdo. Um Button
# transparente é sobreposto ao conteúdo (mesmo rect, desenhado por cima) → captura o clique em
# qualquer ponto, hover destaca o card todo, cursor vira mãozinha. enabled=false → card apagado
# e SEM clique (use p/ estado bloqueado, pondo um selo de motivo dentro do VBox).
static func clickable_card(border := BRONZE, on_click := Callable(), enabled := true, tooltip := "") -> Array:
	var res := card(border, enabled)
	if not enabled or not on_click.is_valid():
		return res
	var panel: PanelContainer = res[0]
	var hit := Button.new()
	hit.flat = true
	hit.focus_mode = Control.FOCUS_NONE
	hit.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	if tooltip != "":
		hit.tooltip_text = tooltip   # [CARD_BOTAO] hover no card todo mostra a dica
	var emptysb := StyleBoxEmpty.new()
	for s in ["normal", "hover", "pressed", "focus", "disabled"]:
		hit.add_theme_stylebox_override(s, emptysb)
	hit.pressed.connect(_debounce.bind(hit))
	hit.pressed.connect(on_click)
	hit.mouse_entered.connect(func() -> void:
		if is_instance_valid(panel):
			panel.modulate = Color(1.14, 1.14, 1.14))
	hit.mouse_exited.connect(func() -> void:
		if is_instance_valid(panel):
			panel.modulate = Color(1, 1, 1))
	panel.add_child(hit)   # último filho do PanelContainer = mesmo rect, por cima do conteúdo
	return res

static func section(text: String) -> Control:
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 4)
	v.add_child(spacer(4))   # [SEM_SCROLL] respiro menor antes do cabeçalho de seção
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	# [ICONES_MARCADOR] emoji no início do título → ícone PixelLab (fallback: mantém o texto/emoji)
	var split := Icons.split_emoji(text)
	if split[0] != "":
		var ic := Icons.rect(split[0], 20)
		ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(ic)
		text = split[1]
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

# [PAGINACAO] Cabeçalho de seção com PAGINADOR no canto direito (mesma linha do título):
# [ícone] TÍTULO ──────── ◀ N ▶. page = índice 0-based; has_next = há próxima página.
# on_prev/on_next = callbacks. Reusa `section` (mesmo visual) e encaixa o pager depois da régua.
static func section_paged(text: String, page: int, has_next: bool, on_prev: Callable, on_next: Callable) -> Control:
	var sec := section(text)
	var row: HBoxContainer = sec.get_child(1)   # [spacer, row] → o cabeçalho é o índice 1
	row.add_child(_pager_btn("◀", on_prev, page > 0))
	var pl := Label.new()
	pl.text = "%d" % (page + 1)
	pl.add_theme_font_size_override("font_size", 13)
	pl.add_theme_color_override("font_color", TEXT_DIM)
	pl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	pl.custom_minimum_size = Vector2(20, 0)
	pl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	row.add_child(pl)
	row.add_child(_pager_btn("▶", on_next, has_next))
	return sec

# Botãozinho de paginação (◀/▶). Desabilitado = apagado.
static func _pager_btn(text: String, cb: Callable, enabled: bool) -> Button:
	var b := _btn(text, cb if enabled else Callable(), Vector2(30, 26), 14)
	b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	b.disabled = not enabled
	if not enabled:
		b.modulate = Color(1, 1, 1, 0.4)
	return b

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

# [ICONES_MARCADOR] Linha "[ícone] texto": se o texto começa com um emoji-marcador, troca pelo ícone
# PixelLab; senão devolve um Label simples (mantém o texto/emoji). Use em título de card, badge, linha
# de recompensa — qualquer marcador que hoje é emoji. px = tamanho do ícone; font/cor opcionais.
static func icon_text(text: String, font := 14, col := TEXT, px := 18) -> Control:
	var split := Icons.split_emoji(text)
	var lbl := Label.new()
	lbl.add_theme_font_size_override("font_size", font)
	lbl.add_theme_color_override("font_color", col)
	lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	if split[0] == "":
		lbl.text = text
		return lbl
	lbl.text = split[1]
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 6)
	var ic := Icons.rect(split[0], px)
	ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(ic)
	h.add_child(lbl)
	return h

static func body(text: String) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 14)
	l.add_theme_color_override("font_color", TEXT)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

# kv com um Control no valor (ex.: coin_box pixel-art) em vez de string. [MOEDA]
static func kv_node(key: String, value_node: Control) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 8)
	var k := Label.new()
	k.text = key
	k.custom_minimum_size = Vector2(170, 0)
	k.add_theme_font_size_override("font_size", 14)
	k.add_theme_color_override("font_color", TEXT_DIM)
	h.add_child(k)
	value_node.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(value_node)
	return h

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

# Classe do jogador que está VENDO a lista (warriorClassId). Define o tema das roupas no ícone de
# armadura (Warrior→Knight etc.). Setado pelo Shell.update_topbar + Character. [OUTFITS_CLASSE]
static var current_class := ""

# Gênero do personagem ("male"/"female") → base + peças do paper-doll. Setado pelo Shell. [OUTFITS_FEMALE]
static var current_gender := "male"

# TextureRect padrão de ícone (mesmo enquadramento p/ arma/armadura/slot).
static func _tex_rect(tex: Texture2D, px: int) -> TextureRect:
	var tr := TextureRect.new()
	tr.texture = tex
	tr.custom_minimum_size = Vector2(px, px)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tr.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return tr

# FONTE ÚNICA do ícone de um item (usada pela BAG e pelo SLOT equipado → sempre iguais). Texture2D:
# ARMA → render do modelo 3D; ARMADURA → peça do tema/variante do item; ANEL/COLAR → por raridade;
# resto → ícone genérico do slot (nunca null p/ tipo conhecido). [SLOT_WEAPON_IMG][OUTFITS][ICONES_RARIDADE]
static func item_icon_tex(it: Dictionary) -> Texture2D:
	var ty := str(it.get("type", ""))
	if ty == "WEAPON":
		if _weapons_helper == null:
			_weapons_helper = Weapons.new()
		var kind: String = _weapons_helper.weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
		var model: String = str(Weapons.MODELS.get(kind, ""))
		var p := "res://assets/weapons/icons/" + model + ".png"
		if model != "" and ResourceLoader.exists(p):
			return load(p)
	elif ty == "SHIELD":
		var sp := "res://assets/weapons/icons/" + str(Weapons.SHIELD_MODEL) + ".png"
		if ResourceLoader.exists(sp):
			return load(sp)
	elif Outfits.is_armor_slot(ty):
		var ap := Outfits.icon_path_item(it, ty)   # mesma variante do boneco [OUTFITS_VARIANTES]
		if ap != "" and ResourceLoader.exists(ap):
			return load(ap)
	elif ty == "RING" or ty == "NECKLACE":
		var rt := Icons.tex(ty.to_lower() + "_" + str(clampi(int(it.get("rarity", 1)), 1, 5)))   # por raridade
		if rt != null:
			return rt
	return Icons.item_tex(ty)   # fallback: ícone genérico do slot

# Wrapper TextureRect (bag/cards). Mesma fonte do slot equipado → ícone idêntico nos dois lugares.
static func item_icon_for(it: Dictionary, px := 48) -> TextureRect:
	var t := item_icon_tex(it)
	return _tex_rect(t, px) if t != null else null

# ── Comparação de item vs EQUIPADO ──────────────────────────────────────────────────
# Índice dos itens equipados por type (WEAPON/ARMOR/…). Preenchido pelo Shell (a cada nav)
# e pelo Inventory (após equipar). compare_line lê daqui. [PLANO_UI_SHELL_GODOT]
static var equipped := {}
const _CMP_STATS := [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]

# Reindexa os equipados por type a partir de uma lista de inventário.
static func set_equipped(inv: Array) -> void:
	equipped = {}
	for it in inv:
		if it is Dictionary and bool(it.get("equipped", false)):
			equipped[str(it.get("type", ""))] = it

# Linha "vs equipado": veredito (▲Melhor/▼Pior/◆Lateral) + deltas por stat (verde sobe, vermelho desce).
# Retorna null se: o item está equipado, não há slot comparável equipado, ou não há diferença. [PLANO_UI_SHELL_GODOT]
static func compare_line(it: Dictionary) -> Control:
	if bool(it.get("equipped", false)):
		return null
	var t := str(it.get("type", ""))
	if not equipped.has(t):
		return null
	var cur: Dictionary = equipped[t]
	if int(cur.get("id", -1)) == int(it.get("id", -2)):
		return null   # é o próprio item equipado
	var deltas: Array = []
	var total := 0
	for pair in _CMP_STATS:
		var dv := int(it.get(pair[0], 0)) - int(cur.get(pair[0], 0))
		if dv != 0:
			deltas.append([pair[1], dv])
			total += dv
	if deltas.is_empty():
		return null
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 8)
	row.add_theme_constant_override("v_separation", 2)
	var chip := Label.new()
	chip.add_theme_font_size_override("font_size", 11)
	if total > 0:
		chip.text = "▲ Melhor"; chip.add_theme_color_override("font_color", OK)
	elif total < 0:
		chip.text = "▼ Pior"; chip.add_theme_color_override("font_color", ERR)
	else:
		chip.text = "◆ Lateral"; chip.add_theme_color_override("font_color", WARN)
	row.add_child(chip)
	for entry in deltas:
		var l := Label.new()
		l.text = "%s %+d" % [str(entry[0]), int(entry[1])]
		l.add_theme_font_size_override("font_size", 11)
		l.add_theme_color_override("font_color", OK if int(entry[1]) > 0 else ERR)
		row.add_child(l)
	return row

# Comparação a partir de stats AVULSOS + slot (ItemType). P/ fontes que não são item de inventário
# completo — ex.: receita da Forja (slot + atk/def/hp/str/dex/luk). Monta um item sintético e reusa
# compare_line (id impossível → nunca casa com o equipado). [PLANO_UI_SHELL_GODOT]
static func compare_line_raw(slot: String, atk: int, def_v: int, hp: int, str_v: int, dex_v: int, luk_v: int) -> Control:
	if slot == "":
		return null
	return compare_line({
		"type": slot, "equipped": false, "id": -99999,
		"attackBonus": atk, "defenseBonus": def_v, "healthBonus": hp,
		"strBonus": str_v, "dexBonus": dex_v, "lukBonus": luk_v,
	})

# [STATS_CMP] Linha ÚNICA de stats do item, cada um colorido vs o equipado do MESMO slot:
# ▲ verde = melhor · ▼ vermelho = pior · neutro = igual ou sem item p/ comparar. Substitui a dupla
# (linha de stats crua + compare_line). Mostra só os stats que o item TEM (≠ 0).
static func item_stats_line(it: Dictionary) -> Control:
	var cur: Dictionary = {}
	var t := str(it.get("type", ""))
	if not bool(it.get("equipped", false)) and equipped.has(t) and int(equipped[t].get("id", -1)) != int(it.get("id", -2)):
		cur = equipped[t]
	var has_cmp := not cur.is_empty()
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 10)
	row.add_theme_constant_override("v_separation", 2)
	var any := false
	for pair in _CMP_STATS:
		var v := int(it.get(pair[0], 0))
		var l := Label.new()
		l.add_theme_font_size_override("font_size", 12)
		if has_cmp:
			# COMPARANDO: mostra o DELTA (quanto MUDA se equipar) — ex.: HP -22, ATK +2. Igual (0) não mostra.
			var d := v - int(cur.get(pair[0], 0))
			if d == 0:
				continue
			l.text = "%s%s %+d" % ["▲ " if d > 0 else "▼ ", str(pair[1]), d]
			l.add_theme_color_override("font_color", OK if d > 0 else ERR)
		else:
			# SEM equipado p/ comparar: mostra o valor do próprio item.
			if v == 0:
				continue
			l.text = "%s %+d" % [str(pair[1]), v]
			l.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		any = true
		row.add_child(l)
	return row if any else null

# Sub-linha "Tipo · Nv X · Raridade" com o "Nv X" em VERMELHO quando o item exige nível acima do
# player (não dá pra equipar). player_level <= 0 → não compara (cinza normal). [REQ_LEVEL]
static func item_subline(it: Dictionary, player_level := 0) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 0)
	var ilvl := int(it.get("itemLevel", 1))
	var too_high := player_level > 0 and ilvl > player_level
	var pre := Label.new()
	pre.text = Lang.t(str(it.get("typeDisplay", it.get("type", "")))) + " · "
	pre.add_theme_font_size_override("font_size", 12)
	pre.add_theme_color_override("font_color", TEXT_DIM)
	h.add_child(pre)
	var lv := Label.new()
	lv.text = Lang.t("Nv %d") % ilvl
	lv.add_theme_font_size_override("font_size", 12)
	lv.add_theme_color_override("font_color", ERR if too_high else TEXT_DIM)
	h.add_child(lv)
	var post := Label.new()
	post.text = " · " + Lang.t(str(it.get("rarityName", "")))
	post.add_theme_font_size_override("font_size", 12)
	post.add_theme_color_override("font_color", TEXT_DIM)
	h.add_child(post)
	return h

# level_for > 0 → a sub-linha usa item_subline (Nv vermelho se o item pede nível acima). [REQ_LEVEL]
static func item_row(it: Dictionary, name_text: String, sub_text: String, stats_text: String, actions: Array, level_for := 0) -> PanelContainer:
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
	var ic := item_icon_for(it)   # arma → render do modelo; resto → ícone do slot [SLOT_WEAPON_IMG]
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
	if level_for > 0:
		left.add_child(item_subline(it, level_for))   # [REQ_LEVEL] Nv vermelho se exige nível acima
	elif sub_text != "":
		left.add_child(dim(sub_text))
	var sline := item_stats_line(it)   # [STATS_CMP] stats únicos coloridos vs equipado (substitui stats+compare)
	if sline:
		left.add_child(sline)
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

# Põe os cards num GridContainer RESPONSIVO. builder = func(item) -> Control. compact=true → cards
# menores (até 3 col); normal = até 2 col. As colunas vêm da largura REAL do grid (NÃO da janela —
# embutido no Shell, com nav + cap de 920, a janela é bem mais larga que a área de conteúdo, então
# medir o viewport superestimava e espremia/estourava os cards) e recalculam quando o tamanho muda.
# `host` mantido por compatibilidade da assinatura (os callers passam `self`).
# cell_w/cols_cap (opcionais) sobrepõem a largura-alvo e o teto de colunas — p/ cards mais estreitos
# (ex.: 2 itens por linha na Mochila). Sem eles, mantém o comportamento padrão. [GRID_COLS]
static func grid(host: Control, items: Array, builder: Callable, compact := false, cell_w := 0.0, cols_cap := 0) -> GridContainer:
	var g := GridContainer.new()
	g.columns = 1
	g.add_theme_constant_override("h_separation", 8)
	g.add_theme_constant_override("v_separation", 8)
	g.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for it in items:
		var card: Control = builder.call(it)
		if card != null:
			card.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			g.add_child(card)
	# colunas pela largura PRÓPRIA do grid; recalcula no resize (responsivo de verdade).
	var min_cell := cell_w if cell_w > 0.0 else (360.0 if compact else 430.0)   # largura-alvo por card
	var max_cols := cols_cap if cols_cap > 0 else (3 if compact else 2)
	var relayout := func() -> void:
		var w := g.size.x
		if w <= 0.0:
			return
		var cols := clampi(int((w + 8.0) / (min_cell + 8.0)), 1, max_cols)
		if g.columns != cols:
			g.columns = cols
	g.resized.connect(relayout)
	relayout.call()
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
