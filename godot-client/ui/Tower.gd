extends Control
# ── Tela TORRE INFERNAL ──────────────────────────────────────────────────────────
# Lê GET /api/tower/current: se houver run ativa mostra o andar (boss/atmosfera/stats) +
# botão ⚔ Lutar; senão mostra o lobby (custo 25⚡ + ⚔ Entrar e lutar). Lutar resolve no backend
# (POST /api/tower/fight) e mostra o RESULTADO em texto (recompensas + log) — sem 3D.
# Andar 50: a escolha do Rei Arka (poupar/matar). Ranking de melhores andares em baixo.
# Padrão visual: UiKit [PADRAO_UI_GODOT]. Espelha loadTower/showTowerLobby/showTowerFloor. [MIGRACAO_GODOT]

signal go_back
signal request_battle(data)   # pede ao App o replay 3D (overlay) [MIGRACAO_GODOT]

const STAMINA_COST := 25
const Icons := preload("res://ui/Icons.gd")   # fallback do retrato [TORRE_PREVIEW]

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var warrior: Dictionary = {}     # estamina + comparação Você×Inimigo
var state: Dictionary = {}       # GET /api/tower/current
var last_result: Dictionary = {} # resultado da última luta (texto), se houver
var arka_pending := false        # escolha do Rei Arka no topo
var log_open := false            # log da batalha colapsável

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🏰 Torre", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_BATTLE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	# warrior + run em PARALELO (o ranking saiu — agora vive na aba Classificação)
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current"])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = rs[1]
	if not (rc.get("ok") and rc.get("json") is Dictionary):
		UiKit.show_error(status, rc)
		return
	state = rc["json"]
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	# resultado da última luta (se houver) sempre no topo
	if not last_result.is_empty():
		_render_result()
	if arka_pending:
		_render_arka()
	elif bool(state.get("active", false)):
		_render_floor()
	else:
		_render_lobby()

