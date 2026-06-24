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

# [ONBOARDING] Briefing de chegada (Coroa de Arka) — texto curado em docs/PLANO_QUESTS_LORE.md.
# Literal PT = chave; a tradução EN está no dict do Lang.gd. Aparece 1x (só se !onboardingSeen).
const ONBOARD_BRIEFING := "Coroa de Arka era a joia do novo mundo — ouro, terras, promessas. Aí as feras vieram. Pedimos um exército à Velha Coroa, do outro lado do mar. Mandaram você.\n\nPrometeram poder ao Rei — e ele subiu a torre atrás da promessa, levado pela mão de quem o enganava. Nunca desceu. O que governa lá em cima agora não é mais o Rei.\n\nArranque o que puder dos mortos, suba atrás dele, e reze pra ele ainda ser o Rei quando você chegar."

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
	"Leaderboards": "Classificação — ranking de jogadores e guildas + perfil e social",
	"Mail": "Correio — mensagens, itens e recompensas",
	"Daily": "Diário — recompensa de login (ciclo de 7 dias)",
	"StarterQuests": "Diário de Missões — pra pegar, em progresso e completadas",
}

# [MENUBAR_REORG2] A nav agora é uma LISTA FLAT (sem seções com título) montada direto em _build_nav,
# na ordem do loop de jogo. Conquistas virou sub-aba da Ficha; Correio/Diário/Config foram pro canto
# superior direito da topbar.

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
# [TOPBAR_REORG] cluster do canto superior direito: Correio · Diário · Config (+ badges)
var _mail_btn: Button            # Correio — ícone fixo (mail.png), sem frame-anim; não-lido vira exclamação
var _mail_badge: Control         # [MAIL_BADGE] exclamação vermelha no canto quando há não-lido
var _friends_btn: Button         # [LEADERBOARDS] Amigos — gerenciar amigos/pedidos/convites
var _friends_badge: Control      # exclamação quando há pedido de amizade / convite de guilda pendente
var _daily_btn: Button           # ganha tom dourado quando dá pra resgatar
var _daily_badge: Control        # [DAILY] exclamação quando a recompensa diária está disponível
var _heal_btn: Control           # botão de cura — só aparece com HP < 100
var _buffs_box: GridContainer     # badges dos buffs ativos — GRID compacta ao lado da cura [TOPBAR_BUFFS]
var _nav_buttons: Dictionary = {}   # nome da tela -> Button (destaque do ativo)
var _cache := {}        # nome da tela → node (MANTIDA em memória; alterna visibilidade, não recria)
var _cache_ver := {}    # nome → mutation_count na última atualização (revisita só refaz request se algo mudou)
var _dash: Control = null   # dashboard/home (também cacheado)
# [ONBOARDING v2] Deveres do Recruta: status cacheado + badges (nav dos NPCs + topbar) + oferta 1x/sessão.
var _quest_btn: Button
var _quest_badge: Control
var _starter_status: Array = []
var _nav_badges := {}     # screen -> badge Control no item de nav do NPC
var _offered := {}        # screen -> já ofereci a quest nesta sessão (não repopa a cada visita)

func _ready() -> void:
	current = self
	UiKit.topbar_sink = update_topbar          # telas embedded mandam o warrior pro topbar via set_wallet
	UiKit.equip_changed_sink = _on_equip_changed   # Inventory avisa quando equipa → re-veste o busto (sem fetch à toa)
	UiKit.starter_changed_sink = _refresh_starter  # [ONBOARDING v2] diário avisa → re-render dos badges de quest
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
	await _maybe_onboarding()   # [ONBOARDING] briefing de chegada no 1º login (só se !onboardingSeen)
	await _refresh_starter()    # [ONBOARDING v2] badges de quest (nav dos NPCs + topbar)

func _exit_tree() -> void:
	if current == self:
		current = null
	if UiKit.topbar_sink.is_valid() and UiKit.topbar_sink.get_object() == self:
		UiKit.topbar_sink = Callable()
	if UiKit.equip_changed_sink.is_valid() and UiKit.equip_changed_sink.get_object() == self:
		UiKit.equip_changed_sink = Callable()
	if UiKit.starter_changed_sink.is_valid() and UiKit.starter_changed_sink.get_object() == self:
		UiKit.starter_changed_sink = Callable()

