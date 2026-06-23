extends RefCounted
# ── Helper compartilhado de MONSTROS (bundle Quaternius em res://assets/monsters/) ──
# Auto-escala pelo bounding box (altura-alvo) + pé no chão, e acha a anim idle.
# Usado pelo MonsterViewer (calibração) e, depois, pelo BattleReplay (inimigo PvE).
# Métodos de INSTÂNCIA (sem class_name, igual ao Scenery.gd). Assets gitignored.

const DIR := "res://assets/monsters/"
const TARGET_H := 1.8          # altura-alvo padrão (m) — o auto-fit escala o bicho pra isso
const FACE_OFFSET_DEG := 0.0   # giro global do bundle (ajustar depois de ver no viewer)
const HOVER_H := 0.9           # altura padrão que um VOADOR flutua acima do chão (m)

# Voadores: não grudam no chão, flutuam (HOVER_H). Lista EXPLÍCITA (sem auto-detecção por
# anim — vários têm clip "fly" mas devem andar). Voa só quem está aqui. Alturas finas no passo 2.
const FLYERS := [
	"Alpaking", "Alpaking Evolved", "Armabee", "Armabee Evolved",
	"Demon", "Dragon",
]

# ── ROSTER: altura-alvo por monstro (a batalha auto-fita pra esse tamanho) ────────
const SIZE_SMALL := 1.3
const SIZE_NORMAL := 1.8
const SIZE_BIG := 2.6
const SIZE_BOSS := 3.4
# só os que fogem do NORMAL; o resto cai em SIZE_NORMAL.
const SIZE := {
	"Bunny": SIZE_SMALL, "Cat": SIZE_SMALL, "Chicken": SIZE_SMALL, "Frog": SIZE_SMALL,
	"Fish": SIZE_SMALL, "Birb": SIZE_SMALL, "Armabee": SIZE_SMALL, "Glub": SIZE_SMALL,
	"Cactoro": SIZE_SMALL,
	"Dino": SIZE_BIG, "Dragon": SIZE_BIG, "Goleling Evolved": SIZE_BIG,
	"Mushnub Evolved": SIZE_BIG, "Alpaking Evolved": SIZE_BIG, "Armabee Evolved": SIZE_BIG,
	"Glub Evolved": SIZE_BIG, "Monkroose": SIZE_BIG,
	"Dragon Evolved": SIZE_BOSS, "Mushroom King": SIZE_BOSS,
}

