extends Control
class_name Shell
# ── SHELL persistente: TopBar (busto+identidade+HP/estamina+moedas) + Nav lateral + ContentHost ──
# Substitui a navegação "tela cheia que troca tudo". Hospeda as 21 telas no ContentHost; o warrior
# é buscado 1x aqui e o topbar se atualiza (telas embedded chamam UiKit.set_wallet→Shell.update_topbar).
# Desenho: docs/PLANO_UI_SHELL_GODOT.md [PLANO_UI_SHELL_GODOT]

signal request_battle(data)   # tela pediu replay 3D → App._play_battle (esconde o shell)
signal logout

const Icons := preload("res://ui/Icons.gd")   # BustView é global (class_name), não precisa preload

# Tooltips (hover) dos itens da topbar — explicam o que é cada coisa.
const COIN_TIPS := {
	"gold": "Ouro — moeda de maior valor (1 ouro = 100 prata = 10.000 bronze)",
	"silver": "Prata — 1 prata = 100 bronze",
	"bronze": "Bronze — moeda básica (recompensas, vendas)",
	"soulstone": "SoulStone — moeda premium (VIP, cura instantânea)",
}
const ELEM_ICONS := {"FIRE": "🔥", "WATER": "💧", "EARTH": "🪨", "AIR": "💨"}

# Tooltips (hover) de CADA item do menu lateral — explicam o que cada tela faz. [MENUBAR_HOVER]
const NAV_TIPS := {
	"World": "Mundo — missões, coleta, caça e zonas dos reinos",
	"Delve": "Incursão — run roguelike: mapa de batalhas, baús e push-your-luck",
	"Work": "Trabalho — atividade idle por profissão (XP + bronze)",
	"Temple": "Templo — cura HP, bênçãos e proteção de itens",
	"Tower": "Torre — andares com chefes escalonados",
	"Arena": "Arena — duelos PvP por ranking",
	"Territory": "Território — guerra de guilda por território",
	"Shop": "Loja — itens em rotação, por raridade",
	"Forge": "Forja — refino, craft, joias e encantamento",
	"Auction": "Leilão — mercado entre jogadores (preço fixo)",
	"Stash": "Baú — guarda itens e recursos fora da mochila",
	"Tavern": "Taverna — beba por buff + chat global",
	"Vip": "VIP — vantagens premium (SoulStone)",
	"Character": "Personagem — ficha completa: equipar (paper-doll), atributos e habilidades",
	"Inventory": "Inventário — equipar, vender, sockets",
	"Abilities": "Habilidades — árvore de talentos da classe",
	"Achievements": "Conquistas — marcos e títulos",
	"Guild": "Guilda — membros, tesouro e guerra",
	"Mail": "Correio — mensagens, itens e recompensas",
	"Daily": "Diário — recompensa de login (ciclo de 7 dias)",
}

# Nav em árvore: [seção, [[tela, rótulo], ...]] — o ícone vem de "<tela em minúsculo>.png".
const SECTIONS := [
	["Aventura",   [["World", "Mundo"], ["Work", "Trabalho"], ["Temple", "Templo"]]],
	["Batalha",    [["Tower", "Torre"], ["Arena", "Arena"], ["Territory", "Território"]]],
	["Comércio",   [["Shop", "Loja"], ["Forge", "Forja"], ["Auction", "Leilão"], ["Stash", "Baú"], ["Tavern", "Taverna"], ["Vip", "VIP"]]],
	["Personagem", [["Character", "Personagem"], ["Achievements", "Conquistas"]]],   # [FICHA_PERSONAGEM] Inventário+Habilidades fundidos na ficha
	["Social",     [["Guild", "Guilda"], ["Mail", "Correio"], ["Daily", "Diário"]]],
]

static var current = null   # ref do shell ativo (untyped p/ evitar edge-case de static var da própria classe)

var warrior: Dictionary = {}
var content_host: Control
var active_screen: Control = null
var active_name := ""
# topbar
var _bust: BustView
var _name_lbl: Label
var _title_lbl: Label
var _sub_lbl: Label
var _xp_bar: ProgressBar
var _xp_lbl: Label
var _hp_bar: ProgressBar
var _hp_lbl: Label
var _stam_bar: ProgressBar
var _stam_lbl: Label
var _coins: Dictionary = {}     # key -> Label
var _stat_lbls: Dictionary = {}  # store_key -> Label do valor (ATK/DEF/HP/EVA no topbar) [TOPBAR]
var _buffs_box: HFlowContainer    # badges dos buffs ativos (templo/vip/refeição/encanto/novato/taverna) — linha própria que QUEBRA
var _nav_buttons: Dictionary = {}   # nome da tela -> Button (destaque do ativo)
var _cache := {}        # nome da tela → node (MANTIDA em memória; alterna visibilidade, não recria)
var _cache_ver := {}    # nome → mutation_count na última atualização (revisita só refaz request se algo mudou)
var _dash: Control = null   # dashboard/home (também cacheado)

