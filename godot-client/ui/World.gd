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
		["Safe Shore", "SAFE", "FISHING", 1], ["Wild Coast", "PVP", "FISHING", 10], ["Deep Sea", "HIGH_RISK", "FISHING", 20],
	],
	"MAR_ABENCOADO": [
		["Sacred Cove", "SAFE", "FISHING", 1], ["Deep Reef", "PVP", "FISHING", 10], ["Blessed Abyss", "HIGH_RISK", "FISHING", 20],
	],
	"MINING": [
		["Open Mine", "SAFE", "MINING", 1], ["Deep Tunnels", "PVP", "MINING", 10], ["Forbidden Mines", "HIGH_RISK", "MINING", 20],
	],
	"GRUTAS_DE_CRISTAL": [
		["Shallow Vein", "SAFE", "GARIMPO", 1], ["Deep Grottoes", "PVP", "GARIMPO", 10], ["Forbidden Cavern", "HIGH_RISK", "GARIMPO", 20],
	],
	"COMBAT": [
		["Haunted Courtyard", "SAFE", "", 1], ["Battlefield", "PVP", "", 10], ["War Zone", "HIGH_RISK", "", 20],
	],
}
const TIER_COL := {"SAFE": Color(0.30, 0.80, 0.30), "PVP": Color(1.0, 0.76, 0.0), "HIGH_RISK": Color(0.94, 0.33, 0.33)}
const ELEMENTS := [["FIRE", "🔥 Fire"], ["WATER", "💧 Water"], ["EARTH", "🪨 Earth"], ["AIR", "💨 Air"]]
# [ELEMENTOS] roda RPS: X VENCE Y (×1.25) e PERDE p/ quem vence X (×0.75). FOGO→AR→TERRA→ÁGUA→FOGO.
const ELEM_BEATS := {"FIRE": "AIR", "AIR": "EARTH", "EARTH": "WATER", "WATER": "FIRE"}
const ELEM_WEAK := {"FIRE": "WATER", "AIR": "FIRE", "EARTH": "AIR", "WATER": "EARTH"}
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
var _map_btn: Button = null       # [SEM_SCROLL] "voltar ao mapa" no header (ao lado do 🔄), só com reino aberto
var kingdoms: Array = []          # GET /api/world
var open_kingdom := ""            # reino expandido (só um por vez)
var _pending_open_kingdom := ""   # [INCURSAO] reino a abrir na próxima exibição (vitória da Incursão)
var _pending_delve_report: Dictionary = {}   # [INCURSAO_FIM] relatório da run encerrada p/ exibir SOBRE o território
var _pending_after := {}          # resultado guardado durante o replay 3D (kingdom, kind, result) p/ o relatório
var warrior: Dictionary = {}      # /api/warrior (carteira + gate de nível)
var warrior_level := 1
var selected_element := "FIRE"    # picker de área de elemento
# detalhe do reino aberto (carregado sob demanda)
var quests: Array = []
var active_quests: Array = []
var global_active: Array = []     # [QUESTS_ATIVA_GLOBAL] missão ativa em QUALQUER reino (guard é global)
var zone_session: Dictionary = {}
var active_delve: Dictionary = {}   # [STUCK_FIX] /api/expedition/current — Incursão em andamento

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🌍 Mundo", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	scroll = ui.scroll
	# [SEM_SCROLL] botão "voltar ao mapa" no HEADER (à esquerda do 🔄), pequeno — não ocupa mais uma linha do corpo
	_map_btn = UiKit.icon_btn("🗺", _back_to_map)
	_map_btn.tooltip_text = "Voltar ao mapa"
	_map_btn.visible = false
	var hdr: HBoxContainer = ui.header
	hdr.add_child(_map_btn)
	if ui.refresh != null:
		hdr.move_child(_map_btn, (ui.refresh as Control).get_index())   # logo antes do refresh
	resized.connect(_layout_map)   # [MAPA_MUNDO] recalcula o tamanho do mapa quando a janela muda
	visibility_changed.connect(_on_world_shown)   # [MAPA_MUNDO] reentrar no Mundo volta pro MAPA
	await _refresh()

# [MAPA_MUNDO] O Shell cacheia as telas (não recria) → reentrar no Mundo via nav mostraria o último
# reino aberto. Quando o nó reaparece, reseto pro mapa. A navegação INTERNA (reino ↔ mapa, quests)
# NÃO esconde/mostra o nó, então não dispara isto.
# [INCURSAO] Outra tela pede pra reabrir um reino específico (ex.: vitória da Incursão volta pro território).
func request_open_kingdom(k: String, delve_report := {}) -> void:
	_pending_open_kingdom = k
	_pending_delve_report = delve_report   # [INCURSAO_FIM] {} = sem relatório; senão {kind, j, title?}

func _on_world_shown() -> void:
	if not is_visible_in_tree():
		return
	# vindo da vitória de uma Incursão → abre direto o reino de origem (em vez de resetar pro mapa)
	if _pending_open_kingdom != "":
		var k := _pending_open_kingdom
		_pending_open_kingdom = ""
		open_kingdom = k
		await _open(k)
		# [INCURSAO_FIM] run encerrada → mostra o relatório SOBRE o território já aberto
		if not _pending_delve_report.is_empty():
			var rep := _pending_delve_report
			_pending_delve_report = {}
			_show_delve_report(rep)
		return
	if open_kingdom != "":
		open_kingdom = ""
		_render()

# [INCURSAO_FIM] Relatório de uma Incursão ENCERRADA (vitória/extract/abandono/derrota), exibido sobre a
# tela do território (a tela da Incursão já saiu). report = {kind:"loot"|"defeat", j:{...}, title?}.
func _show_delve_report(report: Dictionary) -> void:
	var j: Dictionary = report["j"] if report.get("j") is Dictionary else {}
	if str(report.get("kind", "loot")) == "defeat":
		var mob := str(j.get("monsterName", "inimigo"))
		var log: Array = j.get("battleLog", []) if j.get("battleLog") is Array else []
		UiKit.show_battle_report(self, false, Lang.t("💀 Derrotado por %s!") % mob, _delve_step_rows(j, true), log)
	else:
		var title := str(report.get("title", ""))
		UiKit.show_battle_report(self, true, (title if title != "" else Lang.t("🔒 Loot garantido!")), _delve_loot_rows(j), [])

