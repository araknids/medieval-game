class_name DarkButtonStyle
extends RefCounted
# ── Botões dark com HIERARQUIA (parecer de UI/UX de jogo) — [BOTAO_DARK] ──────────────
# Diagnóstico: ouro CLARO/saturado (#F5CE66) como moldura CHEIA em TODO botão = grita + sem hierarquia
# sobre um fundo claro. Correção: ouro ANTIGO (#C9A227) usado com PARCIMÔNIA + tiers:
#   • PRIMARY  (1 CTA, ex.: "Lutar"): couro escuro + borda dourada antiga fina (a única com moldura).
#   • SECONDARY (padrão — grade de atalhos, ações): couro escuro + SÓ um traço de bronze EMBAIXO que
#     ACENDE (dourado) no hover. Sem moldura dourada em todo botão (era o que puxava a vista).
# Separação do fundo claro = SOMBRA externa (não borda gritante). StyleBoxFlat = vetorial → nunca
# distorce em botão de qualquer tamanho. Cache estático. Uso: DarkButtonStyle.apply(btn[, tier]).

const PRIMARY := 0
const SECONDARY := 1

static var _cache := {}   # "tier:state" -> StyleBoxFlat

static func apply(btn: Button, tier := SECONDARY) -> void:
	btn.add_theme_stylebox_override("normal",  _box(tier, 0))
	btn.add_theme_stylebox_override("hover",   _box(tier, 1))
	btn.add_theme_stylebox_override("pressed", _box(tier, 2))
	btn.add_theme_stylebox_override("focus",   _box(tier, 1))
	if tier == PRIMARY:
		btn.add_theme_color_override("font_color",         Color8(0xF0, 0xDF, 0xA8))
		btn.add_theme_color_override("font_hover_color",   Color8(0xFB, 0xED, 0xC0))
		btn.add_theme_color_override("font_pressed_color", Color8(0xD8, 0xC6, 0x90))
	else:
		btn.add_theme_color_override("font_color",         Color8(0xD6, 0xC6, 0xA4))
		btn.add_theme_color_override("font_hover_color",   Color8(0xF0, 0xE2, 0xBE))
		btn.add_theme_color_override("font_pressed_color", Color8(0xBE, 0xAE, 0x8E))

static func _box(tier: int, state: int) -> StyleBoxFlat:
	var key := "%d:%d" % [tier, state]
	if _cache.has(key):
		return _cache[key]
	var sb := StyleBoxFlat.new()
	sb.set_corner_radius_all(3)
	sb.content_margin_left = 16; sb.content_margin_right = 16
	sb.content_margin_top = 9;   sb.content_margin_bottom = 9
	# sombra externa: separa o botão do fundo claro (o "pop" vem daqui, não de borda gritante)
	sb.shadow_color = Color(0, 0, 0, 0.38)
	sb.shadow_size = 3
	sb.shadow_offset = Vector2(0, 2)
	if tier == PRIMARY:
		match state:
			0: sb.bg_color = Color8(0x2E, 0x20, 0x10); sb.border_color = Color8(0xC9, 0xA2, 0x27)
			1: sb.bg_color = Color8(0x3C, 0x2A, 0x16); sb.border_color = Color8(0xE0, 0xB5, 0x3A)
			2: sb.bg_color = Color8(0x1E, 0x14, 0x08); sb.border_color = Color8(0x8C, 0x6E, 0x22)
		sb.set_border_width_all(2)
	else:  # SECONDARY — couro escuro + moldura fina DISCRETA (bronze fosco) + base levemente mais forte;
		match state:                 # acende (dourado) no hover. Botão "inteiro", mas bem mais quieto que o CTA.
			0: sb.bg_color = Color8(0x24, 0x1A, 0x12); sb.border_color = Color8(0x6E, 0x56, 0x26)
			1: sb.bg_color = Color8(0x30, 0x24, 0x18); sb.border_color = Color8(0xC9, 0xA2, 0x27)
			2: sb.bg_color = Color8(0x19, 0x12, 0x0C); sb.border_color = Color8(0x4A, 0x3A, 0x1C)
		sb.set_border_width_all(1)   # moldura fina completa (parece botão inteiro, não "pela metade")
		sb.border_width_bottom = 2   # base 1px mais grossa = leve "plaqueta gravada"
	_cache[key] = sb
	return sb