func _ready() -> void:
	current = self
	UiKit.topbar_sink = update_topbar          # telas embedded mandam o warrior pro topbar via set_wallet
	UiKit.equip_changed_sink = _on_equip_changed   # Inventory avisa quando equipa → re-veste o busto (sem fetch à toa)
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	UiKit.bg(self, UiKit.TINT_DEFAULT)
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	root.add_theme_constant_override("separation", 0)
	add_child(root)
	root.add_child(_build_topbar())
	var body := HBoxContainer.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_theme_constant_override("separation", 0)
	root.add_child(body)
	body.add_child(_build_nav())
	content_host = Control.new()
	content_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content_host.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_child(content_host)
	await _initial_load()   # 1x no boot: warrior (topbar) + inventário (índice de comparação + busto)
	_show_dashboard()

func _exit_tree() -> void:
	if current == self:
		current = null
	if UiKit.topbar_sink.is_valid() and UiKit.topbar_sink.get_object() == self:
		UiKit.topbar_sink = Callable()
	if UiKit.equip_changed_sink.is_valid() and UiKit.equip_changed_sink.get_object() == self:
		UiKit.equip_changed_sink = Callable()

# ── TopBar ─────────────────────────────────────────────────────────────────────────
func _build_topbar() -> Control:
	var pc := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.075, 0.09, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20)
	sb.border_width_bottom = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	var col := VBoxContainer.new()   # [TOPBAR_BUFFS] coluna: linha principal + linha de buffs abaixo
	col.add_theme_constant_override("separation", 6)
	pc.add_child(col)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 14)
	col.add_child(row)
	# busto num quadro de pedra
	var frame := PanelContainer.new()
	var fb := StyleBoxFlat.new()
	fb.bg_color = Color(0.05, 0.045, 0.06)
	fb.border_color = Color(0.45, 0.36, 0.22); fb.set_border_width_all(1)
	fb.set_corner_radius_all(3)
	frame.add_theme_stylebox_override("panel", fb)
	frame.custom_minimum_size = Vector2(60, 60)
	_bust = BustView.new()
	_bust.custom_minimum_size = Vector2(56, 56)
	frame.add_child(_bust)
	row.add_child(frame)
	# identidade: nome + título · classe·nível · XP
	var idv := VBoxContainer.new()
	idv.add_theme_constant_override("separation", 1)
	idv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(idv)
	var nameline := HBoxContainer.new(); nameline.add_theme_constant_override("separation", 8)
	_name_lbl = Label.new()
	_name_lbl.add_theme_font_size_override("font_size", 18)
	_name_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	nameline.add_child(_name_lbl)
	_title_lbl = Label.new()
	_title_lbl.add_theme_font_size_override("font_size", 12)
	_title_lbl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	_title_lbl.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	nameline.add_child(_title_lbl)
	idv.add_child(nameline)
	_sub_lbl = Label.new()
	_sub_lbl.add_theme_font_size_override("font_size", 12)
	_sub_lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	idv.add_child(_sub_lbl)
	_xp_bar = _mini_bar(Color(0.42, 0.50, 0.85), 150)
	_xp_bar.tooltip_text = "Experiência — enche e sobe de nível"
	idv.add_child(_xp_bar)
	_xp_lbl = Label.new()   # exp atual / limiar do nível + quanto falta pro próximo
	_xp_lbl.add_theme_font_size_override("font_size", 11)
	_xp_lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	idv.add_child(_xp_lbl)
	# stats de combate preenchendo o espaço vazio entre identidade e vitais [TOPBAR]
	row.add_child(_build_statbox())
	# espaçador (mantém vitais/moedas/buffs à direita)
	var spacer := Control.new(); spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)
	# HP + estamina — linhas alinhadas (ícone pixel | barra | valor à direita) + cura ao lado [HEAL]
	var vit := VBoxContainer.new(); vit.add_theme_constant_override("separation", 5)
	vit.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_hp_bar = _mini_bar(Color(0.80, 0.26, 0.24), 150)
	_hp_lbl = Label.new()
	vit.add_child(_vital_row("hp", _hp_bar, _hp_lbl, "Vida (HP) — atual/máximo; cure no botão ao lado (❤) ou no Templo"))
	_stam_bar = _mini_bar(Color(0.40, 0.68, 0.42), 150)
	_stam_lbl = Label.new()
	vit.add_child(_vital_row("stamina", _stam_bar, _stam_lbl, "Estamina — gasta nas ações; enche 100% em 1h (15min com buff de novato)"))
	row.add_child(vit)
	row.add_child(_heal_button())   # botão de cura (cruz vermelha pixel) ao lado das barras
	# moedas
	var coinbox := VBoxContainer.new(); coinbox.add_theme_constant_override("separation", 2)
	coinbox.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var c1 := HBoxContainer.new(); c1.add_theme_constant_override("separation", 10)
	c1.add_child(_coin("gold")); c1.add_child(_coin("silver")); c1.add_child(_coin("bronze"))
	coinbox.add_child(c1)
	var c2 := HBoxContainer.new(); c2.add_theme_constant_override("separation", 10)
	c2.add_child(_coin("soulstone"))
	coinbox.add_child(c2)
	row.add_child(coinbox)
	# [TOPBAR_BUFFS] buffs ativos numa LINHA PRÓPRIA abaixo do topbar — sempre visível (não some
	# no canto direito como antes) e QUEBRA pra próxima linha quando há vários. Populado em update_topbar.
	_buffs_box = HFlowContainer.new()
	_buffs_box.add_theme_constant_override("h_separation", 6)
	_buffs_box.add_theme_constant_override("v_separation", 4)
	_buffs_box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.add_child(_buffs_box)
	return pc