# Linhas do relatório de LOOT (extract/abandono): bronze/xp/recursos/itens sacados. [INCURSAO_FIM]
func _delve_loot_rows(j: Dictionary) -> Array:
	var rows: Array = []
	if int(j.get("bronzeBanked", 0)) > 0:
		rows.append(UiKit.kv_node("Bronze", UiKit.coin_box(int(j.get("bronzeBanked", 0)), 18)))
	if int(j.get("xpBanked", 0)) > 0:
		rows.append(UiKit.kv(Lang.t("Experiência"), "+%d XP" % int(j.get("xpBanked", 0))))
	if j.get("bankedResources") is Array:
		for d in j["bankedResources"]:
			if d is Dictionary:
				rows.append(UiKit.icon_text("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))], 12, UiKit.TEXT_DIM, 16))
	if int(j.get("keptItems", 0)) > 0:
		rows.append(UiKit.icon_text(Lang.t("🛡 %d item(ns) na mochila") % int(j.get("keptItems", 0)), 12, UiKit.TEXT_DIM, 16))
	if int(j.get("mailedItems", 0)) > 0:
		rows.append(UiKit.icon_text(Lang.t("📬 %d item(ns) no correio (mochila cheia)") % int(j.get("mailedItems", 0)), 12, UiKit.TEXT_DIM, 16))
	return rows

# Linhas do relatório de DERROTA (KO na run): ganhos do passo + aviso de loot perdido. [INCURSAO_FIM]
func _delve_step_rows(j: Dictionary, ko: bool) -> Array:
	var rows: Array = []
	if int(j.get("bronzeGained", 0)) > 0:
		rows.append(UiKit.kv_node("Bronze", UiKit.coin_box(int(j.get("bronzeGained", 0)), 18)))
	if int(j.get("xpGained", 0)) > 0:
		rows.append(UiKit.kv(Lang.t("Experiência"), "+%d XP" % int(j.get("xpGained", 0))))
	if j.get("drops") is Array:
		for d in j["drops"]:
			if d is Dictionary:
				rows.append(UiKit.icon_text("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))], 12, UiKit.TEXT_DIM, 16))
	if str(j.get("lootItemName", "")) != "":
		rows.append(UiKit.icon_text("🎁 " + str(j.get("lootItemName")), 12, UiKit.TEXT_DIM, 16))
	if ko:
		rows.append(UiKit.icon_text(Lang.t("☠ Você caiu — o loot não-sacado foi perdido. Cure-se no Templo."), 12, UiKit.ERR, 16))
	return rows

func _refresh() -> void:
	UiKit.show_loading(self)
	# [REDE_ENXUTA] Quando há reino aberto, o _open() logo abaixo JÁ busca /api/warrior — então
	# não pedimos o guerreiro AQUI (era um duplo-fetch). Sem reino aberto, incluímos /api/warrior
	# no batch p/ o header refletir a carteira (o _render usa `warrior`, e o _open não roda).
	var will_open := open_kingdom != ""
	# [QUESTS_ATIVA_GLOBAL] /active-quests sempre no batch → o mapa e qualquer reino sabem onde está
	# a missão pendente (o guard de "1 missão por vez" é global no backend).
	var paths := ["/api/world", "/api/expedition/current", "/api/world/active-quests"]
	if not will_open:
		paths.append("/api/warrior")
	# reinos + Incursão ativa + missão ativa global (+ guerreiro se não for reabrir reino) em PARALELO
	var rs = await Api.batch_get(paths)
	var rd = rs[1]
	active_delve = rd["json"] if (rd.get("ok") and rd.get("json") is Dictionary) else {}
	var rg = rs[2]
	global_active = rg["json"] if (rg.get("ok") and rg.get("json") is Array) else []
	if not will_open:
		var wr = rs[3]
		if wr.get("ok") and wr.get("json") is Dictionary:
			warrior = wr["json"]
			warrior_level = int(warrior.get("level", 1))
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	kingdoms = r["json"]
	# NÃO auto-abre nenhum reino — o usuário escolhe qual expandir (todos começam fechados).
	# Se já havia um aberto (refresh após uma ação), reabre ele pra atualizar os dados (e o /api/warrior).
	if will_open:
		await _open(open_kingdom)
	else:
		_render()

# Carrega o detalhe do reino (quests + zona ativa) e marca como aberto.
func _open(kingdom: String) -> void:
	if kingdom == "":
		return
	open_kingdom = kingdom
	UiKit.show_loading(self)
	# dispara tudo em PARALELO (independentes); inclui /api/warrior p/ o header refletir o gasto/XP na hora
	# (sem isso o topbar só atualizava no próximo _refresh → parecia "demorar" após quest/zona).
	# inclui /active-quests p/ manter o estado global fresco após coletar/iniciar (gating + banner)
	var paths := ["/api/warrior", "/api/world/%s/quests" % kingdom, "/api/world/%s/quests/active" % kingdom, "/api/zones/current", "/api/world/active-quests"]
	var rs = await Api.batch_get(paths)
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
		warrior_level = int(warrior.get("level", warrior_level))
	var rq = rs[1]
	quests = rq["json"] if (rq.get("ok") and rq.get("json") is Array) else []
	var ra = rs[2]
	active_quests = ra["json"] if (ra.get("ok") and ra.get("json") is Array) else []
	var rz = rs[3]
	zone_session = rz["json"] if (rz.get("ok") and rz.get("json") is Dictionary) else {}
	var rga = rs[4]
	global_active = rga["json"] if (rga.get("ok") and rga.get("json") is Array) else []
	_render()

# "tem tarefa ativa pra coletar" → bloqueia começar outra (espelha o guard GLOBAL do backend).
func _has_active_task() -> bool:
	if not global_active.is_empty():   # missão ativa em QUALQUER reino (guard global)
		return true
	if not active_quests.is_empty():
		return true
	if zone_session.get("active", false):
		return true
	return false

# Reino (nome amigável) onde está a missão ativa, ou "" se nenhuma.
func _kingdom_name(raw: String) -> String:
	for k in kingdoms:
		if k is Dictionary and str(k.get("kingdom", "")) == raw:
			return str(k.get("displayName", raw))
	return raw

# Texto do selo de bloqueio "conclua a tarefa ativa" — NOMEIA o reino da missão (intuitivo).
func _active_task_where() -> String:
	if not global_active.is_empty():
		return Lang.t("Conclua a missão em %s") % _kingdom_name(str(global_active[0].get("kingdom", "")))
	if zone_session.get("active", false):
		return Lang.t("Conclua a expedição ativa")
	return Lang.t("Conclua a tarefa ativa")

# [QUESTS_ATIVA_GLOBAL] Banner no topo apontando ONDE está a missão ativa (+ botão p/ ir lá).
# Só aparece quando a missão NÃO é do reino aberto (lá a seção "Quests Ativas" já a mostra).
func _active_quest_banner() -> void:
	if global_active.is_empty():
		return
	var q: Dictionary = global_active[0]
	var qk := str(q.get("kingdom", ""))
	if qk == open_kingdom and open_kingdom != "":
		return
	var kname := _kingdom_name(qk)
	var ready := bool(q.get("readyToCollect", false))
	var res := UiKit.card(UiKit.WARN)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	box.add_child(UiKit.icon_text("⚠ Missão ativa em %s" % kname, 15, UiKit.WARN, 20))
	box.add_child(UiKit.dim(Lang.t("%s — conclua ela antes de começar outra.") % str(q.get("displayName", "missão"))))
	var btn := UiKit.action((Lang.t("📍 Ir coletar em %s") % kname) if ready else (Lang.t("📍 Ir resolver em %s") % kname), func() -> void: await _open(qk))
	btn.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	box.add_child(btn)
	content.add_child(pc)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	pins = []
	map_holder = null
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	# [STUCK_FIX] Incursão em andamento → botão pra retomar/abandonar (a aba Delve saiu do nav,
	# então este é o caminho de volta pra uma run presa). Espelha o web "Continuar Incursão".
	if bool(active_delve.get("active", false)):
		content.add_child(UiKit.action("⚔ Continuar Incursão em andamento", func() -> void: open_screen.emit("Delve")))
	# [QUESTS_ATIVA_GLOBAL] aviso no topo apontando onde está a missão ativa (se for de outro reino)
	_active_quest_banner()
	# [SEM_SCROLL] o botão "voltar ao mapa" (no header) só aparece com um reino aberto
	if _map_btn != null:
		_map_btn.visible = open_kingdom != ""
	# [MAPA_MUNDO] open_kingdom == "" → mapa-múndi com pins; senão → detalhe do reino aberto.
	if open_kingdom == "":
		_render_map()
	else:
		_render_detail(open_kingdom)

# [SEM_SCROLL] volta do detalhe do reino pro mapa-múndi (botão do header).
func _back_to_map() -> void:
	if open_kingdom != "":
		_toggle(open_kingdom)

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
	# [QUEST_BADGE] "!" amarelo quando há daily disponível neste reino (hover: "Quest disponível").
	if bool(k.get("questAvailable", false)):
		row.add_child(_quest_badge())
	var nm := Label.new()
	nm.text = str(k.get("displayName", kid))
	nm.add_theme_font_size_override("font_size", 12)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.add_theme_color_override("font_outline_color", Color(0, 0, 0))   # contorno → lê sobre o mapa
	nm.add_theme_constant_override("outline_size", 4)
	nm.mouse_filter = Control.MOUSE_FILTER_IGNORE
	row.add_child(nm)
	var cg := str(k.get("controllingGuild", ""))
	if cg != "" and Icons.tex("guild") != null:   # [SEM_WEB_EMOJI] selo de guilda controladora = ícone, não 🛡
		var g := Icons.rect("guild", 14)
		g.mouse_filter = Control.MOUSE_FILTER_IGNORE
		g.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(g)
	pin.add_child(name_box)
	pin.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT:
			_toggle(kid))
	return pin

# [QUEST_BADGE] Marcador "!" AZUL de daily disponível (daily=azul). Usa o ícone PixelLab quest_alert_blue se
# existir, senão um "!" azul estilizado. MOUSE_FILTER_PASS p/ mostrar o tooltip sem bloquear o clique do pin.
func _quest_badge() -> Control:
	var tip := Lang.t("Quest disponível")
	if Icons.tex("quest_alert_blue") != null:   # [QUEST_BADGE] daily = "!" AZUL (consistente com o topbar)
		var r := Icons.rect("quest_alert_blue", 16)
		r.mouse_filter = Control.MOUSE_FILTER_PASS
		r.tooltip_text = tip
		r.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		return r
	var l := Label.new()
	l.text = "!"
	l.tooltip_text = tip
	l.mouse_filter = Control.MOUSE_FILTER_PASS
	l.add_theme_font_size_override("font_size", 14)
	l.add_theme_color_override("font_color", Color(0.40, 0.62, 1.0))
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	l.add_theme_constant_override("outline_size", 4)
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	return l

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
	# [SEM_SCROLL] "voltar ao mapa" virou um botão pequeno no HEADER (ver _ready) — não ocupa mais o corpo.
	# [SEM_WEB_EMOJI] título = ícone pixel-art do território (KINGDOM_ICON) + nome — nunca o emoji do backend.
	# [LEITURA] cabeçalho do reino num CARD (fundo sólido) — antes nome+lore ficavam soltos sobre o mapa, ilegíveis
	var hcard := UiKit.card(UiKit.GOLD_SOFT)
	var hbox: VBoxContainer = hcard[1]
	var head_row := HBoxContainer.new(); head_row.add_theme_constant_override("separation", 9)
	var ikey: String = KINGDOM_ICON.get(kingdom, "")
	if ikey != "" and Icons.tex(ikey) != null:
		var hic := Icons.rect(ikey, 28); hic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		head_row.add_child(hic)
	var head := Label.new()
	head.text = str(k.get("displayName", kingdom))
	head.add_theme_font_size_override("font_size", 20)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head_row.add_child(head)
	hbox.add_child(head_row)
	var cg := str(k.get("controllingGuild", ""))
	hbox.add_child(UiKit.dim(("🛡 " + cg) if cg != "" else "Neutro"))
	if bool(k.get("isMine", false)):
		hbox.add_child(UiKit.dim(Lang.t("Sua guilda: +%d%% XP · +%d%% bronze · +%d%% bônus") % [int(k.get("xpBonus", 0)), int(k.get("bronzeBonus", 0)), int(k.get("exclusiveBonus", 0))]))
	var lore_text := str(k.get("lore", ""))
	if lore_text != "":
		var lore_lbl := UiKit.dim(lore_text)
		lore_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		hbox.add_child(lore_lbl)
	content.add_child(hcard[0])
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
				if str(q.get("status", "")) == "LUNA_PENDING":
					# [LUNA_INTERRUPT] pendente → reabrir help/ignore (senão a quest TRAVA: coletar/abandonar são rejeitados)
					row.add_child(UiKit.small_btn("🐶 Luna", func() -> void: _show_luna_dialog(kingdom, qid)))
				elif bool(q.get("readyToCollect", false)):
					if bool(q.get("interactive", false)) and q.get("dialog") is Dictionary:
						var dlg: Dictionary = q["dialog"]   # interativa: re-abre a escolha
						row.add_child(UiKit.small_btn("▶ Continuar missão", func() -> void: _show_quest_dialog(kingdom, qid, dlg)))
					else:
						row.add_child(UiKit.small_btn("▶ Continuar missão", _collect_quest.bind(kingdom, qid)))
				else:
					var tl := Label.new()
					tl.text = "%dm" % int(q.get("secondsRemaining", 0) / 60)
					tl.add_theme_font_size_override("font_size", 12)
					tl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
					row.add_child(tl)
				row.add_child(UiKit.small_btn("✖ Abandonar missão", _abandon_quest.bind(kingdom, qid), true))
				box.add_child(row)
	# [UI_TRABALHO] Training Hall MOVIDO p/ a tela de Trabalho (Work.gd) — não fica mais no Mundo.
	# DAILY QUESTS
	if not quests.is_empty():
		box.add_child(UiKit.section_with_icon("quest_alert_blue", "Daily Quests"))   # [QUEST_BADGE] "!" azul (daily), não a caixa do daily-reward
		# [SEM_SCROLL] quests em GRID 2-col (era 1 card por linha → encurta bastante)
		box.add_child(UiKit.grid(self, quests, func(q): return _quest_card(kingdom, q) if q is Dictionary else null, false, 280, 2))
	# Zonas de coleta / caça
	if ZONES.has(kingdom):
		box.add_child(UiKit.section("⚗ Áreas de Elemento"))
		box.add_child(_element_picker())
		box.add_child(UiKit.section("⚔ Zonas" if kingdom == "COMBAT" else "🌍 Zonas"))
		# [SEM_SCROLL] 3 zonas em GRID 3-col → 1 linha
		box.add_child(UiKit.grid(self, ZONES[kingdom], func(z): return _zone_card(kingdom, z), false, 200, 3))

# [CARD_BOTAO] Card de quest CLICÁVEL INTEIRO (o card é o botão — sem botão de texto embaixo).
# Cabeçalho: [pergaminho] nome (expande) [recompensa de bronze] [⭐XP] [⚡custo]. Bloqueado (feito/
# sem estamina/tarefa ativa) = card apagado + selo do motivo, sem clique.
func _quest_card(kingdom: String, q: Dictionary) -> PanelContainer:
	var done := bool(q.get("doneToday", false))
	var busy_task := _has_active_task()
	var can_start := bool(q.get("canStart", false))
	var enabled := not done and not busy_task and can_start
	# [QUESTS_ICONE] tipo da quest pela chance de combate: ⚔ Combate (alta) vs 🧭 Exploração (baixa)
	var mc := int(q.get("monsterChance", 0))
	var is_combat := mc >= 50
	var t_icon := "node_combat" if is_combat else "node_event"
	var t_emoji := "⚔" if is_combat else "🧭"
	var t_label := "Combate" if is_combat else "Exploração"
	var t_col := Color(0.94, 0.45, 0.40) if is_combat else Color(0.45, 0.78, 0.72)
	var on_click := func() -> void: _start_quest(kingdom, str(q.get("id", "")))
	var res := UiKit.clickable_card(UiKit.OK if done else UiKit.BRONZE, on_click, enabled, "%s · %d%% de chance de combate" % [Lang.t(t_label), mc])
	var vb: VBoxContainer = res[1]
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	if Icons.tex(t_icon) != null:
		var ir := Icons.rect(t_icon, 26); ir.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		top.add_child(ir)
	else:
		var el := Label.new(); el.text = t_emoji; el.add_theme_font_size_override("font_size", 22)
		el.size_flags_vertical = Control.SIZE_SHRINK_CENTER; top.add_child(el)
	var nm := Label.new(); nm.text = str(q.get("displayName", "?")); nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.OK if done else UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	# [MOEDA] recompensa de bronze (ícone pixel-art) + ⭐XP + ⚡custo como chips compactos
	top.add_child(UiKit.coin_box(int(q.get("bronzeReward", 0)), 14))
	var xp := int(q.get("expReward", 0))
	if xp > 0:
		top.add_child(_mini_chip("star", str(xp), UiKit.GOLD_SOFT, "⭐"))
	var stam := int(q.get("staminaCost", 0))
	if stam > 0:
		top.add_child(_mini_chip("stamina", str(stam), UiKit.WARN, "⚡"))
	vb.add_child(top)
	# [QUESTS_ICONE] rótulo de tipo (cor) — deixa claro combate vs exploração ANTES de clicar
	var tag := Label.new()
	tag.text = Lang.t(t_label)   # [SEM_WEB_EMOJI] sem emoji — o ícone de tipo já está no cabeçalho
	tag.add_theme_font_size_override("font_size", 11)
	tag.add_theme_color_override("font_color", t_col)
	vb.add_child(tag)
	var flavor := str(q.get("flavor", ""))
	if flavor != "":
		vb.add_child(UiKit.dim(flavor))
	if done:
		vb.add_child(_lock_seal("✔ Feito hoje"))
	elif busy_task:
		vb.add_child(_lock_seal(_active_task_where()))   # [QUESTS_ATIVA_GLOBAL] nomeia o reino da missão
	elif not can_start:
		vb.add_child(_lock_seal("Sem estamina"))
	return res[0]

# Chip compacto [ícone][número] (recompensa/custo no cabeçalho do card). Fallback no emoji.
func _mini_chip(icon_key: String, text: String, col: Color, emoji := "") -> Control:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 2)
	if Icons.tex(icon_key) != null:
		var ic := Icons.rect(icon_key, 14); ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(ic)
		var l := Label.new(); l.text = text
		l.add_theme_font_size_override("font_size", 12); l.add_theme_color_override("font_color", col)
		l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(l)
	else:
		var l := Label.new(); l.text = "%s%s" % [emoji, text]
		l.add_theme_font_size_override("font_size", 12); l.add_theme_color_override("font_color", col)
		h.add_child(l)
	return h

# Selo de motivo de bloqueio (texto pequeno em vez de botão desabilitado). Emoji-marcador → ícone.
func _lock_seal(text: String) -> Control:
	return UiKit.icon_text(text, 12, UiKit.TEXT_DIM, 16)

# [CARD_BOTAO] Picker de elemento como toggles SÓ DE ÍCONE (~44px). Ativo destacado (opaco); inativo
# apagado. Fallback no emoji se o ícone elem_* não foi importado.
# [ELEMENTOS] Áreas de elemento: ícone GRANDE sem moldura (o ícone É o botão). Ativo = opaco;
# inativo = apagado (acende no hover). Tooltip = nome + vantagem/fraqueza da roda RPS.
func _element_picker() -> HBoxContainer:
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 18)
	for e in ELEMENTS:
		row.add_child(_element_btn(str(e[0]), str(e[1])))
	return row

func _element_btn(code: String, label: String) -> Control:
	var active := code == selected_element
	var b := Button.new()
	b.flat = true
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(60, 60)
	b.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var empty := StyleBoxEmpty.new()
	for s in ["normal", "hover", "pressed", "focus"]:
		b.add_theme_stylebox_override(s, empty)
	b.tooltip_text = _element_tooltip(code)
	if Icons.set_icon(b, Icons.elem_anim_key(code)):   # [ELEMENTOS] GIF da essência (anima no hover), padronizado
		b.expand_icon = true
		b.icon_alignment = HORIZONTAL_ALIGNMENT_CENTER
		b.add_theme_constant_override("icon_max_width", 52)   # ícone bem maior (era 30)
		b.text = ""
	else:
		var parts := label.split(" ")
		b.text = str(parts[0])   # fallback no emoji do elemento
		b.add_theme_font_size_override("font_size", 40)
	b.modulate = Color(1, 1, 1, 1.0) if active else Color(1, 1, 1, 0.42)   # ativo opaco / inativo apagado
	if not active:   # acende no hover p/ dar feedback
		b.mouse_entered.connect(func() -> void:
			if is_instance_valid(b):
				b.modulate = Color(1, 1, 1, 0.85))
		b.mouse_exited.connect(func() -> void:
			if is_instance_valid(b):
				b.modulate = Color(1, 1, 1, 0.42))
	b.pressed.connect(_select_element.bind(code))
	return b

# Nome traduzido do elemento (a partir do rótulo "🔥 Fire" → Lang.t("Fire")).
func _elem_name(code: String) -> String:
	for e in ELEMENTS:
		if str(e[0]) == code:
			var parts := str(e[1]).split(" ")
			return Lang.t(parts[parts.size() - 1])
	return code

# Tooltip do elemento: nome + vantagem (×1.25) e fraqueza (×0.75) pela roda RPS. [ELEMENTOS]
func _element_tooltip(code: String) -> String:
	var nm := _elem_name(code)
	var strong := _elem_name(str(ELEM_BEATS.get(code, "")))
	var weak := _elem_name(str(ELEM_WEAK.get(code, "")))
	return "%s\n%s +25%% contra %s\n%s -25%% contra %s" % [nm, Lang.t("Vantagem:"), strong, Lang.t("Fraco:"), weak]

# [PVP_FLAG] Tooltip de RISCO da zona — o que o jogador pode PERDER por tier.
func _zone_risk_tooltip(tier: String) -> String:
	match tier:
		"SAFE":
			return Lang.t("🟢 Seguro\nSó PvE — você não perde nada.")
		"PVP":
			return Lang.t("🟡 PvP — exposto por 1h\nSe te saquearem: −10% bronze + XP.\nRecursos e itens ficam seguros.")
		"HIGH_RISK":
			return Lang.t("🔴 Alto risco — exposto por 1h\nSe te saquearem: −50% recursos, −15% bronze, XP\ne 35% de roubar 1 item exposto.\nItens no Baú/Templo ficam a salvo.")
	return ""

# [icon_key, emoji, verbo] da ação de uma zona (caçar/minerar/garimpar/pescar).
func _zone_action(kingdom: String, skill: String) -> Array:
	if kingdom == "COMBAT":
		return ["node_combat", "⚔", Lang.t("Caçar")]
	elif skill == "MINING":
		return ["act_mine", "⛏", Lang.t("Minerar")]
	elif skill == "GARIMPO":
		return ["act_pan", "🔎", Lang.t("Garimpar")]
	return ["fish", "🎣", Lang.t("Pescar")]

# [CARD_BOTAO] Card de zona CLICÁVEL INTEIRO. Cabeçalho: [ícone da ação] nome (expande) [tag PvP/Seguro]
# [⚡custo]. Bloqueado (nível/tarefa ativa/KO) = card apagado + selo do motivo, sem clique.
func _zone_card(kingdom: String, z: Array) -> PanelContainer:
	var zname := str(z[0]); var tier := str(z[1]); var skill := str(z[2]); var min_lv := int(z[3])
	var locked := warrior_level < min_lv
	var ko := kingdom == "COMBAT" and int(warrior.get("hpPercent", 100)) <= 0
	var busy_task := _has_active_task()
	var enabled := not locked and not ko and not busy_task
	var col: Color = TIER_COL.get(tier, Color(0.6, 0.6, 0.6))
	var act := _zone_action(kingdom, skill)
	var on_click := func() -> void: _start_zone_delve(kingdom, tier, skill)
	var tip := _zone_risk_tooltip(tier)   # [PVP_FLAG] hover mostra o que você pode PERDER nessa zona
	var res := UiKit.clickable_card(col, on_click, enabled, tip)
	res[0].tooltip_text = tip   # também no card bloqueado (sem overlay de clique)
	var vb: VBoxContainer = res[1]
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 10)
	if Icons.tex(str(act[0])) != null:
		var ir := Icons.rect(str(act[0]), 30); ir.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		top.add_child(ir)
	var nm := Label.new(); nm.text = zname
	nm.add_theme_color_override("font_color", col if enabled else UiKit.TEXT_DIM); nm.add_theme_font_size_override("font_size", 15)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var tag := Label.new()
	tag.text = ("PvP" if tier != "SAFE" else Lang.t("Seguro"))   # [SEM_WEB_EMOJI] tier já vem pela cor do nome
	tag.add_theme_color_override("font_color", UiKit.TEXT_DIM); tag.add_theme_font_size_override("font_size", 11)
	top.add_child(tag)
	if enabled:
		top.add_child(_mini_chip("stamina", str(maxi(5, ZONE_DURATION / 2)), UiKit.WARN, "⚡"))
	vb.add_child(top)
	if locked:
		vb.add_child(_lock_seal("🔒 " + Lang.t("Requer Nv %d") % min_lv))
	elif ko:
		vb.add_child(_lock_seal("❤ " + Lang.t("Inconsciente — cure no Templo")))
	elif busy_task:
		vb.add_child(_lock_seal(_active_task_where()))   # [QUESTS_ATIVA_GLOBAL] nomeia o reino da missão
	return res[0]

