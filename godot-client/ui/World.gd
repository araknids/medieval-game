extends Control
# ── Tela MUNDO / REINOS ───────────────────────────────────────────────────────────
# Lista os reinos (GET /api/world), abre um e mostra suas DAILY QUESTS + zonas de coleta/caça.
# Iniciar quest (start→collect direto, como o web) e coletar a recompensa; resultado/log em TEXTO
# (sem 3D). Coleta/caça de zona via /api/zones (enter→collect instantâneo). Espelha loadWorld /
# renderWorldOverview / renderKingdomDetail do app.js. Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back
signal request_battle(data)   # pede ao App o replay 3D (overlay) [MIGRACAO_GODOT]
signal open_screen(name)      # [INCURSAO] entrar numa zona abre a tela da run (Delve)

const Icons := preload("res://ui/Icons.gd")

# Reinos de coleta/caça → as 3 zonas (tier SAFE/PVP/HIGH_RISK) que o web mostra em renderKingdomDetail.
# [name, tier, skillType("" p/ COMBAT), minLevel, role]
const ZONES := {
	"FISHING": [
		["🏖 Safe Shore", "SAFE", "FISHING", 1], ["🌊 Wild Coast", "PVP", "FISHING", 10], ["🦈 Deep Sea", "HIGH_RISK", "FISHING", 20],
	],
	"MAR_ABENCOADO": [
		["🌅 Sacred Cove", "SAFE", "FISHING", 1], ["🐠 Deep Reef", "PVP", "FISHING", 10], ["🔱 Blessed Abyss", "HIGH_RISK", "FISHING", 20],
	],
	"MINING": [
		["⛏ Open Mine", "SAFE", "MINING", 1], ["🪨 Deep Tunnels", "PVP", "MINING", 10], ["💎 Forbidden Mines", "HIGH_RISK", "MINING", 20],
	],
	"GRUTAS_DE_CRISTAL": [
		["🔎 Shallow Vein", "SAFE", "GARIMPO", 1], ["💠 Deep Grottoes", "PVP", "GARIMPO", 10], ["💎 Forbidden Cavern", "HIGH_RISK", "GARIMPO", 20],
	],
	"COMBAT": [
		["🏰 Haunted Courtyard", "SAFE", "", 1], ["⚔ Battlefield", "PVP", "", 10], ["🔥 War Zone", "HIGH_RISK", "", 20],
	],
}
const TIER_COL := {"SAFE": Color(0.30, 0.80, 0.30), "PVP": Color(1.0, 0.76, 0.0), "HIGH_RISK": Color(0.94, 0.33, 0.33)}
const ELEMENTS := [["FIRE", "🔥 Fire"], ["WATER", "💧 Water"], ["EARTH", "🪨 Earth"], ["AIR", "💨 Air"]]
const ZONE_DURATION := 20   # ação instantânea de tamanho fixo (~10⚡ via d/2), igual ao web

# [MAPA_MUNDO] Mapa-múndi de pergaminho (assets/ui/map/world_map.png, 1536×1024). O mapa CABE INTEIRO
# na tela (contain) e cada reino é um PIN por coord normalizada (0..1) cravada na arte.
const MAP_TEX := "res://assets/ui/map/world_map.png"
const MAP_W := 1536.0
const MAP_H := 1024.0
const PIN_POS := {
	"MINING":            Vector2(0.18, 0.47),  # entrada da mina, base das montanhas nevadas (oeste)
	"FISHING":           Vector2(0.45, 0.15),  # navios na baía (norte)
	"MAR_ABENCOADO":     Vector2(0.73, 0.24),  # lago sagrado turquesa brilhante (nordeste)
	"GRUTAS_DE_CRISTAL": Vector2(0.45, 0.55),  # espinhos de cristal azul (centro)
	"COMBAT":            Vector2(0.74, 0.60),  # fortaleza maldita escura (leste)
}
# Ícone pixel-art (assets/ui/icons/<key>.png) de cada território — fallback no emoji se faltar.
const KINGDOM_ICON := {
	"MINING": "map_mines",
	"FISHING": "map_fishing",
	"MAR_ABENCOADO": "map_blessed",
	"GRUTAS_DE_CRISTAL": "map_crystal",
	"COMBAT": "map_fortress",
}
const PIN_ICON_PX := 34   # marcador fica SOBRE o local; o nome cai ABAIXO dele

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var map_holder: Control = null    # [MAPA_MUNDO] container do mapa (hospeda os pins, mantém o aspecto)
var pins: Array = []              # [MAPA_MUNDO] [{node, kingdom}] dos reinos sobre o mapa
var scroll: Control = null        # [MAPA_MUNDO] ScrollContainer da scaffold (p/ medir a área visível)
var kingdoms: Array = []          # GET /api/world
var open_kingdom := ""            # reino expandido (só um por vez)
var _pending_after := {}          # resultado guardado durante o replay 3D (kingdom, kind, result) p/ o relatório
var warrior: Dictionary = {}      # /api/warrior (carteira + gate de nível)
var warrior_level := 1
var selected_element := "FIRE"    # picker de área de elemento
# detalhe do reino aberto (carregado sob demanda)
var quests: Array = []
var active_quests: Array = []
var training: Dictionary = {}
var zone_session: Dictionary = {}
var active_delve: Dictionary = {}   # [STUCK_FIX] /api/expedition/current — Incursão em andamento

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🌍 Mundo", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	scroll = ui.scroll
	resized.connect(_layout_map)   # [MAPA_MUNDO] recalcula o tamanho do mapa quando a janela muda
	visibility_changed.connect(_on_world_shown)   # [MAPA_MUNDO] reentrar no Mundo volta pro MAPA
	await _refresh()