func _coin(key: String) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 5)
	h.tooltip_text = str(COIN_TIPS.get(key, ""))   # hover explica a moeda
	h.mouse_filter = Control.MOUSE_FILTER_STOP      # recebe o hover (o rect/label são IGNORE)
	h.add_child(Icons.rect(key, 20))
	var l := Label.new(); l.text = "0"; l.add_theme_font_size_override("font_size", 13)
	l.add_theme_color_override("font_color", UiKit.TEXT)
	l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(l)
	_coins[key] = l
	return h

# [TOPBAR] Bloco de stats de combate (preenche o vazio do topbar): ATK/DEF/HP efetivos + esquiva.
func _build_statbox() -> VBoxContainer:
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 2)
	box.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var r1 := HBoxContainer.new(); r1.add_theme_constant_override("separation", 14)
	r1.add_child(_stat_chip("stat_atk", "ATK", "atk", "Ataque efetivo de combate (base + gear + buffs + skills + postura + pet + taverna)"))
	r1.add_child(_stat_chip("slot_shield", "DEF", "def", "Defesa efetiva — mitiga o dano recebido"))
	box.add_child(r1)
	var r2 := HBoxContainer.new(); r2.add_theme_constant_override("separation", 14)
	r2.add_child(_stat_chip("hp", "HP", "hp", "Vida máxima efetiva de combate (com buffs/pet)"))
	r2.add_child(_stat_chip("attr_agility", "EVA", "eva", "Esquiva — chance de evitar o golpe (DEX/AGI + buffs)"))
	box.add_child(r2)
	# linha 3: ATRIBUTOS (só ícone + valor; nome no hover) [TOPBAR]
	var r3 := HBoxContainer.new(); r3.add_theme_constant_override("separation", 10)
	r3.add_child(_stat_chip("attr_strength", "", "str", "Força (STR) — dano corpo-a-corpo"))
	r3.add_child(_stat_chip("attr_dexterity", "", "dex", "Destreza (DEX) — acerto + dano de arco"))
	r3.add_child(_stat_chip("attr_constitution", "", "con", "Constituição (CON) — +8 HP por ponto"))
	r3.add_child(_stat_chip("attr_agility", "", "agi", "Agilidade (AGI) — golpe extra + esquiva"))
	r3.add_child(_stat_chip("attr_luck", "", "luk", "Sorte (LUK) — crítico"))
	box.add_child(r3)   # INT removido (Mago não implementado)
	return box

# Chip "[ícone] RÓTULO valor" — guarda o Label de valor em _stat_lbls[store_key] (atualizado em update_topbar).
func _stat_chip(icon_key: String, label: String, store_key: String, tip: String) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 4)
	h.tooltip_text = tip
	h.mouse_filter = Control.MOUSE_FILTER_STOP
	if Icons.tex(icon_key) != null:
		var ic := Icons.rect(icon_key, 18)
		h.add_child(ic)
	if label != "":   # atributos só com ícone (nome no hover) ficam mais compactos
		var lk := Label.new(); lk.text = label
		lk.add_theme_font_size_override("font_size", 11)
		lk.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		lk.mouse_filter = Control.MOUSE_FILTER_IGNORE
		h.add_child(lk)
	var lv := Label.new(); lv.text = "0"
	lv.add_theme_font_size_override("font_size", 13)
	lv.add_theme_color_override("font_color", UiKit.TEXT)
	lv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	lv.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(lv)
	_stat_lbls[store_key] = lv
	return h

