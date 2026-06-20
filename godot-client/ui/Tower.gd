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
const PAGE_SIZE := 20            # [PAGINACAO] ranking paginado (offset no backend)
const Icons := preload("res://ui/Icons.gd")   # fallback do retrato [TORRE_PREVIEW]

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var warrior: Dictionary = {}     # p/ saber estamina + destacar "me" no ranking
var state: Dictionary = {}       # GET /api/tower/current
var ranking: Array = []          # GET /api/tower/ranking (página atual)
var page := 0                    # [PAGINACAO] página do ranking
var last_result: Dictionary = {} # resultado da última luta (texto), se houver
var arka_pending := false        # escolha do Rei Arka no topo
var log_open := false            # log da batalha colapsável (P0: não empurra o ranking)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🏰 Torre", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_BATTLE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	# warrior + run + ranking (página atual) em PARALELO (independentes)
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current", "/api/tower/ranking?page=%d" % page])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = rs[1]
	if not (rc.get("ok") and rc.get("json") is Dictionary):
		UiKit.show_error(status, rc)
		return
	state = rc["json"]
	var rr = rs[2]
	ranking = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else []
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
	# ranking de melhores andares
	_render_ranking()

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

# ── ANDAR (run ativa) ────────────────────────────────────────────────────────────
func _render_floor() -> void:
	var is_mvp := bool(state.get("isMvp", false))
	var border := Color(UiKit.GOLD) if is_mvp else Color(0.33, 0.33, 0.4)
	var res := UiKit.card(border)
	var vb: VBoxContainer = res[1]
	var cur := int(state.get("currentFloor", 1))
	var maxf := int(state.get("maxFloor", 0))
	# [TORRE_PREVIEW] corpo em 2 colunas: texto à esquerda (cresce) + retrato do inimigo à direita.
	var bodyrow := HBoxContainer.new(); bodyrow.add_theme_constant_override("separation", 14)
	vb.add_child(bodyrow)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 4)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bodyrow.add_child(col)
	var num := Label.new()
	num.text = Lang.t("🏰 Andar %d%s") % [cur, ("  /  %d" % maxf) if maxf > 0 else ""]
	num.add_theme_font_size_override("font_size", 21); num.add_theme_color_override("font_color", UiKit.GOLD)
	col.add_child(num)
	var highest := int(state.get("highestFloor", 0))
	if highest > 0:
		var hc := Label.new(); hc.text = Lang.t("✔ Andar mais alto vencido: %d") % highest
		hc.add_theme_color_override("font_color", UiKit.OK); hc.add_theme_font_size_override("font_size", 12)
		col.add_child(hc)
	var atmo := str(state.get("atmosphere", ""))
	if atmo != "":
		col.add_child(UiKit.dim(atmo))
	# boss
	var monsters: Array = state.get("monsters", []) if state.get("monsters") is Array else []
	var boss_name := str(monsters[0]) if monsters.size() > 0 else str(state.get("bossName", "?"))
	var bn := Label.new()
	bn.text = ("👑 " if is_mvp else "") + boss_name
	bn.add_theme_font_size_override("font_size", 17); bn.add_theme_color_override("font_color", border)
	col.add_child(bn)
	if monsters.size() > 1:
		# [TORRE_GAUNTLET] agrupa repetidos ("Nome ×3") e tira o "Gauntlet" cru. Se todos iguais
		# (o nome já está no título acima), só diz QUANTOS enfrentar; senão lista os distintos.
		var grouped := _group_names(monsters)
		var gl := Label.new()
		if grouped.size() == 1:
			gl.text = Lang.t("⚔ %d inimigos em sequência") % monsters.size()
		else:
			gl.text = Lang.t("⚔ Sequência: %s") % " · ".join(grouped)
		gl.add_theme_color_override("font_color", Color(0.8, 0.4, 0.6)); gl.add_theme_font_size_override("font_size", 12)
		gl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		col.add_child(gl)
	var rec := int(state.get("recommendedLevel", 0))
	if rec > 0:
		var rl := Label.new(); rl.text = Lang.t("🚩 Nível recomendado %d+") % rec
		rl.add_theme_color_override("font_color", UiKit.WARN); rl.add_theme_font_size_override("font_size", 12)
		col.add_child(rl)
	var stats := Label.new()
	stats.text = "❤ %d HP    ⚔ %d ATK    🛡 %d DEF    🎯 AC %d" % [
		int(state.get("bossHp", 0)), int(state.get("bossAtk", 0)),
		int(state.get("bossDef", 0)), int(state.get("bossAc", 0))]
	stats.add_theme_color_override("font_color", UiKit.TEXT); stats.add_theme_font_size_override("font_size", 13)
	col.add_child(stats)
	# [MOEDA] recompensa com ícone pixel-art (bronze) em vez de emoji
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 6)
	var rew_lbl := Label.new(); rew_lbl.text = "Recompensa:"
	rew_lbl.add_theme_color_override("font_color", UiKit.GOLD_SOFT); rew_lbl.add_theme_font_size_override("font_size", 12)
	rew.add_child(rew_lbl)
	rew.add_child(UiKit.coin_box(cur * 40, 16))
	var rew_xp := Label.new(); rew_xp.text = "· ⭐ %d exp" % (cur * 20)
	rew_xp.add_theme_color_override("font_color", UiKit.GOLD_SOFT); rew_xp.add_theme_font_size_override("font_size", 12)
	rew.add_child(rew_xp)
	col.add_child(rew)
	# [TORRE_PREVIEW] retrato animado do inimigo à direita (alcova; MVP = moldura dourada + tag BOSS)
	bodyrow.add_child(_enemy_portrait(_tower_art_key(cur, is_mvp), is_mvp, border))
	# CTA full-width ABAIXO das 2 colunas (nunca estreitado pelo retrato)
	vb.add_child(UiKit.action_big("⚔ Lutar", _fight))
	content.add_child(res[0])

