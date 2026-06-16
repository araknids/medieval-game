class_name Outfits
extends RefCounted
# ── Mapa de roupas por ITEM (paper-doll + ícone de item) [OUTFITS_CLASSE] ────────────
# O visual da armadura é do PRÓPRIO ITEM (não de quem veste) — qualquer classe usa qualquer item.
# Cada item tem um tema FIXO (backend `outfitTheme`: KNIGHT/NOBLE/RANGER/PEASANT); a ideia é os bônus
# do item favorecerem a classe do tema (Knight→guerreiro etc.), mas sem trava de uso.
# Pack: "Modular Character Outfits - Fantasy" (Quaternius). Peças em assets/outfits/<tema>/,
# ícones 2D em assets/outfits/icons/. Usado por DollView, BustView e UiKit.

# classId (enum do backend) → tema "natural" da classe (só p/ telas que queiram um tema base; o
# visual do item NÃO depende disso — ver theme_for_item).
const THEME_FOR_CLASS := {
	"WARRIOR":  "knight",
	"MERCHANT": "noble",
	"ARCHER":   "ranger",
	"RECRUIT":  "peasant",
}
const DEFAULT_THEME := "peasant"
# ordem ESPELHA o backend InventoryService.OUTFIT_THEMES (fallback determinístico bate com o servidor)
const THEME_ORDER := ["knight", "noble", "ranger", "peasant", "wizard"]

# tema → slot (ItemType) → LISTA de variantes da peça. A peça mostrada é escolhida deterministicamente
# pelo NOME do item (igual ao tema) → drops diferentes mostram variantes diferentes (elmo aberto vs com
# chifre, peitoral de placa vs pano, ombreira redonda vs espinho vs cachecol…) sem seletor. [OUTFITS_VARIANTES]
# Peasant/Wizard não têm elmo/ombreira no pack → ficam de cabeça/ombro NUS.
const SLOT_PIECE := {
	"knight": {
		"ARMOR":    ["Male_Knight_Body_Armor", "Male_Knight_Body_Cloth"],
		"GLOVES":   ["Male_Knight_Arms"],
		"BOOTS":    ["Male_Knight_Feet_Armor"],
		"PANTS":    ["Male_Knight_Legs_Armor"],
		"HELMET":   ["Male_Knight_Head_Armet", "Male_Knight_Head_Horns"],
		"SHOULDER": ["Male_Knight_Acc_Pauldron_Round", "Male_Knight_Acc_Pauldron_Spike", "Male_Knight_Acc_Scarf"],
	},
	"noble": {
		"ARMOR":    ["Male_Noble_Body"],
		"GLOVES":   ["Male_Noble_Arms"],
		"BOOTS":    ["Male_Noble_Feet"],
		"PANTS":    ["Male_Noble_Legs"],
		"HELMET":   ["Male_Noble_Head_Crown"],
		"SHOULDER": ["Male_Noble_Acc_Pauldron", "Male_Noble_Acc_Pauldron_Lion", "Male_Noble_Acc_Gorget"],
	},
	"ranger": {
		"ARMOR":    ["Male_Ranger_Body"],
		"GLOVES":   ["Male_Ranger_Arms"],
		"BOOTS":    ["Male_Ranger_Feet_Boots"],
		"PANTS":    ["Male_Ranger_Legs"],
		"HELMET":   ["Male_Ranger_Head_Hood"],
		"SHOULDER": ["Male_Ranger_Acc_Pauldron"],
	},
	"peasant": {
		"ARMOR":    ["Male_Peasant_Body"],
		"GLOVES":   ["Male_Peasant_Arms"],
		"BOOTS":    ["Male_Peasant_Feet"],
		"PANTS":    ["Male_Peasant_Legs"],
	},
	"wizard": {
		"ARMOR":    ["Male_Wizard_Body"],
		"GLOVES":   ["Male_Wizard_Arms"],
		"BOOTS":    ["Male_Wizard_Feet"],
		"PANTS":    ["Male_Wizard_Legs"],
	},
}