func _mini_bar(fill: Color, w: int) -> ProgressBar:
	var pb := ProgressBar.new()
	pb.min_value = 0; pb.max_value = 100; pb.value = 0
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(w, 14)
	pb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var bgs := StyleBoxFlat.new()
	bgs.bg_color = Color(0.04, 0.035, 0.05)
	bgs.set_corner_radius_all(4)
	bgs.set_border_width_all(1); bgs.border_color = Color(0, 0, 0, 0.75)
	var fgs := StyleBoxFlat.new()
	fgs.bg_color = fill
	fgs.set_corner_radius_all(4)
	fgs.set_border_width_all(1); fgs.border_color = fill.lightened(0.28)   # brilho de topo
	pb.add_theme_stylebox_override("background", bgs)
	pb.add_theme_stylebox_override("fill", fgs)
	return pb

# Linha de vital alinhada: [ícone pixel | barra | valor à direita (largura fixa)]. Tooltip na linha toda.
func _vital_row(icon_key: String, bar: ProgressBar, value_lbl: Label, tip: String) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 7)
	h.tooltip_text = tip
	h.mouse_filter = Control.MOUSE_FILTER_STOP
	h.add_child(Icons.rect(icon_key, 18))   # ícone pixel (hp/stamina) — as duas linhas alinham pela esquerda
	h.add_child(bar)
	value_lbl.add_theme_font_size_override("font_size", 11)
	value_lbl.add_theme_color_override("font_color", UiKit.TEXT)
	value_lbl.custom_minimum_size = Vector2(58, 0)
	value_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	value_lbl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(value_lbl)
	return h

# Botão de cura do Templo: ícone do anjo curando o cavaleiro (heal_temple) → cura sem trocar de tela.
# Fallback: ícone antigo (cruz) → ❤. [FICHA_PERSONAGEM] a cruz agora é o botão de atribuir atributo.
func _heal_button() -> Control:
	# [HEAL] botão com a PALAVRA "CURA"/"HEAL" (PixelLab) conforme o idioma; fallback no ícone (cruz) → ❤.
	var worded := "heal_en" if Lang.current() == "en" else "heal_pt"
	var t := Icons.tex(worded)
	var is_word := t != null
	if t == null:
		t = Icons.tex("heal_temple")
	if t == null:
		t = Icons.tex("heal")
	if t != null:
		var b := TextureButton.new()
		b.texture_normal = t
		b.ignore_texture_size = true
		b.stretch_mode = TextureButton.STRETCH_KEEP_ASPECT_CENTERED
		b.custom_minimum_size = Vector2(74, 30) if is_word else Vector2(36, 36)   # botão da palavra é 2.5:1
		b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		b.modulate = Color(1, 1, 1, 0.9)
		b.tooltip_text = "Curar agora (Templo) — sem sair da tela"
		b.mouse_entered.connect(func() -> void: b.modulate = Color(1, 1, 1, 1))
		b.mouse_exited.connect(func() -> void: b.modulate = Color(1, 1, 1, 0.9))
		b.pressed.connect(_on_quick_heal)
		return b
	var fb := Button.new()
	fb.text = "❤"
	StoneStyle.apply(fb)
	fb.add_theme_font_size_override("font_size", 16)
	fb.add_theme_color_override("font_color", Color(0.86, 0.32, 0.30))
	fb.custom_minimum_size = Vector2(36, 32)
	fb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	fb.tooltip_text = "Curar agora (Templo) — sem sair da tela"
	fb.pressed.connect(_on_quick_heal)
	return fb

# Linha "rótulo  [barra]  valor" — guarda o Label de valor em meta "vlabel".
func _labeled_bar(label: String, pb: ProgressBar) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	var k := Label.new(); k.text = label; k.add_theme_font_size_override("font_size", 11)
	k.add_theme_color_override("font_color", UiKit.TEXT_DIM); k.custom_minimum_size = Vector2(58, 0)
	h.add_child(k)
	h.add_child(pb)
	var v := Label.new(); v.text = ""; v.add_theme_font_size_override("font_size", 11)
	v.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	h.add_child(v)
	h.set_meta("vlabel", v)
	return h

# Tooltip (hover) na LINHA inteira: o container recebe o hover e os filhos viram IGNORE.
func _tip_row(row: Control, tip: String) -> void:
	row.tooltip_text = tip
	row.mouse_filter = Control.MOUSE_FILTER_STOP
	for c in row.get_children():
		if c is Control:
			(c as Control).mouse_filter = Control.MOUSE_FILTER_IGNORE

