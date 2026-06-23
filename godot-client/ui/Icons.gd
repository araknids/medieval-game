extends RefCounted
# ── Helper de ÍCONES de UI ────────────────────────────────────────────────────────
# Carrega os PNGs de assets/ui/icons/ por NOME (ex.: "world", "gold", "attr_strength").
# Se o ícone ainda não existe, cai no fallback (mantém o emoji do label) — assim dá pra
# fiar tudo já e os ícones que faltam aparecem sozinhos quando forem gerados. [MIGRACAO_GODOT]
# Uso: const Icons := preload("res://ui/Icons.gd"); Icons.label_button(b, "world", "🌍 Mundo")

const DIR := "res://assets/ui/icons/"

# Textura do ícone `key`, ou null se não houver arquivo.
static func tex(key: String) -> Texture2D:
	var p := DIR + key + ".png"
	if ResourceLoader.exists(p):
		return load(p) as Texture2D
	return null

# Seta o ícone num Button (escala pro tamanho do botão). Retorna true se aplicou.
static func set_icon(b: Button, key: String) -> bool:
	var t := tex(key)
	if t == null:
		return false
	b.icon = t
	b.expand_icon = true
	b.add_theme_constant_override("h_separation", 8)
	add_hover(b, HOVER_GROW_BTN, HOVER_BRIGHT_BTN)   # [HOVER_ICON] botão com ícone: pop (largo só clareia, ver _hover_to)
	_anim_hover(b, key)                              # [HOVER_ICON_ANIM] cicla anim/<key>/ no hover (se existir)
	return true

# Button com ícone + texto. label_with_emoji = "🌍 Mundo": com ícone vira ícone + "Mundo";
# sem ícone, mantém "🌍 Mundo" (fallback). Retorna o próprio botão.
static func label_button(b: Button, key: String, label_with_emoji: String) -> Button:
	if set_icon(b, key):
		var i := label_with_emoji.find(" ")
		if i >= 0:
			b.text = label_with_emoji.substr(i + 1).strip_edges()
		else:
			b.text = label_with_emoji
	else:
		b.text = label_with_emoji
	return b

# ── Emoji-marcador → ícone PixelLab [ICONES_MARCADOR] ───────────────────────────────
# Quando um texto de UI começa com um emoji que é ÍCONE (cabeçalho de seção, título de card,
# linha de recompensa, badge), trocamos pelo ícone. Inline no meio de frase NÃO entra aqui.
const EMOJI_ICON := {
	"🎒": "carried", "🔒": "locked", "🎁": "gift", "📦": "package",
	"💀": "skull", "☠": "skull", "💎": "gem", "⭐": "star", "⚠": "warning",
	"⏳": "hourglass", "🐟": "fish", "🎣": "map_fishing", "⛏": "map_mines",
	"🔎": "act_pan", "🏃": "act_flee", "🙏": "bless",
	"👑": "node_boss", "📜": "node_event", "⚔": "node_combat", "🗡": "node_combat",
	"🛡": "slot_shield", "🔥": "elem_fire", "🏆": "achievements", "🥇": "gold",
	"🍺": "tavern", "🏰": "map_fortress", "🌍": "world", "🗺": "world", "🔨": "forge",
	"💰": "gold", "🪙": "bronze", "🥈": "silver", "🥉": "bronze",
	"❤": "hp", "⚡": "stamina", "🏅": "achievements", "🎯": "tower",
	"📬": "mail", "📭": "mail", "📩": "mail", "📨": "mail", "✉": "mail",
	"❗": "quest_alert", "❕": "quest_alert",   # [QUEST_BADGE] "!" de daily disponível
	"🗓": "daily", "📅": "daily", "📆": "daily",   # [SEM_WEB_EMOJI] cabeçalho Daily Quests
	"🏦": "treasury", "👥": "members", "😓": "fatigue", "💾": "territory",   # [GUILD_TABS] tira emoji de web da Guilda
}

# Separa um emoji-ícone do INÍCIO do texto. Retorna [icon_key, resto] — ["", texto] se não houver.
# Tira o seletor de variação (U+FE0F) p/ casar "⚔️"/"⚠️" etc.
static func split_emoji(text: String) -> Array:
	var t := text.strip_edges()
	var sp := t.find(" ")
	if sp <= 0:
		return ["", text]
	var head := t.substr(0, sp).replace("️", "")
	if EMOJI_ICON.has(head) and tex(EMOJI_ICON[head]) != null:
		return [EMOJI_ICON[head], t.substr(sp + 1).strip_edges()]
	return ["", text]