# ── LOBBY (sem run ativa) ────────────────────────────────────────────────────────
func _render_lobby() -> void:
	var stamina := int(warrior.get("stamina", 0))
	var no_stamina := stamina < STAMINA_COST
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var vb: VBoxContainer = res[1]
	# [TORRE_PREVIEW] 2 colunas: texto à esquerda + retrato de QUEM te espera no próximo andar à direita.
	var bodyrow := HBoxContainer.new(); bodyrow.add_theme_constant_override("separation", 14)
	vb.add_child(bodyrow)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 4)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bodyrow.add_child(col)
	var h := Label.new(); h.text = "⚔ Entrar na Torre"; h.add_theme_font_size_override("font_size", 19)
	h.add_theme_color_override("font_color", UiKit.GOLD)
	col.add_child(h)
	col.add_child(UiKit.dim(Lang.t("Custo: ⚡ %d estamina   ·   Sua estamina: %d/100") % [STAMINA_COST, stamina]))
	col.add_child(UiKit.dim("Lute andar por andar. Se perder, é expulso. Vá o mais longe que conseguir!"))
	# quem te espera no próximo andar (towerBestFloor+1) — vem do payload do lobby [TORRE_PREVIEW]
	var nf := int(state.get("nextFloor", 1))
	var next_mvp := bool(state.get("isMvp", false))
	var next_name := str(state.get("bossName", ""))
	if next_name != "":
		col.add_child(UiKit.dim(Lang.t("A seguir — Andar %d: %s") % [nf, next_name]))
	bodyrow.add_child(_enemy_portrait(_tower_art_key(nf, next_mvp), next_mvp, UiKit.GOLD if next_mvp else Color(0.33, 0.33, 0.4)))
	# P0: "Entrar" já dispara a primeira luta — deixa explícito no rótulo (+ custo). CTA full-width abaixo.
	if no_stamina:
		var b := UiKit.action_big("Sem estamina", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		vb.add_child(UiKit.action_big(Lang.t("⚔ Entrar e lutar · ⚡%d") % STAMINA_COST, _enter))
	content.add_child(res[0])

# ── ANDAR (run ativa) — descrição/lore em destaque + painel do inimigo ───────────────
func _render_floor() -> void:
	var is_mvp := bool(state.get("isMvp", false))
	var border := Color(UiKit.GOLD) if is_mvp else Color(0.33, 0.33, 0.4)
	var res := UiKit.card(border)
	var vb: VBoxContainer = res[1]
	vb.add_theme_constant_override("separation", 8)
	var cur := int(state.get("currentFloor", 1))
	var maxf := int(state.get("maxFloor", 0))
	# cabeçalho: andar + recorde
	var hrow := HBoxContainer.new(); hrow.add_theme_constant_override("separation", 12)
	var num := Label.new()
	num.text = Lang.t("Andar %d%s") % [cur, ("  /  %d" % maxf) if maxf > 0 else ""]
	num.add_theme_font_size_override("font_size", 22); num.add_theme_color_override("font_color", UiKit.GOLD)
	num.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hrow.add_child(num)
	var highest := int(state.get("highestFloor", 0))
	if highest > 0:
		var hc := Label.new(); hc.text = Lang.t("✔ Recorde: %d") % highest
		hc.add_theme_color_override("font_color", UiKit.OK); hc.add_theme_font_size_override("font_size", 12)
		hc.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		hrow.add_child(hc)
	vb.add_child(hrow)
	# DESCRIÇÃO DO ANDAR (atmosfera = lore; nos andares de chefe é a lore do próprio chefe) — em destaque
	var atmo := str(state.get("atmosphere", ""))
	if atmo != "":
		vb.add_child(_lore_block(atmo, is_mvp))
	# O INIMIGO: nome/perigo/comparação (esquerda) + retrato (direita)
	vb.add_child(UiKit.section("O inimigo"))
	var bodyrow := HBoxContainer.new(); bodyrow.add_theme_constant_override("separation", 14)
	vb.add_child(bodyrow)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 5)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bodyrow.add_child(col)
	var monsters: Array = state.get("monsters", []) if state.get("monsters") is Array else []
	var boss_name := str(monsters[0]) if monsters.size() > 0 else str(state.get("bossName", "?"))
	var bn := Label.new()
	bn.text = ("👑 " if is_mvp else "") + boss_name
	bn.add_theme_font_size_override("font_size", 18); bn.add_theme_color_override("font_color", border)
	bn.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(bn)
	col.add_child(_danger_badge(int(state.get("recommendedLevel", 0))))
	col.add_child(_vs_grid())
	if monsters.size() > 1:
		# [TORRE_GAUNTLET] agrupa repetidos ("Nome ×3"); se todos iguais, só diz QUANTOS.
		var grouped := _group_names(monsters)
		var gl := Label.new()
		if grouped.size() == 1:
			gl.text = Lang.t("⚔ %d inimigos em sequência") % monsters.size()
		else:
			gl.text = Lang.t("Sequência: %s") % " · ".join(grouped)
		gl.add_theme_color_override("font_color", Color(0.8, 0.4, 0.6)); gl.add_theme_font_size_override("font_size", 12)
		gl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		col.add_child(gl)
	bodyrow.add_child(_enemy_portrait(_tower_art_key(cur, is_mvp), is_mvp, border))
	# recompensa
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 6)
	var rew_lbl := Label.new(); rew_lbl.text = Lang.t("Recompensa:")
	rew_lbl.add_theme_color_override("font_color", UiKit.GOLD_SOFT); rew_lbl.add_theme_font_size_override("font_size", 12)
	rew_lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rew.add_child(rew_lbl)
	rew.add_child(UiKit.coin_box(cur * 40, 16))
	var rew_xp := Label.new(); rew_xp.text = "· ⭐ %d exp" % (cur * 20)
	rew_xp.add_theme_color_override("font_color", UiKit.GOLD_SOFT); rew_xp.add_theme_font_size_override("font_size", 12)
	rew_xp.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rew.add_child(rew_xp)
	vb.add_child(rew)
	vb.add_child(UiKit.action_big("⚔ Lutar", _fight))
	content.add_child(res[0])