# ── Female [OUTFITS_FEMALE] ── mesmas pastas/temas; nomes diferem (Feet/Legs SEM "_Armor", "Pauldrons" plural).
const SLOT_PIECE_FEMALE := {
	"knight": {
		"ARMOR":    ["Female_Knight_Body_Armor", "Female_Knight_Body_Cloth"],
		"GLOVES":   ["Female_Knight_Arms"],
		"BOOTS":    ["Female_Knight_Feet"],
		"PANTS":    ["Female_Knight_Legs"],
		"HELMET":   ["Female_Knight_Head_Armet", "Female_Knight_Head_Horns"],
		"SHOULDER": ["Female_Knight_Acc_Pauldrons_Round", "Female_Knight_Acc_Pauldrons_Spike", "Female_Knight_Acc_Scarf"],
	},
	"noble": {
		"ARMOR":    ["Female_Noble_Body"],
		"GLOVES":   ["Female_Noble_Arms"],
		"BOOTS":    ["Female_Noble_Feet"],
		"PANTS":    ["Female_Noble_Legs"],
		"HELMET":   ["Female_Noble_Head_Crown"],
		"SHOULDER": ["Female_Noble_Acc_Pauldron", "Female_Noble_Acc_Pauldron_Lion", "Female_Noble_Acc_Gorget"],
	},
	"ranger": {
		"ARMOR":    ["Female_Ranger_Body"],
		"GLOVES":   ["Female_Ranger_Arms"],
		"BOOTS":    ["Female_Ranger_Feet"],
		"PANTS":    ["Female_Ranger_Legs"],
		"HELMET":   ["Female_Ranger_Head_Hood"],
		"SHOULDER": ["Female_Ranger_Acc_Pauldrons"],
	},
	"peasant": {
		"ARMOR":    ["Female_Peasant_Body"],
		"GLOVES":   ["Female_Peasant_Arms"],
		"BOOTS":    ["Female_Peasant_Feet"],
		"PANTS":    ["Female_Peasant_Legs"],
	},
	"wizard": {
		"ARMOR":    ["Female_Wizard_Body"],
		"GLOVES":   ["Female_Wizard_Arms"],
		"BOOTS":    ["Female_Wizard_Feet"],
		"PANTS":    ["Female_Wizard_Legs"],
	},
}

# ── Raridade → cor + brilho [SKIN_RARIDADE] ──
# 5 raridades (1=Comum,2=Incomum,3=Raro,4=Épico,5=Lendário) caem em 3 BANDAS:
#   banda 0 = Comum/Incomum · banda 1 = Raro · banda 2 = Épico/Lendário.
const BAND_FOR_RARITY := [0, 0, 1, 2, 2]   # rarity 1..5 → banda 0/1/2
# Cada tema escolhe QUAL variante de cor (1=base, 2="_2", 3="_3") vai em cada banda [Comum, Raro, Lendário].
# Curadoria do dono: o visual "melhor" (ex.: dourado) fica no Lendário. [SKIN_RARIDADE]
const BAND_VARIANT := {
	"knight":  [3, 1, 2],   # Comum=cor3, Raro=cor1, Lendário=cor2 (dourado)
	"noble":   [3, 2, 1],
	"ranger":  [3, 1, 2],
	"peasant": [1, 3, 2],
	"wizard":  [1, 2, 3],   # não especificado → ordem natural
}
# emissão por raridade (mesmas cores do Weapons.RARITY_TINT): branco/verde/azul/roxo/dourado.
const RARITY_TINT := [Color(0.82, 0.84, 0.88), Color(0.45, 0.85, 0.45), Color(0.35, 0.60, 1.0), Color(0.72, 0.40, 0.95), Color(1.0, 0.78, 0.28)]
# energia de emissão por raridade — PLACEHOLDER, afinar em engine (0 = sem brilho). Valores BAIXOS de
# propósito: emissão de superfície inteira lava a armadura se forte (validado no render de preview).
const RARITY_GLOW := [0.0, 0.05, 0.09, 0.14, 0.20]

# slots de armadura que este sistema veste/icona (RING/NECKLACE/WEAPON/SHIELD ficam de fora)
const ARMOR_SLOTS := ["ARMOR", "GLOVES", "BOOTS", "PANTS", "HELMET", "SHOULDER"]

