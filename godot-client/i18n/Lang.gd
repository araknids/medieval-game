class_name Lang
extends RefCounted
# ── i18n PT/EN do cliente Godot ─────────────────────────────────────────────────────
# Registra uma Translation "en" (PT→EN) no TranslationServer. A tradução AUTOMÁTICA do Godot
# (auto_translate dos Control) então traduz Labels/Buttons/tooltips SEM precisar editar cada tela.
# Locale "pt" = padrão (retorna a própria chave em PT). Persiste em user://lang.cfg.
# Strings dinâmicas (com %d/%s) precisam de tr() no template — use Lang.t("...%d") % n. [I18N]
#
# Uso: App._ready() → Lang.apply_saved();  Settings → Lang.set_lang("en"/"pt").

const CFG := "user://lang.cfg"
static var _registered := false

# PT (chave, = o literal no código) → EN. Emoji ficam iguais nos dois lados.
const EN := {
	# — Nav / seções / telas —
	"Aventura": "Adventure", "Batalha": "Battle", "Comércio": "Trade", "Personagem": "Character",
	"Social": "Social", "Mundo": "World", "Trabalho": "Work", "Templo": "Temple", "Torre": "Tower",
	"Arena": "Arena", "Território": "Territory", "Territórios": "Territories", "Loja": "Shop", "Forja": "Forge",
	"Leilão": "Auction", "Baú": "Stash", "Taverna": "Tavern", "Inventário": "Inventory",
	"Habilidades": "Abilities", "Conquistas": "Achievements", "Guilda": "Guild", "Correio": "Mail",
	"Diário": "Daily", "Configurações": "Settings", "Recompensa Diária": "Daily Reward",
	# — Dashboard / topbar —
	"Atalhos": "Shortcuts", "Início": "Home", "🏠  Início": "🏠  Home",
	"Escolha uma atividade no menu à esquerda, ou use os atalhos abaixo.": "Pick an activity from the menu on the left, or use the shortcuts below.",
	"Estado do Guerreiro": "Warrior Status", "Atributos": "Attributes", "Combate": "Combat",
	"Tesouro": "Treasure", "Recompensa": "Reward", "Progresso": "Progress", "Rendimento": "Yield",
	"Nível": "Level", "Ataque": "Attack", "Defesa": "Defense", "Vida máx": "Max HP", "Rank (arena)": "Rank (arena)",
	"Estamina": "Stamina", "Experiência": "Experience", "⭐ Experiência": "⭐ Experience",
	# — Atributos (efeitos) —
	"dano corpo-a-corpo": "melee damage", "+8 HP por ponto": "+8 HP per point",
	"acerto · dano de arco": "accuracy · bow damage", "golpe extra · esquiva": "extra hit · dodge",
	"crítico": "critical", "reservado (Mago)": "reserved (Mage)",
	# — Botões comuns —
	"Lutar": "Fight", "LUTAR": "FIGHT", "⚔   LUTAR": "⚔   FIGHT", "⚔  Lutar": "⚔  Fight", "⚔ Lutar": "⚔ Fight",
	"Comprar": "Buy", "Cancelar": "Cancel", "✖ Cancelar": "✖ Cancel", "Equipar": "Equip", "Desequipar": "Unequip",
	"Vender": "Sell", "Listar": "List", "Coletar": "Collect", "Coletar loot": "Collect loot",
	"Fechar": "Close", "Abrir": "Open", "Enviar": "Send", "Remover": "Remove", "Resetar": "Reset",
	"Proteger": "Protect", "Refinar": "Refine", "Reforjar": "Reforge", "♻ Reforjar": "♻ Reforge",
	"Craftar": "Craft", "Beber": "Drink", "Escolher": "Choose", "Transferir": "Transfer", "Expulsar": "Kick",
	"Deletar": "Delete", "🗑 Deletar": "🗑 Delete", "Declarar": "Declare", "Entrar": "Enter", "Sair": "Exit",
	"senha": "password", "Conectando…": "Connecting…",
	"🚪 Sair": "🚪 Leave", "Usar título": "Use title", "+ Aprender": "+ Learn", "Iniciar Quest": "Start Quest",
	"📜 Começar": "📜 Begin", "📜 Ver log": "📜 View log", "📜 Ocultar log": "📜 Hide log",
	"🪙 Coletar dinheiro": "🪙 Collect money", "⭐ Coletar XP": "⭐ Collect XP", "🎁 Reivindicar": "🎁 Claim",
	"📦 Adicionar à mochila": "📦 Add to bag", "→ Baú": "→ Stash", "→ Mochila": "→ Bag",
	# — Login —
	"Criar conta": "Create account", "Já tenho conta — entrar": "I have an account — log in",
	"Não tem conta? Criar uma": "No account? Create one", "Entrar automaticamente": "Log in automatically",
	"nome do guerreiro": "warrior name", "usuário": "username", "senha (mín. 8)": "password (min. 8)",
	"Nome (3-30 chars)": "Name (3-30 chars)", "Descrição (opcional)": "Description (optional)",
	"Preencha usuário e senha.": "Fill in username and password.",
	"Sessão expirada — entre de novo.": "Session expired — log in again.",
	# — Status / mensagens —
	"Ponto aplicado": "Point applied", "Item adicionado!": "Item added!", "Recurso adicionado!": "Resource added!",
	"Ouro coletado!": "Gold collected!", "Joia criada!": "Gem created!", "Carta deletada.": "Mail deleted.",
	"Quest abandonada.": "Quest abandoned.", "Expedição cancelada.": "Expedition cancelled.",
	"Treino cancelado.": "Training cancelled.", "Habilidades resetadas.": "Abilities reset.",
	"Título atualizado.": "Title updated.", "Guilda criada!": "Guild created!", "Guilda dissolvida.": "Guild disbanded.",
	"Liderança transferida.": "Leadership transferred.", "Membro expulso.": "Member kicked.",
	"Guerra declarada!": "War declared!", "Ataque declarado!": "Attack declared!", "Ataque cancelado.": "Attack cancelled.",
	"Você entrou na guilda!": "You joined the guild!", "Você saiu da guilda.": "You left the guild.",
	"Informe um preço válido.": "Enter a valid price.", "Informe um valor válido.": "Enter a valid amount.",
	"Listado! (taxa de 5% cobrada)": "Listed! (5% fee charged)",
	"Listagem cancelada — item de volta na mochila.": "Listing cancelled — item back in your bag.",
	"Item travado no PvP — não dá pra vender enquanto exposto.": "Item PvP-locked — can't sell while exposed.",
	"Erro desconhecido": "Unknown error", "Erro (status)": "Error (status)",
	"Doado! Tesouro: %s": "Donated! Treasury: %s",
	"Regenerando HP… o templo cura instantaneamente.": "Regenerating HP… the temple heals instantly.",
	"Seu guerreiro está nocauteado. Cure para voltar ao combate.": "Your warrior is knocked out. Heal to return to combat.",
	"Errou o gole… só o bronze foi.": "Missed the sip… only the bronze is gone.",
	"🍺 Acertou! +1 stack": "🍺 Nailed it! +1 stack",
	# — Estados vazios / dicas —
	"Mochila vazia": "Empty bag", "Baú vazio": "Empty stash", "Caixa vazia": "Empty box", "Sem itens": "No items",
	"Sem minério": "No ore", "Sem estamina": "No stamina", "⚡ Sem estamina": "⚡ No stamina",
	"Nenhum": "None", "Neutro": "Neutral", "Nenhum item equipado": "No item equipped",
	"Nenhum item à venda agora": "Nothing for sale right now", "Nenhum jogador ainda": "No players yet",
	"Nenhum registro ainda": "No records yet", "Nenhum título ainda": "No titles yet",
	"Nenhum emprego disponível": "No jobs available", "Nenhum território de guerra": "No war territory",
	"Nenhuma bênção disponível agora.": "No blessing available right now.",
	"Nenhuma guilda criada ainda.": "No guild created yet.", "Nenhuma guilda elegível.": "No eligible guild.",
	"Sem itens nesta rotação": "No items this rotation", "Sem descrição.": "No description.",
	"Sem bênção ativa": "No active blessing", "Sem buff de bebida ativo.": "No drink buff active.",
	"— nada equipado —": "— nothing equipped —", "— nada nessa raridade —": "— nothing in this rarity —",
	"— nada à venda nessa raridade —": "— nothing for sale in this rarity —",
	"— nada na mochila nessa raridade —": "— nothing in the bag in this rarity —",
	"— nada na mochila p/ listar —": "— nothing in the bag to list —",
	"— nenhum item nessa raridade —": "— no item in this rarity —",
	"— nenhuma conquista neste filtro —": "— no achievement in this filter —",
	"— nenhuma listagem ativa —": "— no active listing —",
	"— nenhuma listagem nessa raridade —": "— no listing in this rarity —",
	"— sem doações ainda —": "— no donations yet —", "— sem fragmentos —": "— no fragments —",
	"— sem materiais (minere/colete pra forjar) —": "— no materials (mine/gather to forge) —",
	"— sem receitas —": "— no recipes —",
	"— nenhum inimigo disponível para atacar agora —": "— no enemy available to attack right now —",
	"Seja o primeiro a fundar uma!": "Be the first to found one!",
	"Seja o primeiro a lutar na arena": "Be the first to fight in the arena",
	"Suba a torre para entrar no ranking": "Climb the tower to enter the ranking",
	"Vença missões no 🌍 Mundo para conseguir itens": "Win quests in the 🌍 World to get items",
	"Colete recursos e equipamentos para guardar aqui": "Gather resources and gear to store here",
	"Recompensas, itens e recados chegam aqui": "Rewards, items and messages arrive here",
	"Equipamentos da mochila aparecem aqui p/ reparo/reforja": "Gear from your bag shows up here for repair/reforge",
	"Equipe itens no 🎒 Inventário para protegê-los": "Equip items in the 🎒 Inventory to protect them",
	"Deposite itens da mochila para protegê-los": "Deposit items from your bag to protect them",
	"Crie a sua ou entre numa existente abaixo": "Create your own or join an existing one below",
	"Receba uma bênção abaixo para se fortalecer em combate": "Take a blessing below to grow stronger in combat",
	"Ative o VIP abaixo para destravar os benefícios": "Activate VIP below to unlock the perks",
	"Pague bronze por XP puro.": "Pay bronze for pure XP.",
	"Suba de nível para destravar novos trabalhos.": "Level up to unlock new jobs.",
	"Itens protegidos não são perdidos em PvP.": "Protected items are not lost in PvP.",
	"Reseta à meia-noite UTC. VIP tem mais lutas por dia.": "Resets at midnight UTC. VIP gets more fights per day.",
	"Volte amanhã para a próxima recompensa.": "Come back tomorrow for the next reward.",
	"Volte após a próxima rotação do mercador": "Come back after the merchant's next rotation",
	"Volte mais tarde ou liste algo abaixo": "Come back later or list something below",
	"Volte mais tarde — os territórios aparecem quando a guerra está ativa.": "Come back later — territories appear when war is active.",
	"Filtrar por raridade (vale p/ todas as seções):": "Filter by rarity (applies to all sections):",
	# — Raridade —
	"Comum": "Common", "Incomum": "Uncommon", "Raro": "Rare", "Épico": "Epic", "Lendário": "Legendary", "Todas": "All",
	# — Comparação de item —
	"▲ Melhor": "▲ Better", "▼ Pior": "▼ Worse", "◆ Lateral": "◆ Sidegrade", "vs equipado": "vs equipped",
	"· ⚔ equipado": "· ⚔ equipped", "na venda": "on sale", "você recebe": "you receive",
	# — Tooltips topbar —
	"Bronze — moeda básica (recompensas, vendas)": "Bronze — basic currency (rewards, sales)",
	"Prata — 1 prata = 100 bronze": "Silver — 1 silver = 100 bronze",
	"Ouro — moeda de maior valor (1 ouro = 100 prata = 10.000 bronze)": "Gold — highest-value coin (1 gold = 100 silver = 10,000 bronze)",
	"SoulStone — moeda premium (VIP, cura instantânea)": "SoulStone — premium currency (VIP, instant heal)",
	"Experiência — enche e sobe de nível": "Experience — fills up and levels you up",
	"Vida (HP) — atual/máximo; regenera com o tempo; cure na hora no Templo": "Health (HP) — current/max; regenerates over time; heal instantly at the Temple",
	"Estamina — gasta nas ações; enche 100% em 1h (15min com buff de novato)": "Stamina — spent on actions; refills 100% in 1h (15min with newbie buff)",
	"Ataque efetivo de combate (base + gear + buffs + skills + postura + pet + taverna)": "Effective combat attack (base + gear + buffs + skills + stance + pet + tavern)",
	"Defesa efetiva — mitiga o dano recebido": "Effective defense — mitigates incoming damage",
	"Vida máxima efetiva de combate (com buffs/pet)": "Effective max combat HP (with buffs/pet)",
	"Esquiva — chance de evitar o golpe (DEX/AGI + buffs)": "Dodge — chance to avoid the hit (DEX/AGI + buffs)",
	# — Combate / batalha —
	"Inimigos": "Enemies", "Chefe": "Boss", "Placar": "Score", "Próxima batalha": "Next battle",
	"Em andamento…": "In progress…", "🏃 Fugir": "🏃 Flee", "⚔ Encarar": "⚔ Face it",
	"☠ Derrotado — cure-se no Templo": "☠ Defeated — heal at the Temple",
	"✅ Quest concluída!": "✅ Quest complete!", "✅ Expedição concluída!": "✅ Expedition complete!",
	"⚔ Sobreviveu à expedição!": "⚔ Survived the expedition!", "💀 Derrotado na expedição!": "💀 Defeated in the expedition!",
	"🏆 Chefe errante abatido!": "🏆 Roaming boss slain!", "🏆 Venceu": "🏆 Won", "💀 Perdeu": "💀 Lost",
	"Chefe derrotado! Suba para o próximo andar quando quiser.": "Boss defeated! Climb to the next floor whenever you want.",
	"🐶 Uma cãozinha (Luna) apareceu e interrompeu a missão! O que fazer?": "🐶 A little dog (Luna) appeared and interrupted the quest! What now?",
	"Ajudar a Luna": "Help Luna", "Terminar a missão": "Finish the quest",
	# — KO / estados —
	"💀 Nocauteado": "💀 Knocked out", "💀 NOCAUTEADO (em recuperação)": "💀 KNOCKED OUT (recovering)",
	"💀 Inconsciente": "💀 Unconscious", "❤ Inconsciente — cure no Templo": "❤ Unconscious — heal at the Temple",
	"✔ HP cheio": "✔ Full HP", "❤ Cura grátis": "❤ Free heal", "Cheia": "Full",
	# — Trabalho —
	"Trabalhando": "Working", "Trabalhando… você não pode aventurar enquanto trabalha.": "Working… you can't adventure while working.",
	"Trabalho cancelado — nenhuma hora completa.": "Work cancelled — no full hour.",
	"Cancelar trabalho": "Cancel work", "Empregos (%d)": "Jobs (%d)",
	"Cancelar o trabalho? Você perde o progresso da hora em andamento e recebe só as horas completas.": "Cancel work? You lose the current hour's progress and only get the full hours.",
	"XP da profissão": "Profession XP", "Termina em": "Ends in",
	# — Torre / treino —
	"🏋 Training Hall": "🏋 Training Hall", "🏋 Treinar (2h)": "🏋 Train (2h)",
	"⚔ Entrar na Torre": "⚔ Enter the Tower", "🏰 Ranking — Melhores Andares": "🏰 Ranking — Best Floors",
	"Lute andar por andar. Se perder, é expulso. Vá o mais longe que conseguir!": "Fight floor by floor. If you lose, you're out. Go as far as you can!",
	"Colete a tarefa ativa": "Collect the active task", "Termine a tarefa ativa": "Finish the active task",
	"Colete recursos": "Collect resources",
	# — Arena —
	"Duelo instantâneo. Vitória: +25 rank, ~200 bronze.": "Instant duel. Win: +25 rank, ~200 bronze.",
	"🏅 Rank": "🏅 Rank", "🏆 Ranking": "🏆 Ranking", "Lutas hoje": "Fights today",
	# — Mundo / zonas / quests —
	"Quests Ativas": "Active Quests", "🗓 Daily Quests": "🗓 Daily Quests", "⚗ Áreas de Elemento": "⚗ Element Areas",
	"⚔ Zonas": "⚔ Zones", "🌍 Zonas": "🌍 Zones", "Controlada por": "Controlled by",
	"Toque numa região do mapa para viajar até o reino.": "Tap a region on the map to travel to the kingdom.",
	"🗺 Voltar ao mapa": "🗺 Back to the map",
	# — Forja —
	"Refinar Minérios → Barras": "Refine Ores → Bars", "Craftar Equipamento": "Craft Equipment",
	"Criar Joias": "Create Gems", "Criar Joia": "Create Gem", "🔧 Manutenção (Reparar / Reforjar)": "🔧 Maintenance (Repair / Reforge)",
	"🔧 Reparar": "🔧 Repair", "📦 Seus materiais": "📦 Your materials",
	"? Os stats serão re-rolados — isso é irreversível.": "? Stats will be re-rolled — this is irreversible.",
	# — Taverna —
	"Acerte o tempo no gole para ganhar +1 stack de buff. Cobra 1🥉 sempre.": "Time the sip to gain +1 buff stack. Always costs 1🥉.",
	"🍺 Beber AGORA!": "🍺 Drink NOW!", "💬 Chat": "💬 Chat", "Diga algo…": "Say something…",
	# — Templo / bênçãos —
	"Bênçãos": "Blessings", "🙏 Bênçãos": "🙏 Blessings", "🙏 ABENÇOADO": "🙏 BLESSED",
	"Curar (grátis)": "Heal (free)", "💎 Cura instantânea (1 SoulStone)": "💎 Instant heal (1 SoulStone)",
	"Proteção de Itens": "Item Protection", "Desprotegido": "Unprotected", "🛡 Protegido": "🛡 Protected",
	# — VIP —
	"💎 VIP": "💎 VIP", "👑 Ativar VIP": "👑 Activate VIP", "👑 Renovar VIP (+30 dias)": "👑 Renew VIP (+30 days)",
	"👑 VIP ativado!": "👑 VIP activated!", "👑 Confirmar": "👑 Confirm",
	"👑 VIP Heal (grátis)": "👑 VIP Heal (free)", "Você não tem VIP ativo.": "You have no active VIP.",
	"10 lutas/dia (em vez de 5)": "10 fights/day (instead of 5)", "50 slots (em vez de 30)": "50 slots (instead of 30)",
	"2 simultâneas": "2 at once", "com cooldown (10 min)": "with cooldown (10 min)", "a qualquer momento": "anytime",
	# — Guilda / guerra —
	"Liderança": "Leadership", "Guildas existentes": "Existing guilds", "👥 Membros": "👥 Members",
	"🏦 Tesouro": "🏦 Treasury", "💰 Doar": "💰 Donate", "Doar para o tesouro": "Donate to the treasury",
	"🏆 Top Doadores": "🏆 Top Donors", "🛡 Criar Guilda": "🛡 Create Guild", "🚪 Sair da Guilda": "🚪 Leave Guild",
	"💀 Dissolver Guilda": "💀 Disband Guild", "💀 Dissolver": "💀 Disband",
	"Criar nova guilda  (custa 100 bronze)": "Create a new guild  (costs 100 bronze)",
	"Você não pertence a nenhuma guilda.": "You don't belong to any guild.",
	"Entre numa guilda para participar da guerra de território.": "Join a guild to take part in territory war.",
	"Sair da guilda? Você perde os bônus e a contribuição.": "Leave the guild? You lose the bonuses and your contribution.",
	"Dissolver a guilda PERMANENTEMENTE? Todos os membros são expulsos.": "Disband the guild PERMANENTLY? All members are kicked.",
	"Só o líder da guilda pode declarar.": "Only the guild leader can declare.",
	"Só o líder pode declarar guerra.": "Only the leader can declare war.",
	"⚔ Guerra de Guilda": "⚔ Guild War", "⚔ Declarar Guerra": "⚔ Declare War", "⚔ Declarar ataque": "⚔ Declare attack",
	"⚔ Declarar": "⚔ Declare", "⚔ Atacar": "⚔ Attack", "Escolha uma guilda rival": "Choose a rival guild",
	"Declarar guerra de 7 dias?": "Declare a 7-day war?",
	"Rivais precisam ter controlado um território.": "Rivals must have controlled a territory.",
	"⚔ Formação 3×5 e territórios virão em telas próprias.": "⚔ 3×5 formation and territories will come in their own screens.",
	# — Conquistas / títulos —
	"Desbloqueadas": "Unlocked", "Bloqueadas": "Locked",
	"Desbloqueie conquistas abaixo para ganhar títulos.": "Unlock achievements below to earn titles.",
	"Escolha uma classe (Path Trial no Nv.10) para destravar as habilidades dela.": "Choose a class (Path Trial at Lv.10) to unlock its abilities.",
	# — Mercador / classes —
	"Mercador": "Merchant", "Recruta": "Recruit",
	# — Settings (a nova tela) —
	"Idioma": "Language", "Idioma / Language": "Language", "Português": "Portuguese", "Inglês": "English",
	"Escolha o idioma da interface.": "Choose the interface language.",
	"Idioma alterado.": "Language changed.",
	# — Confirmações genéricas —
	"Esta escolha é definitiva.": "This choice is final.",
	"? Anexos não coletados serão perdidos.": "? Uncollected attachments will be lost.",
	"? O item volta pra mochila (a taxa de 5% não é devolvida).": "? The item returns to your bag (the 5% fee is not refunded).",
	# — Templates de formato (com %d/%s) — precisam de tr() no call site: Lang.t("...") % args —
	"Carregando…": "Loading…",
	"%s · Nível %d": "%s · Level %d",
	"%s · Nv %d · %s": "%s · Lv %d · %s",
	"%s   Nv.%d": "%s   Lv.%d",
	"Nv %d": "Lv %d", "Nv %d · ❤ %d%%": "Lv %d · ❤ %d%%",
	"Requer Nv %d": "Requires Lv %d",
	"Nível %d": "Level %d",
	"Bem-vindo, %s": "Welcome, %s",
	"%d  (faltam %d)": "%d  (%d to go)",
	"(cheia em %d min)": "(full in %d min)",
	"(%d livre%s)": "(%d free%s)",
	"Faltam %d de exp pro próximo nível": "%d exp to next level",
	"Experiência: %d / %d (faltam %d pro próximo nível)": "Experience: %d / %d (%d to next level)",
	"Custo: ⚡ %d estamina   ·   Sua estamina: %d/100": "Cost: ⚡ %d stamina   ·   Your stamina: %d/100",
	"Custo: ⚡ %d estamina  ·  Sua estamina: %d/100": "Cost: ⚡ %d stamina  ·  Your stamina: %d/100",
	"%d min restantes": "%d min left", "%d s restantes": "%d s left", "%d min": "%d min",
	"%dh %02dmin restantes": "%dh %02dmin left", "%dh %dmin": "%dh %dmin",
	"Expira em %s": "Expires in %s", "Termina em %s": "Ends in %s", "Em %s": "In %s",
	"(por %s)": "(by %s)", "De: %s": "From: %s",
	"Membros (%d)": "Members (%d)", "Itens (%d)": "Items (%d)",
	"Equipado (%d)": "Equipped (%d)", "Mochila (%d)": "Bag (%d)", "Mochila (%d/%d)": "Bag (%d/%d)",
	"Baú (%d/%s)": "Stash (%d/%s)", "Proteção de Itens (%d/%d)": "Item Protection (%d/%d)",
	"Limite diário (%d/%d)": "Daily limit (%d/%d)", "Lutas hoje: %d/%d": "Fights today: %d/%d",
	"Conquistas & Títulos   (%d/%d)": "Achievements & Titles   (%d/%d)",
	"Habilidades — %s": "Abilities — %s", "Forja Lv.%d %s": "Forge Lv.%d %s",
	"Conquistas (%d/%d)": "Achievements (%d/%d)",
	"Sua guilda: +%d%% XP · +%d%% bronze · +%d%% bônus": "Your guild: +%d%% XP · +%d%% bronze · +%d%% perk",
	"Bônus: +%d%% XP · +%d%% drop · +%d%% bronze": "Bonus: +%d%% XP · +%d%% drop · +%d%% bronze",
	"Em guerra com %s": "At war with %s", "Controlada por %s": "Controlled by %s",
	"Dia %d": "Day %d",
	"Precisa de %d 💎 (você tem %d)": "Need %d 💎 (you have %d)",
	"Gastar %d 💎 SoulStones para %s VIP por 30 dias?": "Spend %d 💎 SoulStones to %s VIP for 30 days?",
	"Curar (%s)": "Heal (%s)", "Vender (%s)": "Sell (%s)",
	"Durabilidade: %d%%": "Durability: %d%%",
	"%d × %d (você × inimigo)": "%d × %d (you × enemy)",
	"%s contra %s%s · placar %d×%d": "%s vs %s%s · score %d×%d",
	"Buff de Novato: estamina e HP regeneram 4× mais rápido — %dh restantes": "Newbie Buff: stamina and HP regenerate 4× faster — %dh left",
	"Buff da Taverna: +%.2f%% em TODOS os stats — %s": "Tavern Buff: +%.2f%% to ALL stats — %s",
	"Arma encantada (%s): ±25%% por elemento — %s": "Enchanted weapon (%s): ±25%% per element — %s",
	"Armadura encantada (%s): ±25%% por elemento — %s": "Enchanted armor (%s): ±25%% per element — %s",
	"Bem Alimentado: %s — %s": "Well Fed: %s — %s",
	"Bênção do Templo: %s — %s": "Temple Blessing: %s — %s",
	"Bênção VIP (2º slot): %s — %s": "VIP Blessing (2nd slot): %s — %s",
	"Abrindo %s…": "Opening %s…",
	"Lv.%d → Lv.%d  (faltam %s)": "Lv.%d → Lv.%d  (%s to go)",
	"%dh · 🪙%d": "%dh · 🪙%d", "/h    ⭐ %d xp/h": "/h    ⭐ %d xp/h",
	"+%d XP — coletar": "+%d XP — collect", "+%d XP · +%d bronze": "+%d XP · +%d bronze",
	"De: %s   ·   %s": "From: %s   ·   %s",
	# — palavras soltas p/ montar plural/fontes nos call sites —
	"livre": "free", "livres": "free",
	"🛡 equip": "🛡 gear", "🍺 taverna": "🍺 tavern", "🥋 postura": "🥋 stance",
	"⭐ skill/afins": "⭐ skill/etc", "base": "base",
	# — Tipos de item (display PT do backend) + venda —
	"Arma": "Weapon", "Elmo": "Helmet", "Armadura": "Armor", "Calça": "Pants", "Botas": "Boots",
	"Luvas": "Gloves", "Escudo": "Shield", "Anel": "Ring", "Colar": "Necklace", "Ombreira": "Shoulder",
	"Vender %s?": "Sell %s?", "Vendido!": "Sold!", "Comprado!": "Bought!",
	"✔ Comprado": "✔ Bought",
	"🛒 Próxima rotação em %dh %02dm %02ds": "🛒 Next rotation in %dh %02dm %02ds",
	# — World / zonas / quests / resultados —
	"⚔ Expedição em andamento (%s)": "⚔ Expedition in progress (%s)",
	"⚔ Caçar · ⚡%d": "⚔ Hunt · ⚡%d", "⛏ Minerar · ⚡%d": "⛏ Mine · ⚡%d",
	"🔎 Garimpar · ⚡%d": "🔎 Pan · ⚡%d", "🎣 Pescar · ⚡%d": "🎣 Fish · ⚡%d",
	"🏋 Treino completo! +%d XP": "🏋 Training complete! +%d XP",
	"🎉 Novo companheiro: %s!": "🎉 New companion: %s!",
	"💀 Derrotado por %s — sem recompensa.": "💀 Defeated by %s — no reward.",
	"⚔ %s derrotado!": "⚔ %s defeated!",
	"item roubado: %s": "item stolen: %s",
	"💀 %s (Lv %d) escapou da Torre e bloqueou sua expedição!\n\n⚔ Encarar = combate.   🏃 Fugir = %d%% (se falhar, cai na luta).": "💀 %s (Lv %d) escaped the Tower and blocked your expedition!\n\n⚔ Face it = combat.   🏃 Flee = %d%% (fail and you fall into the fight).",
	# — Temple —
	# — Tavern —
	"🍺 +%.2f%% em todos os stats · %d stacks · %d:%02d": "🍺 +%.2f%% to all stats · %d stacks · %d:%02d",
	# — Daily —
	"🔥 Sequência: %d": "🔥 Streak: %d",
	"📬 %d por correio (mochila cheia)": "📬 %d by mail (bag full)",
	"🎁 Recebido! 🔥 %d   —   %s": "🎁 Received! 🔥 %d   —   %s",
	# — Vip —
	"⚔ Lutas de arena: %d/%d": "⚔ Arena fights: %d/%d",
	"VIP — 30 dias  (%d 💎)": "VIP — 30 days  (%d 💎)",
	"VIP ATIVO": "VIP ACTIVE", "dia restante": "day left", "dias restantes": "days left",
	# — Tower —
	"⚔ Entrar e lutar · ⚡%d": "⚔ Enter and fight · ⚡%d",
	"🏰 Andar %d%s": "🏰 Floor %d%s",
	"✔ Andar mais alto vencido: %d": "✔ Highest floor beaten: %d",
	"🚩 Nível recomendado %d+": "🚩 Recommended level %d+",
	"🏆 Andar %d vencido!": "🏆 Floor %d beaten!", "💀 Derrotado no andar %d": "💀 Defeated on floor %d",
	"🏆 Título desbloqueado: %s": "🏆 Title unlocked: %s",
	# — Arena —
	"⚔ Lutar · ⚡%d": "⚔ Fight · ⚡%d",
	"🏆 Vitória vs %s!": "🏆 Victory vs %s!", "💀 Derrota para %s": "💀 Defeat to %s",
	# — Guild —
	"⭐ Nível máximo (Lv.%d) — total contribuído: %s": "⭐ Max level (Lv.%d) — total contributed: %s",
	"Expulsar %s da guilda?": "Kick %s from the guild?",
	"Transferir a liderança para %s? Você deixa de ser líder.": "Transfer leadership to %s? You stop being the leader.",
	# — Mail —
	"📥 Caixa de entrada%s": "📥 Inbox%s", "  (%d não-lidas)": "  (%d unread)",
	"🪙 Coletar %s": "🪙 Collect %s", "🪙 %s (já coletado)": "🪙 %s (already collected)",
	"📦 %s (já reivindicado)": "📦 %s (already claimed)",
	"Deletar a carta de \"%s\"? Anexos não coletados serão perdidos.": "Delete the mail from \"%s\"? Uncollected attachments will be lost.",
	# — Stash —
	"Taxa: %d bronze por movimento (depositar/sacar)": "Fee: %d bronze per move (deposit/withdraw)",
	# — Auction —
	"🛒 Comprar (%d)": "🛒 Buy (%d)", "📋 Minhas listagens (%d/10)": "📋 My listings (%d/10)",
	"➕ Listar um item (%d)": "➕ List an item (%d)",
	"%s · 🔧%d%% · ⏳ %s": "%s · 🔧%d%% · ⏳ %s",
	"Vendedor: %s · você recebe": "Seller: %s · you receive", "Vendedor: %s": "Seller: %s",
	"Cancelar a listagem de \"%s\"? O item volta pra mochila (a taxa de 5% não é devolvida).": "Cancel the listing of \"%s\"? The item returns to your bag (the 5% fee is not refunded).",
	# — Work —
	"🔒 Requer nível %d": "🔒 Requires level %d",
	"⚒ Trabalho concluído! +%s   +⭐%d XP (%s)": "⚒ Work complete! +%s   +⭐%d XP (%s)",
	"Trabalho cancelado — parcial: +%s   +⭐%d XP": "Work cancelled — partial: +%s   +⭐%d XP",
	# — Abilities —
	"⭐ %d ponto": "⭐ %d point", "⭐ %d pontos": "⭐ %d points",
	"⬆ %s para gastar": "⬆ %s to spend",
	"Você tem %s de habilidade guardado.": "You have %s of abilities saved.",
	"🔄 Resetar habilidades (%s)": "🔄 Reset abilities (%s)",
	"Resetar todas as habilidades por %s? Os pontos voltam para você.": "Reset all abilities for %s? The points return to you.",
	# — Achievements / Territory —
	"🏰 Sua guilda controla: %s": "🏰 Your guild controls: %s",
	"⚔ Declarando:": "⚔ Declaring:", "🎒 Mochila": "🎒 Bag", "Lutando…": "Fighting…",
	"Entrando…": "Entering…", "● NOVA": "● NEW", "⏰ EXPIRADO": "⏰ EXPIRED",
	"🥇 Ouro": "🥇 Gold", "🥈 Prata": "🥈 Silver", " (você)": " (you)",
	"🎉 A doação subiu a guilda para o nível %d!": "🎉 The donation raised the guild to level %d!",
	"%d ponto": "%d point", "%d pontos": "%d points", "Aprimorado!": "Upgraded!",
	"expirando…": "expiring…", "Custo:": "Cost:", "OK": "OK",
	"Guerreiro": "Warrior", "Arqueiro": "Archer", "Aprendiz": "Apprentice",
	"Você tem %d ponto%s de habilidade guardado%s.": "You have %d ability point%s saved%s.",
}

