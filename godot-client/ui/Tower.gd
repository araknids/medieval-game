extends Control
# ── Tela TORRE INFERNAL ──────────────────────────────────────────────────────────
# Lê GET /api/tower/current: se houver run ativa mostra o andar (boss/atmosfera/stats) +
# botão ⚔ Lutar; senão mostra o lobby (custo 25⚡ + ⚔ Entrar e lutar). Lutar resolve no backend
# (POST /api/tower/fight) e mostra o RESULTADO em texto (recompensas + log) — sem 3D.
# Andar 50: a escolha do Rei Arka (poupar/matar). Ranking de melhores andares em baixo.
# Padrão visual: UiKit [PADRAO_UI_GODOT]. Espelha loadTower/showTowerLobby/showTowerFloor. [MIGRACAO_GODOT]

signal go_back

const STAMINA_COST := 25

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var warrior: Dictionary = {}     # p/ saber estamina + destacar "me" no ranking
var state: Dictionary = {}       # GET /api/tower/current
var ranking: Array = []          # GET /api/tower/ranking
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
	UiKit.flash(status, "Carregando…", 0)
	# warrior + run + ranking em PARALELO (independentes)
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current", "/api/tower/ranking"])
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
	UiKit.flash(status, "", 0)
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
	var h := Label.new(); h.text = "⚔ Entrar na Torre"; h.add_theme_font_size_override("font_size", 19)
	h.add_theme_color_override("font_color", UiKit.GOLD)
	vb.add_child(h)
	vb.add_child(UiKit.dim("Custo: ⚡ %d estamina   ·   Sua estamina: %d/100" % [STAMINA_COST, stamina]))
	vb.add_child(UiKit.dim("Lute andar por andar. Se perder, é expulso. Vá o mais longe que conseguir!"))
	# P0: "Entrar" já dispara a primeira luta — deixa explícito no rótulo (+ custo).
	if no_stamina:
		var b := UiKit.action_big("Sem estamina", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		vb.add_child(UiKit.action_big("⚔ Entrar e lutar · ⚡%d" % STAMINA_COST, _enter))
	content.add_child(res[0])

# ── ANDAR (run ativa) ────────────────────────────────────────────────────────────
func _render_floor() -> void:
	var is_mvp := bool(state.get("isMvp", false))
	var border := Color(UiKit.GOLD) if is_mvp else Color(0.33, 0.33, 0.4)
	var res := UiKit.card(border)
	var vb: VBoxContainer = res[1]
	var cur := int(state.get("currentFloor", 1))
	var maxf := int(state.get("maxFloor", 0))
	var num := Label.new()
	num.text = "🏰 Andar %d%s" % [cur, ("  /  %d" % maxf) if maxf > 0 else ""]
	num.add_theme_font_size_override("font_size", 21); num.add_theme_color_override("font_color", UiKit.GOLD)
	vb.add_child(num)
	var highest := int(state.get("highestFloor", 0))
	if highest > 0:
		var hc := Label.new(); hc.text = "✔ Andar mais alto vencido: %d" % highest
		hc.add_theme_color_override("font_color", UiKit.OK); hc.add_theme_font_size_override("font_size", 12)
		vb.add_child(hc)
	var atmo := str(state.get("atmosphere", ""))
	if atmo != "":
		vb.add_child(UiKit.dim(atmo))
	# boss
	var monsters: Array = state.get("monsters", []) if state.get("monsters") is Array else []
	var boss_name := str(monsters[0]) if monsters.size() > 0 else str(state.get("bossName", "?"))
	var bn := Label.new()
	bn.text = ("👑 " if is_mvp else "") + boss_name
	bn.add_theme_font_size_override("font_size", 17); bn.add_theme_color_override("font_color", border)
	vb.add_child(bn)
	if monsters.size() > 1:
		var names: Array = []
		for mob in monsters:
			names.append(str(mob))
		var gl := Label.new(); gl.text = "⚔ Gauntlet — %s" % " · ".join(names)
		gl.add_theme_color_override("font_color", Color(0.8, 0.4, 0.6)); gl.add_theme_font_size_override("font_size", 12)
		gl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		vb.add_child(gl)
	var rec := int(state.get("recommendedLevel", 0))
	if rec > 0:
		var rl := Label.new(); rl.text = "🚩 Nível recomendado %d+" % rec
		rl.add_theme_color_override("font_color", UiKit.WARN); rl.add_theme_font_size_override("font_size", 12)
		vb.add_child(rl)
	var stats := Label.new()
	stats.text = "❤ %d HP    ⚔ %d ATK    🛡 %d DEF    🎯 AC %d" % [
		int(state.get("bossHp", 0)), int(state.get("bossAtk", 0)),
		int(state.get("bossDef", 0)), int(state.get("bossAc", 0))]
	stats.add_theme_color_override("font_color", UiKit.TEXT); stats.add_theme_font_size_override("font_size", 13)
	vb.add_child(stats)
	var rew := Label.new()
	rew.text = "Recompensa: 🥉 %d bronze · ⭐ %d exp" % [cur * 40, cur * 20]
	rew.add_theme_color_override("font_color", UiKit.GOLD_SOFT); rew.add_theme_font_size_override("font_size", 12)
	vb.add_child(rew)
	vb.add_child(UiKit.action_big("⚔ Lutar", _fight))
	content.add_child(res[0])

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
	h.text = ("🏆 Andar %d vencido!" % floor_num) if won else ("💀 Derrotado no andar %d" % floor_num)
	h.add_theme_font_size_override("font_size", 18); h.add_theme_color_override("font_color", border)
	vb.add_child(h)
	if won:
		vb.add_child(UiKit.kv("🥉 Bronze", "+%d" % int(last_result.get("bronzeEarned", 0))))
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
	content.add_child(UiKit.section("🏰 Ranking — Melhores Andares"))
	if ranking.is_empty():
		content.add_child(UiKit.empty("Nenhum registro ainda", "Suba a torre para entrar no ranking"))
		return
	var my_name := str(warrior.get("name", ""))
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
		var pos := Label.new(); pos.text = "%d." % i; pos.custom_minimum_size = Vector2(34, 0)
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

# ── Ações async ──────────────────────────────────────────────────────────────────
func _enter() -> void:
	if busy: return
	busy = true
	UiKit.flash(status, "Entrando…", 0)
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
	UiKit.flash(status, "Lutando…", 0)
	var r = await Api.tower_fight()
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); busy = false; return
	var data: Dictionary = r["json"]
	last_result = data
	log_open = false
	arka_pending = bool(data.get("arkaChoicePending", false))
	# re-sincroniza estamina/HP + próximo andar (ou lobby se perdeu) — em PARALELO
	var rs = await Api.batch_get(["/api/warrior", "/api/tower/current", "/api/tower/ranking"])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = rs[1]
	if rc.get("ok") and rc.get("json") is Dictionary:
		state = rc["json"]
	var rr = rs[2]
	ranking = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else ranking
	busy = false
	_render()

func _arka(spare: bool) -> void:
	if busy: return
	busy = true
	UiKit.flash(status, "…", 0)
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
		"log": [("🏆 Título desbloqueado: %s" % ("O Misericordioso" if spare else "Regicida"))],
	}
	_render()