# ── [ONBOARDING] Briefing de chegada (Camada A) ─────────────────────────────────────
# Só no 1º login (backend: !onboardingSeen). Dim + card dourado + briefing da Coroa de Arka +
# CTA que marca visto e leva o recruta ao Mundo (1ª ação clara). Doc: docs/PLANO_ONBOARDING.md
func _maybe_onboarding() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.onboarding_status()
	if not (r.get("ok") and r.get("json") is Dictionary):
		return
	if bool(r["json"].get("seen", true)):
		return   # já viu (ou erro de leitura → não incomoda)
	_show_welcome(api)

func _show_welcome(api) -> void:
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.78)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	panel.custom_minimum_size = Vector2(600, 0)
	vb.add_theme_constant_override("separation", 12)
	center.add_child(panel)
	# [ONBOARDING] mapa de Coroa de Arka no topo — aterriza o nome + preview de onde vai aventurar
	var map_tex := load("res://assets/ui/map/world_map.png")
	if map_tex != null:
		var banner := TextureRect.new()
		banner.texture = map_tex
		banner.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		banner.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
		banner.custom_minimum_size = Vector2(560, 190)
		banner.clip_contents = true
		vb.add_child(banner)
	var ttl := Label.new()
	ttl.text = "Coroa de Arka"
	ttl.add_theme_font_size_override("font_size", 24)
	ttl.add_theme_color_override("font_color", UiKit.GOLD)
	ttl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(ttl)
	var body := Label.new()
	body.text = Lang.t(ONBOARD_BRIEFING)
	body.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.custom_minimum_size = Vector2(560, 0)
	body.add_theme_font_size_override("font_size", 15)
	body.add_theme_constant_override("line_spacing", 6)   # respiro entre linhas (mais leve de ler)
	body.add_theme_color_override("font_color", UiKit.TEXT)
	vb.add_child(body)
	var cta := UiKit.action_big(Lang.t("Conquistar meu lugar"), func() -> void:
		await api.onboarding_seen()
		if is_instance_valid(overlay):
			overlay.queue_free()
		_open("Character"))
	cta.custom_minimum_size = Vector2(500, 48)
	vb.add_child(cta)

# ── [ONBOARDING v2] Deveres do Recruta: badges (nav + topbar) + oferta no NPC ────────
func _refresh_starter() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.starter_quests()
	if not (r.get("ok") and r.get("json") is Dictionary):
		return
	var was_done := {}   # [ONBOARDING] estado anterior (id → done) p/ detectar transição → toast direcional
	for q in _starter_status:
		if q is Dictionary:
			was_done[str(q.get("id", ""))] = (str(q.get("state", "")) == "done")
	_starter_status = r["json"].get("quests", []) if r["json"].get("quests") is Array else []
	for q in _starter_status:
		if q is Dictionary and str(q.get("state", "")) == "done" \
				and was_done.has(str(q.get("id", ""))) and not bool(was_done[str(q.get("id", ""))]):
			_starter_done_toast(str(q.get("id", "")))   # acabou de concluir → próximo passo
	_apply_starter_badges()

# [ONBOARDING] ao equipar, tenta concluir o dever de equipar (backend valida arma+armadura). Silencioso se faltar.
func _try_equip_quest() -> void:
	var d := _starter_by_id("equip")
	if d.is_empty() or str(d.get("state", "")) != "accepted":
		return
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.starter_quest_turn_in("equip")
	if r.get("ok"):
		await _refresh_starter()   # detecta equip→done → toast direcional (vá ao Templo)

func _starter_by_id(id: String) -> Dictionary:
	for q in _starter_status:
		if q is Dictionary and str(q.get("id", "")) == id:
			return q
	return {}

# [ONBOARDING] toast "feito → próximo passo" ao concluir cada dever (o badge "!" no NPC seguinte reforça).
func _starter_done_toast(id: String) -> void:
	var msg: String = {
		"equip": "Armado. Agora procure o Padre Anselmo no Templo — você chegou ferido.",
		"heal":  "Curado. O Capitão Garrick espera no Salão de Treino.",
		"quest": "Você provou seu valor. A guarnição é sua, recruta.",
	}.get(id, "")
	if msg != "":
		UiKit.toast(self, Lang.t(msg), "", 1)

