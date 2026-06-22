extends Control
# [LEADERBOARDS] Hub de Amigos (ícone na topbar): amigos + pedidos de amizade + convites de guilda.
# "Mensagem" leva pro Correio com o nick preenchido (open_mail_to → Shell._open_mail_compose).
signal go_back
signal open_mail_to(recipient)

var content: VBoxContainer
var status: Label

func _ready() -> void:
	var ui := UiKit.scaffold(self, "Amigos",
		func() -> void: go_back.emit(),
		func() -> void: await _refresh(),
		UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	var fr = await Api.friends_list()
	var gi = await Api.guild_invites()
	UiKit.hide_loading()
	var friends_data: Dictionary = fr["json"] if (fr.get("ok") and fr.get("json") is Dictionary) else {}
	var invites: Array = []
	if gi.get("ok") and gi.get("json") is Dictionary:
		invites = gi["json"].get("invites", [])
	_render(friends_data, invites)

func _render(fr: Dictionary, guild_invites: Array) -> void:
	for c in content.get_children():
		c.queue_free()
	var incoming: Array = fr.get("incoming", [])
	var friends: Array = fr.get("friends", [])
	var outgoing: Array = fr.get("outgoing", [])
	if not incoming.is_empty():
		content.add_child(UiKit.section("Pedidos de amizade"))
		for f in incoming:
			content.add_child(_request_row(f, true))
	if not guild_invites.is_empty():
		content.add_child(UiKit.section("Convites de guilda"))
		for inv in guild_invites:
			content.add_child(_guild_invite_row(inv))
	content.add_child(UiKit.section("Amigos"))
	if friends.is_empty():
		content.add_child(UiKit.dim(Lang.t("Sem amigos ainda. Adicione tocando num jogador na Classificação.")))
	else:
		var list := VBoxContainer.new()
		list.add_theme_constant_override("separation", 5)
		for f in friends:
			list.add_child(_friend_row(f))
		content.add_child(UiKit.capped_scroll(list, 420.0))
	if not outgoing.is_empty():
		content.add_child(UiKit.section("Pedidos enviados"))
		for f in outgoing:
			content.add_child(_request_row(f, false))

func _friend_row(f: Dictionary) -> Control:
	var pid := int(f.get("playerId", 0))
	var pname := str(f.get("warriorName", "?"))
	var card := UiKit.card(UiKit.BRONZE)
	var vb: VBoxContainer = card[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	var nm := Label.new()
	nm.text = _named(f)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	row.add_child(nm)
	var sub := Label.new()
	sub.text = "Lv%d" % int(f.get("level", 1))
	sub.add_theme_font_size_override("font_size", 12)
	sub.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	row.add_child(sub)
	row.add_child(UiKit.small_btn(Lang.t("Mensagem"), func() -> void: open_mail_to.emit(pname)))
	row.add_child(UiKit.small_btn(Lang.t("Remover"), _do_remove.bind(pid), true))
	vb.add_child(row)
	return card[0]

func _request_row(f: Dictionary, incoming: bool) -> Control:
	var rid := int(f.get("requestId", 0))
	var card := UiKit.card(UiKit.GOLD_SOFT if incoming else UiKit.BRONZE)
	var vb: VBoxContainer = card[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	var nm := Label.new()
	nm.text = "%s  ·  Lv%d" % [str(f.get("warriorName", "?")), int(f.get("level", 1))]
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(nm)
	if incoming:
		row.add_child(UiKit.small_btn(Lang.t("Aceitar"), _do_accept.bind(rid)))
		row.add_child(UiKit.small_btn(Lang.t("Recusar"), _do_decline.bind(rid), true))
	else:
		row.add_child(UiKit.small_btn(Lang.t("Cancelar"), _do_decline.bind(rid), true))
	vb.add_child(row)
	return card[0]

func _guild_invite_row(inv: Dictionary) -> Control:
	var iid := int(inv.get("inviteId", 0))
	var card := UiKit.card(UiKit.GOLD_SOFT)
	var vb: VBoxContainer = card[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	var nm := Label.new()
	nm.text = Lang.t("%s — convidado por %s") % [str(inv.get("guildName", "?")), str(inv.get("inviterName", "?"))]
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	row.add_child(nm)
	row.add_child(UiKit.small_btn(Lang.t("Aceitar"), _do_invite_accept.bind(iid)))
	row.add_child(UiKit.small_btn(Lang.t("Recusar"), _do_invite_decline.bind(iid), true))
	vb.add_child(row)
	return card[0]

# ── ações ──
func _do_accept(rid: int) -> void:
	var r = await Api.friend_accept(rid)
	await _toast_refresh(r, Lang.t("Amigo adicionado."))

func _do_decline(rid: int) -> void:
	await Api.friend_decline(rid)
	await _refresh()

func _do_remove(pid: int) -> void:
	var r = await Api.friend_remove(pid)
	await _toast_refresh(r, Lang.t("Amigo removido."))

func _do_invite_accept(iid: int) -> void:
	var r = await Api.guild_invite_accept(iid)
	await _toast_refresh(r, Lang.t("Você entrou na guilda!"))

func _do_invite_decline(iid: int) -> void:
	await Api.guild_invite_decline(iid)
	await _refresh()

func _toast_refresh(r, ok_msg: String) -> void:
	if r.get("ok"):
		UiKit.toast(self, ok_msg, "members", 1)
		await _refresh()
	else:
		UiKit.toast(self, UiKit.err_text(r), "", 2)

func _named(f: Dictionary) -> String:
	var title := str(f.get("title", ""))
	return (("⟨%s⟩ " % title) if title != "" else "") + str(f.get("warriorName", "?"))