static func theme_for_class(class_id: String) -> String:
	return THEME_FOR_CLASS.get(class_id.to_upper(), DEFAULT_THEME)

# normaliza "male"/"female" (qualquer caixa/lixo → male)
static func _norm_gender(gender: String) -> String:
	return "female" if gender.to_lower().begins_with("f") else "male"

# "knight" -> "Knight" (p/ o nome-base das texturas T_<Tema>_*)
static func _theme_cap(theme: String) -> String:
	return theme.substr(0, 1).to_upper() + theme.substr(1)

# Fallback determinístico p/ quando o item não trouxe outfitTheme (legado/algum DTO): soma dos bytes
# UTF-8 do nome % 4 — MESMA fórmula do backend (InventoryService.outfitThemeFor) → resultado bate.
static func _theme_from_name(name: String) -> String:
	if name == "":
		return DEFAULT_THEME
	var sum := 0
	for b in name.to_utf8_buffer():
		sum += int(b)
	return THEME_ORDER[sum % THEME_ORDER.size()]

# Tema (pasta) de um ITEM: usa o `outfitTheme` do backend; cai no fallback pelo nome se faltar.
static func theme_for_item(it: Dictionary) -> String:
	var t := str(it.get("outfitTheme", "")).to_lower()
	if SLOT_PIECE.has(t):
		return t
	return _theme_from_name(str(it.get("name", "")))

# pasta da peça derivada do basename
static func _dir_for(base: String) -> String:
	if "Knight" in base: return "knight"
	if "Noble" in base: return "noble"
	if "Ranger" in base: return "ranger"
	if "Wizard" in base: return "wizard"
	return "peasant"

# Índice da VARIANTE de peça p/ um item (estável pelo nome+slot → mesmo item = mesma peça sempre;
# itens diferentes variam). count<=1 → 0. [OUTFITS_VARIANTES]
static func _variant_index(seed_name: String, slot: String, count: int) -> int:
	if count <= 1:
		return 0
	var s := 0
	for b in (seed_name + slot).to_utf8_buffer():
		s += int(b)
	return s % count

# basename da peça p/ (tema, slot, gênero), escolhendo a variante pelo seed (nome do item). seed=="" → 1ª.
static func _base(theme: String, slot: String, gender := "male", seed_name := "") -> String:
	var table: Dictionary = SLOT_PIECE_FEMALE if _norm_gender(gender) == "female" else SLOT_PIECE
	var m: Dictionary = table.get(theme, {})
	var opts: Array = m.get(slot, [])
	if opts.is_empty():
		return ""
	return str(opts[_variant_index(seed_name, slot, opts.size())])

# ── por TEMA (folder) ── peça 3D é gênero-aware; ícone 2D fica no Male (thumb neutro, sem render female ainda).
static func piece_path_theme(theme: String, slot: String, gender := "male", seed_name := "") -> String:
	var base := _base(theme, slot, gender, seed_name)
	return "res://assets/outfits/%s/%s.gltf" % [_dir_for(base), base] if base != "" else ""

static func icon_path_theme(theme: String, slot: String) -> String:
	var base := _base(theme, slot, "male")   # ícone 2D = variante principal (índice 0)
	return "res://assets/outfits/icons/%s.png" % base if base != "" else ""

# ── por ITEM (o caso padrão — visual vem do item) ──
static func piece_path_item(it: Dictionary, slot: String, gender := "male") -> String:
	return piece_path_theme(theme_for_item(it), slot, gender, str(it.get("name", "")))

static func icon_path_item(it: Dictionary, slot: String) -> String:
	return icon_path_theme(theme_for_item(it), slot)

static func is_armor_slot(slot: String) -> bool:
	return ARMOR_SLOTS.has(slot)

# ── Recolor por raridade [SKIN_RARIDADE] ──
# Variante de cor (1/2/3) que este tema usa p/ a raridade dada (via banda + BAND_VARIANT).
static func variant_for(theme: String, rarity: int) -> int:
	var band: int = BAND_FOR_RARITY[clamp(rarity - 1, 0, 4)]
	var order: Array = BAND_VARIANT.get(theme, [1, 2, 3])
	return int(order[band])