func _apply_starter_badges() -> void:
	var any_open := false
	for q in _starter_status:
		if q is Dictionary:
			var st := str(q.get("state", ""))
			_set_nav_badge(str(q.get("npcScreen", "")), st == "available")
			if st == "available":   # "!" no topbar = tem quest NOVA pra pegar (some ao aceitar tudo)
				any_open = true
	if _quest_badge != null and is_instance_valid(_quest_badge):
		_quest_badge.visible = any_open

func _set_nav_badge(scr: String, on: bool) -> void:
	if scr == "":
		return
	var btn = _nav_buttons.get(scr)
	if btn == null or not is_instance_valid(btn):
		return
	var badge = _nav_badges.get(scr)
	if badge == null or not is_instance_valid(badge):
		badge = _make_quest_badge(true)   # nav: centralizado verticalmente com o texto
		btn.add_child(badge)
		_nav_badges[scr] = badge
	badge.visible = on

func _starter_available_for(scr: String) -> Dictionary:
	for q in _starter_status:
		if q is Dictionary and str(q.get("npcScreen", "")) == scr and str(q.get("state", "")) == "available":
			return q
	return {}

# [ONBOARDING v3] Botão de quest p/ a tela do NPC, por ESTADO: available→"Pegar missão" (diálogo de aceitar);
# accepted→ação de concluir conforme o tipo (Concluir=equipar / Curar / Entregar). locked/done/sem-quest → null.
func quest_button_for(scr: String) -> Button:
	var q := _starter_for_screen(scr)
	if q.is_empty():
		return null
	var st := str(q.get("state", ""))
	var which := str(q.get("id", ""))
	if st == "available":
		var b := UiKit.action(Lang.t("Pegar missão"), func() -> void: _show_quest_offer(q))
		b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		return b
	if st == "accepted":
		var comp := str(q.get("comp", ""))
		if comp == "QUEST":   # completa por EVENTO (fazer 1 missão) → só guia pro Mundo
			var nav := UiKit.action(Lang.t("Ir ao Mundo"), func() -> void: _open("World"))
			nav.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			return nav
		var b := UiKit.action(_accepted_label(comp), func() -> void: await _quest_turn_in(which))
		b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		return b
	return null

func _accepted_label(comp: String) -> String:
	return Lang.t("Curar") if comp == "HEAL" else Lang.t("Concluir")

# duty (available OU accepted) cuja tela do NPC é `scr`
func _starter_for_screen(scr: String) -> Dictionary:
	for q in _starter_status:
		if q is Dictionary and str(q.get("npcScreen", "")) == scr:
			var st := str(q.get("state", ""))
			if st == "available" or st == "accepted":
				return q
	return {}

func _quest_turn_in(which: String) -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.starter_quest_turn_in(which)
	if r.get("ok"):
		await _after_quest_change()
	else:
		UiKit.notify(self, UiKit.err_text(r), true)

# após aceitar/concluir: atualiza badges + re-renderiza a tela atual (o botão da quest some/muda)
func _after_quest_change() -> void:
	await _refresh_starter()
	if active_screen != null and is_instance_valid(active_screen) and active_screen.has_method("_refresh"):
		await active_screen._refresh()

func _maybe_offer(scr: String) -> void:
	if bool(_offered.get(scr, false)):
		return   # já ofereci nesta sessão (o badge no nav + o diário seguem como caminho)
	var q := _starter_available_for(scr)
	if q.is_empty():
		return
	_offered[scr] = true
	_show_quest_offer(q)

func _show_quest_offer(q: Dictionary) -> void:
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	overlay.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed and is_instance_valid(overlay):
			overlay.queue_free())
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	panel.custom_minimum_size = Vector2(460, 0)
	vb.add_theme_constant_override("separation", 12)
	center.add_child(panel)
	# [ONBOARDING] retrato do NPC que pede a quest (veterano = Guarda do Salão / padre = Templo); equip não tem NPC
	var portrait_key: String = {"equip": "veteran", "quest": "veteran", "heal": "priest"}.get(str(q.get("id", "")), "")
	if portrait_key != "" and Icons.tex(portrait_key) != null:
		var pr := Icons.rect(portrait_key, 96)
		pr.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		vb.add_child(pr)
	var npc := Label.new()
	npc.text = str(q.get("npc", "?"))
	npc.add_theme_font_size_override("font_size", 20)
	npc.add_theme_color_override("font_color", UiKit.GOLD)
	npc.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(npc)
	var fl := Label.new()
	fl.text = str(q.get("flavor", ""))
	fl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	fl.custom_minimum_size = Vector2(420, 0)
	fl.add_theme_font_size_override("font_size", 14)
	fl.add_theme_color_override("font_color", UiKit.TEXT)
	vb.add_child(fl)
	var which := str(q.get("id", ""))
	var accept := UiKit.action_big(Lang.t("Aceitar missão"), func() -> void:
		var api = get_node_or_null("/root/Api")
		if api != null:
			await api.starter_quest_accept(which)
		if is_instance_valid(overlay):
			overlay.queue_free()
		await _after_quest_change())
	accept.custom_minimum_size = Vector2(420, 46)
	vb.add_child(accept)