# [MAPA_MUNDO] O Shell cacheia as telas (não recria) → reentrar no Mundo via nav mostraria o último
# reino aberto. Quando o nó reaparece, reseto pro mapa. A navegação INTERNA (reino ↔ mapa, quests)
# NÃO esconde/mostra o nó, então não dispara isto.
func _on_world_shown() -> void:
	if is_visible_in_tree() and open_kingdom != "":
		open_kingdom = ""
		_render()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# guerreiro (gate das zonas) + reinos + Incursão ativa em PARALELO — chamadas independentes
	var rs = await Api.batch_get(["/api/warrior", "/api/world", "/api/expedition/current"])
	var wr = rs[0]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
		warrior_level = int(warrior.get("level", 1))
	var rd = rs[2]
	active_delve = rd["json"] if (rd.get("ok") and rd.get("json") is Dictionary) else {}
	var r = rs[1]
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	kingdoms = r["json"]
	# NÃO auto-abre nenhum reino — o usuário escolhe qual expandir (todos começam fechados).
	# Se já havia um aberto (refresh após uma ação), reabre ele pra atualizar os dados.
	if open_kingdom != "":
		await _open(open_kingdom)
	else:
		_render()

# Carrega o detalhe do reino (quests + zona ativa) e marca como aberto.
func _open(kingdom: String) -> void:
	if kingdom == "":
		return
	open_kingdom = kingdom
	UiKit.flash(status, Lang.t("Abrindo %s…") % kingdom, 0)
	# dispara tudo em PARALELO (independentes); training só no COMBAT — máx. 4 = cabe no pool
	var has_training := kingdom == "COMBAT"
	var paths := ["/api/world/%s/quests" % kingdom, "/api/world/%s/quests/active" % kingdom, "/api/zones/current"]
	if has_training:
		paths.append("/api/world/COMBAT/training")
	var rs = await Api.batch_get(paths)
	var rq = rs[0]
	quests = rq["json"] if (rq.get("ok") and rq.get("json") is Array) else []
	var ra = rs[1]
	active_quests = ra["json"] if (ra.get("ok") and ra.get("json") is Array) else []
	var rz = rs[2]
	zone_session = rz["json"] if (rz.get("ok") and rz.get("json") is Dictionary) else {}
	var rt: Dictionary = {}
	if has_training:
		var rtr = rs[3]
		if rtr.get("ok") and rtr.get("json") is Dictionary:
			rt = rtr["json"]
	training = rt
	_render()

# "tem tarefa ativa pra coletar" neste reino → bloqueia começar outra (espelha os checks do backend).
func _has_active_task() -> bool:
	if not active_quests.is_empty():
		return true
	if zone_session.get("active", false):
		return true
	if training.get("active", false) and not training.get("readyToCollect", false):
		return true
	return false

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	pins = []
	map_holder = null
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	# [STUCK_FIX] Incursão em andamento → botão pra retomar/abandonar (a aba Delve saiu do nav,
	# então este é o caminho de volta pra uma run presa). Espelha o web "Continuar Incursão".
	if bool(active_delve.get("active", false)):
		content.add_child(UiKit.action("⚔ Continuar Incursão em andamento", func() -> void: open_screen.emit("Delve")))
	# [MAPA_MUNDO] open_kingdom == "" → mapa-múndi com pins; senão → detalhe do reino aberto.
	if open_kingdom == "":
		_render_map()
	else:
		_render_detail(open_kingdom)

# ── [MAPA_MUNDO] Mapa-múndi: TextureRect do pergaminho + 1 pin clicável por reino ─────────────────
func _render_map() -> void:
	var tex = load(MAP_TEX) if ResourceLoader.exists(MAP_TEX) else null   # Texture2D ou null
	if tex == null:
		# Fallback (mapa ainda não importado pelo Godot): botões simples → a tela segue usável.
		content.add_child(UiKit.dim("Mapa não importado ainda — abra o projeto no Godot. Reinos:"))
		for k in kingdoms:
			if k is Dictionary:
				var kid := str(k.get("kingdom", ""))
				content.add_child(UiKit.action("%s %s" % [str(k.get("icon", "")), str(k.get("displayName", kid))], _toggle.bind(kid)))
		return
	map_holder = Control.new()
	map_holder.size_flags_horizontal = Control.SIZE_SHRINK_CENTER   # largura vem do fit → centraliza
	map_holder.clip_contents = true
	content.add_child(map_holder)
	var tr := TextureRect.new()
	tr.texture = tex
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE   # escala p/ o tamanho do nó (não impõe 1536×1024)
	tr.stretch_mode = TextureRect.STRETCH_SCALE       # holder já tem o aspecto do mapa → não distorce
	tr.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	map_holder.add_child(tr)
	pins = []
	for k in kingdoms:
		if k is Dictionary:
			var kid := str(k.get("kingdom", ""))
			if PIN_POS.has(kid):
				var pin := _make_pin(k)
				map_holder.add_child(pin)
				pins.append({"node": pin, "kingdom": kid})
	_layout_map()
	content.add_child(UiKit.dim("Toque numa região do mapa para viajar até o reino."))