# Bloco de narrativa (descrição do andar / lore do chefe): inset legível, autowrap.
func _lore_block(text: String, is_mvp: bool) -> Control:
	var pc := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.05, 0.045, 0.06, 0.92)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.GOLD_SOFT if is_mvp else UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(9)
	pc.add_theme_stylebox_override("panel", sb)
	var l := UiKit.body(text)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.add_theme_color_override("font_color", UiKit.TEXT)
	l.add_theme_font_size_override("font_size", 13)
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pc.add_child(l)
	return pc

# Badge de perigo: nível recomendado vs seu nível (cor da dificuldade).
func _danger_badge(rec: int) -> Control:
	if rec <= 0:
		return UiKit.spacer(0)
	var lvl := int(warrior.get("level", 1))
	var label := ""
	var col := UiKit.TEXT_DIM
	if lvl >= rec + 5:
		label = Lang.t("Fácil"); col = UiKit.OK
	elif lvl >= rec:
		label = Lang.t("Equilibrado"); col = UiKit.GOLD
	elif lvl >= rec - 3:
		label = Lang.t("Arriscado"); col = Color(0.93, 0.6, 0.2)
	else:
		label = Lang.t("Mortal"); col = UiKit.ERR
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	var t := Label.new(); t.text = Lang.t("Perigo:")
	t.add_theme_font_size_override("font_size", 12); t.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	h.add_child(t)
	var b := Label.new(); b.text = "%s  ·  rec. Lv%d · você Lv%d" % [label, rec, lvl]
	b.add_theme_font_size_override("font_size", 12); b.add_theme_color_override("font_color", col)
	h.add_child(b)
	return h

# Comparação Você × Inimigo (ATK/DEF/HP/AC): verde no seu lado quando está acima.
func _vs_grid() -> GridContainer:
	var g := GridContainer.new()
	g.columns = 3
	g.add_theme_constant_override("h_separation", 12)
	g.add_theme_constant_override("v_separation", 2)
	g.add_child(_vs_cell("", UiKit.TEXT_DIM, 11, 40, HORIZONTAL_ALIGNMENT_LEFT))
	g.add_child(_vs_cell(Lang.t("Você"), UiKit.GOLD_SOFT, 11, 58, HORIZONTAL_ALIGNMENT_RIGHT))
	g.add_child(_vs_cell(Lang.t("Inimigo"), UiKit.GOLD_SOFT, 11, 58, HORIZONTAL_ALIGNMENT_RIGHT))
	_vs_stat(g, "ATK", int(warrior.get("combatAttack", 0)), int(state.get("bossAtk", 0)))
	_vs_stat(g, "DEF", int(warrior.get("combatDefense", 0)), int(state.get("bossDef", 0)))
	_vs_stat(g, "HP", int(warrior.get("combatHealth", 0)), int(state.get("bossHp", 0)))
	_vs_stat(g, "AC", int(warrior.get("armorClass", 0)), int(state.get("bossAc", 0)))
	return g

func _vs_stat(g: GridContainer, label: String, you: int, enemy: int) -> void:
	g.add_child(_vs_cell(label, UiKit.TEXT_DIM, 13, 40, HORIZONTAL_ALIGNMENT_LEFT))
	g.add_child(_vs_cell(str(you), UiKit.OK if you >= enemy else UiKit.TEXT, 13, 58, HORIZONTAL_ALIGNMENT_RIGHT))
	g.add_child(_vs_cell(str(enemy), UiKit.TEXT if you >= enemy else Color(0.93, 0.5, 0.45), 13, 58, HORIZONTAL_ALIGNMENT_RIGHT))

