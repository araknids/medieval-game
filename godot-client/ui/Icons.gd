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
	# Elementos (roda: Fogo→Ar→Terra→Água→Fogo)
	"elem_fire": ["Fogo — vence Ar, perde p/ Água", "Fire — beats Air, loses to Water"],
	"elem_water": ["Água — vence Fogo, perde p/ Terra", "Water — beats Fire, loses to Earth"],
	"elem_earth": ["Terra — vence Água, perde p/ Ar", "Earth — beats Water, loses to Air"],
	"elem_air": ["Ar — vence Terra, perde p/ Fogo", "Air — beats Earth, loses to Fire"],
	# Slots de equipamento
	"slot_weapon": ["Arma", "Weapon"], "slot_helmet": ["Elmo", "Helmet"],
	"slot_chest": ["Peitoral", "Chest armor"], "slot_legs": ["Calças", "Leggings"],
	"slot_boots": ["Botas", "Boots"], "slot_gloves": ["Luvas", "Gloves"],
	"slot_shield": ["Escudo", "Shield"], "slot_ring": ["Anel", "Ring"],
	"slot_necklace": ["Colar", "Necklace"],
	# Nós da Incursão
	"node_combat": ["Combate — luta contra um inimigo", "Combat — fight an enemy"],
	"node_elite": ["Elite — inimigo mais forte", "Elite — tougher enemy"],
	"node_treasure": ["Tesouro — baú com loot", "Treasure — a loot chest"],
	"node_event": ["Evento — uma escolha", "Event — a choice"],
	"node_camp": ["Acampamento — garante o loot coletado", "Camp — bank your loot"],
	"node_boss": ["Chefe — fim da incursão", "Boss — end of the delve"],
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

# TextureRect pronto pra HUD (recurso/atributo). size em px; null-safe (volta um TextureRect vazio).
# tooltip: texto explícito; "" → usa a descrição do mapa ICON_TIP (se houver). Com tooltip → MOUSE_FILTER_PASS
# (mostra o hover SEM bloquear o clique do card/pai); sem → IGNORE (transparente, como antes). [ICON_TOOLTIP]
static func rect(key: String, px := 24, tooltip := "") -> TextureRect:
	var tr := TextureRect.new()
	tr.texture = tex(key)
	tr.custom_minimum_size = Vector2(px, px)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	var tt := tooltip if tooltip != "" else tip(key)
	if tt != "":
		tr.tooltip_text = tt
		tr.mouse_filter = Control.MOUSE_FILTER_PASS
	else:
		tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return tr