# ── TopBar ─────────────────────────────────────────────────────────────────────────
func _build_topbar() -> Control:
	var pc := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.075, 0.09, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20)
	sb.border_width_bottom = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	var col := VBoxContainer.new()   # coluna do topbar (linha principal; buffs agora moram NA linha, ao lado da cura)
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
	row.add_child(_divider())
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
	_xp_bar.mouse_filter = Control.MOUSE_FILTER_STOP   # [XP_INLINE] recebe o hover → tooltip com o "faltam X"
	idv.add_child(_xp_bar)
	# [XP_INLINE] valor X/Y DENTRO da barra (a label "Faltam..." de baixo saiu → coluna de identidade
	# mais baixa, encosta na altura do busto). O "quanto falta" agora é o tooltip da própria barra.
	_xp_lbl = Label.new()
	_xp_lbl.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_xp_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_xp_lbl.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	_xp_lbl.add_theme_font_size_override("font_size", 10)
	_xp_lbl.add_theme_color_override("font_color", Color(1, 1, 1, 0.94))
	_xp_lbl.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.85))
	_xp_lbl.add_theme_constant_override("outline_size", 2)
	_xp_lbl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_xp_bar.add_child(_xp_lbl)
	# [TOPBAR_REORG] HP + estamina logo após a identidade (ícone | barra | valor) + cura ao lado [HEAL]
	row.add_child(_divider())
	var vit := VBoxContainer.new(); vit.add_theme_constant_override("separation", 5)
	vit.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_hp_bar = _mini_bar(Color(0.80, 0.26, 0.24), 150)
	_hp_lbl = Label.new()
	vit.add_child(_vital_row("hp", _hp_bar, _hp_lbl, "Vida (HP) — atual/máximo; cure no botão ao lado ou no Templo"))
	_stam_bar = _mini_bar(Color(0.40, 0.68, 0.42), 150)
	_stam_lbl = Label.new()
	vit.add_child(_vital_row("stamina", _stam_bar, _stam_lbl, "Estamina — gasta nas ações; enche 100% em 1h (15min com buff de novato)"))
	row.add_child(vit)
	# [TOPBAR_BUFFS] buffs ao lado das barras de vital, em GRID compacta (antes era uma linha cheia abaixo do personagem)
	_buffs_box = GridContainer.new()
	_buffs_box.columns = 2   # grid compacta (2 col) ao lado da cura, em vez de uma linha cheia
	_buffs_box.add_theme_constant_override("h_separation", 5)
	_buffs_box.add_theme_constant_override("v_separation", 4)
	_buffs_box.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(_buffs_box)
	# espaçador empurra moedas + cluster pra direita
	var spacer := Control.new(); spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)
	# [HEAL] botão de cura à ESQUERDA das moedas (movido da posição antiga ao lado das barras de vital)
	row.add_child(_heal_button())
	# [TOPBAR_REORG] moedas numa LINHA só: ouro · prata · bronze · soulstone (VIP)
	var coinbox := HBoxContainer.new(); coinbox.add_theme_constant_override("separation", 10)
	coinbox.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	coinbox.add_child(_coin("gold")); coinbox.add_child(_coin("silver"))
	coinbox.add_child(_coin("bronze")); coinbox.add_child(_coin("soulstone"))
	row.add_child(coinbox)
	# [TOPBAR_REORG] cluster do canto superior direito: Correio · Diário · Config
	row.add_child(_divider())
	row.add_child(_topbar_actions())
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