# ── Nav lateral (árvore recolhível) ─────────────────────────────────────────────────
func _build_nav() -> Control:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(210, 0)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.07, 0.065, 0.08, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20); sb.border_width_right = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	var scroll := ScrollContainer.new()
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.follow_focus = true
	pc.add_child(scroll)
	var nav := VBoxContainer.new()
	nav.add_theme_constant_override("separation", 4)
	nav.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(nav)
	# LUTAR (ação destacada)
	var fight := _stone_btn("LUTAR", 44)
	Icons.set_icon(fight, "arena")
	fight.tooltip_text = "Lutar — entra na batalha 3D"   # [MENUBAR_HOVER]
	fight.add_theme_font_size_override("font_size", 18)
	fight.pressed.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	nav.add_child(fight)
	# Início (dashboard)
	var home := _stone_btn("🏠  Início", 38)
	home.tooltip_text = "Início — painel inicial com atalhos"   # [MENUBAR_HOVER]
	home.pressed.connect(_show_dashboard)
	_nav_buttons["__home__"] = home
	nav.add_child(home)
	# seções recolhíveis
	for section in SECTIONS:
		var items := VBoxContainer.new(); items.add_theme_constant_override("separation", 3)
		var head := Button.new()
		head.flat = true
		head.alignment = HORIZONTAL_ALIGNMENT_LEFT
		head.text = "▾  " + Lang.t(str(section[0])).to_upper()
		head.add_theme_font_size_override("font_size", 13)
		head.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		head.pressed.connect(func() -> void:
			items.visible = not items.visible
			head.text = ("▾  " if items.visible else "▸  ") + Lang.t(str(section[0])).to_upper()
		)
		nav.add_child(head)
		nav.add_child(items)
		for entry in section[1]:
			items.add_child(_nav_item(str(entry[0]), str(entry[1])))
	nav.add_child(_spacer(6))
	nav.add_child(_nav_item("Settings", "Configurações"))   # ⚙ idioma PT/EN + opções [I18N]
	nav.add_child(_spacer(10))
	var out := _stone_btn("Sair", 36)
	out.tooltip_text = "Sair — desconecta da conta"   # [MENUBAR_HOVER]
	out.pressed.connect(func() -> void: logout.emit())
	nav.add_child(out)
	return pc

func _nav_item(scr: String, label: String) -> Button:
	var b := Button.new()
	b.flat = true
	b.alignment = HORIZONTAL_ALIGNMENT_LEFT
	b.custom_minimum_size = Vector2(0, 34)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	Icons.label_button(b, scr.to_lower(), label)
	b.tooltip_text = str(NAV_TIPS.get(scr, label))   # [MENUBAR_HOVER] hover explica a tela
	b.add_theme_font_size_override("font_size", 14)
	b.pressed.connect(func() -> void: _open(scr))
	_nav_buttons[scr] = b
	return b

func _set_active(nm: String) -> void:
	active_name = nm
	for k in _nav_buttons:
		var b: Button = _nav_buttons[k]
		if k == nm:
			b.add_theme_color_override("font_color", UiKit.GOLD)
			b.modulate = Color(1, 1, 1, 1)
		else:
			b.remove_theme_color_override("font_color")
			b.modulate = Color(1, 1, 1, 0.82)

# ── Navegação / hospedagem das telas ────────────────────────────────────────────────
func _open(scr: String) -> void:
	if scr == "":
		return
	var mc := _mutation_count()
	# já carregada → mostra na hora (0 request); revalida só se algo mudou no servidor desde a última visita
	if _cache.has(scr) and is_instance_valid(_cache[scr]):
		var cached: Control = _cache[scr]
		_show_only(cached)
		active_screen = cached
		_set_active(scr)
		if int(_cache_ver.get(scr, -1)) != mc and cached.has_method("_refresh"):
			_cache_ver[scr] = mc
			await cached._refresh()
		return
	# 1ª vez: instancia, cacheia (embedded). O _ready da tela já faz o _refresh inicial.
	var scene = load("res://ui/%s.tscn" % scr)
	if scene == null:
		push_warning("tela não encontrada: %s" % scr)
		return
	var node = scene.instantiate()
	node.set_meta("embedded", true)   # UiKit.scaffold roda em modo embutido (sem fundo/←/carteira)
	_cache[scr] = node
	_cache_ver[scr] = mc
	content_host.add_child(node)
	_wire_screen(node)
	_show_only(node)
	active_screen = node
	_set_active(scr)

func _mutation_count() -> int:
	var api = get_node_or_null("/root/Api")
	return int(api.mutation_count) if api != null else 0

