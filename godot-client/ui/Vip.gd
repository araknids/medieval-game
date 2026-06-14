extends Control
# ── Tela VIP / SoulStone ──────────────────────────────────────────────────────────
# Lê GET /api/vip/status (isVip, vipExpiresAt, soulStones, arenaFights…) + /api/warrior
# (carteira do header) e mostra o status VIP + saldo de SoulStone. Ação primária:
# comprar/renovar VIP (POST /api/vip/buy, 15💎, +30 dias). Mount celestial/pet/expansão de
# bag ficaram de fora (endpoints à parte). Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const VIP_COST := 15

var content: VBoxContainer
var status: Label
var wallet: Label
var data: Dictionary = {}
var warrior: Dictionary = {}
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "💎 VIP", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/vip/status", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	var is_vip := bool(data.get("isVip", false))
	var ss := int(data.get("soulStones", 0))
	# ── Banner de status ──
	if is_vip:
		var days := _days_left(str(data.get("vipExpiresAt", "")))
		_render_active_banner(days)
		var exp := str(data.get("vipExpiresAt", ""))
		if exp.length() >= 10:
			content.add_child(UiKit.dim(Lang.t("Expira em %s") % exp.substr(0, 10)))
		var lim := int(data.get("arenaFightLimit", 0))
		var rem := int(data.get("arenaFightsRemaining", 0))
		content.add_child(UiKit.dim(Lang.t("⚔ Lutas de arena: %d/%d") % [lim - rem, lim]))
	else:
		content.add_child(UiKit.empty("Você não tem VIP ativo.", "Ative o VIP abaixo para destravar os benefícios"))
	# ── Benefícios + compra ──
	content.add_child(UiKit.section(Lang.t("VIP — 30 dias  (%d 💎)") % VIP_COST))
	var res := UiKit.card(UiKit.GOLD)
	var box: VBoxContainer = res[1]
	box.add_child(UiKit.kv("🎒 Mochila", "50 slots (em vez de 30)"))
	box.add_child(UiKit.kv("❤ Cura grátis", "com cooldown (10 min)"))
	box.add_child(UiKit.kv("⚔ Arena", "10 lutas/dia (em vez de 5)"))
	box.add_child(UiKit.kv("🙏 Bênçãos", "2 simultâneas"))
	content.add_child(res[0])
	var can_buy := ss >= VIP_COST
	var buy := UiKit.action_big("👑 Renovar VIP (+30 dias)" if is_vip else "👑 Ativar VIP", _confirm_buy if (can_buy and not busy) else Callable())
	buy.disabled = busy or not can_buy
	content.add_child(buy)
	if not can_buy:
		content.add_child(UiKit.dim(Lang.t("Precisa de %d 💎 (você tem %d)") % [VIP_COST, ss]))
	# ── Saldo ──
	content.add_child(UiKit.section("Saldo"))
	content.add_child(UiKit.kv("💎 SoulStone", "%d" % ss, UiKit.GOLD))

# Banner VIP ativo em destaque (card dourado + glow), no espírito do banner do Templo.
func _render_active_banner(days: int) -> void:
	var res := UiKit.card(UiKit.GOLD)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	sb.shadow_color = Color(1.0, 0.8, 0.35, 0.28)
	sb.shadow_size = 8
	var head := Label.new()
	head.text = "👑 %s — %d %s" % [Lang.t("VIP ATIVO"), days, Lang.t("dia restante") if days == 1 else Lang.t("dias restantes")]
	head.add_theme_font_size_override("font_size", 20)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(head)
	content.add_child(pc)

# ── Ação: comprar/renovar VIP (premium → confirma) ──────────────────────────────
func _confirm_buy() -> void:
	if busy: return
	UiKit.confirm(self, Lang.t("Gastar %d 💎 SoulStones para %s VIP por 30 dias?") % [VIP_COST, Lang.t("renovar o") if bool(data.get("isVip", false)) else Lang.t("ativar o")],
		"👑 Confirmar", func() -> void: await _buy_vip())

func _buy_vip() -> void:
	if busy: return
	busy = true
	var r = await Api.vip_buy()
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		# resposta traz vipExpiresAt + soulStones atualizados → reflete no cache local
		data["isVip"] = true
		data["vipExpiresAt"] = str(j.get("vipExpiresAt", data.get("vipExpiresAt", "")))
		data["soulStones"] = int(j.get("soulStones", data.get("soulStones", 0)))
		busy = false
		_render()
		UiKit.flash(status, str(j.get("message", Lang.t("👑 VIP ativado!"))), 1)
	else:
		busy = false
		UiKit.show_error(status, r)

# Dias restantes a partir de um ISO timestamp (ex.: "2026-07-11T12:00:00").
func _days_left(iso: String) -> int:
	if iso.length() < 10:
		return 0
	var exp := Time.get_unix_time_from_datetime_string(iso)
	if exp <= 0:
		return 0
	var now := Time.get_unix_time_from_system()
	return maxi(0, int(ceil((exp - now) / 86400.0)))