# [TOPBAR_REORG] Divisória fina vertical entre grupos da topbar.
func _divider() -> Control:
	var d := ColorRect.new()
	d.color = Color(0.40, 0.32, 0.20, 0.5)
	d.custom_minimum_size = Vector2(1, 40)
	d.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	return d

# [TOPBAR_REORG] Cluster do canto superior direito: Correio · Diário · Config (ícones 36×36 flat).
func _topbar_actions() -> Control:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	h.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	# [MAIL_BADGE] carta com frame-anim PADRÃO: anima no HOVER (igual aos outros ícones do topo); o
	# não-lido vira uma exclamação vermelha no canto, por cima do ícone.
	# [ONBOARDING v2] Diário de Missões (Deveres do Recruta) — ao lado do Correio.
	_quest_btn = _topbar_icon_btn("quest_log", "Diário de Missões", func() -> void: _open("StarterQuests"))
	_quest_badge = _make_quest_badge()
	_quest_btn.add_child(_quest_badge)
	h.add_child(_quest_btn)
	_mail_btn = _topbar_icon_btn("mail", "Correio — mensagens e recompensas", func() -> void: _open("Mail"))
	_mail_badge = _make_alert_badge()
	_mail_btn.add_child(_mail_badge)
	h.add_child(_mail_btn)
	# [LEADERBOARDS] Amigos — gerenciar amigos + pedidos + convites de guilda (badge quando há pendência)
	_friends_btn = _topbar_icon_btn("members", "Amigos — lista, pedidos e convites de guilda", func() -> void: _open("Friends"))
	_friends_badge = _make_alert_badge()
	_friends_btn.add_child(_friends_badge)
	h.add_child(_friends_btn)
	_daily_btn = _topbar_icon_btn("daily", "Recompensa diária", func() -> void: _open("Daily"))
	_daily_badge = _make_alert_badge()   # [DAILY] exclamação quando dá pra resgatar
	_daily_btn.add_child(_daily_badge)
	h.add_child(_daily_btn)
	h.add_child(_topbar_icon_btn("settings", "Configurações", func() -> void: _open("Settings")))
	return h

# Botão de ícone flat 36×36 (sem fundo de botão). animate=true → ícone + hover-pop + FRAME-ANIM
# (engrenagem girando / presente abrindo); animate=false → ícone fixo + só hover-pop (carta, p/ não
# trocar de cor no hover).
func _topbar_icon_btn(key: String, tip: String, cb: Callable, animate := true) -> Button:
	var b := Button.new()
	b.flat = true
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(36, 36)
	b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	b.tooltip_text = tip
	var empty := StyleBoxEmpty.new()
	for st in ["normal", "hover", "pressed", "focus"]:
		b.add_theme_stylebox_override(st, empty)
	var ok := Icons.set_icon(b, key) if animate else _icon_static_hover(b, key)
	if ok:
		b.add_theme_constant_override("icon_max_width", 30)
	else:
		b.text = key.substr(0, 1).to_upper()   # fallback até o PNG importar
	b.pressed.connect(cb)
	return b

# Ícone estático no botão + só o hover-pop (cresce/clareia), SEM o frame-anim que troca os quadros.
func _icon_static_hover(b: Button, key: String) -> bool:
	var t := Icons.tex(key)
	if t == null:
		return false
	b.icon = t
	b.expand_icon = true
	Icons.add_hover(b, Icons.HOVER_GROW_BTN, Icons.HOVER_BRIGHT_BTN)
	return true

# [MAIL_BADGE] Selo de alerta: exclamação vermelha no canto superior direito do ícone. Começa oculto.
# [ONBOARDING v2] Badge AMARELO de quest (mesma exclamação `quest_alert` do mapa do mundo).
# centered=true → alinhado VERTICALMENTE com o texto (item de nav lateral); false → canto sup. direito (topbar).
func _make_quest_badge(centered := false) -> Control:
	var t := Icons.tex("quest_alert")
	if t == null:
		return _make_alert_badge()   # fallback se o PNG não importou
	var tr := TextureRect.new()
	tr.texture = t
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tr.custom_minimum_size = Vector2(16, 16)
	tr.anchor_left = 1.0; tr.anchor_right = 1.0
	if centered:   # nav lateral: centro vertical (alinha com o texto do item)
		tr.anchor_top = 0.5; tr.anchor_bottom = 0.5
		tr.offset_left = -24; tr.offset_top = -8
		tr.offset_right = -8; tr.offset_bottom = 8
	else:          # topbar 36×36: canto superior direito (igual aos outros badges)
		tr.anchor_top = 0.0; tr.anchor_bottom = 0.0
		tr.offset_left = -17; tr.offset_top = -1
		tr.offset_right = 1;  tr.offset_bottom = 17
	return tr

