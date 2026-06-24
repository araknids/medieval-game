extends Control
# ── Tela ARENA (PvP por ranking) ───────────────────────────────────────────────
# [ARENA_ESCOLHA] Estilo Shakes & Fidget: clicar em "Lutar" abre um POPUP com 3 OPONENTES
# (GET /api/arena/opponents), cada um com stats. Escolher um = duelo (POST /api/arena/fight
# {opponentId}) → replay 3D. Ranking abaixo. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back
signal request_battle(data)   # pede ao App o replay 3D (overlay) [MIGRACAO_GODOT]

const Icons := preload("res://ui/Icons.gd")
const STAMINA_COST := 25
const PAGE_SIZE := 20         # [PAGINACAO] ranking paginado (offset no backend)
const MAX_REROLLS := 3        # [ARENA_ESCOLHA] trocas de oponente por abertura do popup

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var rank: Array = []          # cache do ranking (página atual)
var page := 0                 # [PAGINACAO] página do ranking
var w: Dictionary = {}        # warrior (estamina / nome destacado no rank)
var last_result: Dictionary = {}   # resultado do último duelo (mostrado em texto)
var opponents: Array = []          # [ARENA_ESCOLHA] os 3 oponentes oferecidos
var your_power := 0                # poder do jogador (vem do backend; não usado na UI agora)
var _offer_max_hp := 1             # maior HP entre os 3 oponentes (escala da barra de vida)
var reroll_count := 0              # trocas usadas nesta abertura do popup
var _picker: Control = null        # overlay do popup de escolha (1 por vez)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "⚔ Arena", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_BATTLE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

# reoffer=true → re-sorteia os 3 oponentes. false → só paginar o rank.
func _refresh(reoffer := true) -> void:
	_close_picker()
	UiKit.show_loading(self)
	var paths := ["/api/warrior", "/api/arena/rank?page=%d" % page]
	if reoffer:
		paths.append("/api/arena/opponents")
	var rs = await Api.batch_get(paths)
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		w = rw["json"]
	var rr = rs[1]
	if not (rr.get("ok") and rr.get("json") is Array):
		UiKit.show_error(status, rr)
		return
	rank = rr["json"]
	if reoffer:
		_apply_opponents(rs[2])
	_render()

func _apply_opponents(ro) -> void:
	if ro is Dictionary and ro.get("ok") and ro.get("json") is Dictionary:
		var j: Dictionary = ro["json"]
		opponents = j.get("opponents", []) if j.get("opponents") is Array else []
		your_power = int(j.get("yourPower", your_power))

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, w)
	# ── Painel de luta: status + botão que abre o popup ──
	var stamina := int(w.get("stamina", 100))
	var ko := bool(w.get("isKnockedOut", false))
	var no_stamina := stamina < STAMINA_COST
	var fights := int(w.get("arenaFightsToday", 0))
	var limit := int(w.get("arenaFightLimit", 5))
	var at_limit := fights >= limit
	content.add_child(UiKit.section("Entrar em batalha"))
	content.add_child(UiKit.kv("Estamina", "%d/100" % stamina, UiKit.WARN if no_stamina else UiKit.TEXT))
	content.add_child(UiKit.kv("Lutas hoje", "%d/%d" % [fights, limit], UiKit.WARN if at_limit else UiKit.TEXT))
	content.add_child(UiKit.dim(Lang.t("Custo ⚡%d por luta · Vitória: +25 rank, ~200 bronze.") % STAMINA_COST))
	if ko:
		var b := UiKit.action_big("💀 Nocauteado", Callable()); b.disabled = true
		content.add_child(b)
	elif at_limit:
		var b := UiKit.action_big(Lang.t("Limite diário (%d/%d)") % [fights, limit], Callable()); b.disabled = true
		content.add_child(b)
		content.add_child(UiKit.dim("Reseta à meia-noite UTC. VIP tem mais lutas por dia."))
	elif no_stamina:
		var b := UiKit.action_big("⚡ Sem estamina", Callable()); b.disabled = true
		content.add_child(b)
	else:
		content.add_child(UiKit.action_big(Lang.t("⚔ Lutar · ⚡%d") % STAMINA_COST, _open_picker))
	# ── resultado do último duelo (texto, fallback sem replay) ──
	if not last_result.is_empty():
		content.add_child(UiKit.spacer(8))
		content.add_child(_result_box(last_result))
	# ── ranking ──
	content.add_child(UiKit.section_paged("🏆 Ranking", page, rank.size() >= PAGE_SIZE, _page_prev, _page_next))
	if rank.is_empty():
		if page > 0:
			content.add_child(UiKit.dim("Fim do ranking."))
		else:
			content.add_child(UiKit.empty("Nenhum jogador ainda", "Seja o primeiro a lutar na arena"))
	else:
		var my_name := str(w.get("name", ""))
		var base := page * PAGE_SIZE
		var i := 0
		for r in rank:
			if r is Dictionary:
				i += 1
				content.add_child(_rank_row(base + i, r, str(r.get("warriorName", "")) == my_name))