# [INCURSAO] Abre o Mundo já expandido no reino dado (vitória da Incursão volta pro território de origem).
func _open_world_at(kingdom: String) -> void:
	var w = _cache.get("World")
	if w != null and is_instance_valid(w) and w.has_method("request_open_kingdom"):
		w.request_open_kingdom(kingdom)
	_open("World")

# Mostra só `node` no content_host; os escondidos são CONGELADOS (process disabled) → 0 polling/CPU.
func _show_only(node: Control) -> void:
	if _dash != null and is_instance_valid(_dash):
		_dash.visible = (_dash == node)
		_dash.process_mode = Node.PROCESS_MODE_INHERIT if _dash == node else Node.PROCESS_MODE_DISABLED
	for k in _cache:
		var n = _cache[k]
		if is_instance_valid(n):
			n.visible = (n == node)
			n.process_mode = Node.PROCESS_MODE_INHERIT if n == node else Node.PROCESS_MODE_DISABLED

func _wire_screen(c: Control) -> void:
	if c.has_signal("go_back"):
		c.go_back.connect(_show_dashboard)
	if c.has_signal("open_screen"):
		c.open_screen.connect(_open)
	if c.has_signal("open_world_at"):
		c.open_world_at.connect(_open_world_at)   # [INCURSAO] vitória → abre o Mundo já no reino de onde saiu
	if c.has_signal("go_inventory"):
		c.go_inventory.connect(func() -> void: _open("Character"))   # [FICHA_PERSONAGEM] inventário vive na ficha
	if c.has_signal("go_battle"):
		c.go_battle.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	if c.has_signal("request_battle"):
		c.request_battle.connect(func(data) -> void: request_battle.emit(data))
	if c.has_signal("logout"):
		c.logout.connect(func() -> void: logout.emit())

# Chamado pelo App quando o replay de batalha termina → atualiza topbar/busto + a tela ativa.
func _on_battle_over() -> void:
	await _initial_load()   # batalha pode ter dado XP/loot/HP → topbar + busto + índice frescos
	if active_screen != null and is_instance_valid(active_screen):
		if active_screen.has_method("_on_battle_over"):
			active_screen._on_battle_over()
		elif active_screen.has_method("_refresh"):
			await active_screen._refresh()

# ── Dashboard / home ────────────────────────────────────────────────────────────────
func _show_dashboard() -> void:
	if _dash == null or not is_instance_valid(_dash):
		_dash = _build_dashboard()
		content_host.add_child(_dash)
	_show_only(_dash)
	active_screen = null
	_set_active("__home__")