# Pin: MARCADOR (ícone do território) SOBRE o local + NOME numa caixa ABAIXO — o rótulo não cobre
# mais a construção do mapa. Clique em qualquer parte abre o reino.
func _make_pin(k: Dictionary) -> Control:
	var kid := str(k.get("kingdom", ""))
	var pin := VBoxContainer.new()
	pin.alignment = BoxContainer.ALIGNMENT_CENTER
	pin.add_theme_constant_override("separation", 1)
	pin.mouse_filter = Control.MOUSE_FILTER_STOP
	pin.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	# marcador: ícone pixel-art do território (fallback no emoji do backend se o PNG não existir)
	var icon_key: String = KINGDOM_ICON.get(kid, "")
	if icon_key != "" and Icons.tex(icon_key) != null:
		var ir := Icons.rect(icon_key, PIN_ICON_PX)
		ir.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		pin.add_child(ir)
	else:
		var ie := Label.new()
		ie.text = str(k.get("icon", "📍"))
		ie.add_theme_font_size_override("font_size", PIN_ICON_PX - 6)
		ie.add_theme_color_override("font_outline_color", Color(0, 0, 0))
		ie.add_theme_constant_override("outline_size", 5)
		ie.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		ie.mouse_filter = Control.MOUSE_FILTER_IGNORE
		pin.add_child(ie)
	# nome numa caixa, ABAIXO do marcador
	var name_box := PanelContainer.new()
	name_box.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	name_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.05, 0.04, 0.82)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.OK if bool(k.get("isMine", false)) else UiKit.GOLD_SOFT
	sb.set_corner_radius_all(4)
	sb.content_margin_left = 7; sb.content_margin_right = 7
	sb.content_margin_top = 2; sb.content_margin_bottom = 2
	sb.shadow_color = Color(0, 0, 0, 0.6); sb.shadow_size = 4
	name_box.add_theme_stylebox_override("panel", sb)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 4)
	name_box.add_child(row)
	var nm := Label.new()
	nm.text = str(k.get("displayName", kid))
	nm.add_theme_font_size_override("font_size", 12)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.add_theme_color_override("font_outline_color", Color(0, 0, 0))   # contorno → lê sobre o mapa
	nm.add_theme_constant_override("outline_size", 4)
	nm.mouse_filter = Control.MOUSE_FILTER_IGNORE
	row.add_child(nm)
	var cg := str(k.get("controllingGuild", ""))
	if cg != "":
		var g := Label.new()
		g.text = "🛡"
		g.add_theme_font_size_override("font_size", 12)
		g.add_theme_color_override("font_color", UiKit.OK)
		g.mouse_filter = Control.MOUSE_FILTER_IGNORE
		row.add_child(g)
	pin.add_child(name_box)
	pin.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT:
			_toggle(kid))
	return pin

# [MAPA_MUNDO] Dimensiona o mapa p/ CABER INTEIRO na área visível (contain) + recoloca os pins.
# avail = largura do content (já capada pela scaffold) × altura visível do scroll. Sem loop: o
# tamanho do mapa deriva dessas duas medidas, que NÃO dependem do tamanho do próprio mapa.
func _layout_map() -> void:
	if map_holder == null or not is_instance_valid(map_holder):
		return
	var avail_w := content.size.x
	if avail_w <= 0.0:
		avail_w = size.x
	var avail_h := (scroll.size.y if scroll != null else size.y) - 48.0   # respiro p/ a dica abaixo
	if avail_h <= 0.0:
		avail_h = size.y * 0.7
	var s := minf(avail_w / MAP_W, avail_h / MAP_H)
	s = maxf(s, 0.05)
	var map_w := floorf(MAP_W * s)
	var map_h := floorf(MAP_H * s)
	if map_holder.custom_minimum_size.x != map_w or map_holder.custom_minimum_size.y != map_h:
		map_holder.custom_minimum_size = Vector2(map_w, map_h)
	var sz := Vector2(map_w, map_h)
	for p in pins:
		var node: Control = p["node"]
		if not is_instance_valid(node):
			continue
		node.reset_size()
		var pos: Vector2 = PIN_POS[p["kingdom"]]
		# âncora no MARCADOR (topo da pilha): centro horizontal no ponto + centro do ícone sobre o
		# local; o nome (abaixo) não cobre a construção.
		node.position = pos * sz - Vector2(node.size.x * 0.5, PIN_ICON_PX * 0.5)