# ItemType (backend) → arquivo de ícone do slot. ARMOR=peito, PANTS=perna, SHOULDER reusa o peito.
const ITEM_TYPE_ICON := {
	"WEAPON": "slot_weapon", "SHIELD": "slot_shield", "HELMET": "slot_helmet",
	"ARMOR": "slot_chest", "PANTS": "slot_legs", "BOOTS": "slot_boots",
	"GLOVES": "slot_gloves", "SHOULDER": "slot_chest", "RING": "slot_ring",
	"NECKLACE": "slot_necklace",
}

# Textura do ícone de um ITEM pelo seu type (ex.: "WEAPON" → slot_weapon.png). null se não mapeado.
static func item_tex(item_type: String) -> Texture2D:
	var key: String = ITEM_TYPE_ICON.get(item_type.to_upper(), "")
	if key == "":
		return null
	return tex(key)

# ── Tooltips dos ícones [ICON_TOOLTIP] ──────────────────────────────────────────────
# key → [pt, en]. Hover explica o que o ícone significa (o jogador não precisa adivinhar).
# Self-contido (PT+EN aqui) → não polui o Lang.gd. Só keys com sentido próprio entram; ícones
# de seção que JÁ vêm com rótulo de texto ganham um reforço curto. Sem entrada = sem tooltip.
const ICON_TIP := {
	# Moedas
	"bronze": ["Bronze — moeda básica", "Bronze — basic coin"],
	"silver": ["Prata — vale 100 bronze", "Silver — worth 100 bronze"],
	"gold": ["Ouro — vale 100 prata", "Gold — worth 100 silver"],
	"soulstone": ["Pedra da Alma — moeda premium (VIP)", "SoulStone — premium currency (VIP)"],
	# Vitais
	"hp": ["Vida (HP) — regenera com o tempo", "Health (HP) — regenerates over time"],
	"stamina": ["Estamina — gasta nas ações; enche em 1h", "Stamina — spent on actions; refills in 1h"],
	# Atributos
	"attr_strength": ["Força — dano corpo a corpo", "Strength — melee damage"],
	"attr_dexterity": ["Destreza — acerto e dano de arco", "Dexterity — accuracy and bow damage"],
	"attr_constitution": ["Constituição — vida máxima", "Constitution — max health"],
	"attr_agility": ["Agilidade — esquiva e golpes extra", "Agility — dodge and extra hits"],
	"attr_luck": ["Sorte — chance de crítico", "Luck — critical hit chance"],
	"attr_intellect": ["Intelecto — reservado p/ o Mago", "Intellect — reserved for the Mage"],
	"stat_atk": ["Ataque — dano por golpe", "Attack — damage per hit"],
	# Elementos (roda: Fogo→Ar→Terra→Água→Fogo) — texto igual ao do Templo [ELEMENTOS]
	"elem_fire": ["Fogo — +25% de dano vs Ar · −25% vs Água", "Fire — +25% damage vs Air · −25% vs Water"],
	"elem_water": ["Água — +25% de dano vs Fogo · −25% vs Terra", "Water — +25% damage vs Fire · −25% vs Earth"],
	"elem_earth": ["Terra — +25% de dano vs Água · −25% vs Ar", "Earth — +25% damage vs Water · −25% vs Air"],
	"elem_air": ["Ar — +25% de dano vs Terra · −25% vs Fogo", "Air — +25% damage vs Earth · −25% vs Fire"],
	# Slots de equipamento
	"slot_weapon": ["Arma", "Weapon"], "slot_helmet": ["Elmo", "Helmet"],
	"slot_chest": ["Peitoral", "Chest armor"], "slot_legs": ["Calças", "Leggings"],
	"slot_boots": ["Botas", "Boots"], "slot_gloves": ["Luvas", "Gloves"],
	"slot_shield": ["Escudo", "Shield"], "slot_ring": ["Anel", "Ring"],
	"slot_necklace": ["Colar", "Necklace"],
	# Guilda [GUILD_TABS]
	"treasury": ["Tesouro da guilda", "Guild treasury"],
	"members": ["Membros", "Members"],
	"crown": ["Líder da guilda", "Guild leader"],
	"fatigue": ["Fadiga de guerra (debuff)", "War fatigue (debuff)"],
	# Nós da Incursão
	"node_combat": ["Combate — luta contra um inimigo", "Combat — fight an enemy"],
	"node_elite": ["Elite — inimigo mais forte", "Elite — tougher enemy"],
	"node_treasure": ["Tesouro — baú com loot", "Treasure — a loot chest"],
	"node_event": ["Evento — uma escolha", "Event — a choice"],
	"node_camp": ["Acampamento — garante o loot coletado", "Camp — bank your loot"],
	"node_boss": ["Chefe — fim da incursão", "Boss — end of the delve"],
	# [QUESTS_ICONE] selos de TIPO de opção de quest
	"opt_roll": ["Teste de atributo — rolagem de dado", "Skill check — a dice roll"],
	"opt_peace": ["Sem combate — escolha pacífica", "No combat — a peaceful choice"],
	# Marcadores
	"carried": ["Carregado — em risco se você cair", "Carried — at risk if you fall"],
	"locked": ["Travado / protegido", "Locked / protected"],
	"gift": ["Recompensa", "Reward"], "package": ["Item", "Item"],
	"skull": ["Derrota / perigo", "Defeat / danger"],
	"gem": ["Joia — encaixa em soquetes", "Gem — fits into sockets"],
	"star": ["XP / experiência", "XP / experience"],
	"warning": ["Atenção", "Warning"], "hourglass": ["Tempo / espera", "Time / wait"],
	"fish": ["Peixe — consuma p/ estamina", "Fish — consume for stamina"],
	"quest_alert": ["Quest disponível", "Quest available"],
	# Ações
	"act_mine": ["Minerar", "Mine"], "act_pan": ["Garimpar", "Pan for gold"],
	"act_flee": ["Fugir", "Flee"], "heal": ["Curar", "Heal"], "bless": ["Bênção", "Blessing"],
	"equip": ["Equipar", "Equip"], "sell": ["Vender", "Sell"],
	"mount": ["Montaria", "Mount"], "pet": ["Mascote", "Pet"],
	"settings": ["Configurações", "Settings"], "declare_war": ["Declarar guerra", "Declare war"],
	# Sub-abas
	"tab_bag": ["Mochila", "Bag"], "tab_attributes": ["Atributos", "Attributes"],
	"tab_abilities": ["Habilidades", "Abilities"],
	# Seções (reforço do rótulo de texto)
	"world": ["Mundo — reinos, quests e zonas", "World — kingdoms, quests and zones"],
	"temple": ["Templo — cura e bênçãos", "Temple — healing and blessings"],
	"forge": ["Forja — criar e reparar itens", "Forge — craft and repair items"],
	"shop": ["Loja", "Shop"], "tower": ["Torre — andares com chefes", "Tower — floors with bosses"],
	"arena": ["Arena — duelos PvP", "Arena — PvP duels"], "guild": ["Guilda", "Guild"],
	"daily": ["Recompensa diária", "Daily reward"],
	"work": ["Trabalho — renda passiva", "Work — passive income"],
	"auction": ["Leilão — mercado entre jogadores", "Auction — player market"],
	"stash": ["Baú — guardar itens", "Stash — store items"],
	"tavern": ["Taverna — beber, chat e buff", "Tavern — drink, chat and buff"],
	"vip": ["VIP / Pedra da Alma", "VIP / SoulStone"],
	"abilities": ["Habilidades de classe", "Class abilities"],
	"achievements": ["Conquistas e títulos", "Achievements and titles"],
	"territory": ["Território — guerra de guildas", "Territory — guild war"],
	"mail": ["Correio", "Mail"], "character": ["Personagem", "Character"],
	"inventory": ["Inventário / mochila", "Inventory / bag"],
	# Abas do Leilão [LEILAO_ABAS]
	"auction_buy": ["Comprar de outros jogadores", "Buy from other players"],
	"auction_listings": ["Suas listagens ativas", "Your active listings"],
	"auction_sell": ["Listar um item da mochila", "List an item from your bag"],
}

