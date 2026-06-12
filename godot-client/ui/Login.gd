extends Control
# ── Tela de LOGIN ────────────────────────────────────────────────────────────────
# Loga via Api (POST /api/auth/login); ao logar, emite `logged_in` (o App vai pra Personagem).
# Pré-preenche do login.cfg (dev) se existir. UI montada por código. [MIGRACAO_GODOT]

signal logged_in

var user_edit: LineEdit
var pass_edit: LineEdit
var status: Label
var btn: Button

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.08, 0.07, 0.10)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var box := VBoxContainer.new()
	box.custom_minimum_size = Vector2(340, 0)
	box.add_theme_constant_override("separation", 12)
	center.add_child(box)
	var title := Label.new()
	title.text = "⚔ Medieval"
	title.add_theme_font_size_override("font_size", 44)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(title)
	var sub := Label.new()
	sub.text = "cliente Godot"
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	sub.modulate = Color(1, 1, 1, 0.5)
	box.add_child(sub)
	box.add_child(_spacer(16))
	user_edit = LineEdit.new()
	user_edit.placeholder_text = "usuário"
	box.add_child(user_edit)
	pass_edit = LineEdit.new()
	pass_edit.placeholder_text = "senha"
	pass_edit.secret = true
	box.add_child(pass_edit)
	btn = Button.new()
	btn.text = "Entrar"
	btn.custom_minimum_size = Vector2(0, 40)
	box.add_child(btn)
	status = Label.new()
	status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status.modulate = Color(1, 0.7, 0.6)
	box.add_child(status)
	btn.pressed.connect(_do_login)
	pass_edit.text_submitted.connect(func(_t: String) -> void: _do_login())
	# dev: pré-preenche do login.cfg (gitignored)
	var cf := ConfigFile.new()
	if cf.load("res://login.cfg") == OK:
		user_edit.text = str(cf.get_value("login", "user", ""))
		pass_edit.text = str(cf.get_value("login", "pass", ""))

func _do_login() -> void:
	if Api.token != "":
		logged_in.emit(); return
	btn.disabled = true
	status.modulate = Color(1, 1, 1, 0.6)
	status.text = "Conectando…"
	var r = await Api.login(user_edit.text, pass_edit.text)
	btn.disabled = false
	if r.get("ok") and Api.token != "":
		logged_in.emit()
	else:
		status.modulate = Color(1, 0.6, 0.55)
		status.text = "Login falhou (%s)" % str(r.get("status", "?"))

func _spacer(h: int) -> Control:
	var s := Control.new()
	s.custom_minimum_size = Vector2(0, h)
	return s