# [INCURSAO] Inicia uma Incursão ZONE a partir da zona do reino (🟢/🟡/🔴 → tier 1/2/3) e abre a tela da run.
func _start_zone_delve(kingdom: String, tier: String, skill: String, confirmed := false) -> void:
	if busy: return
	if kingdom == "COMBAT" and not confirmed:   # [HP_WARN] caça PvE → avisa se entrar ferido (HP < 50%)
		UiKit.confirm_danger(self, warrior, 0, func() -> void: _start_zone_delve(kingdom, tier, skill, true))
		return
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

func _collect_quest(kingdom: String, quest_id: int, option_id := "", confirmed := false) -> void:
	if busy: return
	# [PERIGO] avisa antes de resolver FERIDO (a quest pode cair em combate). confirmed=true pula o aviso
	# (opção pacífica → sem combate; ou já confirmado). confirm_danger só mostra o popup se HP<50%.
	if not confirmed:
		UiKit.confirm_danger(self, warrior, 0, func() -> void: _collect_quest(kingdom, quest_id, option_id, true))
		return
	busy = true
	var r = await Api.quest_collect(kingdom, quest_id, option_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _open(kingdom); return
	var j: Dictionary = r["json"]
	if bool(j.get("lunaPending", false)):   # a Luna interrompeu → ajudar ou terminar
		_show_luna_dialog(kingdom, quest_id)
		return
	if j.get("roll") is Dictionary:   # [DADO] teste de atributo (d20) → anima o resultado antes de prosseguir
		var rbe = j.get("battleEvents")
		await _show_dice_dialog(j["roll"], rbe is Array and (rbe as Array).size() >= 2)
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		# encontrou monstro → replay 3D por cima; guarda o RESULTADO p/ o relatório pós-replay [BATTLE_REPORT]
		_pending_after = {"kingdom": kingdom, "kind": "quest", "result": j}
		request_battle.emit({"events": be, "scene": str(j.get("scene", "")), "won": bool(j.get("monsterDefeated", false)), "enemy": str(j.get("monsterName", ""))})
	else:
		await _open(kingdom)   # refresca a lista; status some aqui → desfecho vai no relatório
		_show_quest_report(j)

# [DADO] Dialog do teste de atributo (d20): o número ROLA (cicla) e trava no valor REAL, mostra a conta
# (rolado + mod vs CD), SUCESSO/FALHA e uma explicação. Bloqueante — await até clicar Continuar.
func _show_dice_dialog(roll: Dictionary, battle_follows: bool) -> void:
	var rolled := int(roll.get("rolled", 1))
	var mod := int(roll.get("mod", 0))
	var dc := int(roll.get("dc", 0))
	var passed := bool(roll.get("passed", false))
	var attr := str(roll.get("attr", ""))
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.74)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD)
	var panel: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	panel.custom_minimum_size = Vector2(360, 0)
	box.add_theme_constant_override("separation", 10)
	center.add_child(panel)
	var title := Label.new()
	title.text = Lang.t("Teste de %s") % attr
	title.add_theme_font_size_override("font_size", 17); title.add_theme_color_override("font_color", UiKit.GOLD)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(title)
	var die := Label.new()
	die.text = "?"
	die.add_theme_font_size_override("font_size", 56)
	die.add_theme_color_override("font_color", UiKit.TEXT)
	die.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	die.custom_minimum_size = Vector2(0, 78)
	box.add_child(die)
	var breakdown := Label.new()
	breakdown.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	breakdown.add_theme_font_size_override("font_size", 14); breakdown.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	breakdown.visible = false
	box.add_child(breakdown)
	var result := Label.new()
	result.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	result.add_theme_font_size_override("font_size", 22); result.visible = false
	box.add_child(result)
	var expl := UiKit.dim("")
	expl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	expl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	expl.custom_minimum_size = Vector2(320, 0); expl.visible = false
	box.add_child(expl)
	var cont := UiKit.action(Lang.t("Continuar"), func() -> void: pass)
	cont.visible = false
	box.add_child(cont)
	# ── rola: cicla números aleatórios, depois trava no valor real ──
	for i in 16:
		die.text = str((randi() % 20) + 1)
		await get_tree().create_timer(0.045).timeout
	die.text = str(rolled)
	die.add_theme_color_override("font_color", UiKit.OK if passed else UiKit.ERR)
	breakdown.text = "%d %+d %s = %d   vs   CD %d" % [rolled, mod, attr, rolled + mod, dc]
	breakdown.visible = true
	result.text = Lang.t("SUCESSO") if passed else Lang.t("FALHA")
	result.add_theme_color_override("font_color", UiKit.OK if passed else UiKit.ERR)
	result.visible = true
	if passed:
		expl.text = Lang.t("Você superou o teste.")
	elif battle_follows:
		expl.text = Lang.t("Falhou — o inimigo te alcançou. Prepare-se para lutar!")
	else:
		expl.text = Lang.t("Falhou — o desfecho não foi o esperado.")
	expl.visible = true
	cont.visible = true
	await cont.pressed
	if is_instance_valid(overlay):
		overlay.queue_free()

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
	# [QUESTS_ICONE] cada opção carrega o "kind" (fight/check/peaceful) → selo de tipo no botão
	var opts: Array = []
	for o in dialog.get("options", []):
		if o is Dictionary:
			opts.append([str(o.get("kind", "")), str(o.get("hint", "")), str(o.get("label", "?")), str(o.get("id", ""))])
	_kind_choice_dialog(str(dialog.get("intro", "")), opts, func(opt_id) -> void:
		# [PERIGO] opção PACÍFICA não cai em combate → pula o aviso de HP (confirmed=true).
		# fight/check → confirmed=false → confirm_danger avisa se ferido antes de resolver.
		var kind := ""
		for o in opts:
			if str(o[3]) == str(opt_id):
				kind = str(o[0]); break
		await _collect_quest(kingdom, quest_id, str(opt_id), kind == "peaceful"))