# Caminho da textura de albedo da variante de cor p/ (tema, raridade).
static func variant_tex_path(theme: String, rarity: int) -> String:
	var v: int = variant_for(theme, rarity)
	var suffix := "" if v == 1 else "_%d" % v   # 1→base, 2→"_2", 3→"_3"
	return "res://assets/outfits/%s/T_%s%s_BaseColor.png" % [theme, _theme_cap(theme), suffix]

# Recolore UMA MeshInstance3D de armadura conforme a raridade: troca SÓ o albedo p/ a variante de cor
# (só na superfície cujo albedo é T_<Tema>_BaseColor — pula pele exposta). SEM emissão: a emissão de
# superfície inteira tingia o corpo de neon e escondia o item — a raridade vira a AURA de brasas (rarity_aura).
static func recolor_mesh(mi: MeshInstance3D, theme: String, rarity: int) -> void:
	if mi == null or mi.mesh == null or rarity < 1 or not SLOT_PIECE.has(theme):
		return
	var base_tex := "T_%s_BaseColor" % _theme_cap(theme)
	var variant_path := variant_tex_path(theme, rarity)   # pode ser a cor1 (base) — aí o swap é no-op
	if not ResourceLoader.exists(variant_path):
		return
	var vtex := load(variant_path)
	for s in mi.mesh.get_surface_count():
		var mat := mi.get_active_material(s)
		if mat == null or not (mat is BaseMaterial3D):
			continue
		var alb: Texture2D = (mat as BaseMaterial3D).albedo_texture
		# só a superfície da ARMADURA (albedo == T_<Tema>_BaseColor); pele exposta fica intacta.
		if alb == null or not alb.resource_path.get_file().begins_with(base_tex):
			continue
		var m: BaseMaterial3D = mat.duplicate()
		m.albedo_texture = vtex   # só a cor da variante (banda de raridade) — sem brilho na malha
		mi.set_surface_override_material(s, m)

# Aura de raridade: BRASAS sutis subindo ao redor do corpo (estilo do foguinho de vitória), na cor da
# raridade. Comum/Incomum (1-2) = sem aura; Raro+ acende. `completeness` (0..1 = peças do set / 6) deixa
# MAIS visível com o set completo (mais brasas + mais opacas). Filho do lutador → segue ele. [SKIN_RARIDADE]
static func rarity_aura(rarity: int, completeness := 1.0) -> GPUParticles3D:
	if rarity < 3:
		return null
	var rt: int = clamp(rarity - 1, 0, 4)
	var tint: Color = RARITY_TINT[rt]
	var c := clampf(completeness, 0.0, 1.0)
	var p := GPUParticles3D.new()
	p.local_coords = false                   # brasas sobem no MUNDO (não viram "cano" quando o corpo cai) + trilha
	p.amount = maxi(4, int(round(5.0 + 12.0 * c)))   # set completo = mais brasas
	p.lifetime = 1.5
	p.preprocess = 1.2                       # já começa "cheio" (sem aparecer do nada)
	p.randomness = 0.5
	p.position = Vector3(0, 0.55, 0)         # emite ao redor do corpo (tornozelos→peito)
	var m := ParticleProcessMaterial.new()
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	m.emission_box_extents = Vector3(0.26, 0.5, 0.16)
	m.direction = Vector3.UP
	m.spread = 6.0
	m.initial_velocity_min = 0.35
	m.initial_velocity_max = 0.8
	m.gravity = Vector3(0, 0.35, 0)          # sobem devagar (fogo)
	m.scale_min = 0.25
	m.scale_max = 0.6
	var a := 0.14 + 0.42 * c                  # SUTIL; mais opaco com set completo
	var grad := Gradient.new()
	grad.set_color(0, Color(tint.r, tint.g, tint.b, 0.0))
	grad.add_point(0.25, Color(tint.r, tint.g, tint.b, a))
	grad.set_color(2, Color(tint.r, tint.g, tint.b, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = grad
	m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new()
	q.size = Vector2(0.055, 0.07)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.vertex_color_use_as_albedo = true
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	qm.emission_enabled = true
	qm.emission = tint
	qm.emission_energy_multiplier = 1.0
	q.material = qm
	p.draw_pass_1 = q
	p.set_meta("rarity_aura", true)
	return p