# bloqueado de lutar? (KO / sem estamina / limite diário)
func _blocked() -> bool:
	var stamina := int(w.get("stamina", 100))
	return bool(w.get("isKnockedOut", false)) or stamina < STAMINA_COST \
		or int(w.get("arenaFightsToday", 0)) >= int(w.get("arenaFightLimit", 5))

# ── [ARENA_ESCOLHA] Abre o POPUP de escolha (re-sorteia se ainda não tem oponentes) ──
func _open_picker() -> void:
	if busy or _blocked(): return
	if opponents.is_empty():
		busy = true
		UiKit.show_loading(self)
		var rs = await Api.batch_get(["/api/arena/opponents"])
		busy = false
		UiKit.hide_loading()
		_apply_opponents(rs[0])
	reroll_count = 0
	_show_picker()

func _show_picker() -> void:
	_close_picker()
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	overlay.gui_input.connect(func(ev: InputEvent) -> void:   # clicar FORA do painel fecha
		if ev is InputEventMouseButton and ev.pressed:
			_close_picker())
	add_child(overlay)
	_picker = overlay
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	box.add_theme_constant_override("separation", 8)
	# cabeçalho: título · lutas hoje · trocar · fechar
	var fights := int(w.get("arenaFightsToday", 0))
	var limit := int(w.get("arenaFightLimit", 5))
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 10)
	var ttl := Label.new(); ttl.text = Lang.t("Escolha seu oponente")
	ttl.add_theme_font_size_override("font_size", 18); ttl.add_theme_color_override("font_color", UiKit.GOLD)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(ttl)
	var ftl := Label.new(); ftl.text = Lang.t("Lutas hoje %d/%d") % [fights, limit]
	ftl.autowrap_mode = TextServer.AUTOWRAP_OFF
	ftl.add_theme_font_size_override("font_size", 12); ftl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	ftl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(ftl)
	var rb := UiKit.small_btn(Lang.t("↻ Trocar"), _reroll)
	if reroll_count >= MAX_REROLLS:
		rb.disabled = true; rb.tooltip_text = Lang.t("Sem mais trocas nesta visita")
	head.add_child(rb)
	head.add_child(UiKit.small_btn("✖", _close_picker))
	box.add_child(head)
	# escala da barra de vida = maior HP dos 3 (pra comparar VIDA, não o resultado)
	_offer_max_hp = 1
	for o in opponents:
		if o is Dictionary:
			_offer_max_hp = maxi(_offer_max_hp, int(o.get("hp", 0)))
	# 3 cards lado a lado
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10)
	for o in opponents:
		if o is Dictionary:
			row.add_child(_opp_card(o))
	box.add_child(row)
	center.add_child(pc)

func _close_picker() -> void:
	if _picker != null and is_instance_valid(_picker):
		_picker.queue_free()
	_picker = null

func _reroll() -> void:
	if busy or reroll_count >= MAX_REROLLS: return
	busy = true
	var rs = await Api.batch_get(["/api/arena/opponents"])
	busy = false
	_apply_opponents(rs[0])
	reroll_count += 1
	_show_picker()   # reabre com os novos 3

# ── Card de um oponente (compacto): retrato + nome/nível + barra de vida ──
func _opp_card(o: Dictionary) -> PanelContainer:
	var hp := int(o.get("hp", 0))
	var npc := bool(o.get("isNpc", false))
	var res := UiKit.clickable_card(UiKit.GOLD_SOFT, _pick.bind(int(o.get("opponentId", 0))), true, Lang.t("Lutar contra %s") % str(o.get("name", "?")))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	pc.custom_minimum_size = Vector2(176, 0)
	box.add_theme_constant_override("separation", 4)
	# retrato (avatar de classe; fallback no 'character' até importar os PNGs)
	var key := ("class_mercenary" if npc else "class_" + str(o.get("classId", "recruit")))
	if Icons.tex(key) == null: key = "class_recruit"
	if Icons.tex(key) == null: key = "character"
	var port := Icons.rect(key, 60)
	port.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	box.add_child(port)
	# nome com ALTURA FIXA (2 linhas, "…" se passar) → todos os cards alinham mesmo com nome grande
	var nm := Label.new()
	nm.text = str(o.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 15); nm.add_theme_color_override("font_color", UiKit.GOLD)
	nm.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nm.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	nm.max_lines_visible = 2
	nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	nm.custom_minimum_size = Vector2(0, 40)
	box.add_child(nm)
	# nível numa linha própria (posição consistente entre os cards)
	var lvl := Label.new(); lvl.text = Lang.t("Nv %d") % int(o.get("level", 1))
	lvl.add_theme_font_size_override("font_size", 12); lvl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	lvl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(lvl)
	# só a barra de VIDA (vermelha) — sem revelar quem ganha
	box.add_child(UiKit.bar(Lang.t("Vida"), hp, maxi(_offer_max_hp, 1), Color(0.80, 0.22, 0.20), str(hp)))
	return pc

