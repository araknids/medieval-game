extends Control
# ── Tela TEMPLO ──────────────────────────────────────────────────────────────────
# GET /api/temple (HP/cura/bênçãos/VIP) + /api/inventory (itens p/ proteção) + /api/warrior (carteira).
# Cura, abençoar (buff), proteger item. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")   # [TEMPLO_PADRE] retrato do padre (PixelLab)

var data: Dictionary = {}        # cache de /api/temple
var warrior: Dictionary = {}     # /api/warrior (carteira do header)
var equipped: Array = []         # itens equipados (p/ proteção)
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "⛪ Templo", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/temple", "/api/inventory", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var inv = rs[1]
	equipped = []
	if inv.get("ok") and inv.get("json") is Array:
		for it in inv["json"]:
			if it is Dictionary and it.get("equipped", false):
				equipped.append(it)
	var wr = rs[2]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	_render_priest_header()
	# [TEMPLO_UI] HP, bênção ativa e CURA removidos daqui — HP/buff no topbar, cura no botão do header.
	content.add_child(UiKit.section("🙏 Bênçãos"))
	_render_buff_options()
	content.add_child(UiKit.section(Lang.t("Proteção de Itens (%d/%d)") % [int(data.get("protectedCount", 0)), int(data.get("maxProtected", 3))]))
	_render_protection()

# [TEMPLO_PADRE] Cabeçalho do padre: retrato (PixelLab) + nome + texto de sabor (como o mercador na Loja).
func _render_priest_header() -> void:
	var head := HBoxContainer.new()
	head.add_theme_constant_override("separation", 12)
	var icon_key := "priest" if Icons.tex("priest") != null else ("temple" if Icons.tex("temple") != null else "")
	if icon_key != "":
		var portrait := Icons.rect(icon_key, 72)
		portrait.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		head.add_child(portrait)
	var col := VBoxContainer.new()
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	col.add_theme_constant_override("separation", 3)
	var name_lbl := Label.new()
	name_lbl.text = Lang.t("Padre Anselmo")
	name_lbl.add_theme_font_size_override("font_size", 22)
	name_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	col.add_child(name_lbl)
	var quote := UiKit.dim("\"%s\"" % Lang.t("Fui enviado pelos céus para amparar os que sofrem. Aqui o cansado encontra cura, o bravo recebe bênçãos para a batalha, e o que lhe é precioso fica a salvo. Descanse um instante, guerreiro — que a luz o acompanhe lá fora."))
	quote.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(quote)
	head.add_child(col)
	content.add_child(head)

# ── Bênçãos disponíveis ────────────────────────────────────────────────────────
func _render_buff_options() -> void:
	if str(data.get("activeBuff2", "")) == "" and bool(data.get("isVip", false)):
		content.add_child(UiKit.dim("👑 Slot de bênção VIP disponível"))
	var buffs = data.get("buffs", [])
	if not (buffs is Array) or buffs.is_empty():
		content.add_child(UiKit.dim("Nenhuma bênção disponível agora."))
		return
	var cells: Array = []
	for b in buffs:
		if b is Dictionary:
			cells.append(b)
	content.add_child(UiKit.grid(self, cells, _buff_cell, false, 150, 5))   # bênçãos ESTREITAS (até 5 col) → ocupam menos largura

# [TEMPLO_UI] Bênção = CARD CLICÁVEL inteiro (clica = aplica), sem botão gigante. Header [ícone] nome +
# custo; sub = efeito. Efeito completo no tooltip do card. [CARD_BOTAO]
func _buff_cell(b: Dictionary) -> Control:
	var eff_txt := str(b.get("effect", ""))
	var nm_txt := str(b.get("displayName", b.get("id", "?")))
	# efeito + custo vão no TOOLTIP (card estreito mostra só ícone + nome)
	var tip := nm_txt + ("\n" + eff_txt if eff_txt != "" else "")
	var on_click := func() -> void: _apply_buff(str(b.get("id", "")))
	var res := UiKit.clickable_card(UiKit.GOLD_SOFT, on_click, true, tip)
	var pc: PanelContainer = res[0]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 2)
	# linha única: ícone + nome (estreito)
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 5)
	var ic := Label.new(); ic.text = str(b.get("icon", "✨")); ic.add_theme_font_size_override("font_size", 16)
	top.add_child(ic)
	var nm := Label.new(); nm.text = nm_txt
	nm.add_theme_font_size_override("font_size", 13); nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL; nm.clip_text = true
	top.add_child(nm)
	box.add_child(top)
	# custo (linha 2, compacta)
	box.add_child(UiKit.coin_box(int(b.get("bronzeCost", 0)), 12))
	return res[0]

# ── Proteção de itens ──────────────────────────────────────────────────────────
func _render_protection() -> void:
	# [TEMPLO_UI] intro compacto numa LINHA só: explicação + custo (autowrap OFF p/ não espremer no HBox).
	var prot := HBoxContainer.new(); prot.add_theme_constant_override("separation", 5)
	var pa := UiKit.dim("Protegidos não se perdem em PvP ·")
	pa.autowrap_mode = TextServer.AUTOWRAP_OFF; pa.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(pa)
	var coin := UiKit.coin_box(50, 14); coin.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(coin)
	var pb := UiKit.dim("/item")
	pb.autowrap_mode = TextServer.AUTOWRAP_OFF; pb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(pb)
	content.add_child(prot)
	if equipped.is_empty():
		content.add_child(UiKit.dim("Nenhum item equipado — equipe no 🎒 Inventário para proteger."))
		return
	content.add_child(UiKit.grid(self, equipped, _protect_cell, false, 230, 3))   # grid bem compacto (3 col)

# [TEMPLO_UI] Item de proteção = CARD CLICÁVEL (clica = protege/remove). Selo de escudo quando protegido;
# sem botão. Tooltip explica a ação. [CARD_BOTAO]
func _protect_cell(it: Dictionary) -> Control:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var guarded := bool(it.get("guarded", false))
	var id := int(it.get("id", 0))
	var on_click := func() -> void:
		if guarded:
			_unprotect(id)
		else:
			_protect(id)
	var tip := Lang.t("Tocar para remover a proteção") if guarded else Lang.t("Tocar para proteger (não se perde em PvP) · custo 50 bronze")
	var res := UiKit.clickable_card(col, on_click, true, tip)
	var pc: PanelContainer = res[0]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(8)
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 2)
	# [TEMPLO_UI] ÍCONE REAL do item (arma/armadura/anel…) à esquerda — igual ao Inventário.
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	var icon := UiKit.item_icon_for(it, 36)
	if icon != null:
		icon.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(icon)
	var info := VBoxContainer.new(); info.add_theme_constant_override("separation", 1)
	info.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	info.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 14)
	nm.add_theme_color_override("font_color", col)
	nm.clip_text = true
	info.add_child(nm)
	if guarded:
		info.add_child(UiKit.icon_text("🛡 Protegido", 12, UiKit.OK, 16))
	else:
		info.add_child(UiKit.icon_text("🔒 Tocar para proteger", 12, UiKit.TEXT_DIM, 16))
	row.add_child(info)
	box.add_child(row)
	return res[0]

# ── Ações: await DIRETO na API; trata o resultado e re-sincroniza ───────────────
func _apply_buff(buff_id: String) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_apply_buff(buff_id))
	busy = false

func _protect(id: int) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_protect(id))
	busy = false

func _unprotect(id: int) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_unprotect(id))
	busy = false

# r = resultado JÁ resolvido; re-sincroniza e mostra o feedback.
func _do(r: Variant) -> void:
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", "OK"))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)