# ── [MAPA_MUNDO] Detalhe de um reino: voltar ao mapa + cabeçalho + o miolo (quests/zonas) ─────────
func _render_detail(kingdom: String) -> void:
	var k := _kingdom_data(kingdom)
	content.add_child(UiKit.action("🗺 Voltar ao mapa", _toggle.bind(kingdom)))
	var head := Label.new()
	head.text = "%s %s" % [str(k.get("icon", "")), str(k.get("displayName", kingdom))]
	head.add_theme_font_size_override("font_size", 20)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	content.add_child(head)
	var cg := str(k.get("controllingGuild", ""))
	content.add_child(UiKit.dim(("🛡 " + cg) if cg != "" else "Neutro"))
	if bool(k.get("isMine", false)):
		content.add_child(UiKit.dim(Lang.t("Sua guilda: +%d%% XP · +%d%% bronze · +%d%% bônus") % [int(k.get("xpBonus", 0)), int(k.get("bronzeBonus", 0)), int(k.get("exclusiveBonus", 0))]))
	var lore_text := str(k.get("lore", ""))
	if lore_text != "":
		content.add_child(UiKit.dim(lore_text))
	_build_detail(content, kingdom)

func _kingdom_data(kid: String) -> Dictionary:
	for k in kingdoms:
		if k is Dictionary and str(k.get("kingdom", "")) == kid:
			return k
	return {}

# Detalhe do reino aberto: pvp banner + tarefas ativas + quests + zonas de coleta/caça.
func _build_detail(box: VBoxContainer, kingdom: String) -> void:
	box.add_child(HSeparator.new())
	# tarefa de zona ativa pendurada
	if zone_session.get("active", false):
		var zname := str(zone_session.get("zoneName", zone_session.get("zone", "")))
		var ready := bool(zone_session.get("readyToCollect", false))
		box.add_child(UiKit.dim(Lang.t("⚔ Expedição em andamento (%s)") % Lang.t(zname)))
		if ready:
			box.add_child(UiKit.action("Coletar loot", _collect_zone.bind(int(zone_session.get("id", 0)))))
		else:
			box.add_child(UiKit.action_danger("✖ Cancelar expedição", _cancel_zone.bind(int(zone_session.get("id", 0)))))
	# quests ativas (coletar / abandonar)
	if not active_quests.is_empty():
		box.add_child(UiKit.section("Quests Ativas"))
		for q in active_quests:
			if q is Dictionary:
				var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
				var lbl := UiKit.body(str(q.get("displayName", "?")))
				lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
				row.add_child(lbl)
				var qid := int(q.get("id", 0))
				if bool(q.get("readyToCollect", false)):
					if bool(q.get("interactive", false)) and q.get("dialog") is Dictionary:
						var dlg: Dictionary = q["dialog"]   # interativa: re-abre a escolha
						row.add_child(UiKit.small_btn("Escolher", func() -> void: _show_quest_dialog(kingdom, qid, dlg)))
					else:
						row.add_child(UiKit.small_btn("Coletar", _collect_quest.bind(kingdom, qid)))
				else:
					var tl := Label.new()
					tl.text = "%dm" % int(q.get("secondsRemaining", 0) / 60)
					tl.add_theme_font_size_override("font_size", 12)
					tl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
					row.add_child(tl)
				row.add_child(UiKit.small_btn("✖", _abandon_quest.bind(kingdom, qid), true))
				box.add_child(row)
	# Training Hall (só COMBAT)
	if kingdom == "COMBAT":
		_build_training(box)
	# DAILY QUESTS
	if not quests.is_empty():
		box.add_child(UiKit.section("🗓 Daily Quests"))
		for q in quests:
			if q is Dictionary:
				box.add_child(_quest_card(kingdom, q))
	# Zonas de coleta / caça
	if ZONES.has(kingdom):
		box.add_child(UiKit.section("⚗ Áreas de Elemento"))
		box.add_child(_element_picker())
		box.add_child(UiKit.section("⚔ Zonas" if kingdom == "COMBAT" else "🌍 Zonas"))
		for z in ZONES[kingdom]:
			box.add_child(_zone_card(kingdom, z))

func _build_training(box: VBoxContainer) -> void:
	box.add_child(UiKit.section("🏋 Training Hall"))
	if training.get("active", false):
		box.add_child(UiKit.dim(Lang.t("+%d XP — coletar") % int(training.get("xpReward", 0))))
		if bool(training.get("readyToCollect", false)):
			box.add_child(UiKit.action("⭐ Coletar XP", _collect_training.bind(int(training.get("id", 0)))))
		box.add_child(UiKit.action_danger("✖ Cancelar", _cancel_training.bind(int(training.get("id", 0)))))
	else:
		box.add_child(UiKit.dim("Pague bronze por XP puro."))
		if _has_active_task():
			var b := UiKit.action("Colete a tarefa ativa", Callable())
			b.disabled = true
			box.add_child(b)
		else:
			box.add_child(UiKit.action("🏋 Treinar (2h)", _start_training.bind(2)))

