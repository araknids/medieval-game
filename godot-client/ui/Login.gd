extends Control
# ── Tela de LOGIN + REGISTRO + entrar automaticamente ────────────────────────────
# Fundo 3D + título dourado + caixa de pedra. Login ({username,password}) OU Registro
# ({username,warriorName,email,password} — o backend já loga). "Entrar automaticamente"
# salva o TOKEN (JWT, ~7 dias, revogável — NÃO a senha) em user://session.cfg e valida no boot
# (get_warrior); se ainda vale, entra direto; se expirou, cai no login. [MIGRACAO_GODOT]

signal logged_in

const MenuFx := preload("res://ui/MenuFx.gd")
const SESSION := "user://session.cfg"   # creds locais p/ auto-login (por usuário, fora do git)

var fx: MenuFx
var box: VBoxContainer        # caixa do formulário (reconstruída ao trocar de modo)
var status: Label
var mode := "login"           # "login" | "register"
var _busy := false
var user_edit: LineEdit
var wname_edit: LineEdit
var email_edit: LineEdit
var pass_edit: LineEdit
var auto_check: CheckBox
var gender_sel := "MALE"       # escolha de gênero no registro (cosmético) [OUTFITS_FEMALE]

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	fx = MenuFx.new()   # o castelo 3D vem do App (persistente atrás de tudo) — não monta o próprio aqui
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var outer := VBoxContainer.new()
	outer.add_theme_constant_override("separation", 14)
	center.add_child(outer)
	outer.add_child(fx.title("⚔ MEDIEVAL", 64))
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", fx.panel())
	outer.add_child(panel)
	box = VBoxContainer.new()
	box.custom_minimum_size = Vector2(360, 0)
	box.add_theme_constant_override("separation", 10)
	panel.add_child(box)
	_build_form()
	await _try_auto_login()

# (Re)constrói o formulário conforme o modo (login/registro).
func _build_form() -> void:
	for c in box.get_children():
		c.queue_free()
	wname_edit = null
	email_edit = null
	var is_reg := mode == "register"
	var head := Label.new()
	head.text = "Criar conta" if is_reg else "Entrar"
	head.add_theme_font_size_override("font_size", 20)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(head)
	user_edit = UiKit.input("usuário")
	box.add_child(user_edit)
	if is_reg:
		wname_edit = UiKit.input("nome do guerreiro (máx 20)")
		wname_edit.max_length = 20   # [NICK_LIMIT] casa com o @Size(max=20) do backend
		box.add_child(wname_edit)
		email_edit = UiKit.input("email")
		box.add_child(email_edit)
		box.add_child(_gender_picker())
	pass_edit = UiKit.input("senha (mín. 8)" if is_reg else "senha")
	pass_edit.secret = true
	box.add_child(pass_edit)
	pass_edit.text_submitted.connect(func(_t: String) -> void: await _submit())
	auto_check = CheckBox.new()
	auto_check.text = "Entrar automaticamente"
	auto_check.button_pressed = true
	auto_check.add_theme_color_override("font_color", UiKit.TEXT)
	box.add_child(auto_check)
	var btn := fx.button("Criar conta" if is_reg else "Entrar")
	btn.custom_minimum_size = Vector2(0, 42)
	btn.pressed.connect(_submit)
	box.add_child(btn)
	var toggle := Button.new()
	toggle.flat = true
	toggle.text = "Já tenho conta — entrar" if is_reg else "Não tem conta? Criar uma"
	toggle.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	toggle.pressed.connect(func() -> void:
		mode = "login" if is_reg else "register"
		_build_form())
	box.add_child(toggle)
	status = Label.new()
	status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(status)
	_prefill_dev()

# Seletor de gênero (cosmético) p/ o registro — dois botões radio (♂/♀). [OUTFITS_FEMALE]
func _gender_picker() -> HBoxContainer:
	gender_sel = "MALE"
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	var lbl := Label.new()
	lbl.text = "Gênero:"
	lbl.add_theme_color_override("font_color", UiKit.TEXT)
	row.add_child(lbl)
	var grp := ButtonGroup.new()
	var bm := Button.new()
	bm.text = "♂ Masc"
	bm.toggle_mode = true
	bm.button_group = grp
	bm.button_pressed = true
	bm.toggled.connect(func(on: bool) -> void: gender_sel = "MALE" if on else gender_sel)
	row.add_child(bm)
	var bf := Button.new()
	bf.text = "♀ Fem"
	bf.toggle_mode = true
	bf.button_group = grp
	bf.toggled.connect(func(on: bool) -> void: gender_sel = "FEMALE" if on else gender_sel)
	row.add_child(bf)
	return row

# Pré-preenche do login.cfg (dev) se existir — conveniência local.
func _prefill_dev() -> void:
	var cf := ConfigFile.new()
	if cf.load("res://login.cfg") == OK:
		if user_edit and user_edit.text == "":
			user_edit.text = str(cf.get_value("login", "user", ""))
		if pass_edit and pass_edit.text == "":
			pass_edit.text = str(cf.get_value("login", "pass", ""))

func _submit() -> void:
	if _busy: return
	if user_edit.text.strip_edges() == "" or pass_edit.text == "":
		UiKit.flash(status, Lang.t("Preencha usuário e senha."), 2)
		return
	_busy = true
	UiKit.show_loading(self)
	var r: Dictionary
	if mode == "register":
		r = await Api.register(user_edit.text, wname_edit.text, email_edit.text, pass_edit.text, gender_sel)
	else:
		r = await Api.login(user_edit.text, pass_edit.text)
	_busy = false
	if r.get("ok") and Api.token != "":
		_save_session(user_edit.text)
		logged_in.emit()
	else:
		UiKit.flash(status, UiKit.err_text(r), 2)

# Auto-login: valida o TOKEN salvo (não a senha). Se ainda vale, entra direto; se expirou
# (ou foi revogado), mostra o login com o usuário já preenchido.
func _try_auto_login() -> void:
	var cf := ConfigFile.new()
	if cf.load(SESSION) != OK:
		return
	var u := str(cf.get_value("auth", "user", ""))
	if u != "" and user_edit:
		user_edit.text = u            # prefill do usuário (NÃO é segredo)
	var tok := str(cf.get_value("auth", "token", ""))
	if tok == "":
		return
	Api.token = tok                   # tenta com o token salvo
	UiKit.show_loading(self)
	_busy = true
	var r = await Api.get_warrior()   # request autenticado leve = valida o token
	_busy = false
	if r.get("ok"):
		logged_in.emit()
	else:
		Api.token = ""                # expirou/inválido → limpa e cai no login
		_clear_token()
		UiKit.flash(status, "Sessão expirada — entre de novo.", 0)

# Salva a sessão: guarda o TOKEN (expira em ~7 dias, revogável) — NUNCA a senha. username só p/ prefill.
func _save_session(username: String) -> void:
	var cf := ConfigFile.new()
	cf.set_value("auth", "user", username)
	if auto_check and auto_check.button_pressed and Api.token != "":
		cf.set_value("auth", "token", Api.token)
		cf.set_value("auth", "auto", true)
	cf.save(SESSION)   # desmarcado → sem token (limpa o auto-login anterior)

# Zera só o token salvo (mantém o usuário p/ prefill).
func _clear_token() -> void:
	var cf := ConfigFile.new()
	cf.load(SESSION)
	cf.set_value("auth", "token", "")
	cf.save(SESSION)