# Descrição do ícone `key` no idioma atual; "" se não houver. [ICON_TOOLTIP]
static func tip(key: String) -> String:
	if not ICON_TIP.has(key):
		return ""
	var pair: Array = ICON_TIP[key]
	return str(pair[1]) if Lang.current() == "en" else str(pair[0])

# ── Hover juice [HOVER_ICON] ────────────────────────────────────────────────────────
# Passar o mouse num ícone → ele cresce + clareia suave (e volta). Frame-rate independent
# (Tween com overshoot). Mexe SÓ em scale/modulate → não causa reflow nem empurra o layout.
# Aplicado automaticamente em rect() (cresce+clareia) e set_icon() (botão: só clareia, p/ não
# estourar full-width) → todo ícone do jogo ganha o hover sem tocar em nenhuma tela.
const HOVER_GROW := 1.13          # quanto o ícone (TextureRect) cresce no hover
const HOVER_GROW_BTN := 1.08      # botão pequeno (nav/ação) cresce; largo/full-width só clareia
const HOVER_BRIGHT := 1.20        # quanto clareia (multiplica o modulate)
const HOVER_BRIGHT_BTN := 1.10    # botão clareia menos (já tem hover de cor no StyleBox)
const HOVER_BTN_MAX_W := 260.0    # acima disso (ou expand-fill) o botão só clareia (não escala)
const HOVER_SPIKE := 1.30         # "estalo" de brilho ao entrar (spike→assenta) = punch [UIUX]