# ── MAPA nome-do-inimigo (backend) → monstro. Primeira palavra que casar VENCE (ordem
# importa: específico antes de genérico). Inimigos HUMANOIDES (cavaleiro/bandido/orc/
# guarda…) não casam nenhuma → viram HUMANO (mesmo rig do player). Backend: ~70 nomes
# temáticos (Young Dragon, Stone Golem, Lesser Demon, Sea Serpent, Mine Wraith, Mushnub…).
const NAME_MAP := [
	["dragon", "Dragon"], ["demon", "Demon"], ["devil", "Blue Demon"], ["infernal", "Blue Demon"],
	["lich", "Ghost Skull"], ["skull", "Ghost Skull"], ["bone", "Ghost Skull"],
	["specter", "Ghost"], ["spectre", "Ghost"], ["wraith", "Ghost"], ["ghost", "Ghost"],
	["spirit", "Ghost"], ["husk", "Ghost"], ["phantom", "Ghost"], ["shade", "Ghost"],
	["golem", "Goleling Evolved"], ["ogre", "Goleling Evolved"], ["troll", "Goleling Evolved"],
	["kraken", "Glub Evolved"], ["leviathan", "Glub Evolved"], ["serpent", "Glub"],
	["crab", "Glub"], ["drowned", "Glub"], ["tide", "Glub"], ["kelp", "Glub"],
	["fish", "Fish"], ["sea", "Glub"], ["aquatic", "Fish"],
	["worm", "Cactoro"], ["spider", "Hywirl"], ["aberration", "Hywirl"], ["thing", "Hywirl"],
	["bat", "Armabee"], ["bee", "Armabee"], ["wasp", "Armabee"],
	["mushroom", "Mushroom King"], ["mush", "Mushnub"], ["fungus", "Mushnub"], ["spore", "Mushnub"],
	["frog", "Frog"], ["toad", "Frog"], ["bear", "Dino"], ["boar", "Dino"], ["behemoth", "Dino"],
	["dino", "Dino"], ["lizard", "Dino"], ["raptor", "Dino"], ["beast", "Monkroose"],
	["wolf", "Monkroose"], ["ape", "Monkroose"], ["bunny", "Bunny"], ["rabbit", "Bunny"],
	["cat", "Cat"], ["feline", "Cat"], ["chicken", "Chicken"], ["bird", "Birb"], ["raven", "Birb"],
	["crystal", "Green Spiky Blob"], ["gem", "Green Spiky Blob"], ["prismatic", "Green Spiky Blob"],
	["glimmer", "Green Spiky Blob"], ["blob", "Green Blob"], ["slime", "Green Blob"],
	["ooze", "Green Blob"], ["gel", "Green Blob"], ["alien", "Alien"], ["cactoro", "Cactoro"],
	["deepworm", "Cactoro"], ["siren", "Glub"],
	# — PT: nomes LOCALIZADOS (o backend traduz monster.* p/ português; casa por palavra inteira). [I18N]
	# Ex.: "Serpente Marinha" casava só o EN "serpent" (palavra inteira ≠ "serpente") → vinha humano. Agora vira besta.
	["serpente", "Glub"], ["caranguejo", "Glub"], ["afogado", "Glub"], ["sereia", "Glub"], ["abissal", "Glub"],
	["leviatã", "Glub Evolved"], ["leviata", "Glub Evolved"], ["peixe", "Fish"],
	["aranha", "Hywirl"], ["aberração", "Hywirl"], ["aberracao", "Hywirl"], ["prismático", "Hywirl"], ["prismatico", "Hywirl"],
	["morcego", "Armabee"], ["morcegos", "Armabee"], ["verme", "Cactoro"], ["ogro", "Goleling Evolved"],
	["espectro", "Ghost"], ["fantasma", "Ghost"], ["gema", "Green Spiky Blob"], ["lodo", "Green Blob"], ["gosma", "Green Blob"],
	# — PT: gaps de monstro de VERDADE que vinham humano (o jogo roda em PT → backend manda nome PT). [I18N]
	# Os HUMANOIDES (bandido/cavaleiro/guarda/carrasco/sacerdote/cultista/pirata…) NÃO entram aqui de
	# propósito — continuam humano (mesmo rig do player), que é o certo pro tema da Fortaleza/quests.
	["demônio", "Demon"], ["demonio", "Demon"],                       # Demônio Menor, Campeão Infernal (+ "infernal" EN já casa Blue Demon)
	["dragão", "Dragon"], ["dragao", "Dragon"],                       # Dragão Jovem
	["esqueleto", "Ghost Skull"], ["caveira", "Ghost Skull"],         # Guerreiro Esqueleto
	["casca", "Ghost"], ["morto", "Ghost"], ["cadáver", "Ghost"], ["cadaver", "Ghost"], ["zumbi", "Ghost"], ["apodrecido", "Ghost"], # mortos-vivos: Casca do Desertor/Vigia, Morto Rastejante, Cadáver Empoado
	["fera", "Monkroose"], ["besta", "Monkroose"], ["lobo", "Monkroose"], ["macaco", "Monkroose"], # Fera/Lobo Selvagem, Besta de Cristal
	["javali", "Dino"], ["urso", "Dino"], ["lagarto", "Dino"],        # Javali Gigante, Urso Enfurecido
	["maré", "Glub"], ["mare", "Glub"],                               # Servo da Maré, A Maré Ávida
	["coisa", "Hywirl"], ["horror", "Hywirl"],                        # Coisa-da-Vertigem/Altar, Horror Dragado/da Torre
	["cristal", "Green Spiky Blob"], ["joias", "Green Spiky Blob"], ["joia", "Green Spiky Blob"], # Coração/Besta de Cristal, Horror Incrustado de Joias
	["cogumelo", "Mushroom King"], ["sereia", "Glub"],               # (sereia já existe; reforço) Sereia das Sombras
]

# palavras que indicam BOSS → aumenta a altura ×1.25
const BOSS_WORDS := [
	"boss", "tyrant", "behemoth", "ancient", "elder", "greater", "colossal",
	"king", "lord", "evolved", "champion", "warden",
]

# Os 30 do bundle (nomes = nome do arquivo sem .glb). Hard-coded p/ rodar em build exportada
# (DirAccess em res:// só lista no editor).
const NAMES := [
	"Alien", "Alpaking", "Alpaking Evolved", "Armabee", "Armabee Evolved",
	"Birb", "Blue Demon", "Bunny", "Cactoro", "Cat", "Chicken", "Demon",
	"Dino", "Dragon", "Dragon Evolved", "Fish", "Frog", "Ghost", "Ghost Skull",
	"Glub", "Glub Evolved", "Goleling", "Goleling Evolved", "Green Blob",
	"Green Spiky Blob", "Hywirl", "Monkroose", "Mushnub", "Mushnub Evolved",
	"Mushroom King",
]

# Instancia o .glb (self-contained: mesh + rig + anims próprias). null se faltar.
func instance(mname: String) -> Node3D:
	var path := mname if mname.begins_with("res://") else \
			DIR + mname + ("" if mname.to_lower().ends_with(".glb") else ".glb")
	var ps: PackedScene = load(path)
	if ps == null:
		push_warning("monstro não carregou: %s" % path)
		return null
	return ps.instantiate() as Node3D