# escolher um card → fecha o popup e luta
func _pick(opponent_id: int) -> void:
	_close_picker()
	_fight(opponent_id)

func _page_prev() -> void:
	if busy or page <= 0: return
	page -= 1
	await _refresh(false)

func _page_next() -> void:
	if busy or rank.size() < PAGE_SIZE: return
	page += 1
	await _refresh(false)

# ── Resultado do duelo: título + recompensas + log de batalha (texto) ──
func _result_box(d: Dictionary) -> PanelContainer:
	var won := bool(d.get("won", false))
	var col: Color = UiKit.OK if won else UiKit.ERR
	var res := UiKit.card(col)
	var panel: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	var ttl := Label.new()
	var opp := str(d.get("opponent", "?"))
	ttl.text = (Lang.t("🏆 Vitória vs %s!") % opp) if won else (Lang.t("💀 Derrota para %s") % opp)
	ttl.add_theme_color_override("font_color", col); ttl.add_theme_font_size_override("font_size", 18)
	box.add_child(ttl)
	var rc := int(d.get("rankChange", 0))
	box.add_child(UiKit.kv("🏅 Rank", "%s%d pts" % ["+" if rc > 0 else "", rc]))
	box.add_child(UiKit.kv_node("Recompensa", UiKit.coin_box(int(d.get("goldEarned", 0)), 18)))
	var log: Array = d.get("log", []) if d.get("log") is Array else []
	if not log.is_empty():
		box.add_child(UiKit.spacer(4))
		for line in log:
			var s := str(line)
			if s.begins_with("WINNER:"):
				continue
			box.add_child(UiKit.dim(s))
	box.add_child(UiKit.spacer(4))
	box.add_child(UiKit.small_btn("Fechar", func() -> void: last_result = {}; _render()))
	return panel

func _rank_row(pos: int, r: Dictionary, is_me: bool) -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD if is_me else Color(1, 1, 1, 0.12))
	var panel: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	if is_me:
		var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
		sb.bg_color = Color(0.18, 0.15, 0.09, 0.94)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var pl := Label.new(); pl.text = "#%d" % pos; pl.custom_minimum_size = Vector2(40, 0)
	pl.add_theme_color_override("font_color", UiKit.GOLD if pos <= 3 else UiKit.TEXT_DIM)
	hb.add_child(pl)
	var title := str(r.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(r.get("warriorName", "?"))
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.add_theme_color_override("font_color", UiKit.GOLD if is_me else UiKit.TEXT)
	hb.add_child(nm)
	var pts := Label.new(); pts.text = "%d pts" % int(r.get("rankPoints", 0)); pts.custom_minimum_size = Vector2(70, 0)
	pts.add_theme_color_override("font_color", UiKit.TEXT)
	hb.add_child(pts)
	var wl := Label.new(); wl.text = "%d/%d" % [int(r.get("wins", 0)), int(r.get("losses", 0))]
	wl.custom_minimum_size = Vector2(60, 0); wl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	hb.add_child(wl)
	# [PROFILE_INSPECT] botão de ver o perfil (mesmo dialog da Classificação: boneco 3D + atributos)
	var pid := int(r.get("playerId", 0))
	if pid > 0:
		hb.add_child(UiKit.small_btn_icon(Lang.t("Ver"), "character", func() -> void: await ProfileInspect.open(self, pid)))
	return panel

# ── Ação: duelo contra o oponente escolhido → guarda o resultado e lança o replay. ──
func _fight(opponent_id: int, confirmed := false) -> void:
	if busy or _blocked(): return
	if not confirmed:   # [PERIGO] avisa se ferido (HP<50%) OU em forte desvantagem (>=70% chance de perder)
		var ep := 0
		for o in opponents:
			if int(o.get("opponentId", 0)) == opponent_id:
				ep = int(o.get("power", 0)); break
		UiKit.confirm_danger(self, w, ep, func() -> void: _fight(opponent_id, true), Callable(), your_power)
		return
	_close_picker()
	busy = true
	UiKit.show_loading(self)
	var r = await Api.arena_fight(opponent_id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		if j.has("error"):
			UiKit.flash(status, str(j.get("error")), 2)
			return
		last_result = j
		var be = j.get("battleEvents")
		if be is Array and be.size() >= 2:
			UiKit.hide_loading()
			request_battle.emit({"events": be, "scene": str(j.get("scene", "arena")), "won": bool(j.get("won", false)), "enemy": str(j.get("opponent", ""))})
		else:
			await _refresh()
	else:
		UiKit.show_error(status, r)

# o App chama isto quando o replay 3D termina (volta pra Arena, re-oferta 3 novos)
func _on_battle_over() -> void:
	await _refresh()