# [QUESTS_ICONE] Diálogo de quest com SELO DE TIPO por opção (combate/roll/pacífico). Espelha
# _choice_dialog mas usa UiKit.quest_option_button. options = [[kind, hint, label, value], …].
func _kind_choice_dialog(title_text: String, options: Array, cb: Callable) -> void:
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
		var val = opt[3]
		var b := UiKit.quest_option_button(str(opt[0]), str(opt[1]), str(opt[2]), func() -> void:
			overlay.queue_free()
			cb.call(val))
		vb.add_child(b)

# A Luna apareceu: ajudar (abre mão da recompensa) ou terminar a missão. [CARD_BOTAO] botões de ícone.
func _show_luna_dialog(kingdom: String, quest_id: int) -> void:
	_icon_choice_dialog(Lang.t("🐶 Uma cãozinha (Luna) apareceu e interrompeu a missão! O que fazer?"),
		[["pet", "🐶", Lang.t("Ajudar"), "help"], ["node_event", "📜", Lang.t("Terminar"), "ignore"]],
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
				_show_luna_result(jr))

# [LUNA_INTERRUPT] Desfecho da decisão da Luna: mostra a NARRATIVA dela (afeição/companheira) num
# modal de texto — antes caía no toast genérico "Quest concluída" e o texto sumia. Se "terminar"
# retomou uma quest de COMBATE, faz o replay + relatório (como _collect_quest).
func _show_luna_result(jr: Dictionary) -> void:
	var be = jr.get("battleEvents")
	if be is Array and be.size() >= 2:
		_pending_after = {"kingdom": open_kingdom, "kind": "quest", "result": jr}
		request_battle.emit({"events": be, "scene": str(jr.get("scene", "")), "won": bool(jr.get("monsterDefeated", false)), "enemy": str(jr.get("monsterName", ""))})
		return
	var parts: Array = []
	var pet := str(jr.get("acquiredPet", ""))
	if pet != "" and pet != "false":
		parts.append(Lang.t("🐶 Nova companheira: %s!") % pet)
	var narr := str(jr.get("narrative", "")).strip_edges()
	if narr != "":
		parts.append(narr)
	var bronze := int(jr.get("bronzeEarned", 0))
	var xp := int(jr.get("xpEarned", 0))
	if bronze > 0 or xp > 0:
		parts.append(Lang.t("Recompensa: %d bronze · +%d XP") % [bronze, xp])
	if parts.is_empty():
		parts.append(Lang.t("✅ Missão concluída."))
	_show_result("\n\n".join(parts))

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
		b.custom_minimum_size = Vector2(380, 40)
		vb.add_child(b)