func _build_dashboard() -> Control:
	var scroll := ScrollContainer.new()
	scroll.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var pad := MarginContainer.new()
	pad.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for s in ["left", "right", "top", "bottom"]:
		pad.add_theme_constant_override("margin_" + s, 20)
	scroll.add_child(pad)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 12)
	box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pad.add_child(box)
	var hi := Label.new()
	hi.text = Lang.t("Bem-vindo, %s") % str(warrior.get("name", "guerreiro"))
	hi.add_theme_font_size_override("font_size", 26)
	hi.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(hi)
	box.add_child(UiKit.dim("Escolha uma atividade no menu à esquerda, ou use os atalhos abaixo."))
	# LUTAR grande
	var fight := UiKit.action_big("⚔  Lutar", func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	fight.custom_minimum_size = Vector2(0, 52)
	box.add_child(fight)
	# atalhos rápidos
	box.add_child(UiKit.section("Atalhos"))
	var grid := GridContainer.new(); grid.columns = 3
	grid.add_theme_constant_override("h_separation", 10); grid.add_theme_constant_override("v_separation", 10)
	grid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for sc in [["World", "Mundo"], ["Character", "Personagem"], ["Shop", "Loja"], ["Daily", "Diário"], ["Forge", "Forja"], ["Tower", "Torre"]]:
		var b := _stone_btn(str(sc[1]), 44)
		Icons.set_icon(b, str(sc[0]).to_lower())
		var target: String = str(sc[0])
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.pressed.connect(func() -> void: _open(target))
		grid.add_child(b)
	box.add_child(grid)
	return scroll

# ── Atualização do warrior / topbar ─────────────────────────────────────────────────
# Carga inicial (1x no boot / após batalha): warrior (topbar) + inventário (índice + busto).
func _initial_load() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.get_warrior()
	if r.get("ok") and r.get("json") is Dictionary:
		warrior = r["json"]
		update_topbar(warrior)
	var inv = await api.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		UiKit.set_equipped(inv["json"])
		if _bust != null and is_instance_valid(_bust):
			_bust.apply(inv["json"], str(warrior.get("warriorClassId", "")), str(warrior.get("gender", UiKit.current_gender)))

# Equip mudou (Inventory avisa) → reindexa comparação + re-veste o busto. Usa o inventário que o
# Inventory já tem (SEM fetch); só busca se vier vazio. [PLANO_UI_SHELL_GODOT]
func _on_equip_changed(inv_arr := []) -> void:
	if inv_arr is Array and not inv_arr.is_empty():
		UiKit.set_equipped(inv_arr)
		if _bust != null and is_instance_valid(_bust):
			_bust.apply(inv_arr, str(warrior.get("warriorClassId", "")), str(warrior.get("gender", UiKit.current_gender)))
		return
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var inv = await api.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		UiKit.set_equipped(inv["json"])
		if _bust != null and is_instance_valid(_bust):
			_bust.apply(inv["json"], str(warrior.get("warriorClassId", "")), str(warrior.get("gender", UiKit.current_gender)))

# Atualiza só o topbar a partir de um WarriorResponse (chamado tb pelas telas via UiKit.set_wallet).
func update_topbar(w: Dictionary) -> void:
	if w.is_empty() or _name_lbl == null:
		return
	UiKit.current_class = str(w.get("warriorClassId", UiKit.current_class))   # tema das roupas no ícone de item [OUTFITS_CLASSE]
	UiKit.current_gender = str(w.get("gender", UiKit.current_gender)).to_lower()   # base/peças Male/Female [OUTFITS_FEMALE]
	_name_lbl.text = str(w.get("name", "?"))
	var t := str(w.get("title", ""))
	_title_lbl.text = ("⟨%s⟩" % t) if t != "" else ""
	_sub_lbl.text = Lang.t("%s · Nível %d") % [Lang.t(str(w.get("warriorClass", "Recruta"))), int(w.get("level", 1))]
	var xp := int(w.get("experience", 0))
	var need := int(w.get("expNeeded", 0))   # LIMIAR do nível (100×nv^1.8), não o restante
	_xp_bar.max_value = maxi(1, need)
	_xp_bar.value = clampi(xp, 0, need)
	if _xp_lbl != null:
		_xp_lbl.text = Lang.t("Faltam %d de exp pro próximo nível") % maxi(0, need - xp)
	_xp_bar.tooltip_text = Lang.t("Experiência: %d / %d (faltam %d pro próximo nível)") % [xp, need, maxi(0, need - xp)]
	var hp := int(w.get("hpPercent", w.get("currentHp", 100)))
	_hp_bar.value = clampi(hp, 0, 100)
	if _hp_lbl != null:
		var maxhp := int(w.get("totalHealth", 0))   # HP máximo (base+bônus); atual = max × %/100
		if maxhp > 0:
			_hp_lbl.text = "%d/%d" % [int(round(maxhp * hp / 100.0)), maxhp]
		else:
			_hp_lbl.text = "%d%%" % hp
	var stam := int(w.get("stamina", 0))
	_stam_bar.value = clampi(stam, 0, 100)
	if _stam_lbl != null:
		_stam_lbl.text = "%d%%" % stam
	for key in _coins:
		var field: String = "soulStones" if key == "soulstone" else str(key)
		_coins[key].text = str(int(w.get(field, 0)))
	# [TOPBAR] stats de combate (efetivos)
	if _stat_lbls.has("atk"): _stat_lbls["atk"].text = str(int(w.get("combatAttack", w.get("totalAttack", 0))))
	if _stat_lbls.has("def"): _stat_lbls["def"].text = str(int(w.get("combatDefense", w.get("totalDefense", 0))))
	if _stat_lbls.has("hp"): _stat_lbls["hp"].text = str(int(w.get("combatHealth", w.get("totalHealth", 0))))
	if _stat_lbls.has("eva"): _stat_lbls["eva"].text = "%d%%" % int(w.get("evasionChance", 0))
	# atributos (valores crus alocados)
	for pair in [["str", "strength"], ["dex", "dexterity"], ["con", "constitution"], ["agi", "agility"], ["luk", "luck"]]:
		if _stat_lbls.has(pair[0]): _stat_lbls[pair[0]].text = str(int(w.get(pair[1], 0)))
	_refresh_buffs(w)

# Badges dos buffs ATIVOS na topbar (com tooltip de nome + tempo). Reconstrói a cada update.
func _refresh_buffs(w: Dictionary) -> void:
	if _buffs_box == null:
		return
	for c in _buffs_box.get_children():
		c.queue_free()
	var ab := str(w.get("activeBuff", ""))
	if ab != "":
		_buffs_box.add_child(_buff_badge(ab, Lang.t("Bênção do Templo: %s — %s") % [ab, _fmt_left(int(w.get("buffSecondsLeft", 0)))]))
	var ab2 := str(w.get("activeBuff2", ""))
	if ab2 != "":
		_buffs_box.add_child(_buff_badge(ab2, Lang.t("Bênção VIP (2º slot): %s — %s") % [ab2, _fmt_left(int(w.get("buff2SecondsLeft", 0)))]))
	var meal := str(w.get("mealBuff", ""))
	if meal != "":
		_buffs_box.add_child(_buff_badge(meal, Lang.t("Bem Alimentado: %s — %s") % [meal, _fmt_left(int(w.get("mealBuffSecondsLeft", 0)))]))
	var we := str(w.get("weaponElement", ""))
	if we != "":
		_buffs_box.add_child(_buff_badge_icon("elem_" + we.to_lower(), "⚔", Lang.t("Arma encantada (%s): ±25%% por elemento — %s") % [we, _fmt_left(int(w.get("weaponElementSecondsLeft", 0)))]))
	var ae := str(w.get("armorElement", ""))
	if ae != "":
		_buffs_box.add_child(_buff_badge_icon("elem_" + ae.to_lower(), "🛡", Lang.t("Armadura encantada (%s): ±25%% por elemento — %s") % [ae, _fmt_left(int(w.get("armorElementSecondsLeft", 0)))]))
	if bool(w.get("newbieBuffActive", false)):
		_buffs_box.add_child(_buff_badge("🐣", Lang.t("Buff de Novato: estamina e HP regeneram 4× mais rápido — %dh restantes") % int(w.get("newbieBuffHoursLeft", 0))))
	var tav := float(w.get("tavernBuffPct", 0.0))
	if tav > 0.0:
		_buffs_box.add_child(_buff_badge_icon("tavern", "+%.2f%%" % tav, Lang.t("Buff da Taverna: +%.2f%% em TODOS os stats — %s") % [tav, _fmt_left(int(w.get("tavernBuffSecondsLeft", 0)))]))
	# [TOPBAR_BUFFS] prefixo "Buffs:" só quando há algum; esconde a linha inteira se não há nenhum
	if _buffs_box.get_child_count() > 0:
		var lbl := Label.new()
		lbl.text = Lang.t("Buffs:")
		lbl.add_theme_font_size_override("font_size", 12)
		lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		_buffs_box.add_child(lbl)
		_buffs_box.move_child(lbl, 0)
		_buffs_box.visible = true
	else:
		_buffs_box.visible = false

func _buff_badge(text: String, tip: String) -> Control:
	var pc := PanelContainer.new()
	pc.tooltip_text = tip
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.12, 0.11, 0.08, 0.95)
	sb.border_color = UiKit.GOLD_SOFT; sb.set_border_width_all(1)
	sb.set_corner_radius_all(3); sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", UiKit.GOLD)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	pc.add_child(l)
	return pc