func _make_alert_badge() -> Control:
	var badge := PanelContainer.new()
	badge.mouse_filter = Control.MOUSE_FILTER_IGNORE
	# canto superior direito do botão 36×36, estourando levemente pra fora
	badge.anchor_left = 1.0; badge.anchor_right = 1.0
	badge.anchor_top = 0.0; badge.anchor_bottom = 0.0
	badge.offset_left = -15; badge.offset_top = -3
	badge.offset_right = 2;  badge.offset_bottom = 14
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.86, 0.14, 0.11)
	sb.set_corner_radius_all(9)
	sb.border_color = Color(1, 1, 1, 0.9); sb.set_border_width_all(1)
	badge.add_theme_stylebox_override("panel", sb)
	var l := Label.new()
	l.text = "!"
	l.add_theme_font_size_override("font_size", 11)
	l.add_theme_color_override("font_color", Color(1, 1, 1))
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	badge.add_child(l)
	badge.visible = false
	return badge

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
		b.tooltip_text = "Pagar ao padre para curar o herói (custa bronze conforme o dano)"
		b.visible = false                  # [HEAL] só aparece com HP < 100 (ver update_topbar)
		b.mouse_entered.connect(func() -> void: b.modulate = Color(1, 1, 1, 1))
		b.mouse_exited.connect(func() -> void: b.modulate = Color(1, 1, 1, 0.9))
		b.pressed.connect(_on_quick_heal)
		_heal_btn = b
		return b
	var fb := Button.new()
	fb.text = "❤"
	DarkButtonStyle.apply(fb)
	fb.add_theme_font_size_override("font_size", 16)
	fb.add_theme_color_override("font_color", Color(0.86, 0.32, 0.30))
	fb.custom_minimum_size = Vector2(36, 32)
	fb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	fb.tooltip_text = "Pagar ao padre para curar o herói (custa bronze conforme o dano)"
	fb.visible = false                     # [HEAL] só aparece com HP < 100
	fb.pressed.connect(_on_quick_heal)
	_heal_btn = fb
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
	pc.custom_minimum_size = Vector2(158, 0)   # [MENUBAR_REORG] mais estreito (labels são curtos)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.07, 0.065, 0.08, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20); sb.border_width_right = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	# [MENUBAR_REORG] SEM ScrollContainer — a barra cabe inteira em 720p (pedido do dono: sem scroll).
	var nav := VBoxContainer.new()
	nav.add_theme_constant_override("separation", 2)
	nav.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	# [MENUBAR_REORG2] a nav preenche a ALTURA do painel → os itens (com expand vertical) distribuem a
	# folga e chegam até embaixo, proporcional (pedido do dono). Expand só reparte o espaço LIVRE → sem scroll.
	nav.size_flags_vertical = Control.SIZE_EXPAND_FILL
	pc.add_child(nav)
	# Início (dashboard) — flat + ícone (mesmo padrão dos itens)
	var home := Button.new()
	home.flat = true
	home.alignment = HORIZONTAL_ALIGNMENT_LEFT
	home.custom_minimum_size = Vector2(0, 34)
	home.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	home.size_flags_vertical = Control.SIZE_EXPAND_FILL
	home.add_theme_constant_override("icon_max_width", 28)
	Icons.label_button(home, "home", "Início")
	home.tooltip_text = "Início — painel inicial com atalhos"
	home.add_theme_font_size_override("font_size", 14)
	home.pressed.connect(_show_dashboard)
	_nav_buttons["__home__"] = home
	nav.add_child(home)
	# [MENUBAR_REORG2] lista FLAT, SEM títulos de seção (pedido do dono) — ordem por loop de jogo
	# (parecer do UX sênior): herói no topo → aventura → combate → cidade/serviços. Separadores finos
	# (_nav_divider) marcam os grupos no lugar dos antigos headers de texto.
	nav.add_child(_nav_item("Character", "Personagem"))   # logo abaixo do Início
	nav.add_child(_nav_divider())
	nav.add_child(_nav_item("World", "Mundo"))            # coração do loop: aventurar
	nav.add_child(_nav_item("Work", "Trabalho"))          # idle (planta o timer)
	nav.add_child(_nav_divider())
	nav.add_child(_nav_item("Tower", "Torre"))            # combate PvE
	nav.add_child(_nav_item("Arena", "Arena"))            # combate PvP
	nav.add_child(_nav_item("Guild", "Guilda"))           # social + guerra de território
	nav.add_child(_nav_item("Leaderboards", "Classificação"))  # [LEADERBOARDS] ranking + perfil + social
	nav.add_child(_nav_divider())
	nav.add_child(_nav_item("Temple", "Templo"))          # manutenção do herói
	nav.add_child(_nav_item("Forge", "Forja"))            # craft/refino/encantar
	nav.add_child(_nav_item("Shop", "Loja"))
	nav.add_child(_nav_item("Auction", "Leilão"))
	nav.add_child(_nav_item("Stash", "Baú"))
	nav.add_child(_nav_item("Tavern", "Taverna"))
	nav.add_child(_nav_item("Vip", "VIP"))                # premium por último
	nav.add_child(_spacer(6))
	nav.add_child(_nav_divider())
	var out := _stone_btn("Sair", 32)
	out.tooltip_text = "Sair — desconecta da conta"
	out.pressed.connect(func() -> void: logout.emit())
	nav.add_child(out)
	return pc

