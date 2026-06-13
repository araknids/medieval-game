extends Control
# ── Tela de LOGIN (tela-título, estilo grimdark/Diablo) ──────────────────────────
# Fundo 3D noturno + título dourado + caixa de login em painel de pedra. Loga via Api;
# ao logar emite `logged_in`. Pré-preenche do login.cfg (dev). [MIGRACAO_GODOT]

signal logged_in

const MenuFx := preload("res://ui/MenuFx.gd")

var user_edit: LineEdit
var pass_edit: LineEdit
var status: Label
var btn: Button

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var fx := MenuFx.new()
	fx.bg_3d(self, "castle")
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var outer := VBoxContainer.new()
	outer.add_theme_constant_override("separation", 14)
	center.add_child(outer)
	outer.add_child(fx.title("⚔ MEDIEVAL", 64))
	# painel de pedra com a caixa de login
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", fx.panel())
	outer.add_child(panel)
	var box := VBoxContainer.new()
	box.custom_minimum_size = Vector2(340, 0)
	box.add_theme_constant_override("separation", 10)
	panel.add_child(box)
	user_edit = UiKit.input("usuário")
	box.add_child(user_edit)
	pass_edit = UiKit.input("senha"); pass_edit.secret = true
	box.add_child(pass_edit)
	btn = fx.button("Entrar")
	btn.custom_minimum_size = Vector2(0, 42)
	box.add_child(btn)
	status = Label.new()
	status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(status)
	btn.pressed.connect(_do_login)
	pass_edit.text_submitted.connect(func(_t: String) -> void: _do_login())
	var cf := ConfigFile.new()
	if cf.load("res://login.cfg") == OK:
		user_edit.text = str(cf.get_value("login", "user", ""))
		pass_edit.text = str(cf.get_value("login", "pass", ""))

func _do_login() -> void:
	if Api.token != "":
		logged_in.emit(); return
	btn.disabled = true
	UiKit.flash(status, "Conectando…", 0)
	var r = await Api.login(user_edit.text, pass_edit.text)
	btn.disabled = false
	if r.get("ok") and Api.token != "":
		logged_in.emit()
	else:
		UiKit.flash(status, "Login falhou (%s)" % str(r.get("status", "?")), 2)