# Liga o hover-pop num Control. grow=1.0 → sem escala (só brilho). Idempotente. [HOVER_ICON]
static func add_hover(node: Control, grow := HOVER_GROW, bright := HOVER_BRIGHT) -> void:
	if node == null or node.has_meta("hover_fx"):
		return
	node.set_meta("hover_fx", true)
	node.set_meta("hover_grow", grow)
	node.set_meta("hover_bright", bright)
	node.set_meta("hover_base", node.modulate)
	node.mouse_entered.connect(_hover_to.bind(node, true))
	node.mouse_exited.connect(_hover_to.bind(node, false))

static func _hover_to(node: Control, on: bool) -> void:
	if not is_instance_valid(node):
		return
	node.pivot_offset = node.size * 0.5        # cresce a partir do centro
	var base: Color = node.get_meta("hover_base", Color.WHITE)
	var grow: float = node.get_meta("hover_grow", HOVER_GROW)
	var bright: float = node.get_meta("hover_bright", HOVER_BRIGHT)
	# [HOVER_ICON] botão largo/expand-fill não escala (estouraria a largura) — só clareia.
	if grow > 1.0 and node is Button and ((node.size_flags_horizontal & Control.SIZE_EXPAND) != 0 or node.size.x > HOVER_BTN_MAX_W):
		grow = 1.0
	var s := grow if on else 1.0
	_kill_meta_tween(node, "hover_sw")
	_kill_meta_tween(node, "hover_mw")
	# escala (overshoot ao entrar, suave ao sair)
	var sw := node.create_tween().set_trans(Tween.TRANS_BACK if on else Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
	sw.tween_property(node, "scale", Vector2(s, s), 0.14)
	node.set_meta("hover_sw", sw)
	# brilho: ao ENTRAR dá um "estalo" (spike→assenta) = punch; ao sair volta suave [UIUX]
	var mw := node.create_tween()
	if on:
		var rest := Color(base.r * bright, base.g * bright, base.b * bright, base.a)
		var spike := Color(base.r * HOVER_SPIKE, base.g * HOVER_SPIKE, base.b * HOVER_SPIKE, base.a)
		mw.tween_property(node, "modulate", spike, 0.06).set_ease(Tween.EASE_OUT)
		mw.tween_property(node, "modulate", rest, 0.18).set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
	else:
		mw.tween_property(node, "modulate", base, 0.12)
	node.set_meta("hover_mw", mw)

static func _kill_meta_tween(node: Control, key: String) -> void:
	var t: Tween = node.get_meta(key, null)
	if t != null and t.is_valid():
		t.kill()

# ── Ícone ANIMADO no hover [HOVER_ICON_ANIM] ─────────────────────────────────────────
# Se existir res://assets/ui/icons/anim/<key>/f0..fN.png, o botão CICLA esses quadros
# enquanto o mouse está em cima (idle temático: forja martelando, globo girando…) e volta
# ao quadro 0 ao sair. Frames gerados no PixelLab (create_map_object + animate_object).
const ANIM_DIR := "res://assets/ui/icons/anim/"
const ANIM_FPS := 0.11            # segundos por quadro (~9 fps) — loop ambiente calmo, não frenético [UIUX]

static func _anim_frames(key: String) -> Array:
	var out: Array = []
	var n := 0
	while true:
		var p := ANIM_DIR + key + "/f%d.png" % n
		if not ResourceLoader.exists(p):
			break
		out.append(load(p)); n += 1
	return out

# Liga o ciclo-no-hover num botão (se houver anim p/ a key). Rest = quadro 0.
static func _anim_hover(b: Button, key: String) -> void:
	if b.has_meta("anim_fx"):
		return
	var frames := _anim_frames(key)
	if frames.size() < 2:
		return
	b.set_meta("anim_fx", true)
	b.set_meta("anim_frames", frames)
	b.icon = frames[0]
	b.mouse_entered.connect(_anim_start.bind(b))
	b.mouse_exited.connect(_anim_stop.bind(b))

static func _anim_start(b: Button) -> void:
	if not is_instance_valid(b):
		return
	var frames: Array = b.get_meta("anim_frames", [])
	if frames.size() < 2:
		return
	# [UIUX] LOOP enquanto o mouse está em cima (o dono quer ficar animado parado); reseta ao sair (_anim_stop).
	var prev: Tween = b.get_meta("anim_tw", null)
	if prev != null and prev.is_valid():
		return                              # já animando neste hover → não reinicia
	var tw := b.create_tween().set_loops()  # loop contínuo até mouse_exited
	for fr in frames:
		tw.tween_callback(_anim_set.bind(b, fr)).set_delay(ANIM_FPS)
	b.set_meta("anim_tw", tw)

static func _anim_set(b: Button, fr: Texture2D) -> void:
	if is_instance_valid(b):
		b.icon = fr

static func _anim_stop(b: Button) -> void:
	if not is_instance_valid(b):
		return
	var tw: Tween = b.get_meta("anim_tw", null)
	if tw != null and tw.is_valid():
		tw.kill()
	var rest = b.get_meta("anim_rest", null)   # [HOVER_ICON] ícone de descanso dinâmico (ex.: Correio lido/não-lido)
	if rest != null:
		b.icon = rest
		return
	var frames: Array = b.get_meta("anim_frames", [])
	if frames.size() > 0:
		b.icon = frames[0]

# [HOVER_ICON_ANIM] Frame-cycle numa TextureRect, disparado pelo hover de `host` (o Control que captura o
# mouse — pode ser a própria rect, ou o Button-pai quando o ícone vive dentro dum VBox). No-op se não
# houver anim/<key>/f0..fN. Espelha _anim_hover (que é só p/ Button.icon).
static func anim_rect(host: Control, tr: TextureRect, key: String) -> void:
	if host == null or tr == null or host.has_meta("anim_rect_fx"):
		return
	var frames := _anim_frames(key)
	if frames.size() < 2:
		return
	host.set_meta("anim_rect_fx", true)
	tr.texture = frames[0]
	host.mouse_entered.connect(_anim_rect_start.bind(host, tr, frames))
	host.mouse_exited.connect(_anim_rect_stop.bind(host, tr, frames))

static func _anim_rect_start(host: Control, tr: TextureRect, frames: Array) -> void:
	if not is_instance_valid(tr):
		return
	var prev: Tween = host.get_meta("anim_rect_tw", null)
	if prev != null and prev.is_valid():
		return                              # já animando neste hover → não reinicia
	var tw := tr.create_tween().set_loops()
	for fr in frames:
		tw.tween_callback(_anim_rect_set.bind(tr, fr)).set_delay(ANIM_FPS)
	host.set_meta("anim_rect_tw", tw)

static func _anim_rect_set(tr: TextureRect, fr: Texture2D) -> void:
	if is_instance_valid(tr):
		tr.texture = fr

# Quadros anim/<key>/f0..fN (público; [] se não existir). Usado por popups que tocam a animação
# direto (ex.: baú abrindo na Incursão, caneca no minigame da Taverna). [HOVER_ICON_ANIM]
static func frames(key: String) -> Array:
	return _anim_frames(key)

# Toca os quadros de anim/<key>/ num TextureRect em LOOP contínuo. Devolve o Tween (kill() p/ parar)
# ou null se não houver frames. Não mexe na textura inicial se vazio (mantém o fallback do chamador).
static func play_loop(tr: TextureRect, key: String, fps := ANIM_FPS) -> Tween:
	var fr := _anim_frames(key)
	if tr == null or not is_instance_valid(tr) or fr.size() < 2:
		return null
	tr.texture = fr[0]
	var tw := tr.create_tween().set_loops()
	for f in fr:
		tw.tween_callback(_anim_rect_set.bind(tr, f)).set_delay(fps)
	return tw

# Toca os quadros de anim/<key>/ UMA vez (SEM loop) e PARA no último (ex.: baú que abre e fica aberto).
# Igual ao play_loop mas sem set_loops → ao terminar, a textura fica no último quadro.
static func play_once(tr: TextureRect, key: String, fps := ANIM_FPS) -> Tween:
	var fr := _anim_frames(key)
	if tr == null or not is_instance_valid(tr) or fr.size() < 2:
		return null
	tr.texture = fr[0]
	var tw := tr.create_tween()   # sem set_loops → roda 1x e segura no fr[-1]
	for f in fr:
		tw.tween_callback(_anim_rect_set.bind(tr, f)).set_delay(fps)
	return tw

static func _anim_rect_stop(host: Control, tr: TextureRect, frames: Array) -> void:
	var tw: Tween = host.get_meta("anim_rect_tw", null)
	if tw != null and tw.is_valid():
		tw.kill()
	if is_instance_valid(tr) and frames.size() > 0:
		tr.texture = frames[0]

# TextureRect pronto pra HUD (recurso/atributo). size em px; null-safe (volta um TextureRect vazio).
# tooltip: texto explícito; "" → usa a descrição do mapa ICON_TIP (se houver). Sempre MOUSE_FILTER_PASS
# (recebe o hover-pop SEM bloquear o clique do card/pai). [ICON_TOOLTIP][HOVER_ICON]
static func rect(key: String, px := 24, tooltip := "") -> TextureRect:
	var tr := TextureRect.new()
	tr.texture = tex(key)
	tr.custom_minimum_size = Vector2(px, px)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	var tt := tooltip if tooltip != "" else tip(key)
	if tt != "":
		tr.tooltip_text = tt
	tr.mouse_filter = Control.MOUSE_FILTER_PASS   # PASS: anima no hover mas deixa o clique passar pro pai
	if tr.texture != null:
		if key == "star":
			_pulse_ambient(tr, px)                 # [XP_PULSE] o ícone de XP brilha/pulsa SEMPRE (em vez do hover-pop, que conflitaria com o tween)
		else:
			add_hover(tr)                          # [HOVER_ICON] cresce + clareia
			anim_rect(tr, tr, key)                 # [HOVER_ICON_ANIM] cicla anim/<key>/ no hover (se existir)
	return tr

# [XP_PULSE] Pulso ambiente (brilho dourado + leve escala) em loop suave — o ícone de XP "brilha/pulsa"
# em TODO lugar que o representa (quest, kv rows). Começa só quando o nó entra na árvore (create_tween
# exige estar na cena). One-shot: cada rect() cria um TextureRect novo, então não re-conecta.
static func _pulse_ambient(node: Control, px: int) -> void:
	node.tree_entered.connect(_start_pulse.bind(node, px), CONNECT_ONE_SHOT)

static func _start_pulse(node: Control, px: int) -> void:
	if not is_instance_valid(node):
		return
	node.pivot_offset = Vector2(px, px) / 2.0                # escala a partir do centro
	var tw := node.create_tween().set_loops().set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	tw.tween_property(node, "modulate", Color(1.30, 1.20, 0.80, 1.0), 0.7)   # clareia/dourado
	tw.parallel().tween_property(node, "scale", Vector2(1.08, 1.08), 0.7)
	tw.tween_property(node, "modulate", Color(1, 1, 1, 1), 0.7)
	tw.parallel().tween_property(node, "scale", Vector2.ONE, 0.7)

# [ELEMENTOS] O ícone de um elemento (FIRE/WATER/EARTH/AIR) usa o GIF da ESSÊNCIA correspondente
# (res_<x>_essence, que tem anim/ e anima no hover) — padroniza buffs/Temple/World. O tooltip da RODA
# (+25% vs X) continua sob a key "elem_<x>" no ICON_TIP, então passe Icons.tip("elem_"+code) à parte.
static func elem_anim_key(code: String) -> String:
	return "res_" + code.to_lower() + "_essence"