# [MENUBAR_REORG2] separador fino e discreto entre grupos da nav (substitui os títulos de seção).
# ~7px de altura → 4 deles cabem folgado em 720p sem scroll.
func _nav_divider() -> Control:
	var m := MarginContainer.new()
	m.add_theme_constant_override("margin_top", 3)
	m.add_theme_constant_override("margin_bottom", 3)
	m.add_theme_constant_override("margin_left", 4)
	m.add_theme_constant_override("margin_right", 4)
	var line := ColorRect.new()
	line.color = Color(1, 1, 1, 0.07)   # baixíssimo contraste
	line.custom_minimum_size = Vector2(0, 1)
	m.add_child(line)
	return m

func _nav_item(scr: String, label: String) -> Button:
	var b := Button.new()
	b.flat = true
	b.alignment = HORIZONTAL_ALIGNMENT_LEFT
	b.custom_minimum_size = Vector2(0, 34)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.size_flags_vertical = Control.SIZE_EXPAND_FILL       # [MENUBAR_REORG2] cresce p/ preencher a barra (proporcional)
	b.add_theme_constant_override("icon_max_width", 28)
	b.clip_text = true                                    # corta label longo se preciso
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
		_maybe_offer(scr)   # [ONBOARDING v3] modal de aceitar ao chegar no NPC (1×/sessão) — guia o recruta
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
	_maybe_offer(scr)   # [ONBOARDING v3] modal de aceitar ao chegar no NPC (1×/sessão)

func _mutation_count() -> int:
	var api = get_node_or_null("/root/Api")
	return int(api.mutation_count) if api != null else 0

# [MAIL_COMPOSE] Abre o Correio já no compositor com o nick preenchido ("Enviar mensagem" de Amigos/Classificação).
func _open_mail_compose(recipient: String) -> void:
	_open("Mail")
	var m = _cache.get("Mail")
	if m != null and is_instance_valid(m) and m.has_method("request_compose"):
		m.request_compose(recipient)