# [TOPBAR] Badge de buff com ÍCONE pixel-art + texto. Cai no texto-só se o ícone não existir (fallback limpo).
func _buff_badge_icon(key: String, text: String, tip: String) -> Control:
	var pc := PanelContainer.new()
	pc.tooltip_text = tip
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.12, 0.11, 0.08, 0.95)
	sb.border_color = UiKit.GOLD_SOFT; sb.set_border_width_all(1)
	sb.set_corner_radius_all(3); sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 3)
	h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	if Icons.tex(key) != null:
		h.add_child(Icons.rect(key, 16))
	if text != "":
		var l := Label.new(); l.text = text
		l.add_theme_font_size_override("font_size", 12)
		l.add_theme_color_override("font_color", UiKit.GOLD)
		l.mouse_filter = Control.MOUSE_FILTER_IGNORE
		h.add_child(l)
	pc.add_child(h)
	return pc

func _elem_icon(e: String) -> String:
	return str(ELEM_ICONS.get(e, "✨"))

func _fmt_left(secs: int) -> String:
	if secs <= 0:
		return "expirando"
	var h := secs / 3600
	var m := (secs % 3600) / 60
	if h > 0:
		return "%dh %dmin" % [h, m]
	if m > 0:
		return "%d min" % m
	return "%d s" % secs

# ── helpers ──────────────────────────────────────────────────────────────────────────
# [HEAL] Cura rápida da topbar: chama o Templo e atualiza HP/moedas sem trocar de tela.
func _on_quick_heal() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	await api.temple_heal()
	var r = await api.get_warrior()
	if r.get("ok") and r.get("json") is Dictionary:
		warrior = r["json"]
		update_topbar(warrior)

func _stone_btn(text: String, h: int) -> Button:
	var b := Button.new()
	b.text = text
	StoneStyle.apply(b)
	b.custom_minimum_size = Vector2(0, h)
	return b

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