# AABB combinado de TODAS as malhas, no espaço LOCAL do root (independe da transform do root).
# (root precisa estar na árvore p/ global_transform valer.)
func local_aabb(root: Node3D) -> AABB:
	var acc := AABB()
	var has := false
	var inv := root.global_transform.affine_inverse()
	for vi: VisualInstance3D in _visuals(root):
		var a: AABB = vi.get_aabb()
		var rel: Transform3D = inv * vi.global_transform
		for i in 8:
			var corner := a.position + Vector3(
				a.size.x if (i & 1) else 0.0,
				a.size.y if (i & 2) else 0.0,
				a.size.z if (i & 4) else 0.0)
			var p := rel * corner
			if has:
				acc = acc.expand(p)
			else:
				acc = AABB(p, Vector3.ZERO); has = true
	return acc

# Escala o monstro pra `target_h` e ajusta a altura: pés no chão (hover=0) ou flutuando
# `hover` metros acima dele. Retorna {scale, height, ground_y} (ground_y = y dos pés no chão).
func fit(node: Node3D, target_h := TARGET_H, hover := 0.0) -> Dictionary:
	var box := local_aabb(node)
	var h: float = maxf(box.size.y, 0.001)
	var s := target_h / h
	node.scale = Vector3(s, s, s)
	var ground_y := -box.position.y * s     # min.y * escala → sobe pra encostar no chão
	node.position.y = ground_y + hover
	return {"scale": s, "height": h, "ground_y": ground_y}

# Voador? Só quem está na lista EXPLÍCITA FLYERS (o `_node` fica pro caso de voltar a auto-detectar).
func is_flyer(mname: String, _node: Node3D) -> bool:
	return mname in FLYERS

# Altura-alvo de um monstro (roster); default NORMAL.
func size_for(mname: String) -> float:
	return SIZE.get(mname, SIZE_NORMAL)

# Metadados p/ spawnar um monstro JÁ conhecido (override manual ou pick): file+altura+hover.
func meta_for(mname: String) -> Dictionary:
	return {"kind": "monster", "file": mname, "target_h": size_for(mname),
			"hover": (HOVER_H if mname in FLYERS else 0.0)}

# Decide como encenar um inimigo do backend pelo NOME:
#   {kind:"human"}                                  → humanoide/desconhecido (mesmo rig do player)
#   {kind:"monster", file, target_h, hover}         → besta do bundle (auto-fit + hover)
func pick_for(enemy_name: String) -> Dictionary:
	# casa por PALAVRA inteira (hífen vira espaço) → "Young Dragon" casa "dragon",
	# mas "Escaped" NÃO casa "ape". Backend nomeia a besta como palavra ("Stone Golem").
	var nm := _strip_icon(enemy_name).to_lower().replace("-", " ")
	var padded := " " + nm + " "
	var file := ""
	for pair in NAME_MAP:
		if (" " + String(pair[0]) + " ") in padded:
			file = String(pair[1])
			break
	if file == "":
		return {"kind": "human"}   # sem palavra de besta → humano (cavaleiro/bandido/orc/etc.)
	var th := size_for(file)
	for bw in BOSS_WORDS:
		if (" " + String(bw) + " ") in padded:
			th *= 1.25
			break
	return {"kind": "monster", "file": file, "target_h": th,
			"hover": (HOVER_H if file in FLYERS else 0.0)}

# Tira o ícone de elemento (emoji + espaço) que o backend às vezes prefixa no nome.
func _strip_icon(s: String) -> String:
	var sp := s.find(" ")
	if sp > 0:
		var first := s.substr(0, sp)
		for ch in first:
			if ch.unicode_at(0) > 127:   # caractere não-ASCII = emoji/ícone
				return s.substr(sp + 1).strip_edges()
	return s

# Nome da anim idle (case-insensitive); senão a 1ª anim. "" se não houver AnimationPlayer/anim.
func find_idle(ap: AnimationPlayer) -> String:
	if ap == null: return ""
	var names := ap.get_animation_list()
	for n in names:
		if "idle" in String(n).to_lower():
			return String(n)
	return String(names[0]) if names.size() > 0 else ""

# Toca a idle (em loop) do monstro instanciado.
func play_idle(node: Node3D) -> void:
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	if ap == null: return
	var idle := find_idle(ap)
	if idle == "": return
	var a := ap.get_animation(idle)
	if a: a.loop_mode = Animation.LOOP_LINEAR
	ap.play(idle)

func _visuals(root: Node) -> Array:
	var out: Array = []
	var stack: Array = [root]
	while not stack.is_empty():
		var n: Node = stack.pop_back()
		if n is VisualInstance3D:
			out.append(n)
		for c in n.get_children():
			stack.append(c)
	return out