# [CARD_BOTAO] Diálogo de escolha ICON-PRIMARY: título + botões de ícone em linha. Para escolhas
# binárias (Encarar/Fugir, Ajudar/Terminar). options = [[icon_key, emoji, label, value], …].
func _icon_choice_dialog(title_text: String, options: Array, cb: Callable) -> void:
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
	vb.add_theme_constant_override("separation", 14)
	center.add_child(panel)
	var lbl := UiKit.body(title_text)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.custom_minimum_size = Vector2(380, 0)
	vb.add_child(lbl)
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 16)
	vb.add_child(row)
	for opt in options:
		var val = opt[3]
		var b := UiKit.icon_choice_btn(str(opt[0]), str(opt[1]), str(opt[2]), func() -> void:
			overlay.queue_free()
			cb.call(val))
		row.add_child(b)

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
	_icon_choice_dialog(intro, [["node_combat", "⚔", Lang.t("Encarar"), "fight"], ["act_flee", "🏃", Lang.t("Fugir"), "flee"]], func(choice) -> void:
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
	var j: Dictionary = r["json"]
	# [ZONA_CHEFE] Feedback do roll de FUGA antes de prosseguir: sucesso (escapou) vs falha (caiu na
	# luta). Antes ia direto pro replay/relatório sem dizer o resultado do dado. Inferência: se a resposta
	# da fuga traz battleEvents, a fuga FALHOU (combate forçado); senão, escapou.
	if choice == "flee":
		var be = j.get("battleEvents")
		var failed: bool = be is Array and be.size() >= 2
		UiKit.flash(status, Lang.t("🏃 Falhou a fuga — o chefe te alcançou!") if failed else Lang.t("🏃 Fuga bem-sucedida!"), 1 if failed else 0)
		await get_tree().create_timer(0.9).timeout   # deixa a mensagem aparecer antes do replay/relatório
	await _handle_zone_result(activity_id, j)

func _cancel_zone(activity_id: int) -> void:
	if busy: return
	busy = true
	await Api.zone_cancel(activity_id)
	busy = false
	if open_kingdom != "":
		await _open(open_kingdom)
	UiKit.flash(status, "Expedição cancelada.", 0)

# ── [TOAST] Desfecho SIMPLES (coleta/loot sem batalha) → toast com chips, sem clique de OK ───────────
# [DESFECHO] Label da NARRATIVA do desfecho (texto da história, com wrap) — usado no modal sem-combate
# e no relatório de combate (win/lose).
func _narr_label(narr: String) -> Label:
	var nl := Label.new()
	nl.text = narr
	nl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	nl.custom_minimum_size = Vector2(440, 0)
	nl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nl.add_theme_color_override("font_color", UiKit.TEXT)
	return nl

# [DESFECHO] Modal de desfecho da opção SEM combate: a HISTÓRIA (narrativa) + a recompensa + OK.
# Antes caía no toast genérico e o texto da escolha sumia → "só escolher a opção e boa". [QUESTS_INTERATIVAS]
func _show_quest_outcome(j: Dictionary) -> void:
	var narr := str(j.get("narrative", "")).strip_edges()
	if narr == "":
		_quest_reward_toast(j)   # sem narrativa → toast simples (fallback)
		return
	var rows: Array = []
	rows.append(_narr_label(narr))
	var bronze := int(j.get("bronzeEarned", 0))
	if bronze > 0: rows.append(UiKit.kv_node("Recompensa", UiKit.coin_box(bronze, 18)))
	var xp := int(j.get("xpEarned", 0))
	if xp > 0: rows.append(UiKit.kv("⭐ Experiência", "+%d XP" % xp))
	if j.get("droppedItem") is Dictionary:
		rows.append(UiKit.dim("🎁 " + str(j["droppedItem"].get("name", "item"))))
	var pet0 := str(j.get("acquiredPet", ""))
	if pet0 != "" and pet0 != "false":
		rows.append(UiKit.dim(Lang.t("🐶 Nova companheira: %s!") % pet0))
	UiKit.show_battle_report(self, true, Lang.t("📜 Desfecho"), rows, [])

# [AVISO_QUEST] Host do toast = o Shell PERSISTENTE, não a tela: ao concluir a quest o estado muda
# (XP/bronze/level) e a tela re-renderiza/é trocada — se o toast fosse filho dela, sumia antes de aparecer.
func _toast_host():
	return Shell.current if Shell.current != null else self

func _quest_reward_toast(r: Dictionary) -> void:
	var pet := str(r.get("acquiredPet", ""))
	if pet != "" and pet != "false":
		UiKit.reward_toast(_toast_host(), Lang.t("🎁 Novo companheiro: %s!") % pet, [])
		return
	var chips: Array = []
	var bronze := int(r.get("bronzeEarned", 0))
	if bronze > 0: chips.append(UiKit.coin_box(bronze, 16))
	var xp := int(r.get("xpEarned", 0))
	if xp > 0: chips.append(["star", "+%d XP" % xp])
	if r.get("droppedItem") is Dictionary:
		chips.append(["gift", str(r["droppedItem"].get("name", "item"))])
	UiKit.reward_toast(_toast_host(), Lang.t("✅ Quest concluída!"), chips)

func _zone_reward_toast(r: Dictionary) -> void:
	var chips: Array = []
	if str(r.get("lootItemName", "")) != "":
		chips.append(["gift", str(r.get("lootItemName"))])
	if r.get("drops") is Array:
		for d in r["drops"]:
			if d is Dictionary:
				chips.append(["package", "%s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))]])
	var bronze := int(r.get("bronzeGained", 0))
	if bronze > 0: chips.append(UiKit.coin_box(bronze, 16))
	var xp := int(r.get("xpGained", 0))
	if xp > 0: chips.append(["star", "+%d XP" % xp])
	UiKit.reward_toast(_toast_host(), Lang.t("✅ Expedição concluída!"), chips)

# ── Relatório de batalha (estilo da Torre): card win/loss + recompensas + log colapsável. [BATTLE_REPORT]
# Combate → relatório completo; sem combate (coleta pura/pet) → modal de texto simples.
func _show_quest_report(j: Dictionary) -> void:
	var monster := bool(j.get("monsterEncountered", false))
	# [AVISO_QUEST] banner no topo a CADA quest CONCLUÍDA (persistente no Shell) — exceto derrota em combate
	if not monster or bool(j.get("monsterDefeated", false)):
		_quest_reward_toast(j)
	if not monster:
		var narr := str(j.get("narrative", "")).strip_edges()
		if narr != "":
			_show_quest_outcome(j)   # narrativa → ALÉM do toast, modal com a HISTÓRIA + recompensa
		return
	var won := bool(j.get("monsterDefeated", false))
	var mob := str(j.get("monsterName", "inimigo"))
	var title := (Lang.t("⚔ %s derrotado!") % mob) if won else (Lang.t("💀 Derrotado por %s — sem recompensa.") % mob)
	var rows: Array = []
	var narr := str(j.get("narrative", "")).strip_edges()   # [DESFECHO] história do desfecho de combate (win/lose)
	if narr != "":
		rows.append(_narr_label(narr))
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
		_zone_reward_toast(j)   # [TOAST] expedição sem combate → toast (sem clique)
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