func _quest_card(kingdom: String, q: Dictionary) -> PanelContainer:
	var done := bool(q.get("doneToday", false))
	var res := UiKit.card(UiKit.OK if done else UiKit.BRONZE)
	var vb: VBoxContainer = res[1]
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = str(q.get("displayName", "?")); nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	# [MOEDA] recompensa de bronze em ícone pixel-art + exp/estamina
	var info := HBoxContainer.new(); info.add_theme_constant_override("separation", 4)
	info.add_child(UiKit.coin_box(int(q.get("bronzeReward", 0)), 14))
	var info_x := Label.new()
	info_x.text = "⭐%d  ⚡%d" % [int(q.get("expReward", 0)), int(q.get("staminaCost", 0))]
	info_x.add_theme_color_override("font_color", UiKit.TEXT_DIM); info_x.add_theme_font_size_override("font_size", 12)
	info_x.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	info.add_child(info_x)
	top.add_child(info)
	vb.add_child(top)
	var flavor := str(q.get("flavor", ""))
	if flavor != "":
		vb.add_child(UiKit.dim(flavor))
	if done:
		vb.add_child(UiKit.dim("✔ Feito hoje"))
	elif _has_active_task():
		var b := UiKit.action("Termine a tarefa ativa", Callable())
		b.disabled = true
		vb.add_child(b)
	elif not bool(q.get("canStart", false)):
		var b := UiKit.action("Sem estamina", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		var stam := int(q.get("staminaCost", 0))
		var label := "📜 Começar" if bool(q.get("interactive", false)) else "Iniciar Quest"
		if stam > 0:
			label += " · ⚡%d" % stam
		vb.add_child(UiKit.action(label, _start_quest.bind(kingdom, str(q.get("id", "")))))
	return res[0]

func _element_picker() -> HBoxContainer:
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
	for e in ELEMENTS:
		var b := Button.new()
		Icons.label_button(b, "elem_" + str(e[0]).to_lower(), str(e[1]))  # ícone + nome (fallback no emoji)
		StoneStyle.apply(b)
		b.add_theme_font_size_override("font_size", 13)
		b.custom_minimum_size = Vector2(96, 36)
		b.toggle_mode = true; b.button_pressed = (str(e[0]) == selected_element)
		b.pressed.connect(_select_element.bind(str(e[0])))
		row.add_child(b)
	return row

func _zone_card(kingdom: String, z: Array) -> PanelContainer:
	var zname := str(z[0]); var tier := str(z[1]); var skill := str(z[2]); var min_lv := int(z[3])
	var locked := warrior_level < min_lv
	var col: Color = TIER_COL.get(tier, Color(0.6, 0.6, 0.6))
	var res := UiKit.card(Color(0.3, 0.3, 0.3, 0.5) if locked else col)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	if locked:
		panel.modulate = Color(1, 1, 1, 0.6)
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = zname; nm.add_theme_color_override("font_color", col); nm.add_theme_font_size_override("font_size", 15)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var tag := Label.new()
	tag.text = "🔒 Lv.%d+" % min_lv if locked else ("⚔ PvP" if tier != "SAFE" else "✔ Seguro")
	tag.add_theme_color_override("font_color", UiKit.TEXT_DIM); tag.add_theme_font_size_override("font_size", 11)
	top.add_child(tag)
	vb.add_child(top)
	var stam := maxi(5, ZONE_DURATION / 2)
	if locked:
		# P0: gate visível na própria ação (botão desabilitado diz POR QUE)
		var b := UiKit.action(Lang.t("Requer Nv %d") % min_lv, Callable())
		b.disabled = true
		vb.add_child(b)
	elif _has_active_task():
		var b := UiKit.action("Colete a tarefa ativa", Callable())
		b.disabled = true
		vb.add_child(b)
	elif kingdom == "COMBAT" and int(warrior.get("hpPercent", 100)) <= 0:
		# KO: a caçada é combate real (o backend recusa com 400 "unconscious"). Guarda no clique. [HP_GUARD]
		var b := UiKit.action("❤ Inconsciente — cure no Templo", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		var verb: String
		if kingdom == "COMBAT":
			verb = Lang.t("⚔ Caçar · ⚡%d") % stam
		elif skill == "MINING":
			verb = Lang.t("⛏ Minerar · ⚡%d") % stam
		elif skill == "GARIMPO":
			verb = Lang.t("🔎 Garimpar · ⚡%d") % stam
		else:
			verb = Lang.t("🎣 Pescar · ⚡%d") % stam
		# [INCURSAO] a zona agora LANÇA uma Incursão (tier por cor). O enter→collect antigo saiu da UI.
		vb.add_child(UiKit.action(verb, _start_zone_delve.bind(kingdom, tier, skill)))
	return panel

# [INCURSAO] Inicia uma Incursão ZONE a partir da zona do reino (🟢/🟡/🔴 → tier 1/2/3) e abre a tela da run.
func _start_zone_delve(kingdom: String, tier: String, skill: String) -> void:
	if busy: return
	busy = true
	var tier_num := 3 if tier == "HIGH_RISK" else (2 if tier == "PVP" else 1)
	var skill_arg: Variant = skill if skill != "" else null
	var r = await Api.expedition_start("ZONE", kingdom, tier, skill_arg, selected_element, tier_num)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		open_screen.emit("Delve")   # o Shell abre a Delve; o _refresh dela pega a run nova (/current)
	else:
		_show_error(r)
		# [STUCK_FIX] falhou por já ter uma Incursão ativa? abre a Delve (resume/abandona),
		# senão o jogador fica preso (sem aba Delve no nav). Locale-independente: checa /current.
		var cur = await Api.expedition_current()
		if cur.get("ok") and cur.get("json") is Dictionary and bool(cur["json"].get("active", false)):
			open_screen.emit("Delve")

# ── Ações (1 chamada cada; em sucesso re-abre o reino p/ refrescar; em falha mostra o erro) ───────
func _toggle(kingdom: String) -> void:
	if busy: return
	if kingdom == open_kingdom:
		open_kingdom = ""
		_render()
		return
	busy = true
	await _open(kingdom)
	busy = false

func _select_element(el: String) -> void:
	selected_element = el
	_render()

func _start_quest(kingdom: String, quest_type: String) -> void:
	if busy: return
	busy = true
	var r = await Api.quest_start(kingdom, quest_type)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _open(kingdom); return
	var j: Dictionary = r["json"]
	var qid := int(j.get("id", 0))
	# interativa: mostra o diálogo (intro + opções) → coleta com optionId. Senão resolve direto.
	if bool(j.get("interactive", false)) and j.get("dialog") is Dictionary:
		_show_quest_dialog(kingdom, qid, j["dialog"])
	else:
		await _collect_quest(kingdom, qid)

func _collect_quest(kingdom: String, quest_id: int, option_id := "") -> void:
	if busy: return
	busy = true
	var r = await Api.quest_collect(kingdom, quest_id, option_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _open(kingdom); return
	var j: Dictionary = r["json"]
	if bool(j.get("lunaPending", false)):   # a Luna interrompeu → ajudar ou terminar
		_show_luna_dialog(kingdom, quest_id)
		return
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		# encontrou monstro → replay 3D por cima; guarda o RESULTADO p/ o relatório pós-replay [BATTLE_REPORT]
		_pending_after = {"kingdom": kingdom, "kind": "quest", "result": j}
		request_battle.emit({"events": be, "scene": str(j.get("scene", "")), "won": bool(j.get("monsterDefeated", false)), "enemy": str(j.get("monsterName", ""))})
	else:
		await _open(kingdom)   # refresca a lista; status some aqui → desfecho vai no relatório
		_show_quest_report(j)

# o App chama isto quando o replay 3D termina (volta pro Mundo + mostra o desfecho da quest)
func _on_battle_over() -> void:
	var kingdom := str(_pending_after.get("kingdom", open_kingdom))
	var kind := str(_pending_after.get("kind", ""))
	var result: Dictionary = _pending_after["result"] if _pending_after.get("result") is Dictionary else {}
	_pending_after = {}
	await _open(kingdom)
	if kind == "quest":
		_show_quest_report(result)
	elif kind == "zone":
		_show_zone_report(result)

# Diálogo de quest interativa: intro + um botão por opção (coleta com o optionId escolhido).
func _show_quest_dialog(kingdom: String, quest_id: int, dialog: Dictionary) -> void:
	var opts: Array = []
	for o in dialog.get("options", []):
		if o is Dictionary:
			opts.append([str(o.get("label", "?")), str(o.get("id", ""))])
	_choice_dialog(str(dialog.get("intro", "")), opts, func(opt_id) -> void:
		await _collect_quest(kingdom, quest_id, str(opt_id)))

# A Luna apareceu: ajudar (abre mão da recompensa) ou terminar a missão.
func _show_luna_dialog(kingdom: String, quest_id: int) -> void:
	_choice_dialog("🐶 Uma cãozinha (Luna) apareceu e interrompeu a missão! O que fazer?",
		[["Ajudar a Luna", "help"], ["Terminar a missão", "ignore"]],
		func(action) -> void:
			if busy: return
			busy = true
			var r = await Api.quest_luna(kingdom, quest_id, str(action))
			busy = false
			var jr: Dictionary = r["json"] if (r.get("ok") and r.get("json") is Dictionary) else {}
			if jr.is_empty():
				_show_error(r)
			await _open(kingdom)
			if not jr.is_empty():
				_show_quest_report(jr))

# Overlay genérico de escolha: título + botões. cb.call(valor) ao escolher. [MIGRACAO_GODOT]
func _choice_dialog(title_text: String, options: Array, cb: Callable) -> void:
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	vb.add_theme_constant_override("separation", 10)
	center.add_child(panel)
	var lbl := UiKit.body(title_text)
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	for opt in options:
		var val = opt[1]
		var b := UiKit.action(str(opt[0]), func() -> void:
			overlay.queue_free()
			cb.call(val))
		b.custom_minimum_size = Vector2(460, 40)
		vb.add_child(b)

# Modal de RESULTADO: texto + botão OK. Persiste (o status some no _open). Substitui o showCollectModal do web.
func _show_result(text: String) -> void:
	if text.strip_edges() == "":
		return
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	vb.add_theme_constant_override("separation", 12)
	center.add_child(panel)
	var lbl := UiKit.body(text)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	var ok := UiKit.action("OK", func() -> void: overlay.queue_free())
	ok.custom_minimum_size = Vector2(460, 40)
	vb.add_child(ok)
	ok.call_deferred("grab_focus")

func _abandon_quest(kingdom: String, quest_id: int) -> void:
	if busy: return
	busy = true
	await Api.quest_abandon(kingdom, quest_id)
	busy = false
	await _open(kingdom)
	UiKit.flash(status, "Quest abandonada.", 0)

func _start_training(hours: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_start(hours)
	if r.get("ok") and r.get("json") is Dictionary:
		# [SEM_TIMER] instantâneo: resolve e mostra o resultado direto
		busy = false
		await _collect_training(int(r["json"].get("id", 0)))
		return
	else:
		_show_error(r)
	busy = false
	await _open("COMBAT")

func _collect_training(session_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_collect(session_id)
	var msg := ""
	if r.get("ok") and r.get("json") is Dictionary:
		msg = Lang.t("🏋 Treino completo! +%d XP") % int(r["json"].get("xpEarned", 0))
	else:
		_show_error(r)
	busy = false
	await _open("COMBAT")
	if msg != "":
		_show_result(msg)

func _cancel_training(session_id: int) -> void:
	if busy: return
	busy = true
	await Api.training_cancel(session_id)
	busy = false
	await _open("COMBAT")
	UiKit.flash(status, "Treino cancelado.", 0)

# Coleta/caça de zona: enter → collect direto (instantâneo, como o web). Resultado em texto.
func _enter_zone(kingdom: String, tier: String, role: String, skill: String) -> void:
	if busy: return
	busy = true
	var skill_arg: Variant = skill if skill != "" else null
	var r = await Api.zone_enter(tier, role, skill_arg, ZONE_DURATION, kingdom, selected_element)
	if r.get("ok") and r.get("json") is Dictionary:
		var id := int(r["json"].get("id", 0))
		busy = false
		await _collect_zone(id)
		return
	else:
		_show_error(r)
	busy = false
	await _open(kingdom)

func _collect_zone(activity_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.zone_collect(activity_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r)
		if open_kingdom != "":
			await _open(open_kingdom)
		return
	await _handle_zone_result(activity_id, r["json"])

# [BATALHA_ANIMADA] Trata a resposta de collect/boss da zona: se veio encontro de combate
# (battleEvents), dispara o replay 3D igual à quest — o desfecho em texto aparece DEPOIS
# (via _on_battle_over). Sem combate → mostra o texto direto. Espelha o _collect_quest.
func _handle_zone_result(activity_id: int, j: Dictionary) -> void:
	if bool(j.get("bossPending", false)):
		_show_boss_dialog(activity_id, j)   # [ZONA_CHEFE] chefe errante: encarar ou fugir
		return
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		# encontrou monstro na expedição → replay 3D por cima; guarda o RESULTADO p/ o relatório [BATTLE_REPORT]
		_pending_after = {"kingdom": open_kingdom, "kind": "zone", "result": j}
		request_battle.emit({"events": be, "scene": str(j.get("scene", "")), "won": bool(j.get("survived", false)), "enemy": str(j.get("attackerName", ""))})
		return
	if open_kingdom != "":
		await _open(open_kingdom)
	_show_zone_report(j)

# [ZONA_CHEFE] Chefe errante apareceu na expedição: encarar (combate 3D) ou tentar fugir.
func _show_boss_dialog(activity_id: int, j: Dictionary) -> void:
	var bname := str(j.get("bossName", "Chefe"))
	var lvl := int(j.get("bossLevel", 0))
	var flee := int(j.get("fleeChance", 0))
	var intro := Lang.t("💀 %s (Lv %d) escapou da Torre e bloqueou sua expedição!\n\n⚔ Encarar = combate.   🏃 Fugir = %d%% (se falhar, cai na luta).") % [bname, lvl, flee]
	_choice_dialog(intro, [["⚔ Encarar", "fight"], ["🏃 Fugir", "flee"]], func(choice) -> void:
		await _resolve_boss(activity_id, str(choice)))

func _resolve_boss(activity_id: int, choice: String) -> void:
	if busy: return
	busy = true
	var r
	if choice == "fight":
		r = await Api.zone_boss_fight(activity_id)
	else:
		r = await Api.zone_boss_flee(activity_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r)
		if open_kingdom != "":
			await _open(open_kingdom)
		return
	await _handle_zone_result(activity_id, r["json"])

func _cancel_zone(activity_id: int) -> void:
	if busy: return
	busy = true
	await Api.zone_cancel(activity_id)
	busy = false
	if open_kingdom != "":
		await _open(open_kingdom)
	UiKit.flash(status, "Expedição cancelada.", 0)

# ── Texto de resultado (substitui o showCollectModal do web) ──────────────────────────────────────
func _quest_result_text(r: Dictionary) -> String:
	if bool(r.get("acquiredPet", false)) or str(r.get("acquiredPet", "")) != "":
		var pet := str(r.get("acquiredPet", ""))
		if pet != "" and pet != "false":
			return Lang.t("🎉 Novo companheiro: %s!") % pet
	var lost := bool(r.get("monsterEncountered", false)) and not bool(r.get("monsterDefeated", false))
	if lost:
		return Lang.t("💀 Derrotado por %s — sem recompensa.") % str(r.get("monsterName", "monstro"))
	var parts: Array = []
	if bool(r.get("monsterEncountered", false)):
		parts.append(Lang.t("⚔ %s derrotado!") % str(r.get("monsterName", "inimigo")))
	else:
		parts.append(Lang.t("✅ Quest concluída!"))
	parts.append(Lang.t("+%d XP · +%d bronze") % [int(r.get("xpEarned", 0)), int(r.get("bronzeEarned", 0))])
	if r.get("droppedItem") is Dictionary:
		parts.append("🎁 " + str(r["droppedItem"].get("name", "item")))
	return "   ".join(parts)

func _zone_result_text(r: Dictionary) -> String:
	if bool(r.get("wasAttacked", false)) and not bool(r.get("survived", false)):
		var s := Lang.t("💀 Derrotado na expedição!")
		if str(r.get("attackerName", "")) != "":
			s += " " + (Lang.t("(por %s)") % str(r.get("attackerName")))
		if str(r.get("lostItemName", "")) != "":
			s += " · " + (Lang.t("item roubado: %s") % str(r.get("lostItemName")))
		return s
	var parts: Array = []
	var slew_boss := bool(r.get("wasAttacked", false)) and bool(r.get("survived", false)) and str(r.get("lootItemName", "")) != ""
	if slew_boss:
		parts.append(Lang.t("🏆 Chefe errante abatido!"))
	elif bool(r.get("wasAttacked", false)):
		parts.append(Lang.t("⚔ Sobreviveu à expedição!"))
	else:
		parts.append(Lang.t("✅ Expedição concluída!"))
	if str(r.get("lootItemName", "")) != "":
		parts.append("🎁 " + str(r.get("lootItemName")))
	if r.get("drops") is Array:
		for d in r["drops"]:
			if d is Dictionary:
				parts.append("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))])
	if int(r.get("bronzeGained", 0)) > 0:
		parts.append("🥉 +%d bronze" % int(r.get("bronzeGained", 0)))
	if int(r.get("xpGained", 0)) > 0:
		parts.append("⭐ +%d XP" % int(r.get("xpGained", 0)))
	return "   ".join(parts)

# ── Relatório de batalha (estilo da Torre): card win/loss + recompensas + log colapsável. [BATTLE_REPORT]
# Combate → relatório completo; sem combate (coleta pura/pet) → modal de texto simples.
func _show_quest_report(j: Dictionary) -> void:
	if not bool(j.get("monsterEncountered", false)):
		_show_result(_quest_result_text(j))
		return
	var won := bool(j.get("monsterDefeated", false))
	var mob := str(j.get("monsterName", "inimigo"))
	var title := (Lang.t("⚔ %s derrotado!") % mob) if won else (Lang.t("💀 Derrotado por %s — sem recompensa.") % mob)
	var rows: Array = []
	if won:
		rows.append(UiKit.kv_node("Recompensa", UiKit.coin_box(int(j.get("bronzeEarned", 0)), 18)))
		rows.append(UiKit.kv("⭐ Experiência", "+%d XP" % int(j.get("xpEarned", 0))))
		if j.get("droppedItem") is Dictionary:
			rows.append(UiKit.dim("🎁 " + str(j["droppedItem"].get("name", "item"))))
	else:
		rows.append(UiKit.dim(Lang.t("☠ Derrotado — cure-se no Templo")))
	var log: Array = j.get("battleLog", []) if j.get("battleLog") is Array else []
	UiKit.show_battle_report(self, won, title, rows, log)

func _show_zone_report(j: Dictionary) -> void:
	if not bool(j.get("wasAttacked", false)):
		_show_result(_zone_result_text(j))   # expedição sem combate → coleta normal
		return
	var survived := bool(j.get("survived", false))
	var title: String
	if not survived:
		title = Lang.t("💀 Derrotado na expedição!")
	elif str(j.get("lootItemName", "")) != "":
		title = Lang.t("🏆 Chefe errante abatido!")
	else:
		title = Lang.t("⚔ Sobreviveu à expedição!")
	var rows: Array = []
	if survived:
		if str(j.get("lootItemName", "")) != "":
			rows.append(UiKit.dim("🎁 " + str(j.get("lootItemName"))))
		if j.get("drops") is Array:
			for d in j["drops"]:
				if d is Dictionary:
					rows.append(UiKit.dim("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))]))
		if int(j.get("bronzeGained", 0)) > 0:
			rows.append(UiKit.kv_node("Recompensa", UiKit.coin_box(int(j.get("bronzeGained", 0)), 18)))
		if int(j.get("xpGained", 0)) > 0:
			rows.append(UiKit.kv("⭐ Experiência", "+%d XP" % int(j.get("xpGained", 0))))
	else:
		rows.append(UiKit.dim(Lang.t("☠ Derrotado — cure-se no Templo")))
		if str(j.get("lostItemName", "")) != "":
			rows.append(UiKit.dim(Lang.t("item roubado: %s") % str(j.get("lostItemName"))))
	var log: Array = j.get("battleLog", []) if j.get("battleLog") is Array else []
	UiKit.show_battle_report(self, survived, title, rows, log)

func _show_error(r) -> void:
	UiKit.show_error(status, r)
	_show_result("⚠ " + UiKit.err_text(r))   # erro vai no modal também (o status fica no topo, fora de vista)