func _vs_cell(text: String, col: Color, fs: int, w: int, align: int) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", fs)
	l.add_theme_color_override("font_color", col)
	l.custom_minimum_size = Vector2(w, 0)
	l.horizontal_alignment = align
	return l

# [TORRE_PREVIEW] andar → chave de arte. 1 BUSTO ÚNICO por andar (assets/ui/tower/f<andar>/),
# mapeado pelo NÚMERO do andar (i18n-proof — o nome do inimigo é localizado PT/EN). O `_is_mvp`
# segue no parâmetro só p/ a moldura dourada (decidida em _enemy_portrait), não muda a chave.
func _tower_art_key(floor: int, _is_mvp: bool) -> String:
	return "f%d" % floor

# [TORRE_PREVIEW] Retrato emoldurado do inimigo numa alcova escura. MVP = moldura dourada + tag BOSS.
# Sem arte ainda (TowerPreview.make == null) → cai no ícone da torre (nunca caixa vazia).
func _enemy_portrait(key: String, is_mvp: bool, border: Color) -> Control:
	var px := 140 if is_mvp else 124
	var art: Control = TowerPreview.make(key, px)
	if art == null:
		art = Icons.rect("tower", px)        # fallback estático até a arte ser importada
	var inner := VBoxContainer.new(); inner.add_theme_constant_override("separation", 2)
	inner.add_child(art)
	if is_mvp:
		var tag := Label.new(); tag.text = "BOSS"
		tag.add_theme_font_size_override("font_size", 10)
		tag.add_theme_color_override("font_color", UiKit.GOLD)
		tag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		inner.add_child(tag)
	var frame := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.05, 0.045, 0.06)                  # alcova recuada (combina com UiKit.input)
	sb.set_border_width_all(2); sb.border_color = border
	sb.set_corner_radius_all(4)
	sb.content_margin_left = 5; sb.content_margin_right = 5
	sb.content_margin_top = 5; sb.content_margin_bottom = 5
	frame.add_theme_stylebox_override("panel", sb)
	frame.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	frame.add_child(inner)
	return frame

# [TORRE_GAUNTLET] Agrupa nomes repetidos preservando a ordem: ["A","A","B"] → ["A ×2","B"].
func _group_names(arr: Array) -> Array:
	var order: Array = []
	var counts := {}
	for x in arr:
		var s := str(x)
		if not counts.has(s):
			order.append(s); counts[s] = 0
		counts[s] = int(counts[s]) + 1
	var out: Array = []
	for s in order:
		var c := int(counts[s])
		out.append(("%s ×%d" % [s, c]) if c > 1 else s)
	return out

# ── Escolha do Rei Arka (andar 50) ───────────────────────────────────────────────
func _render_arka() -> void:
	var res := UiKit.card(UiKit.GOLD)
	var vb: VBoxContainer = res[1]
	var h := Label.new(); h.text = "👑 O Rei Cai"; h.add_theme_font_size_override("font_size", 19)
	h.add_theme_color_override("font_color", UiKit.GOLD)
	vb.add_child(h)
	var txt := UiKit.body("O Rei Arka cai de joelhos, a luz emprestada se apagando. Por um instante, o homem que fundou um reino olha para você — e tem medo. \"Misericórdia\", ele sussurra. \"Por favor.\"")
	vb.add_child(txt)
	vb.add_child(UiKit.action("🕊 Poupá-lo", _arka.bind(true)))
	vb.add_child(UiKit.action_danger("🗡 Executá-lo", _arka.bind(false)))
	vb.add_child(UiKit.dim("Esta escolha é definitiva."))
	content.add_child(res[0])