# [INCURSAO] Abre o Mundo já expandido no reino dado (vitória da Incursão volta pro território de origem).
func _open_world_at(kingdom: String, delve_report := {}) -> void:
	var w = _cache.get("World")
	if w != null and is_instance_valid(w) and w.has_method("request_open_kingdom"):
		w.request_open_kingdom(kingdom, delve_report)   # [INCURSAO_FIM] relatório da run encerrada (se houver)
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
	if c.has_signal("open_mail_to"):                                 # [MAIL_COMPOSE] "Enviar mensagem" → Correio com nick preenchido
		c.open_mail_to.connect(_open_mail_compose)
	if c.has_signal("go_battle"):
		c.go_battle.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	if c.has_signal("request_battle"):
		c.request_battle.connect(func(data) -> void: request_battle.emit(data))
	if c.has_signal("logout"):                                # [LOGOUT] Settings → Shell → App (limpa token+session)
		c.logout.connect(func() -> void: logout.emit())
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
		_try_equip_quest()   # [ONBOARDING] equipou → tenta concluir o dever de equipar
		return
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var inv = await api.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		UiKit.set_equipped(inv["json"])
		if _bust != null and is_instance_valid(_bust):
			_bust.apply(inv["json"], str(warrior.get("warriorClassId", "")), str(warrior.get("gender", UiKit.current_gender)))
	_try_equip_quest()   # [ONBOARDING] equipou → tenta concluir o dever de equipar

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
		_xp_lbl.text = "%d/%d" % [clampi(xp, 0, need), maxi(1, need)]   # [XP_INLINE] valor dentro da barra
	_xp_bar.tooltip_text = Lang.t("Experiência: %d / %d — faltam %d pro próximo nível") % [xp, need, maxi(0, need - xp)]
	var hp := int(w.get("hpPercent", w.get("currentHp", 100)))
	_hp_bar.value = clampi(hp, 0, 100)
	if _heal_btn != null:
		_heal_btn.visible = hp < 100   # [HEAL] botão de cura só aparece quando ferido
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
	# [MAIL_BADGE] não-lido vira EXCLAMAÇÃO vermelha no canto (ícone da carta fica fixo, sem trocar de cor)
	if _mail_btn != null:
		var unread := int(w.get("unreadMail", 0))
		if _mail_badge != null:
			_mail_badge.visible = unread > 0
		_mail_btn.tooltip_text = ("Correio — %d não lida(s)" % unread) if unread > 0 else "Correio — mensagens e recompensas"
	# [LEADERBOARDS] badge de Amigos: pedidos de amizade + convites de guilda pendentes
	if _friends_badge != null:
		var social := int(w.get("pendingSocial", 0))
		_friends_badge.visible = social > 0
		if _friends_btn != null:
			_friends_btn.tooltip_text = ("Amigos — %d pendência(s)" % social) if social > 0 else "Amigos — lista, pedidos e convites de guilda"
	# [DAILY] exclamação no ícone da diária quando dá pra resgatar (igual ao Correio)
	if _daily_badge != null:
		_daily_badge.visible = bool(w.get("dailyClaimable", false))
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
		# [ELEMENTOS] tooltip estilo Templo: linha do que é + tempo, depois a roda (+25% vs X · −25% vs Y) via Icons.tip
		var wk := "elem_" + we.to_lower()
		_buffs_box.add_child(_buff_badge_icon(Icons.elem_anim_key(we), "⚔", Lang.t("Arma encantada — %s\n%s") % [_fmt_left(int(w.get("weaponElementSecondsLeft", 0))), Icons.tip(wk)]))
	var ae := str(w.get("armorElement", ""))
	if ae != "":
		var ak := "elem_" + ae.to_lower()
		_buffs_box.add_child(_buff_badge_icon(Icons.elem_anim_key(ae), "🛡", Lang.t("Armadura encantada — %s\n%s") % [_fmt_left(int(w.get("armorElementSecondsLeft", 0))), Icons.tip(ak)]))
	if bool(w.get("newbieBuffActive", false)):
		_buffs_box.add_child(_buff_badge("🐣", Lang.t("Buff de Novato: estamina e HP regeneram 4× mais rápido — %dh restantes") % int(w.get("newbieBuffHoursLeft", 0))))
	# [TOPBAR_BUFFS] grid de badges ao lado da cura; some quando não há nenhum buff (sem prefixo de texto)
	_buffs_box.visible = _buffs_box.get_child_count() > 0

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
		var ic := Icons.rect(key, 16)
		ic.tooltip_text = ""   # [TOPBAR_BUFFS] tooltip ÚNICA no badge — sem a tip do ícone duplicando (sobe pro painel via PASS)
		h.add_child(ic)
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

func _stone_btn(text: String, h: int, tier := 1) -> Button:   # [BOTAO_DARK] tier 0=PRIMARY (CTA), 1=SECONDARY
	var b := Button.new()
	b.text = text
	DarkButtonStyle.apply(b, tier)
	b.custom_minimum_size = Vector2(0, h)
	return b

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