# [TORRE_PREVIEW] andar → chave de arte. Mapeado pelo NÚMERO do andar (i18n-proof — o nome do
# inimigo é localizado PT/EN). MVP (10/20/…/50) tem arte própria; cada zona de 9 comuns divide 1 arquétipo.
func _tower_art_key(floor: int, is_mvp: bool) -> String:
	if is_mvp or floor % 10 == 0:
		return "mvp%d" % floor
	return "zone%d" % (int((floor - 1) / 10) + 1)

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

# ── Ranking de melhores andares ──────────────────────────────────────────────────
func _render_ranking() -> void:
	# [PAGINACAO] paginador no CABEÇALHO (canto direito), mesmo estilo da Forja
	content.add_child(UiKit.section_paged("🏰 Ranking — Melhores Andares", page, ranking.size() >= PAGE_SIZE, _page_prev, _page_next))
	if ranking.is_empty():
		if page > 0:
			content.add_child(UiKit.dim("Fim do ranking."))
		else:
			content.add_child(UiKit.empty("Nenhum registro ainda", "Suba a torre para entrar no ranking"))
		return
	var my_name := str(warrior.get("name", ""))
	var base := page * PAGE_SIZE   # posição GLOBAL (#21… na página 2)
	var i := 0
	for r in ranking:
		if not (r is Dictionary):
			continue
		i += 1
		var rname := str(r.get("warriorName", "?"))
		var title := str(r.get("title", ""))
		var mine := rname == my_name
		# P1: minha linha = fundo dourado sutil (não só cor de fonte)
		var res := UiKit.card(UiKit.GOLD if mine else Color(1, 1, 1, 0.12))
		var box: VBoxContainer = res[1]
		if mine:
			var sb: StyleBoxFlat = res[0].get_theme_stylebox("panel")
			sb.bg_color = Color(0.18, 0.15, 0.09, 0.94)
		var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
		box.add_child(row)
		var pos := Label.new(); pos.text = "%d." % (base + i); pos.custom_minimum_size = Vector2(40, 0)
		pos.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		row.add_child(pos)
		var nm := Label.new()
		nm.text = (title + " " if title != "" else "") + rname
		nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		nm.add_theme_color_override("font_color", UiKit.GOLD if mine else UiKit.TEXT)
		row.add_child(nm)
		var fl := Label.new(); fl.text = "🏰 %d" % int(r.get("bestFloor", 0))
		fl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		row.add_child(fl)
		content.add_child(res[0])

func _page_prev() -> void:
	if busy or page <= 0: return
	page -= 1
	await _refresh()

func _page_next() -> void:
	if busy or ranking.size() < PAGE_SIZE: return
	page += 1
	await _refresh()

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

# re-sincroniza estamina/HP + próximo andar (ou lobby) + ranking — em PARALELO
func _resync() -> void:
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current", "/api/tower/ranking?page=%d" % page])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = rs[1]
	if rc.get("ok") and rc.get("json") is Dictionary:
		state = rc["json"]
	var rr = rs[2]
	ranking = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else ranking
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