# ── Resultado da última luta (texto) ─────────────────────────────────────────────
func _render_result() -> void:
	var won := bool(last_result.get("won", false))
	var floor_num := int(last_result.get("floor", 0))
	var border := Color(UiKit.OK) if won else Color(UiKit.ERR)
	var res := UiKit.card(border)
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = res[0].get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	var h := Label.new()
	h.text = (Lang.t("🏆 Andar %d vencido!") % floor_num) if won else (Lang.t("💀 Derrotado no andar %d") % floor_num)
	h.add_theme_font_size_override("font_size", 18); h.add_theme_color_override("font_color", border)
	vb.add_child(h)
	if won:
		vb.add_child(UiKit.kv_node("Recompensa", UiKit.coin_box(int(last_result.get("bronzeEarned", 0)), 18)))   # [MOEDA] ícones pixel-art
		vb.add_child(UiKit.kv("⭐ Experiência", "+%d XP" % int(last_result.get("expEarned", 0))))
	else:
		var d := Label.new(); d.text = "☠ Derrotado — cure-se no Templo"
		d.add_theme_color_override("font_color", UiKit.ERR); vb.add_child(d)
	var note := str(last_result.get("atmosphere", ""))
	if note == "" and won and not bool(last_result.get("runOver", false)):
		note = "Chefe derrotado! Suba para o próximo andar quando quiser."
	if note != "":
		vb.add_child(UiKit.dim(note))
	# log da batalha (colapsável — pode ter muitas linhas e empurrar o ranking)
	var log: Array = last_result.get("log", []) if last_result.get("log") is Array else []
	if not log.is_empty():
		vb.add_child(UiKit.spacer(4))
		vb.add_child(UiKit.small_btn("📜 Ocultar log" if log_open else "📜 Ver log", func() -> void: log_open = not log_open; _render()))
		if log_open:
			for line in log:
				vb.add_child(UiKit.dim(str(line)))
	vb.add_child(UiKit.spacer(4))
	vb.add_child(UiKit.small_btn("Fechar", func() -> void: last_result = {}; log_open = false; _render()))
	content.add_child(res[0])
	content.add_child(UiKit.spacer(8))

# [LEADERBOARDS] Ranking removido da Torre — agora vive na aba Classificação (Torre).

# ── Ações async ──────────────────────────────────────────────────────────────────
func _enter() -> void:
	if busy: return
	busy = true
	UiKit.show_loading(self)
	var r = await Api.tower_enter()
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); busy = false; return
	# o app.js luta automaticamente ao entrar
	busy = false
	await _do_fight()

func _fight() -> void:
	if busy: return
	await _do_fight()

func _do_fight() -> void:
	if busy: return
	busy = true
	UiKit.show_loading(self)
	var r = await Api.tower_fight()
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); return
	var data: Dictionary = r["json"]
	last_result = data
	log_open = false
	arka_pending = bool(data.get("arkaChoicePending", false))
	var be = data.get("battleEvents")
	if be is Array and be.size() >= 2:
		# Torre = misto (humano/monstro pelo nome do andar) → replay 3D por cima
		UiKit.hide_loading()   # [LOADING] tira o dialog antes do replay aparecer por cima
		request_battle.emit({"events": be, "scene": str(data.get("scene", "tower")), "won": bool(data.get("won", false)), "enemy": str(data.get("bossName", ""))})
	else:
		await _resync()

# o App chama isto quando o replay termina
func _on_battle_over() -> void:
	await _resync()

# re-sincroniza estamina/HP + próximo andar (ou lobby) — em PARALELO
func _resync() -> void:
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current"])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = rs[1]
	if rc.get("ok") and rc.get("json") is Dictionary:
		state = rc["json"]
	_render()

func _arka(spare: bool) -> void:
	if busy: return
	busy = true
	UiKit.show_loading(self)
	var r = await Api.tower_arka(spare)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); return
	arka_pending = false
	# substitui o resultado pelo desfecho narrativo da escolha
	last_result = {
		"won": true,
		"floor": int(state.get("currentFloor", 0)),
		"bronzeEarned": 0,
		"expEarned": 0,
		"runOver": true,
		"atmosphere": str(r["json"].get("message", "")),
		"log": [(Lang.t("🏆 Título desbloqueado: %s") % (Lang.t("O Misericordioso") if spare else Lang.t("Regicida")))],
	}
	_render()