# Literais que JÁ estão em inglês no código (nomes de zona etc.) → tradução PT p/ o locale "pt".
const PT_OVERRIDE := {
	"🏖 Safe Shore": "🏖 Praia Segura", "🌊 Wild Coast": "🌊 Costa Selvagem", "🦈 Deep Sea": "🦈 Mar Profundo",
	"🌅 Sacred Cove": "🌅 Enseada Sagrada", "🐠 Deep Reef": "🐠 Recife Profundo", "🔱 Blessed Abyss": "🔱 Abismo Abençoado",
	"⛏ Open Mine": "⛏ Mina Aberta", "🪨 Deep Tunnels": "🪨 Túneis Profundos", "💎 Forbidden Mines": "💎 Minas Proibidas",
	"🔎 Shallow Vein": "🔎 Veio Raso", "💠 Deep Grottoes": "💠 Grutas Profundas", "💎 Forbidden Cavern": "💎 Caverna Proibida",
	"🏰 Haunted Courtyard": "🏰 Pátio Assombrado", "⚔ Battlefield": "⚔ Campo de Batalha", "🔥 War Zone": "🔥 Zona de Guerra",
	"🔥 Fire": "🔥 Fogo", "💧 Water": "💧 Água", "🪨 Earth": "🪨 Terra", "💨 Air": "💨 Ar",
}

