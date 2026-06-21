extends PanelContainer
class_name ItemTooltipCard
# [ITEM_TOOLTIP] Card de item que mostra um tooltip RICO no hover. O Godot chama _make_custom_tooltip
# só quando vai EXIBIR o tooltip (lazy — nunca pré-monta os 200 cards). Guarda o item; o painel é
# montado por UiKit.item_tooltip_panel. ⚠️ precisa de tooltip_text != "" (use " "), senão o override
# nem dispara. item vazio → o Godot usa o tooltip_text normal (ex.: rótulo do slot vazio).

var item: Dictionary = {}
var equipped_slot := false   # slot equipado → rodapé "(clique p/ desequipar)" + sem comparação

func _make_custom_tooltip(_for_text: String) -> Object:
	if item.is_empty():
		return null
	return UiKit.item_tooltip_panel(item, {"equipped": equipped_slot})
