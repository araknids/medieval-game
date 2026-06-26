extends Control
# ── Tela CONFIGURAÇÕES — idioma PT/EN (engrenagem no header) ────────────────────────
# Troca o idioma via Lang (TranslationServer) e recarrega a cena raiz → tudo re-renderiza
# no novo idioma. Padrão visual: UiKit. Futuro: som, gráficos, conta. [I18N]

signal go_back
signal logout   # [LOGOUT] App/Shell limpam o token + session e voltam pro Login

const GameSettings := preload("res://GameSettings.gd")   # [CONFIG] toggle de gore

var content: VBoxContainer
var status: Label
var wallet: Label

func _ready() -> void:
	var ui := UiKit.scaffold(self, "⚙ Configurações", func() -> void: go_back.emit(), func() -> void: _render(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	_render()

func _refresh() -> void:   # o Shell chama _refresh ao revisitar; aqui é só re-render (sem request)
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	content.add_child(UiKit.section("Idioma"))
	content.add_child(UiKit.dim("Escolha o idioma da interface."))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	content.add_child(row)
	row.add_child(_lang_btn("Português", "pt"))
	row.add_child(_lang_btn("English", "en"))
	# [CONFIG] Gore: sangue + desmembramento nas batalhas (desligar p/ alcançar mais público)
	content.add_child(UiKit.section("Conteúdo"))
	content.add_child(UiKit.dim("Sangue e desmembramento nas batalhas. Desligue para um visual mais leve (vale na próxima luta)."))
	var grow := HBoxContainer.new()
	grow.add_theme_constant_override("separation", 10)
	content.add_child(grow)
	grow.add_child(_gore_btn("🩸 Ligado", true))
	grow.add_child(_gore_btn("Desligado", false))
	# [PLAYTEST_FIX] Fundo animado: o cenário 3D atrás dos menus (desligar p/ máquina fraca / preferência)
	content.add_child(UiKit.section("Gráficos"))
	content.add_child(UiKit.dim("Fundo 3D animado atrás dos menus. Desligue para um visual mais leve / máquina fraca."))
	var bgrow := HBoxContainer.new()
	bgrow.add_theme_constant_override("separation", 10)
	content.add_child(bgrow)
	bgrow.add_child(_animbg_btn("Ligado", true))
	bgrow.add_child(_animbg_btn("Desligado", false))
	# [LOGOUT] Conta: sair desconecta e volta pro login (o auto-login salvo e limpo).
	content.add_child(UiKit.section("Conta"))
	content.add_child(UiKit.dim("Sair desconecta sua conta e volta para a tela de login."))
	var crow := HBoxContainer.new()
	crow.add_theme_constant_override("separation", 10)
	content.add_child(crow)
	crow.add_child(UiKit.action_big("Sair da conta", func() -> void: _on_logout()))
	# [GENDER] A escolha de sexo saiu daqui: é definida na CRIAÇÃO do personagem e só troca
	# pagando SoulStone na tela do VIP (não é mais grátis nas Configurações).

func _gore_btn(label: String, on: bool) -> Button:
	var active := GameSettings.gore_enabled() == on
	var b := UiKit.action_big(("✓ " + label) if active else label, func() -> void: _pick_gore(on))
	b.disabled = active
	return b

func _pick_gore(on: bool) -> void:
	if GameSettings.gore_enabled() == on:
		return
	GameSettings.set_gore(on)
	UiKit.flash(status, "Sangue/desmembramento: %s" % ("ligado" if on else "desligado"), 1)
	_render()   # atualiza o ✓ (o efeito vale na próxima batalha)

func _animbg_btn(label: String, on: bool) -> Button:
	var active := GameSettings.animated_bg_enabled() == on
	var b := UiKit.action_big(("✓ " + label) if active else label, func() -> void: _pick_animbg(on))
	b.disabled = active
	return b

func _pick_animbg(on: bool) -> void:
	if GameSettings.animated_bg_enabled() == on:
		return
	GameSettings.set_animated_bg(on)
	var app = get_tree().current_scene   # App é a cena raiz → aplica o fundo ao vivo
	if app != null and app.has_method("apply_animated_bg"):
		app.apply_animated_bg()
	UiKit.flash(status, "Fundo animado: %s" % ("ligado" if on else "desligado"), 1)
	_render()

# [LOGOUT] abre um MODAL de confirmação; confirmar emite o sinal (App/Shell limpam o token).
func _on_logout() -> void:
	UiKit.confirm(self, "Sair da conta? Você voltará para a tela de login.", "Sair",
			func() -> void: logout.emit())

func _lang_btn(label: String, code: String) -> Button:
	var active := Lang.current() == code
	var b := UiKit.action_big(("✓ " + label) if active else label, func() -> void: _pick_lang(code))
	b.auto_translate_mode = Control.AUTO_TRANSLATE_MODE_DISABLED   # nomes de idioma ficam nativos sempre
	b.disabled = active
	return b	
 
func _pick_lang(code: String) -> void:
	if Lang.current() == code:
		return
	Lang.set_lang(code)
	# recarrega a cena raiz → tudo re-renderiza no idioma novo (o cache do Shell rebuilda do zero)
	get_tree().change_scene_to_file("res://App.tscn")
