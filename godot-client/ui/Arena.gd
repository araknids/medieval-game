extends Control
# ── Tela ARENA (PvP por ranking) ───────────────────────────────────────────────
# GET /api/arena/rank → top 20 (nome, título, pontos, V/D) + /api/warrior (carteira).
# Botão "Lutar" dispara POST /api/arena/fight (duelo instantâneo) e mostra o RESULTADO/log
# em texto — sem lançar o 3D (espelha o showCollectModal do app.js). Volta pro Hub (go_back).
# Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back
signal request_battle(data)   # pede ao App o replay 3D (overlay) [MIGRACAO_GODOT]

const STAMINA_COST := 25

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var rank: Array = []          # cache do ranking
var w: Dictionary = {}        # warrior (p/ estamina / nome destacado no rank)
var last_result: Dictionary = {}   # resultado do último duelo (mostrado em texto)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "⚔ Arena", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_BATTLE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# warrior (estamina + destacar meu nome) + ranking em PARALELO (independentes)
	var rs = await Api.batch_get(["/api/warrior", "/api/arena/rank"])
	var rw = rs[0]
	if rw.get("ok") and rw.get("json") is Dictionary:
		w = rw["json"]
	var rr = rs[1]
	if not (rr.get("ok") and rr.get("json") is Array):
		UiKit.show_error(status, rr)
		return
	rank = rr["json"]
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, w)
	# ── Painel de luta ──
	var stamina := int(w.get("stamina", 100))
	var ko := bool(w.get("isKnockedOut", false))
	var no_stamina := stamina < STAMINA_COST
	content.add_child(UiKit.section("Entrar em batalha"))
	content.add_child(UiKit.dim("Custo: ⚡ %d estamina  ·  Sua estamina: %d/100" % [STAMINA_COST, stamina]))
	content.add_child(UiKit.dim("Duelo instantâneo. Vitória: +25 rank, ~200 bronze."))
	if ko:
		var b := UiKit.action_big("💀 Nocauteado", Callable())
		b.disabled = true
		content.add_child(b)
	elif no_stamina:
		var b := UiKit.action_big("⚡ Sem estamina", Callable())
		b.disabled = true
		content.add_child(b)
	else:
		content.add_child(UiKit.action_big("⚔ Lutar · ⚡%d" % STAMINA_COST, _start_fight))
	# ── Resultado do último duelo (texto, no lugar do canvas/replay) ──
	if not last_result.is_empty():
		content.add_child(UiKit.spacer(8))
		content.add_child(_result_box(last_result))
	# ── Ranking ──
	content.add_child(UiKit.section("🏆 Ranking"))
	if rank.is_empty():
		content.add_child(UiKit.empty("Nenhum jogador ainda", "Seja o primeiro a lutar na arena"))
	else:
		var my_name := str(w.get("name", ""))
		var i := 0
		for r in rank:
			if r is Dictionary:
				i += 1
				content.add_child(_rank_row(i, r, str(r.get("warriorName", "")) == my_name))

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
	ttl.text = ("🏆 Vitória vs %s!" % opp) if won else ("💀 Derrota para %s" % opp)
	ttl.add_theme_color_override("font_color", col); ttl.add_theme_font_size_override("font_size", 18)
	box.add_child(ttl)
	var rc := int(d.get("rankChange", 0))
	box.add_child(UiKit.kv("🏅 Rank", "%s%d pts" % ["+" if rc > 0 else "", rc]))
	box.add_child(UiKit.kv("🥉 Bronze", "+%d" % int(d.get("goldEarned", 0))))
	# log de batalha (linha a linha, descartando a tag WINNER:)
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
	# linha do próprio jogador ganha fundo dourado sutil (não só cor de fonte)
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
	return panel

# ── Ação: duelo instantâneo. 1 chamada resolve tudo; guarda o resultado e re-renderiza. ──
func _start_fight() -> void:
	if busy: return
	busy = true
	UiKit.flash(status, "Lutando…", 0)
	var r = await Api.arena_fight()
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		if j.has("error"):
			UiKit.flash(status, str(j.get("error")), 2)
			return
		last_result = j
		var be = j.get("battleEvents")
		if be is Array and be.size() >= 2:
			# vs humano → replay 3D por cima; _on_battle_over volta e mostra a recompensa
			request_battle.emit({"events": be, "scene": str(j.get("scene", "arena")), "won": bool(j.get("won", false)), "enemy": str(j.get("opponent", ""))})
		else:
			await _refresh()   # sem eventos → resultado em texto
	else:
		UiKit.show_error(status, r)

# o App chama isto quando o replay 3D termina (volta pra Arena)
func _on_battle_over() -> void:
	await _refresh()   # estamina/rank + mostra o _result_box(last_result) no _render