# Registra as traduções (1x). Idempotente.
static func _register() -> void:
	if _registered:
		return
	_registered = true
	var en := Translation.new()
	en.locale = "en"
	for k in EN:
		_add(en, k, EN[k])
	TranslationServer.add_translation(en)
	var pt := Translation.new()
	pt.locale = "pt"
	for k in PT_OVERRIDE:
		_add(pt, k, PT_OVERRIDE[k])
	TranslationServer.add_translation(pt)

# Adiciona a mensagem + a variante MAIÚSCULA (UiKit.section/scaffold fazem to_upper no texto exibido).
static func _add(tr_obj: Translation, key, val) -> void:
	tr_obj.add_message(key, val)
	var ku := String(key).to_upper()
	if ku != key:
		tr_obj.add_message(ku, String(val).to_upper())

# Aplica o idioma salvo (chame no boot). Default "pt".
static func apply_saved() -> void:
	_register()
	TranslationServer.set_locale(_load())

# Troca o idioma e persiste. code = "pt" | "en".
static func set_lang(code: String) -> void:
	_register()
	TranslationServer.set_locale(code)
	var cf := ConfigFile.new()
	cf.set_value("lang", "code", code)
	cf.save(CFG)

static func current() -> String:
	return "en" if TranslationServer.get_locale().begins_with("en") else "pt"

static func _load() -> String:
	var cf := ConfigFile.new()
	if cf.load(CFG) == OK:
		return str(cf.get_value("lang", "code", "pt"))
	return "pt"

# Tradução explícita (p/ strings dinâmicas: Lang.t("Nível %d") % n). Usa o locale atual.
static func t(key: String) -> String:
	return TranslationServer.translate(key)
