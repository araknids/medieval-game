extends Control
# ── Tela CONFIGURAÇÕES — idioma PT/EN (engrenagem no header) ────────────────────────
# Troca o idioma via Lang (TranslationServer) e recarrega a cena raiz → tudo re-renderiza
# no novo idioma. Padrão visual: UiKit. Futuro: som, gráficos, conta. [I18N]

signal go_back

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
