// ── Estado global ──
let token    = localStorage.getItem('token');
let player   = null;
let warrior  = null;
let questTypes = [];
let timerIntervals = {};
let fightTimerInterval = null;
let currentUsername = '';

// ── API helper ──
async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': 'Bearer ' + token } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  return res.json();
}

// ── Sistema de 3 moedas ──
// Formata um valor em bronze para exibição com ícones coloridos
function formatCurrency(bronze, silver, gold) {
  const parts = [];
  if (gold   > 0) parts.push(`<span class="c-gold">${gold}🥇</span>`);
  if (silver > 0) parts.push(`<span class="c-silver">${silver}🥈</span>`);
  if (bronze > 0 || parts.length === 0) parts.push(`<span class="c-bronze">${bronze}🟤</span>`);
  return parts.join(' ');
}

// Converte total em bronze para { bronze, silver, gold }
function decompose(totalBronze) {
  totalBronze = Math.max(0, Math.floor(totalBronze));
  const gold   = Math.floor(totalBronze / 10000);
  const silver = Math.floor((totalBronze % 10000) / 100);
  const bronze = totalBronze % 100;
  return { bronze, silver, gold };
}

// Formata a partir de um total em bronze
function fmtBronze(totalBronze) {
  const { bronze, silver, gold } = decompose(totalBronze);
  return formatCurrency(bronze, silver, gold);
}

// ── Renderização do log de batalha ──
function renderBattleLog(lines) {
  return lines.map(line => {
    if (line.startsWith('HP:'))         return `<span class="log-hp">${line}</span>`;
    if (line.includes('❤'))            return `<span class="log-hp">${line}</span>`;
    if (line.includes('🏆'))            return `<span class="log-win">${line}</span>`;
    if (line.includes('esquiva') || line.includes('desvia') || line.includes('bloqueia') ||
        line.includes('rola') || line.includes('para o impacto'))
                                        return `<span class="log-evade">${line}</span>`;
    if (line.includes('───'))           return `<span class="log-separator">${line}</span>`;
    if (line.startsWith('—'))           return `<span class="log-round">${line}</span>`;
    return `<span class="log-hit">${line}</span>`;
  }).join('\n');
}

// ── Narrativas das missões ──
const QUEST_NARRATIVES = {
  PATROL: [
    'Seu guerreiro patrulhou os arredores, mantendo a paz e afugentando bandoleiros.',
    'Uma ronda tranquila pelos arredores da cidade. A noite foi calma.',
    'O guerreiro cruzou cada rua com atenção, garantindo a segurança da região.',
  ],
  DUNGEON: [
    'As trevas da masmorra foram varridas com determinação. Inimigos caíram pelo caminho.',
    'Batalhas nas profundezas ecoaram pelas cavernas. O guerreiro saiu vitorioso.',
    'Criaturas sombrias tentaram barrar o caminho, mas foram derrotadas uma a uma.',
  ],
  RAID: [
    'O raid foi intenso — múltiplos inimigos foram derrotados em combate aberto.',
    'Sangue e glória: o raid foi um sucesso retumbante.',
    'Liderando o ataque, o guerreiro deixou um rastro de vitórias no campo.',
  ],
  BOSS_HUNT: [
    'O chefe rugiu ameaçadoramente, mas caiu diante da determinação do guerreiro.',
    'Uma batalha épica que ficará marcada na memória. O chefe foi abatido.',
    'Após um confronto lendário, o chefe foi finalmente derrotado.',
  ],
};

const DROP_NARRATIVES = [
  'Ao vasculhar os destroços do inimigo, encontrou algo brilhante entre a sujeira...',
  'Num canto esquecido da masmorra, havia um item abandonado há anos...',
  'A vitória trouxe uma surpresa inesperada escondida nos pertences do inimigo...',
  'Entre os escombros da batalha, um reflexo chamou atenção do guerreiro...',
  'Com cuidado, examinou o corpo do inimigo e encontrou algo valioso...',
];

function questNarrative(questType) {
  const key = questType?.toUpperCase().replace(' ', '_') ||
    (questType?.includes('Patrulha') ? 'PATROL' :
     questType?.includes('Masmorra') ? 'DUNGEON' :
     questType?.includes('Raid') ? 'RAID' : 'BOSS_HUNT');
  const arr = QUEST_NARRATIVES[key] || QUEST_NARRATIVES.PATROL;
  return arr[Math.floor(Math.random() * arr.length)];
}

function showMessage(text, isError = false, isDrop = false) {
  const el = document.getElementById('game-message');
  el.textContent = text;
  el.style.display = 'block';
  el.classList.toggle('drop', isDrop);
  el.style.borderColor = isError ? '#cf6679' : isDrop ? '#c97ddb' : '#c9a84c';
  el.style.color = isError ? '#cf6679' : '#e0d5c5';
  clearTimeout(showMessage._t);
  showMessage._t = setTimeout(() => el.style.display = 'none', isDrop ? 6000 : 3500);
}

function formatTime(seconds) {
  if (seconds <= 0) return 'Pronto!';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function statRow(label, base, bonus, total) {
  const display = bonus > 0
    ? `${base}<span class="stat-bonus">+${bonus}</span>`
    : `${total}`;
  return `<div class="warrior-stat-row">
    <span class="label">${label}</span><span class="value">${display}</span>
  </div>`;
}

// ── Auth ──
const ALL_AUTH_FORMS = ['login-form', 'register-form', 'forgot-form', 'reset-form'];
function showAuthForm(id) {
  ALL_AUTH_FORMS.forEach(f => document.getElementById(f).style.display = 'none');
  document.getElementById(id).style.display = 'block';
  document.getElementById('auth-error').textContent = '';
}

function showRegister()       { showAuthForm('register-form'); }
function showLogin()          { showAuthForm('login-form'); }
function showForgotPassword() { showAuthForm('forgot-form'); }

async function forgotPassword() {
  const email = document.getElementById('forgot-email').value.trim();
  const data  = await api('POST', '/api/auth/forgot-password', { email });
  if (data.error) { document.getElementById('auth-error').textContent = data.error; return; }
  document.getElementById('auth-error').style.color = '#4caf82';
  document.getElementById('auth-error').textContent = data.message;
}

async function resetPassword() {
  const password  = document.getElementById('reset-password').value;
  const password2 = document.getElementById('reset-password2').value;
  if (password !== password2) {
    document.getElementById('auth-error').textContent = 'As senhas não coincidem';
    return;
  }
  const token = new URLSearchParams(window.location.search).get('reset');
  const data  = await api('POST', '/api/auth/reset-password', { token, password });
  if (data.error) { document.getElementById('auth-error').textContent = data.error; return; }
  document.getElementById('auth-error').style.color = '#4caf82';
  document.getElementById('auth-error').textContent = data.message;
  setTimeout(() => {
    window.history.replaceState({}, '', '/');
    showLogin();
  }, 2000);
}

async function login() {
  const username = document.getElementById('login-username').value.trim();
  const password = document.getElementById('login-password').value;
  const data = await api('POST', '/api/auth/login', { username, password });
  if (data.error) { document.getElementById('auth-error').textContent = data.error; return; }
  token = data.token; player = data; currentUsername = data.username;
  localStorage.setItem('token', token);
  localStorage.setItem('username', data.username);
  enterGame();
}

async function register() {
  const body = {
    username:    document.getElementById('reg-username').value.trim(),
    email:       document.getElementById('reg-email').value.trim(),
    password:    document.getElementById('reg-password').value,
    warriorName: document.getElementById('reg-warrior-name').value.trim()
  };
  const data = await api('POST', '/api/auth/register', body);
  if (data.error) { document.getElementById('auth-error').textContent = data.error; return; }
  token = data.token; player = data; currentUsername = data.username;
  localStorage.setItem('token', token);
  localStorage.setItem('username', data.username);
  enterGame();
}

function logout() {
  token = null; player = null; warrior = null;
  localStorage.removeItem('token');
  Object.values(timerIntervals).forEach(clearInterval);
  clearInterval(fightTimerInterval);
  document.getElementById('game-screen').style.display = 'none';
  document.getElementById('login-screen').style.display = 'flex';
  showLogin();
}

// ── Jogo ──
function enterGame() {
  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('game-screen').style.display = 'block';
  loadWarrior();
  loadQuestTypes();
  loadActiveQuests();
  setInterval(loadActiveQuests, 10000);
}

// ── Guerreiro ──
async function loadWarrior() {
  const data = await api('GET', '/api/warrior');
  if (data.error) return;
  warrior = data;
  // Atualiza skills se já foram carregadas
  if (skillsData.length === 0 && token) {
    api('GET', '/api/gathering/skills').then(s => { if (Array.isArray(s)) skillsData = s; });
  }

  document.getElementById('hdr-username').textContent = warrior.name;
  document.getElementById('hdr-currency').innerHTML =
    formatCurrency(warrior.bronze ?? 0, warrior.silver ?? 0, warrior.gold ?? 0);
  document.getElementById('hdr-rank').textContent = (warrior.rankPoints ?? '–') + ' pts';

  const xpPct = Math.floor((warrior.experience / warrior.expNeeded) * 100);
  const busy  = warrior.onMission;
  const stamina    = warrior.stamina ?? 100;
  const minsToFull = warrior.minutesToFullStamina ?? 0;
  const staminaInfo = stamina < 100 ? ` <span class="stamina-regen">(+100 em ${minsToFull}min)</span>` : '';

  document.getElementById('warrior-card').innerHTML = `
    <div class="warrior-name">${warrior.name}</div>
    <div class="warrior-class">${warrior.warriorClass}</div>
    <div class="warrior-stat-row"><span class="label">Nível</span><span class="value">${warrior.level}</span></div>
    <div class="xp-bar-wrap">
      <div class="xp-bar-bg"><div class="xp-bar-fill" style="width:${xpPct}%"></div></div>
      <div class="xp-label">EXP ${warrior.experience} / ${warrior.expNeeded}</div>
    </div>
    ${statRow('Ataque', warrior.baseAttack, warrior.bonusAttack, warrior.totalAttack)}
    ${statRow('Defesa', warrior.baseDefense, warrior.bonusDefense, warrior.totalDefense)}
    ${statRow('HP',     warrior.baseHealth,  warrior.bonusHealth,  warrior.totalHealth)}
    <div class="warrior-stat-row">
      <span class="label">Estamina</span>
      <span class="value ${stamina < 30 ? 'stamina-low' : ''}">${stamina}/100${staminaInfo}</span>
    </div>
    <div style="margin-top:.4rem">
      ${warrior.isKnockedOut
        ? `<span class="status-badge status-busy">💀 Inconsciente</span>`
        : `<span class="status-badge ${busy ? 'status-busy' : 'status-available'}">
             ${busy ? '⚔ Ocupado' : '✓ Disponível'}
           </span>`}
    </div>
    <div class="xp-bar-bg" style="margin-top:.3rem">
      <div class="xp-bar-fill" style="width:${warrior.hpPercent ?? 100}%;background:${
        (warrior.hpPercent ?? 100) <= 0 ? '#cf6679' :
        (warrior.hpPercent ?? 100) < 50 ? '#c9a84c' : '#4caf82'}"></div>
    </div>
    <div style="font-size:.7rem;color:#888;margin-top:.1rem">
      ❤ HP ${warrior.hpPercent ?? 100}%
      ${warrior.activeBuff ? `&nbsp;·&nbsp; ${warrior.activeBuff} ativo` : ''}
    </div>
    ${busy ? `<button class="btn-cancel-work" onclick="freeWarrior()" style="margin-top:.4rem;font-size:.72rem">
      🔓 Liberar (se travado)
    </button>` : ''}`;
}

// ── Navegação de locais ──
function goTo(loc) {
  ['tavern','inventory','commerce','temple','zones','skills','work','tower','arena','guild'].forEach(l => {
    document.getElementById('loc-panel-' + l).style.display = l === loc ? 'block' : 'none';
    document.getElementById('loc-' + l).classList.toggle('active', l === loc);
  });
  if (loc === 'temple')   { loadTemple(); }
  if (loc === 'zones')    { loadZones(); }
  if (loc === 'skills')   { loadSkillsTab(); }
  if (loc === 'tower')    { loadTower(); }
  if (loc === 'arena')    { loadRank(); loadCurrentFight(); }
  if (loc === 'commerce') { loadShop(); }
  if (loc === 'inventory'){ renderAttributes(); loadInventory(); }
  if (loc === 'work')     { loadWork(); }
  if (loc === 'guild')    { loadGuild(); }
}

// ── TAVERNA: missões ──
function switchQuestTab(tab) {
  document.getElementById('panel-available').style.display = tab === 'available' ? 'block' : 'none';
  document.getElementById('panel-active').style.display    = tab === 'active'    ? 'block' : 'none';
  document.getElementById('tab-available').classList.toggle('active', tab === 'available');
  document.getElementById('tab-active').classList.toggle('active',    tab === 'active');
}

async function loadQuestTypes() {
  questTypes = await api('GET', '/api/quests/types');
  renderQuestTypes();
}

function renderQuestTypes() {
  const busy = warrior?.onMission ?? false;
  const el = document.getElementById('quest-types-list');
  if (!questTypes.length) { el.innerHTML = 'Carregando...'; return; }
  const stamina = warrior?.stamina ?? 100;
  el.innerHTML = questTypes.map(q => {
    const noStamina = stamina < q.staminaCost;
    const disabled  = busy || noStamina;
    const btnLabel  = busy ? 'Guerreiro ocupado' : noStamina ? `Sem estamina (${stamina}/${q.staminaCost})` : 'Enviar';
    return `
    <div class="quest-card">
      <h3>${q.displayName}</h3>
      <div class="quest-rewards">
        <span>⏱ ${q.durationMinutes} min</span>
        <span>${fmtBronze(q.goldReward)}</span>
        <span>⭐ ${q.expReward} exp</span>
        <span class="stamina-cost">⚡ ${q.staminaCost}</span>
      </div>
      <button class="btn-send" ${disabled ? 'disabled' : ''} onclick="sendOnMission('${q.id}')">
        ${btnLabel}
      </button>
    </div>`;
  }).join('');
}

async function loadActiveQuests() {
  const quests = await api('GET', '/api/quests');
  if (!Array.isArray(quests)) return;
  Object.values(timerIntervals).forEach(clearInterval);
  timerIntervals = {};

  const el = document.getElementById('active-quests-list');
  if (!quests.length) { el.innerHTML = '<p style="color:#888;font-size:.82rem">Nenhuma missão ativa.</p>'; return; }

  el.innerHTML = quests.map(q => `
    <div class="quest-card" id="quest-card-${q.id}">
      <div class="quest-card-top">
        <h3>${q.questType}</h3>
        <span class="timer ${q.secondsRemaining <= 0 ? 'done' : ''}" id="timer-${q.id}">
          ${q.secondsRemaining <= 0 ? 'Pronto!' : formatTime(q.secondsRemaining)}
        </span>
      </div>
      <div class="quest-rewards">
        <span>${fmtBronze(q.goldReward)}</span>
        <span>⭐ ${q.expReward} exp</span>
      </div>
      <button class="btn-collect" id="btn-collect-${q.id}" ${q.secondsRemaining > 0 ? 'disabled' : ''} onclick="collectReward(${q.id})">
        ${q.secondsRemaining > 0 ? 'Aguardando...' : '🎁 Coletar'}
      </button>
    </div>`).join('');

  quests.forEach(q => {
    if (q.secondsRemaining <= 0) return;
    let secs = q.secondsRemaining;
    timerIntervals[q.id] = setInterval(() => {
      secs--;
      const te = document.getElementById(`timer-${q.id}`);
      const be = document.getElementById(`btn-collect-${q.id}`);
      if (!te) { clearInterval(timerIntervals[q.id]); return; }
      if (secs <= 0) {
        te.textContent = 'Pronto!'; te.classList.add('done');
        be.disabled = false; be.textContent = '🎁 Coletar';
        clearInterval(timerIntervals[q.id]);
        loadWarrior();
      } else { te.textContent = formatTime(secs); }
    }, 1000);
  });
}

async function sendOnMission(questType) {
  const quest = await api('POST', '/api/quests/start', { questType });
  if (quest.error) { showMessage(quest.error, true); return; }
  await loadWarrior();
  openQuestProgress(quest);
}

function openQuestProgress(quest) {
  document.getElementById('tavern-normal').style.display   = 'none';
  document.getElementById('tavern-progress').style.display = 'block';
  renderQuestProgress(quest);
}

function closeQuestProgress() {
  document.getElementById('tavern-progress').style.display = 'none';
  document.getElementById('tavern-normal').style.display   = 'block';
  loadQuestTypes();
  loadActiveQuests();
}

async function abandonQuest(questId) {
  if (!confirm('Abandonar a missão? Você não receberá nenhuma recompensa.')) return;
  const data = await api('POST', `/api/quests/${questId}/abandon`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage('Missão abandonada.');
  closeQuestProgress();
  await loadWarrior();
}

function renderQuestProgress(quest) {
  const done = quest.secondsRemaining <= 0;
  document.getElementById('qp-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">${quest.questType}</div>
      <div class="qp-rewards-preview">
        ${fmtBronze(quest.goldReward)} &nbsp;&nbsp; ⭐ ${quest.expReward} exp
      </div>
      <div class="qp-timer ${done ? 'done' : ''}" id="qp-timer">
        ${done ? 'Completo!' : formatTime(quest.secondsRemaining)}
      </div>
      <button class="btn-collect qp-collect-btn" id="qp-btn"
              ${done ? '' : 'disabled'}
              onclick="collectFromProgress(${quest.id})">
        ${done ? '🎁 Coletar' : 'Aguardando...'}
      </button>
      ${!done ? `
      <button class="btn-cancel-work" onclick="abandonQuest(${quest.id})" style="margin-top:.5rem">
        Abandonar (não recebe nada)
      </button>` : ''}
    </div>`;

  if (!done) {
    let secs = quest.secondsRemaining;
    const interval = setInterval(() => {
      secs--;
      const t = document.getElementById('qp-timer');
      const b = document.getElementById('qp-btn');
      if (!t) { clearInterval(interval); return; }
      if (secs <= 0) {
        t.textContent = 'Completo!';
        t.classList.add('done');
        b.disabled = false;
        b.textContent = '🎁 Coletar';
        clearInterval(interval);
      } else {
        t.textContent = formatTime(secs);
      }
    }, 1000);
  }
}

async function collectFromProgress(questId) {
  const data = await api('POST', `/api/quests/${questId}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  let rewardsHtml = `
    <div class="qp-result-row">
      <span class="cr-gold">${fmtBronze(data.goldEarned)}</span>
      <span class="cr-exp">+${data.expReward ?? data.expEarned} exp</span>
    </div>`;

  if (data.droppedItem) {
    const d = data.droppedItem;
    const stats = [
      d.attackBonus  > 0 ? `+${d.attackBonus} ATK`  : '',
      d.defenseBonus > 0 ? `+${d.defenseBonus} DEF` : '',
      d.healthBonus  > 0 ? `+${d.healthBonus} HP`   : '',
    ].filter(Boolean).join('  ');
    rewardsHtml += `
      <div class="qp-result-drop">
        ✨ <strong>${d.name}</strong>
        <span class="drop-stats">${d.typeDisplay} · ${stats}</span>
      </div>`;
  }

  document.getElementById('qp-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">Missão Concluída!</div>
      ${rewardsHtml}
      <button class="btn-send qp-collect-btn" onclick="closeQuestProgress()" style="margin-top:1rem">
        Voltar às Missões
      </button>
    </div>`;

  await loadWarrior();
}

async function collectReward(questId) {
  const data = await api('POST', `/api/quests/${questId}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  const card = document.getElementById(`quest-card-${questId}`);
  if (card) {
    let line = `${fmtBronze(data.goldEarned)}   +${data.expEarned} exp`;
    if (data.droppedItem) {
      const d = data.droppedItem;
      const stats = [
        d.attackBonus  > 0 ? `+${d.attackBonus} ATK`  : '',
        d.defenseBonus > 0 ? `+${d.defenseBonus} DEF` : '',
        d.healthBonus  > 0 ? `+${d.healthBonus} HP`   : '',
      ].filter(Boolean).join(' ');
      line += `\n✨ ${d.name} (${stats})`;
    }
    const narrative = questNarrative(data.questType || '');
    const dropNarrative = data.droppedItem
      ? DROP_NARRATIVES[Math.floor(Math.random() * DROP_NARRATIVES.length)]
      : '';
    card.innerHTML = `<div class="collect-result">
      <p style="color:#888;font-size:.76rem;font-style:italic;margin-bottom:.2rem">${narrative}</p>
      ${line.replace('\n', '<br>')}
      ${dropNarrative ? `<p style="color:#c97ddb;font-size:.76rem;margin-top:.2rem;font-style:italic">${dropNarrative}</p>` : ''}
    </div>`;
  }

  setTimeout(async () => {
    await Promise.all([loadWarrior(), loadActiveQuests()]);
  }, data.droppedItem ? 5000 : 2000);
}

// ── COMÉRCIO: loja ──
function switchCommerceTab(tab) {
  document.getElementById('panel-shop').style.display = tab === 'shop' ? 'block' : 'none';
  document.getElementById('panel-sell').style.display = tab === 'sell' ? 'block' : 'none';
  document.getElementById('tab-shop').classList.toggle('active', tab === 'shop');
  document.getElementById('tab-sell').classList.toggle('active', tab === 'sell');
  if (tab === 'sell') loadSellList();
}

let shopTimerInterval = null;

async function loadShop() {
  const data = await api('GET', '/api/shop');
  if (!data.items) return;

  const { items, merchantName, merchantQuote, secondsUntilNext } = data;

  // Timer da rotação
  clearInterval(shopTimerInterval);
  let secs = secondsUntilNext;

  function renderShopTimer() {
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    const timeStr = `${h}h ${String(m).padStart(2,'0')}m ${String(s).padStart(2,'0')}s`;
    const el = document.getElementById('shop-timer');
    if (el) el.textContent = `🛒 Nova carroça em ${timeStr}`;
  }

  document.getElementById('shop-list').innerHTML = `
    <div class="shop-merchant-header">
      <div class="merchant-name">🧙 ${merchantName}</div>
      <div class="merchant-quote">"${merchantQuote}"</div>
      <div class="shop-timer-wrap"><span id="shop-timer"></span></div>
    </div>
    <div class="shop-items-grid">
      ${items.map(i => {
        const stats = statsText(i);
        return `
          <div class="shop-card ${i.purchased ? 'shop-card-sold' : ''}">
            <div class="shop-item-info">
              <h3 class="rarity-${i.rarity}">${i.name}</h3>
              <div class="shop-stats">${i.typeDisplay} · ${i.rarityName} · ${stats}</div>
            </div>
            <span class="shop-price">${fmtBronze(i.price)}</span>
            ${i.purchased
              ? `<button class="btn-bought" disabled>✓ Comprado</button>`
              : `<button class="btn-buy" onclick="buyItem(${i.id})">Comprar</button>`
            }
          </div>`;
      }).join('')}
    </div>`;

  renderShopTimer();
  shopTimerInterval = setInterval(() => {
    secs--;
    if (secs <= 0) {
      clearInterval(shopTimerInterval);
      const el = document.getElementById('shop-timer');
      if (el) el.textContent = '🛒 A carroça chegou! Novos itens disponíveis!';
      setTimeout(() => loadShop(), 2000);
    } else {
      renderShopTimer();
    }
  }, 1000);
}

async function sellItem(itemId) {
  const data = await api('POST', `/api/inventory/${itemId}/sell`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.message} ${fmtBronze(data.goldEarned)}`);
  loadSellList();
  loadWarrior();
}

async function buyItem(shopItemId) {
  const data = await api('POST', `/api/shop/buy/${shopItemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  loadWarrior();
  loadShop(); // recarrega para marcar item como comprado
}

// ── COMÉRCIO: inventário ──
const ALL_SLOTS = [
  { id:'HELMET','label':'Capacete'}, { id:'ARMOR',   'label':'Armadura'},
  { id:'WEAPON','label':'Espada'},   { id:'SHIELD',  'label':'Escudo'},
  { id:'PANTS', 'label':'Calça'},    { id:'BOOTS',   'label':'Bota'},
  { id:'GLOVES','label':'Luva'},     { id:'SHOULDER','label':'Ombreira'},
  { id:'NECKLACE','label':'Colar'},  { id:'RING',    'label':'Anel'},
];

const ATTR_INFO = {
  STRENGTH:     { icon: '⚔',  label: 'Força',         effect: '+1 ATK' },
  DEXTERITY:    { icon: '🏹', label: 'Destreza',       effect: '+1% evasão' },
  CONSTITUTION: { icon: '🛡',  label: 'Constituição',  effect: '+5 HP / +0.5 DEF' },
  LUCK:         { icon: '🍀', label: 'Sorte',          effect: '+1% drop' },
};

function renderAttributes() {
  if (!warrior) return;
  const pts = warrior.availablePoints ?? 0;
  const el = document.getElementById('attributes-panel');

  const rows = Object.entries(ATTR_INFO).map(([id, info]) => {
    const val = warrior[id.toLowerCase()] ?? 0;
    return `
      <div class="attr-row">
        <span class="attr-icon">${info.icon}</span>
        <span class="attr-label">${info.label}</span>
        <span class="attr-effect">${info.effect}</span>
        <span class="attr-val">${val}</span>
        <button class="btn-attr" ${pts <= 0 ? 'disabled' : ''} onclick="spendPoint('${id}')">+</button>
      </div>`;
  }).join('');

  el.innerHTML = `
    <div class="attr-section">
      <div class="attr-header">
        <span>Atributos</span>
        ${pts > 0 ? `<span class="attr-points-badge">⬆ ${pts} ponto${pts > 1 ? 's' : ''} disponível</span>` : ''}
      </div>
      ${rows}
      <div class="attr-stats-summary">
        Evasão: <strong>${warrior.evasionChance ?? 10}%</strong>
      </div>
    </div>`;
}

async function spendPoint(attributeId) {
  const data = await api('POST', `/api/warrior/attributes/${attributeId}`);
  if (data.error) { showMessage(data.error, true); return; }
  warrior = data;
  renderAttributes();
  // Atualiza painel esquerdo também
  const xpPct = Math.floor((warrior.experience / warrior.expNeeded) * 100);
  document.getElementById('warrior-card').querySelector('.xp-bar-fill')
      ?.style.setProperty('width', xpPct + '%');
  await loadWarrior();
}

async function loadInventory() {
  const items = await api('GET', '/api/inventory');
  if (!Array.isArray(items)) return;

  const equipped = {};
  const bag = [];
  items.forEach(i => { if (i.equipped) equipped[i.type] = i; else bag.push(i); });

  document.getElementById('equipment-slots').innerHTML = `
    <div class="equipment-grid">
      ${ALL_SLOTS.map(slot => {
        const item = equipped[slot.id];
        if (item) return `
          <div class="equip-slot filled">
            <div class="slot-label">${slot.label}</div>
            <div class="slot-item-name rarity-${item.rarity}">${item.name}</div>
            <div class="slot-item-stats">${statsText(item)}</div>
            <button class="btn-unequip" onclick="unequipItem(${item.id})">Desequipar</button>
          </div>`;
        return `
          <div class="equip-slot empty">
            <div class="slot-label">${slot.label}</div>
            <div class="slot-empty-text">— vazio —</div>
          </div>`;
      }).join('')}
    </div>`;

  const bagEl = document.getElementById('bag-items');
  if (!bag.length) { bagEl.innerHTML = '<p style="color:#555;font-size:.8rem">Mochila vazia.</p>'; return; }
  bagEl.innerHTML = bag.map(item => `
    <div class="bag-item" style="flex-direction:column;align-items:flex-start;gap:.3rem">
      <div style="display:flex;justify-content:space-between;width:100%;align-items:center">
        <div>
          <div class="bag-item-name rarity-${item.rarity}">${item.name}</div>
          <div class="bag-item-type">${item.typeDisplay} · ${item.rarityName}</div>
          <div class="bag-item-stats">${statsText(item)}</div>
          ${item.sockets > 0 ? renderSockets(item) : ''}
        </div>
        <button class="btn-equip" onclick="equipItem(${item.id})">Equipar</button>
      </div>
      ${item.description ? `<p class="item-lore">"${item.description}"</p>` : ''}
      ${item.origin ? `<p class="item-origin">📍 ${item.origin}</p>` : ''}
    </div>`).join('');
}

async function loadSellList() {
  const items = await api('GET', '/api/inventory');
  if (!Array.isArray(items)) return;

  const bag = items.filter(i => !i.equipped);
  const el = document.getElementById('sell-list');

  if (!bag.length) {
    el.innerHTML = '<p style="color:#888;font-size:.82rem">Nenhum item na mochila para vender.</p>';
    return;
  }

  el.innerHTML = bag.map(item => `
    <div class="shop-card">
      <div class="shop-item-info">
        <h3 class="rarity-${item.rarity}">${item.name}</h3>
        <div class="shop-stats">${item.typeDisplay} · ${statsText(item)}</div>
      </div>
      <span class="shop-price">${fmtBronze(item.sellPrice)}</span>
      <button class="btn-buy" onclick="sellItem(${item.id})">Vender</button>
    </div>`).join('');
}

// Mostra sockets do item (com joias se tiver)
function renderSockets(item) {
  const gems = item.gems || [];
  const GEM_ICONS = {RUBY:'🔴',SAPPHIRE:'🔵',EMERALD:'💚',DIAMOND:'💎',AMETHYST:'🟣'};
  let slots = '';
  for (let i = 0; i < item.sockets; i++) {
    const gem = gems.find(g => g.slot === i);
    if (gem) {
      slots += `<span class="socket-slot filled" title="${gem.gemName}">${GEM_ICONS[gem.gem]||'💠'}</span>`;
    } else {
      // Socket vazio — mostra opções de joias disponíveis
      const available = resourcesData.filter(r => r.category === 'GEM' && r.quantity > 0);
      if (available.length > 0) {
        const opts = available.map(g =>
          `<option value="${g.type}">${GEM_ICONS[g.type]||'💠'} ${g.displayName}</option>`
        ).join('');
        slots += `
          <span class="socket-slot empty" title="Socket vazio">
            <select onchange="socketGem(${item.id}, this.value)" style="background:transparent;border:none;color:#888;font-size:.65rem;width:20px;cursor:pointer">
              <option value="">+</option>${opts}
            </select>
          </span>`;
      } else {
        slots += `<span class="socket-slot empty" title="Socket vazio (sem joias)">◯</span>`;
      }
    }
  }
  return `<div class="item-sockets" style="margin-top:.2rem">${slots}</div>`;
}

function statsText(item) {
  const parts = [];
  if (item.attackBonus  > 0) parts.push(`+${item.attackBonus} ATK`);
  if (item.defenseBonus > 0) parts.push(`+${item.defenseBonus} DEF`);
  if (item.healthBonus  > 0) parts.push(`+${item.healthBonus} HP`);
  return parts.join('  ') || '–';
}

async function equipItem(itemId) {
  const data = await api('POST', `/api/inventory/${itemId}/equip`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.name} equipado!`);
  await Promise.all([loadWarrior(), loadInventory()]);
}
async function unequipItem(itemId) {
  const data = await api('POST', `/api/inventory/${itemId}/unequip`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.name} desequipado.`);
  await Promise.all([loadWarrior(), loadInventory()]);
}

// ── TEMPLO ──

async function loadTemple() {
  const data = await api('GET', '/api/temple');
  renderTemple(data);
}

function renderTemple(data) {
  const el = document.getElementById('temple-content');
  if (!el) return;

  const hpColor   = data.hpPercent <= 0 ? '#cf6679' : data.hpPercent < 50 ? '#c9a84c' : '#4caf82';
  const hpLabel   = data.isKnockedOut ? '💀 Inconsciente' : `❤ ${data.hpPercent}%`;
  const healLabel = data.healFree ? 'Curar (Grátis)' : `Curar (${fmtBronze(100)})`;

  const buffActive = data.activeBuff
    ? `<div class="temple-buff-active">
        Bênção ativa: <strong>${data.activeBuff}</strong>
        — ${Math.floor(data.buffSecondsLeft / 60)}min restantes
       </div>`
    : '<div class="temple-buff-active" style="color:#888">Nenhuma bênção ativa.</div>';

  const buffsHtml = data.buffs.map(b => `
    <div class="sk-recipe-card">
      <div class="sk-recipe-title">${b.icon} ${b.displayName} — <span style="color:#888">${b.effect}</span></div>
      <div style="font-size:.75rem;color:#888;margin-bottom:.4rem">${fmtBronze(b.bronzeCost)}</div>
      <button class="btn-equip" onclick="applyBuff('${b.id}')">Abençoar</button>
    </div>`).join('');

  el.innerHTML = `
    <div class="sk-section">
      <div class="sk-title">Estado do Guerreiro</div>
      <div class="temple-hp-bar">
        <span style="color:${hpColor};font-weight:bold">${hpLabel}</span>
        <div class="xp-bar-bg" style="margin-top:.3rem">
          <div class="xp-bar-fill" style="width:${data.hpPercent}%;background:${hpColor}"></div>
        </div>
        <div style="font-size:.72rem;color:#888;margin-top:.2rem">
          ${data.isKnockedOut
            ? 'Seu guerreiro não pode lutar até ser curado.'
            : data.hpPercent < 100
            ? 'Regenerando HP... o templo pode curar instantaneamente.'
            : 'HP cheio!'}
        </div>
      </div>
      <button class="btn-collect" onclick="healWarrior()"
              style="margin-top:.6rem"
              ${data.hpPercent >= 100 ? 'disabled' : ''}>
        ${data.hpPercent >= 100 ? '✓ HP Cheio' : healLabel}
      </button>
    </div>

    <div class="sk-section">
      <div class="sk-title">Bênção</div>
      ${buffActive}
      <div style="margin-top:.5rem">${buffsHtml}</div>
    </div>

    <div class="sk-section">
      <div class="sk-title">Proteção de Itens (${data.protectedCount}/${data.maxProtected})</div>
      <p class="zone-desc">Itens protegidos não são perdidos em combate PvP. Custo: ${fmtBronze(50)}/item.</p>
      <div id="temple-protected-items">Carregando itens...</div>
    </div>`;

  loadTempleItems();
}

async function loadTempleItems() {
  const items = await api('GET', '/api/inventory');
  const el = document.getElementById('temple-protected-items');
  if (!el || !Array.isArray(items)) return;

  const equipped = items.filter(i => i.equipped);
  if (!equipped.length) {
    el.innerHTML = '<p style="color:#888;font-size:.8rem">Nenhum item equipado.</p>';
    return;
  }

  el.innerHTML = equipped.map(i => `
    <div class="sk-resource-row">
      <span class="rarity-${i.rarity}">${i.name}</span>
      ${i.guarded
        ? `<button class="btn-unequip" onclick="unprotectItem(${i.id})">🛡 Remover</button>`
        : `<button class="btn-equip"   onclick="protectItem(${i.id})">Proteger</button>`}
    </div>`).join('');
}

async function healWarrior() {
  const data = await api('POST', '/api/temple/heal');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadTemple();
}

async function applyBuff(buffId) {
  const data = await api('POST', `/api/temple/buff/${buffId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadTemple();
}

async function protectItem(itemId) {
  const data = await api('POST', `/api/temple/protect/${itemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadTempleItems();
}

async function unprotectItem(itemId) {
  const data = await api('POST', `/api/temple/unprotect/${itemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  loadTempleItems();
}

// ── EXPEDIÇÕES / ZONAS ──

const ZONE_COLORS = { SAFE:'#4caf82', PVP:'#c9a84c', HIGH_RISK:'#cf6679' };
const ZONE_ICONS  = { SAFE:'🌿', PVP:'⚔', HIGH_RISK:'💀' };

async function loadZones() {
  const [zones, current] = await Promise.all([
    api('GET', '/api/zones'),
    api('GET', '/api/zones/current'),
  ]);
  renderZones(zones, current);
}

function renderZones(zones, current) {
  const el = document.getElementById('zones-content');
  const warriorLevel = warrior?.level ?? 1;
  const busy = warrior?.onMission ?? false;

  if (current.active) {
    renderZoneActive(current);
    return;
  }

  const zonesHtml = zones.map(z => {
    const locked    = warriorLevel < z.minLevel;
    const color     = ZONE_COLORS[z.id] || '#888';
    const icon      = ZONE_ICONS[z.id]  || '🗺';
    const pvp       = z.encounterChancePerHour > 0;
    const durations = [30, 60, 120, 240, 360, 720];

    return `
      <div class="zone-card ${locked ? 'locked' : ''}" style="border-color:${color}20">
        <div class="zone-header">
          <span class="zone-name" style="color:${color}">${icon} ${z.displayName}</span>
          ${locked ? `<span class="wj-lock">🔒 Lv.${z.minLevel}</span>` : ''}
          ${pvp ? `<span class="zone-pvp-badge">⚔ PvP</span>` : ''}
        </div>
        <p class="zone-desc">${z.description}</p>
        <div class="zone-stats">
          <span>×${z.multiplier} recursos</span>
          ${z.npcEncounterChancePerHour > 0 ? `<span style="color:#c9a84c">🐉 ${z.npcEncounterChancePerHour}%/h NPC</span>` : ''}
          ${pvp ? `<span class="stamina-low">⚔ ${z.encounterChancePerHour}%/h PvP</span>` : ''}
        </div>
        ${!locked ? `
          <div class="zone-roles">
            <div class="zone-role-section">
              <div class="sk-title" style="margin-bottom:.4rem">🎣 Coletar (Pesca)</div>
              <div class="sk-duration-btns">
                ${durations.map(d => `
                  <button class="btn-hour" ${busy ? 'disabled' : ''}
                          onclick="enterZone('${z.id}','GATHERING','FISHING',${d})">
                    ${d >= 60 ? d/60+'h' : d+'m'}
                  </button>`).join('')}
              </div>
            </div>
            <div class="zone-role-section" style="margin-top:.5rem">
              <div class="sk-title" style="margin-bottom:.4rem">⛏ Coletar (Mineração)</div>
              <div class="sk-duration-btns">
                ${durations.map(d => `
                  <button class="btn-hour" ${busy ? 'disabled' : ''}
                          onclick="enterZone('${z.id}','GATHERING','MINING',${d})">
                    ${d >= 60 ? d/60+'h' : d+'m'}
                  </button>`).join('')}
              </div>
            </div>
            ${pvp ? `
            <div class="zone-role-section" style="margin-top:.5rem">
              <div class="sk-title" style="margin-bottom:.4rem">🗡 Caçar (Hunter)</div>
              <div class="sk-duration-btns">
                ${[60,120,180,360].map(d => `
                  <button class="btn-hour" ${busy ? 'disabled' : ''}
                          onclick="enterZone('${z.id}','HUNTING',null,${d})">
                    ${d/60}h
                  </button>`).join('')}
              </div>
            </div>` : ''}
          </div>` : ''}
      </div>`;
  }).join('');

  el.innerHTML = zonesHtml;
}

function renderZoneActive(state) {
  const el    = document.getElementById('zones-content');
  const color = ZONE_COLORS[state.zone] || '#888';
  const icon  = ZONE_ICONS[state.zone]  || '🗺';
  const role  = state.role === 'HUNTING' ? '🗡 Caçando' :
                state.skillType === 'FISHING' ? '🎣 Pescando' : '⛏ Minerando';

  let timerSecs = state.secondsRemaining ?? 0;
  clearInterval(window._zoneTimer);

  const timerHtml = () => timerSecs > 0
    ? formatTime(timerSecs)
    : '<span class="done">Pronto!</span>';

  el.innerHTML = `
    <div class="zone-card" style="border-color:${color}50">
      <div class="zone-header">
        <span class="zone-name" style="color:${color}">${icon} ${state.zoneName}</span>
        <span style="color:#888;font-size:.8rem">${role}</span>
      </div>
      <div class="qp-timer" id="zone-timer" style="font-size:2rem">${timerHtml()}</div>
      <p style="color:#888;font-size:.78rem;margin:.4rem 0">
        ${state.attacked && !state.survived
          ? `⚠ Você foi atacado por <strong>${state.attackerName}</strong> durante a expedição!`
          : state.attacked
          ? `Você sobreviveu a um ataque de <strong>${state.attackerName}</strong>!`
          : ''}
      </p>
      <div style="display:flex;gap:.5rem;margin-top:.6rem;flex-wrap:wrap">
        <button class="btn-collect" id="zone-collect-btn"
                ${state.readyToCollect ? '' : 'disabled'}
                onclick="collectZone(${state.id})">
          ${state.readyToCollect ? '🎒 Coletar' : 'Em expedição...'}
        </button>
        ${!state.readyToCollect ? `
          <button class="btn-cancel-work" onclick="cancelZone(${state.id})">Cancelar</button>
        ` : ''}
      </div>
    </div>`;

  if (timerSecs > 0) {
    window._zoneTimer = setInterval(() => {
      timerSecs--;
      const t = document.getElementById('zone-timer');
      const b = document.getElementById('zone-collect-btn');
      if (!t) { clearInterval(window._zoneTimer); return; }
      if (timerSecs <= 0) {
        t.innerHTML = '<span class="done">Pronto!</span>';
        if (b) { b.disabled = false; b.textContent = '🎒 Coletar'; }
        clearInterval(window._zoneTimer);
      } else {
        t.textContent = formatTime(timerSecs);
      }
    }, 1000);
  }
}

async function enterZone(zoneId, role, skillType, durationMinutes) {
  const body = { zone: zoneId, role, durationMinutes };
  if (skillType) body.skillType = skillType;
  const data = await api('POST', '/api/zones/enter', body);
  if (data.error) { showMessage(data.error, true); return; }
  await loadWarrior();
  renderZoneActive(data);
}

async function collectZone(activityId) {
  const data = await api('POST', `/api/zones/${activityId}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  clearInterval(window._zoneTimer);
  await loadWarrior();

  // Mostra resultado
  const el = document.getElementById('zones-content');
  const survived  = data.survived;
  const attacked  = data.wasAttacked;

  let resultHtml = '';
  if (attacked && !survived) {
    const logHtml = renderBattleLog(data.battleLog || []);
    resultHtml = `
      <div class="tower-result-box" style="border-color:#cf6679">
        <div class="tower-result-title" style="color:#cf6679">💀 Você foi derrotado!</div>
        <p style="color:#888;font-size:.82rem;margin:.4rem 0">
          Atacado por: <strong>${data.attackerName}</strong><br>
          Bronze perdido: ${fmtBronze(data.bronzeLost)}
          ${data.lostItemName ? `<br>Item perdido: <span style="color:#c97ddb">${data.lostItemName}</span>` : ''}
        </p>
        <div class="battle-log" style="max-height:200px">${logHtml}</div>
        <button class="btn-send" onclick="loadZones()" style="margin-top:.8rem">Voltar</button>
      </div>`;
  } else {
    const dropsHtml = data.drops.map(d =>
      `${RESOURCE_ICONS[d.type]||'?'} ${d.displayName} ×${d.quantity}`
    ).join('  ') || 'Nada coletado';

    let attackMsg = '';
    if (attacked && survived) {
      const logHtml = renderBattleLog(data.battleLog || []);
      attackMsg = `
        <div style="margin:.5rem 0;padding:.5rem;background:#1a0a0a;border-radius:5px;border:1px solid #8b1a1a">
          <p style="color:#c9a84c;font-size:.8rem;margin-bottom:.3rem">⚔ Você foi atacado mas sobreviveu!</p>
          <div class="battle-log" style="max-height:150px">${logHtml}</div>
        </div>`;
    }

    resultHtml = `
      <div class="tower-result-box">
        <div class="tower-result-title" style="color:#4caf82">✅ Expedição Concluída!</div>
        ${attackMsg}
        <div style="font-size:.85rem;margin:.5rem 0">${dropsHtml}</div>
        ${data.xpGained > 0 ? `<span class="cr-exp">+${data.xpGained} XP skill</span>` : ''}
        <button class="btn-send" onclick="loadZones()" style="margin-top:.8rem">Nova Expedição</button>
      </div>`;

    // Atualiza recursos
    resourcesData = await api('GET', '/api/gathering/resources');
  }

  el.innerHTML = resultHtml;
}

async function cancelZone(activityId) {
  if (!confirm('Cancelar expedição? Você perde todos os recursos coletados.')) return;
  const data = await api('POST', `/api/zones/${activityId}/cancel`);
  if (data.error) { showMessage(data.error, true); return; }
  await loadWarrior();
  loadZones();
}

// ── HABILIDADES (Pesca / Mineração / Forja) ──

let skillsData     = [];
let resourcesData  = [];
let gatheringState = null;
let gatheringTimer = null;
const FISH_DURATIONS = [5, 10, 20, 30, 40];
const MINE_DURATIONS = [10, 20, 30, 45, 60];

const FISH_DESCRIPTIONS = {
  SMALL_FISH:     '+10 estamina',
  SALMON:         '+25 estamina',
  TUNA:           '+40 estamina',
  SHARK:          '+60 estamina',
  LEGENDARY_FISH: '+80 estamina + buff temporário de XP',
};

const RESOURCE_ICONS = {
  SMALL_FISH:'🐟', SALMON:'🐠', TUNA:'🐡', SHARK:'🦈', LEGENDARY_FISH:'🐉',
  COPPER_ORE:'🟤', IRON_ORE:'⬛', SILVER_ORE:'⬜', GOLD_ORE:'🟡', MITHRIL_ORE:'🔷',
  RUBY_FRAGMENT:'💠', SAPPHIRE_FRAGMENT:'🔹', EMERALD_FRAGMENT:'💚', DIAMOND_FRAGMENT:'🔶', AMETHYST_FRAGMENT:'🟣',
  COPPER_BAR:'🟫', IRON_BAR:'⚫', SILVER_BAR:'🪨', GOLD_BAR:'🌟', MITHRIL_BAR:'💎',
  RUBY:'🔴', SAPPHIRE:'🔵', EMERALD:'💚', DIAMOND:'💎', AMETHYST:'🟣',
  LEATHER:'🟫',
};

async function loadSkillsTab() {
  [skillsData, resourcesData] = await Promise.all([
    api('GET', '/api/gathering/skills'),
    api('GET', '/api/gathering/resources'),
  ]);
  gatheringState = await api('GET', '/api/gathering/current');
  switchSkillTab('fish');
}

function switchSkillTab(tab) {
  ['fish','mine','smith','bag'].forEach(t => {
    document.getElementById('sk-panel-' + t).style.display = t === tab ? 'block' : 'none';
    document.getElementById('sk-tab-' + t).classList.toggle('active', t === tab);
  });
  if (tab === 'fish')  renderFishing();
  if (tab === 'mine')  renderMining();
  if (tab === 'smith') renderSmithing();
  if (tab === 'bag')   renderBag();
}

function getSkill(type) {
  return skillsData.find(s => s.skillType === type) || {level:1, experience:0, expNeeded:100};
}

function skillBar(skill) {
  const pct = Math.floor((skill.experience / skill.expNeeded) * 100);
  return `
    <div class="sk-skill-row">
      <span class="sk-skill-label">Lv.${skill.level}</span>
      <div class="xp-bar-bg" style="flex:1"><div class="xp-bar-fill" style="width:${pct}%"></div></div>
      <span class="xp-label" style="margin-left:.4rem">${skill.experience}/${skill.expNeeded} XP</span>
    </div>`;
}

// ── PESCAR ──
function renderFishing() {
  const skill = getSkill('FISHING');
  const fish  = resourcesData.filter(r => r.category === 'FISH');
  const busy  = warrior?.onMission ?? false;
  const activeFish = gatheringState?.active && gatheringState?.skillType === 'FISHING';

  document.getElementById('sk-fish-content').innerHTML = `
    <div class="sk-section">
      <div class="sk-title">🎣 Pesca ${skillBar(skill)}</div>
      ${activeFish ? renderGatheringTimer() : `
        <p class="sk-desc">Escolha a duração da pesca:</p>
        <div class="sk-duration-btns">
          ${FISH_DURATIONS.map(d => `
            <button class="btn-hour ${busy || (gatheringState?.active && !activeFish) ? 'disabled' : ''}"
                    onclick="startGathering('FISHING', ${d})"
                    ${busy || (gatheringState?.active && !activeFish) ? 'disabled' : ''}>
              ${d}min
            </button>`).join('')}
        </div>`}
    </div>
    ${fish.length > 0 ? `
    <div class="sk-section">
      <div class="sk-title">Peixes</div>
      ${fish.map(r => `
        <div class="sk-resource-row">
          <div>
            <span>${RESOURCE_ICONS[r.type] || '?'} ${r.displayName} ×${r.quantity}</span>
            <span style="color:#4caf82;font-size:.72rem;margin-left:.5rem">${FISH_DESCRIPTIONS[r.type] || ''}</span>
          </div>
          <button class="btn-equip" onclick="consumeFish('${r.type}')">Consumir</button>
        </div>`).join('')}
    </div>` : ''}`;
}

// ── MINERAR ──
function renderMining() {
  const skill = getSkill('MINING');
  const ores  = resourcesData.filter(r => ['ORE','FRAGMENT'].includes(r.category));
  const busy  = warrior?.onMission ?? false;
  const activeMine = gatheringState?.active && gatheringState?.skillType === 'MINING';

  document.getElementById('sk-mine-content').innerHTML = `
    <div class="sk-section">
      <div class="sk-title">⛏ Mineração ${skillBar(skill)}</div>
      ${activeMine ? renderGatheringTimer() : `
        <p class="sk-desc">Escolha a duração:</p>
        <div class="sk-duration-btns">
          ${MINE_DURATIONS.map(d => `
            <button class="btn-hour ${busy || (gatheringState?.active && !activeMine) ? 'disabled' : ''}"
                    onclick="startGathering('MINING', ${d})"
                    ${busy || (gatheringState?.active && !activeMine) ? 'disabled' : ''}>
              ${d}min
            </button>`).join('')}
        </div>`}
    </div>
    ${ores.length > 0 ? `
    <div class="sk-section">
      <div class="sk-title">Minérios e Fragmentos</div>
      ${ores.map(r => `
        <div class="sk-resource-row">
          <span>${RESOURCE_ICONS[r.type] || '?'} ${r.displayName} ×${r.quantity}</span>
        </div>`).join('')}
    </div>` : ''}`;
}

function renderGatheringTimer() {
  if (!gatheringState?.active) return '';
  const secs = gatheringState.secondsRemaining ?? 0;
  const done = secs <= 0;
  clearInterval(gatheringTimer);

  if (!done) {
    let s = secs;
    gatheringTimer = setInterval(() => {
      s--;
      const el = document.getElementById('gathering-timer');
      if (!el) { clearInterval(gatheringTimer); return; }
      if (s <= 0) {
        el.textContent = 'Pronto!';
        el.classList.add('done');
        document.getElementById('gathering-collect-btn').disabled = false;
        document.getElementById('gathering-collect-btn').textContent = '🎒 Coletar';
        clearInterval(gatheringTimer);
      } else {
        el.textContent = formatTime(s);
      }
    }, 1000);
  }

  return `
    <div class="gathering-active-box">
      <div class="gathering-active-title">${gatheringState.displayName} — ${gatheringState.durationMinutes}min</div>
      <div class="qp-timer ${done ? 'done' : ''}" id="gathering-timer">
        ${done ? 'Pronto!' : formatTime(secs)}
      </div>
      <div style="display:flex;gap:.5rem;margin-top:.5rem">
        <button class="btn-collect" id="gathering-collect-btn"
                ${done ? '' : 'disabled'}
                onclick="collectGathering(${gatheringState.id})">
          ${done ? '🎒 Coletar' : 'Coletando...'}
        </button>
        ${!done ? `<button class="btn-cancel-work" onclick="cancelGathering(${gatheringState.id})">Cancelar</button>` : ''}
      </div>
    </div>`;
}

// ── FORJA ──
async function renderSmithing() {
  const smithSkill = getSkill('SMITHING');
  const recipes = await api('GET', '/api/smithing/recipes');
  const bars = resourcesData.filter(r => r.category === 'BAR');
  const frags = resourcesData.filter(r => r.category === 'FRAGMENT');

  const refineHtml = recipes.refine?.map(r => `
    <div class="sk-recipe-card ${r.canCraft ? '' : 'locked'}">
      <div class="sk-recipe-title">${RESOURCE_ICONS[r.ore]||''} ${r.oreName} ×${r.oreQty} + ${fmtBronze(r.bronzeCost)} → ${RESOURCE_ICONS[r.bar]||''} ${r.barName}</div>
      <div style="font-size:.75rem;color:#888">Forja Lv.${r.levelRequired} ${!r.canCraft ? '🔒' : ''}</div>
      ${r.canCraft ? `
        <div style="display:flex;align-items:center;gap:.5rem;margin-top:.4rem">
          <input type="number" min="1" value="1" id="refine-qty-${r.ore}" style="width:55px;background:#0f3460;color:#e0d5c5;border:1px solid #444;border-radius:4px;padding:.2rem">
          <button class="btn-equip" onclick="refineOre('${r.ore}', '${r.bar}')">Refinar</button>
        </div>` : ''}
    </div>`).join('') || '';

  const craftHtml = recipes.craft?.map(r => `
    <div class="sk-recipe-card ${r.canCraft ? '' : 'locked'}">
      <div class="sk-recipe-title rarity-${r.rarity}">${r.name} (${r.sockets} socket${r.sockets !== 1 ? 's' : ''})</div>
      <div style="font-size:.75rem;color:#aaa">
        ${r.ingredients.map(i => `${RESOURCE_ICONS[i.resource]||''} ${i.name} ×${i.qty}`).join(' + ')}
        ${r.atk > 0 ? ` · +${r.atk} ATK` : ''}${r.def > 0 ? ` · +${r.def} DEF` : ''}${r.hp > 0 ? ` · +${r.hp} HP` : ''}
      </div>
      <div style="font-size:.75rem;color:#888">Forja Lv.${r.levelRequired} ${!r.canCraft ? '🔒' : ''}</div>
      ${r.canCraft ? `<button class="btn-equip" onclick="craftEquipment('${r.id}')" style="margin-top:.4rem">Craftar</button>` : ''}
    </div>`).join('') || '';

  const gemHtml = recipes.gems?.map(r => {
    const fragData = resourcesData.find(x => x.type === r.fragment);
    const qty = fragData?.quantity ?? 0;
    return `
      <div class="sk-recipe-card ${qty >= 3 ? '' : 'locked'}">
        <div class="sk-recipe-title">${RESOURCE_ICONS[r.fragment]||''} ${r.fragmentName} ×3 → ${RESOURCE_ICONS[r.gem]||''} ${r.gemName}</div>
        <div style="font-size:.75rem;color:#888">Você tem: ${qty} fragmentos</div>
        ${qty >= 3 ? `<button class="btn-equip" onclick="craftGem('${r.fragment}')" style="margin-top:.4rem">Criar Joia</button>` : ''}
      </div>`;
  }).join('') || '';

  document.getElementById('sk-smith-content').innerHTML = `
    <div class="sk-section">
      <div class="sk-title">🔨 Forja ${skillBar(smithSkill)}</div>
    </div>
    <div class="sk-section">
      <div class="sk-title">Refinar Minérios → Barras</div>
      ${refineHtml || '<p style="color:#888;font-size:.8rem">Nenhuma receita disponível</p>'}
    </div>
    <div class="sk-section">
      <div class="sk-title">Craftar Equipamento</div>
      ${craftHtml}
    </div>
    <div class="sk-section">
      <div class="sk-title">Criar Joias</div>
      ${gemHtml}
    </div>`;
}

// ── INVENTÁRIO DE RECURSOS ──
function renderBag() {
  if (resourcesData.length === 0) {
    document.getElementById('sk-bag-content').innerHTML = '<p style="color:#888;font-size:.82rem">Nenhum recurso ainda.</p>';
    return;
  }
  const categories = {FISH:'🎣 Peixes', ORE:'⛏ Minérios', FRAGMENT:'💠 Fragmentos', BAR:'🔩 Barras', GEM:'💎 Joias', MATERIAL:'📦 Materiais'};
  let html = '';
  for (const [cat, label] of Object.entries(categories)) {
    const items = resourcesData.filter(r => r.category === cat && r.quantity > 0);
    if (!items.length) continue;
    html += `<div class="sk-section"><div class="sk-title">${label}</div>`;
    html += items.map(r => `
      <div class="sk-resource-row">
        <div>
          <span>${RESOURCE_ICONS[r.type]||'?'} ${r.displayName} ×${r.quantity}</span>
          ${cat === 'FISH' && FISH_DESCRIPTIONS[r.type]
            ? `<span style="color:#4caf82;font-size:.72rem;margin-left:.5rem">${FISH_DESCRIPTIONS[r.type]}</span>`
            : ''}
        </div>
        ${cat === 'FISH' ? `<button class="btn-equip" onclick="consumeFish('${r.type}')">Consumir</button>` : ''}
      </div>`).join('');
    html += '</div>';
  }
  document.getElementById('sk-bag-content').innerHTML = html;
}

// ── AÇÕES ──
async function startGathering(skillType, duration) {
  const data = await api('POST', '/api/gathering/start', { skillType, durationMinutes: duration });
  if (data.error) { showMessage(data.error, true); return; }
  gatheringState = { active: true, ...data };
  await loadWarrior();
  switchSkillTab(skillType === 'FISHING' ? 'fish' : 'mine');
}

async function collectGathering(id) {
  const data = await api('POST', `/api/gathering/${id}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  const dropsHtml = data.drops.map(d =>
    `${RESOURCE_ICONS[d.type]||'?'} ${d.displayName} ×${d.quantity}`
  ).join('  ');
  showMessage('Coletado! ' + dropsHtml);

  gatheringState = { active: false };
  resourcesData = await api('GET', '/api/gathering/resources');
  await loadWarrior();
  switchSkillTab(document.querySelector('.tab.active')?.id?.replace('sk-tab-','') || 'fish');
}

async function cancelGathering(id) {
  if (!confirm('Cancelar coleta? Você não receberá nada.')) return;
  await api('POST', `/api/gathering/${id}/cancel`);
  gatheringState = { active: false };
  await loadWarrior();
  switchSkillTab(document.querySelector('.tab.active')?.id?.replace('sk-tab-','') || 'fish');
}

async function consumeFish(resourceType) {
  const data = await api('POST', `/api/gathering/consume/${resourceType}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.message} Estamina: ${data.newStamina}/100`);
  resourcesData = await api('GET', '/api/gathering/resources');
  await loadWarrior();
  renderFishing();
  renderBag();
}

async function refineOre(oreType, barType) {
  const qty = parseInt(document.getElementById(`refine-qty-${oreType}`)?.value || '1');
  const data = await api('POST', '/api/smithing/refine', { oreType, quantity: qty });
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  resourcesData = await api('GET', '/api/gathering/resources');
  renderSmithing();
}

async function craftEquipment(recipeId) {
  const data = await api('POST', '/api/smithing/craft', { recipeId });
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.message} (${data.sockets} socket${data.sockets !== 1 ? 's' : ''})`);
  resourcesData = await api('GET', '/api/gathering/resources');
  renderSmithing();
}

async function craftGem(fragmentType) {
  const data = await api('POST', '/api/smithing/gem', { fragmentType });
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  resourcesData = await api('GET', '/api/gathering/resources');
  renderSmithing();
}

async function freeWarrior() {
  if (!confirm('Liberar guerreiro? Só use isso se ele estiver travado sem nenhuma missão ativa.')) return;
  const data = await api('POST', '/api/warrior/free');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message || 'Guerreiro liberado!');
  await loadWarrior();
}

async function socketGem(itemId, gemType) {
  const data = await api('POST', `/api/smithing/socket/${itemId}/${gemType}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  resourcesData = await api('GET', '/api/gathering/resources');
  loadInventory();
}

// ── TRABALHO ──
let workTimerInterval = null;

async function loadWork() {
  const current = await api('GET', '/api/work/current');
  if (current.id) {
    openWorkProgress(current);
  } else {
    showWorkJobList();
  }
}

async function showWorkJobList() {
  document.getElementById('work-progress').style.display = 'none';
  document.getElementById('work-normal').style.display   = 'block';

  const jobs = await api('GET', '/api/work/jobs');
  if (!Array.isArray(jobs)) return;

  const wl = warrior?.workLevel ?? 1;
  const wxp = warrior?.workExperience ?? 0;
  const wExpNeeded = warrior?.workExpNeeded ?? 50;
  const wPct = Math.floor((wxp / wExpNeeded) * 100);
  const bonusPct = Math.round((warrior?.workLevel - 1 ?? 0) * 5);

  document.getElementById('work-job-list').innerHTML = `
    <div class="work-jobs-grid">
      ${jobs.map(job => {
        const locked    = !job.available && job.profLevel < job.minWorkLevel;
        const busy      = warrior?.onMission && !locked;
        const disabled  = locked || busy;
        const xpPct     = Math.floor((job.profXp / job.profXpNeeded) * 100);

        return `
          <div class="work-job-card ${locked ? 'locked' : ''}">
            <div class="wj-header">
              <span class="wj-name">${job.displayName}</span>
              <span class="wj-prof-level">Lv.${job.profLevel}${job.bonusPct > 0 ? ` <span class="wl-bonus">+${job.bonusPct}%</span>` : ''}</span>
            </div>
            <div class="xp-bar-bg" style="margin-bottom:.4rem"><div class="xp-bar-fill" style="width:${xpPct}%"></div></div>
            <p class="wj-desc">${job.description}</p>
            <div class="wj-stats">
              <span>${fmtBronze(job.goldPerHourWithBonus)}/h</span>
              <span>⭐ ${job.xpPerHour} xp/h</span>
              ${locked ? `<span class="wj-req">🔒 Lv.${job.minWorkLevel} necessário</span>` : ''}
            </div>
            ${!locked ? `
              <div class="wj-hours">
                <span>Horas:</span>
                <div class="hours-btns">
                  ${[1,2,4,6,8,12].map(h => `
                    <button class="btn-hour" onclick="startWork('${job.id}', ${h})" ${disabled ? 'disabled' : ''}>
                      ${h}h
                      <span class="hour-gold">${fmtBronze(Math.round(job.goldPerHourWithBonus * h))}</span>
                    </button>
                  `).join('')}
                </div>
              </div>
            ` : ''}
          </div>`;
      }).join('')}
    </div>`;
}

async function startWork(workType, hours) {
  const data = await api('POST', '/api/work/start', { workType, hours });
  if (data.error) { showMessage(data.error, true); return; }
  await loadWarrior();
  openWorkProgress(data);
}

function openWorkProgress(session) {
  document.getElementById('work-normal').style.display   = 'none';
  document.getElementById('work-progress').style.display = 'block';
  renderWorkProgress(session);
}

function renderWorkProgress(session) {
  clearInterval(workTimerInterval);
  const done = session.secondsRemaining <= 0;

  document.getElementById('work-progress-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">${session.jobName}</div>
      <p style="color:#888;font-size:.82rem;margin-bottom:.6rem">${session.description}</p>
      <div class="wj-stats" style="margin-bottom:.8rem">
        <span>${fmtBronze(session.goldReward)}</span>
        <span>⭐ ${session.xpReward} xp de trabalho</span>
        <span>⏱ ${session.hours}h</span>
      </div>
      <div class="qp-timer ${done ? 'done' : ''}" id="work-timer">
        ${done ? 'Concluído!' : formatTime(session.secondsRemaining)}
      </div>
      <button class="btn-collect qp-collect-btn" id="work-btn"
              ${done ? '' : 'disabled'}
              onclick="collectWork(${session.id})">
        ${done ? '💰 Coletar' : 'Trabalhando...'}
      </button>
      ${!done ? `
      <button class="btn-cancel-work" onclick="cancelWork(${session.id})" style="margin-top:.5rem">
        Cancelar (recebe horas completas)
      </button>` : ''}
    </div>`;

  if (!done) {
    let secs = session.secondsRemaining;
    workTimerInterval = setInterval(() => {
      secs--;
      const t = document.getElementById('work-timer');
      const b = document.getElementById('work-btn');
      if (!t) { clearInterval(workTimerInterval); return; }
      if (secs <= 0) {
        t.textContent = 'Concluído!';
        t.classList.add('done');
        b.disabled = false;
        b.textContent = '💰 Coletar';
        clearInterval(workTimerInterval);
      } else {
        t.textContent = formatTime(secs);
      }
    }, 1000);
  }
}

async function collectWork(sessionId) {
  const data = await api('POST', `/api/work/${sessionId}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  document.getElementById('work-progress-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">Trabalho Concluído!</div>
      <div class="qp-result-row">
        <span class="cr-gold">${fmtBronze(data.goldEarned)}</span>
        <span class="cr-exp">+${data.xpEarned} xp trabalho</span>
      </div>
      <p style="color:#888;font-size:.8rem;margin:.5rem 0">${data.jobName}</p>
      <button class="btn-send qp-collect-btn" onclick="closeWork()" style="margin-top:.8rem">
        Voltar aos Empregos
      </button>
    </div>`;

  await loadWarrior();
}

async function cancelWork(sessionId) {
  if (!confirm('Cancelar o trabalho? Você recebe apenas o gold das horas completas.')) return;

  const data = await api('POST', `/api/work/${sessionId}/cancel`);
  if (data.error) { showMessage(data.error, true); return; }

  clearInterval(workTimerInterval);

  const msg = data.goldEarned > 0
    ? `Trabalho cancelado. ${fmtBronze(data.goldEarned)} e +${data.xpEarned} xp pelas horas completas.`
    : 'Trabalho cancelado. Nenhuma hora completa — nada recebido.';

  document.getElementById('work-progress-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">Trabalho Cancelado</div>
      <div class="qp-result-row">
        ${data.goldEarned > 0
          ? `<span class="cr-gold">${fmtBronze(data.goldEarned)}</span>
             <span class="cr-exp">+${data.xpEarned} xp trabalho</span>`
          : `<span style="color:#888">Nenhuma hora completa</span>`}
      </div>
      <button class="btn-send qp-collect-btn" onclick="closeWork()" style="margin-top:.8rem">
        Voltar aos Empregos
      </button>
    </div>`;

  await loadWarrior();
}

async function closeWork() {
  clearInterval(workTimerInterval);
  document.getElementById('work-progress').style.display = 'none';
  document.getElementById('work-normal').style.display   = 'block';
  await showWorkJobList();
}

// ── TORRE INFERNAL ──

async function loadTower() {
  const current = await api('GET', '/api/tower/current');
  if (current.active) {
    showTowerFloor(current);
  } else {
    await showTowerLobby();
  }
}

async function showTowerLobby() {
  document.getElementById('tower-lobby').style.display  = 'block';
  document.getElementById('tower-floor').style.display  = 'none';
  document.getElementById('tower-result').style.display = 'none';

  const ranking = await api('GET', '/api/tower/ranking');
  const stamina = warrior?.stamina ?? 0;
  const busy    = warrior?.onMission ?? false;
  const noStamina = stamina < 25;

  const rankHtml = ranking.length === 0
    ? '<p style="color:#888;font-size:.82rem">Nenhum guerreiro chegou lá ainda.</p>'
    : `<table class="rank-table">
        <thead><tr><th>#</th><th>Guerreiro</th><th>Andar</th></tr></thead>
        <tbody>
          ${ranking.map((r, i) => `
            <tr class="${r.warriorName === warrior?.name ? 'me' : ''}">
              <td class="rank-pos">${i + 1}</td>
              <td class="rank-name">${r.warriorName}</td>
              <td class="rank-pts">🏰 ${r.bestFloor}</td>
            </tr>`).join('')}
        </tbody>
      </table>`;

  document.getElementById('tower-ranking-panel').innerHTML = `
    <div class="tower-enter-box">
      <div class="tower-enter-title">Entrar na Torre</div>
      <p style="color:#888;font-size:.82rem;margin:.4rem 0">
        Custo: <span class="stamina-cost">⚡ 25 estamina</span>
        &nbsp;·&nbsp; Sua estamina: <strong>${stamina}/100</strong>
      </p>
      <p style="color:#888;font-size:.8rem;margin-bottom:.8rem">
        Lute andar por andar. Se perder, é expulso. Chegue o mais longe possível!
      </p>
      <button class="btn-fight"
              ${busy || noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''}
              onclick="enterTower()">
        ${busy ? '⚔ Guerreiro ocupado' : noStamina ? '⚡ Sem estamina' : '🏰 Entrar na Torre'}
      </button>
    </div>
    <h3 style="color:#c9a84c;margin:1rem 0 .5rem;font-size:.85rem;text-transform:uppercase;letter-spacing:.05em">
      Ranking — Melhores Andares
    </h3>
    ${rankHtml}`;
}

function showTowerFloor(state) {
  document.getElementById('tower-lobby').style.display  = 'none';
  document.getElementById('tower-result').style.display = 'none';
  document.getElementById('tower-floor').style.display  = 'block';

  document.getElementById('tower-floor-content').innerHTML = `
    <div class="tower-floor-box">
      <div class="tower-floor-num">🏰 Andar ${state.currentFloor}</div>
      ${state.highestFloor > 0 ? `<div class="tower-cleared">✓ Último andar completado: ${state.highestFloor}</div>` : ''}
      <div class="tower-boss-card">
        <div class="tower-boss-name">${state.bossName}</div>
        <div class="tower-boss-stats">
          <span>❤ ${state.bossHp} HP</span>
          <span>⚔ ${state.bossAtk} ATK</span>
          <span>🛡 ${state.bossDef} DEF</span>
          <span>💨 ${state.bossEvasion}% evasão</span>
        </div>
        <div class="tower-rewards-preview">
          Recompensa: ${fmtBronze(state.currentFloor * 40)} · ⭐ ${state.currentFloor * 20} exp
        </div>
      </div>
      <div style="display:flex;gap:.5rem;margin-top:.8rem">
        <button class="btn-fight" onclick="fightTower()">⚔ Lutar</button>
        <button class="btn-cancel-work" onclick="exitTower()">Sair da Torre</button>
      </div>
    </div>`;
}

async function enterTower() {
  const enter = await api('POST', '/api/tower/enter');
  if (enter.error) { showMessage(enter.error, true); return; }
  await loadWarrior();
  // Luta automaticamente ao entrar
  await fightTower();
}

async function fightTower() {
  const data = await api('POST', '/api/tower/fight');
  if (data.error) { showMessage(data.error, true); return; }
  await loadWarrior();
  showTowerResult(data);
}

function showTowerResult(result) {
  document.getElementById('tower-floor').style.display  = 'none';
  document.getElementById('tower-lobby').style.display  = 'none';
  document.getElementById('tower-result').style.display = 'block';

  const logHtml = renderBattleLog(result.log);

  const title   = result.won ? `🏆 Andar ${result.floor} Completado!` : `💀 Derrotado no Andar ${result.floor}`;
  const color   = result.won ? '#4caf82' : '#cf6679';

  let actions = '';
  if (result.won && !result.runOver) {
    actions = `
      <button class="btn-fight" onclick="nextFloor()" style="margin-right:.5rem">Próximo Andar →</button>
      <button class="btn-cancel-work" onclick="exitTower()">Sair com os ganhos</button>`;
  } else {
    actions = `<button class="btn-send" onclick="closeTowerResult()">Fechar</button>`;
  }

  document.getElementById('tower-result-content').innerHTML = `
    <div class="tower-result-box">
      <div class="tower-result-title" style="color:${color}">${title}</div>
      ${result.won ? `
        <div class="tower-result-rewards">
          ${fmtBronze(result.bronzeEarned)} &nbsp; ⭐ ${result.expEarned} exp
        </div>` : ''}
      <div class="battle-log" style="margin:.6rem 0">${logHtml}</div>
      <div style="display:flex;gap:.5rem;flex-wrap:wrap">${actions}</div>
    </div>`;
}

async function nextFloor() {
  // Avança para o próximo andar e luta automaticamente
  await fightTower();
}

async function exitTower() {
  if (!confirm('Sair da torre? Você mantém os ganhos dos andares já completados.')) return;
  const data = await api('POST', '/api/tower/exit');
  if (data.error) { showMessage(data.error, true); return; }
  await loadWarrior();
  await closeTowerResult();
}

async function closeTowerResult() {
  document.getElementById('tower-result').style.display = 'none';
  await showTowerLobby();
}

// ── ARENA ──
function switchArenaTab(tab) {
  document.getElementById('panel-rank').style.display  = tab === 'rank'  ? 'block' : 'none';
  document.getElementById('panel-fight').style.display = tab === 'fight' ? 'block' : 'none';
  document.getElementById('tab-rank').classList.toggle('active',  tab === 'rank');
  document.getElementById('tab-fight').classList.toggle('active', tab === 'fight');
  if (tab === 'fight') loadCurrentFight();
}

async function loadRank() {
  const rank = await api('GET', '/api/arena/rank');
  if (!Array.isArray(rank)) return;
  if (!rank.length) {
    document.getElementById('rank-list').innerHTML = '<p style="color:#888;font-size:.82rem">Nenhum jogador ainda.</p>';
    return;
  }
  document.getElementById('rank-list').innerHTML = `
    <table class="rank-table">
      <thead><tr><th>#</th><th>Jogador</th><th>Pontos</th><th>V/D</th></tr></thead>
      <tbody>
        ${rank.map((r, i) => `
          <tr class="${r.warriorName === warrior?.name ? 'me' : ''}">
            <td class="rank-pos">${i + 1}</td>
            <td class="rank-name">${r.warriorName}</td>
            <td class="rank-pts">${r.rankPoints}</td>
            <td class="rank-wl">${r.wins}/${r.losses}</td>
          </tr>`).join('')}
      </tbody>
    </table>`;
}

async function loadCurrentFight() {
  const data = await api('GET', '/api/arena/current');
  renderFightArea(data);
}

function renderFightArea(data) {
  const el = document.getElementById('fight-area');
  if (!data.active && !data.id) {
    const stamina = warrior?.stamina ?? 100;
  const noStamina = stamina < 25;
  el.innerHTML = `
      <div class="fight-box">
        <h3>Entrar em batalha</h3>
        <p style="color:#888;font-size:.83rem;margin-bottom:.5rem">
          Custo: <span class="stamina-cost">⚡ 25 estamina</span> &nbsp;·&nbsp; Sua estamina: <strong>${stamina}/100</strong>
        </p>
        <p style="color:#888;font-size:.83rem;margin-bottom:.8rem">
          Batalha dura 1 minuto. Vitória: +25 rank, ${fmtBronze(200)}.
        </p>
        <button class="btn-fight" ${noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''} onclick="startFight()">
          ${noStamina ? '⚡ Sem estamina' : '⚔ Lutar'}
        </button>
      </div>`;
    return;
  }

  if (data.id && data.status === 'FIGHTING') {
    let secs = data.secondsRemaining;
    clearInterval(fightTimerInterval);

    el.innerHTML = `
      <div class="fight-box">
        <h3>Em batalha!</h3>
        <div class="fight-vs">vs <strong>${data.opponentName}</strong></div>
        <div class="fight-timer" id="fight-timer">${formatTime(secs)}</div>
        <p style="color:#888;font-size:.8rem">Volte quando o timer acabar para coletar o resultado.</p>
      </div>`;

    fightTimerInterval = setInterval(() => {
      secs--;
      const te = document.getElementById('fight-timer');
      if (!te) { clearInterval(fightTimerInterval); return; }
      if (secs <= 0) {
        te.textContent = 'Pronto!';
        te.classList.add('done');
        clearInterval(fightTimerInterval);
        el.innerHTML += `<button class="btn-collect" onclick="collectFight(${data.id})" style="margin-top:.5rem">🎁 Coletar resultado</button>`;
      } else { te.textContent = formatTime(secs); }
    }, 1000);
  }
}

async function startFight() {
  const data = await api('POST', '/api/arena/fight');
  if (data.error) { showMessage(data.error, true); return; }
  switchArenaTab('fight');
  renderFightArea(data);
}

async function collectFight(matchId) {
  const data = await api('POST', `/api/arena/${matchId}/collect`);
  if (data.error) { showMessage(data.error, true); return; }

  const result = data.won
    ? `🏆 Vitória contra ${data.opponent}!`
    : `💀 Derrota para ${data.opponent}`;
  const log = renderBattleLog(data.log || []);

  document.getElementById('fight-area').innerHTML = `
    <div class="fight-box">
      <h3>${result}</h3>
      <p style="font-size:.82rem;color:#aaa;margin-bottom:.5rem">
        ${data.won ? '+' : ''}${data.rankChange} pontos de rank &nbsp;·&nbsp; ${fmtBronze(data.goldEarned)}
      </p>
    </div>
    <div class="battle-log">${log}</div>
    <br>
    <button class="btn-fight" onclick="resetFight()">Lutar novamente</button>`;

  loadWarrior();
  loadRank();
}

function resetFight() {
  document.getElementById('fight-area').innerHTML = `
    <div class="fight-box">
      <h3>Entrar em batalha</h3>
      <p style="color:#888;font-size:.83rem;margin-bottom:.8rem">
        Seu guerreiro irá combater outro jogador ou um NPC. A batalha dura 1 minuto.
      </p>
      <button class="btn-fight" onclick="startFight()">⚔ Lutar</button>
    </div>`;
}

// ── Init ──
const resetTokenParam = new URLSearchParams(window.location.search).get('reset');
if (resetTokenParam) {
  document.getElementById('login-screen').style.display = 'flex';
  showAuthForm('reset-form');
} else if (token) {
  api('GET', '/api/warrior').then(data => {
    if (data.error) { logout(); return; }
    warrior = data;
    api('GET', '/api/auth/login').catch(() => {});
    currentUsername = localStorage.getItem('username') || '';
    enterGame();
  });
} else {
  document.getElementById('login-screen').style.display = 'flex';
  showLogin();
}

// ═══════════════════════════════════════════════════════════════════
// GUILDA
// ═══════════════════════════════════════════════════════════════════

async function loadGuild() {
  const el = document.getElementById('guild-content');
  el.innerHTML = '<p>Carregando...</p>';
  try {
    const data = await api('GET', '/api/guild');
    if (data.inGuild) {
      renderGuildPanel(data);
    } else {
      renderNoGuildPanel();
    }
  } catch(e) {
    el.innerHTML = '<p style="color:red">Erro ao carregar guilda.</p>';
  }
}

function renderGuildPanel(g) {
  const el = document.getElementById('guild-content');
  const levelUpBtn = g.isLeader
    ? `<button onclick="guildLevelUp()" style="margin-top:8px">⬆ Subir Nível (${g.levelUpCost} gold)</button>`
    : '';
  const disbandBtn = g.isLeader
    ? `<button onclick="guildDisband()" style="background:#8b0000;margin-top:8px">💀 Dissolver Guilda</button>`
    : `<button onclick="guildLeave()" style="background:#555;margin-top:8px">🚪 Sair da Guilda</button>`;

  const memberRows = g.members.map(m => {
    const badge  = m.isLeader ? ' 👑' : '';
    const kickBtn = g.isLeader && !m.isMe && !m.isLeader
      ? `<button onclick="guildKick(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#8b0000">Expulsar</button>`
      : '';
    const transferBtn = g.isLeader && !m.isMe
      ? `<button onclick="guildTransfer(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#555">Liderança</button>`
      : '';
    return `<tr>
      <td>${m.warriorName}${badge}${m.isMe ? ' <em>(você)</em>' : ''}</td>
      <td style="text-align:right">${kickBtn} ${transferBtn}</td>
    </tr>`;
  }).join('');

  el.innerHTML = `
    <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px;margin-bottom:12px">
      <h3 style="margin:0 0 4px">${g.name} <span style="font-size:12px;color:#aaa">Nv.${g.level}</span></h3>
      <p style="color:#aaa;margin:0 0 8px;font-size:13px">${g.description || 'Sem descrição.'}</p>
      <div style="display:flex;gap:24px;font-size:13px">
        <span>👑 Gold da guilda: <strong>${g.gold}</strong></span>
        <span>👥 Membros: <strong>${g.members.length}/${g.maxMembers}</strong></span>
      </div>
      ${levelUpBtn}
    </div>

    <h4 style="margin:0 0 8px">Membros</h4>
    <table style="width:100%;border-collapse:collapse;font-size:13px">
      ${memberRows}
    </table>

    <div style="margin-top:16px;display:flex;gap:8px;flex-wrap:wrap">
      <input id="donate-amount" type="number" min="1" placeholder="Bronze para doar"
        style="width:160px;padding:6px;background:#111;color:#eee;border:1px solid #555;border-radius:4px">
      <button onclick="guildDonate()">💰 Doar</button>
      ${disbandBtn}
    </div>
    <div id="guild-msg" style="margin-top:8px;min-height:20px"></div>
  `;
}

async function renderNoGuildPanel() {
  const el = document.getElementById('guild-content');
  let listHtml = '<p>Carregando guildas...</p>';
  try {
    const guilds = await api('GET', '/api/guild/list');
    if (guilds.length === 0) {
      listHtml = '<p style="color:#aaa">Nenhuma guilda criada ainda. Seja o primeiro!</p>';
    } else {
      listHtml = guilds.map(g => `
        <div style="background:#1a1a2e;border:1px solid #444;border-radius:6px;padding:10px;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center">
          <div>
            <strong>${g.name}</strong> <span style="font-size:11px;color:#aaa">Nv.${g.level}</span><br>
            <span style="font-size:12px;color:#888">${g.description || ''}</span><br>
            <span style="font-size:12px">👥 ${g.members}/${g.maxMembers}</span>
          </div>
          <button onclick="guildJoin(${g.id})" ${g.members >= g.maxMembers ? 'disabled' : ''}>
            ${g.members >= g.maxMembers ? 'Cheia' : 'Entrar'}
          </button>
        </div>
      `).join('');
    }
  } catch(e) {
    listHtml = '<p style="color:red">Erro ao carregar lista.</p>';
  }

  el.innerHTML = `
    <p style="color:#aaa;font-size:13px">Você não pertence a nenhuma guilda.</p>

    <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:14px;margin-bottom:16px">
      <h4 style="margin:0 0 10px">Criar nova guilda <span style="font-size:12px;color:#aaa">(custa 100 bronze)</span></h4>
      <input id="guild-name"  type="text" placeholder="Nome (3-30 chars)" maxlength="30"
        style="width:100%;padding:7px;background:#111;color:#eee;border:1px solid #555;border-radius:4px;margin-bottom:6px;box-sizing:border-box">
      <input id="guild-desc"  type="text" placeholder="Descrição (opcional)" maxlength="120"
        style="width:100%;padding:7px;background:#111;color:#eee;border:1px solid #555;border-radius:4px;margin-bottom:8px;box-sizing:border-box">
      <button onclick="guildCreate()">🛡 Criar Guilda</button>
    </div>

    <h4 style="margin:0 0 8px">Guildas existentes</h4>
    ${listHtml}
    <div id="guild-msg" style="margin-top:8px;min-height:20px"></div>
  `;
}

function guildMsg(text, ok = true) {
  const el = document.getElementById('guild-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

async function guildCreate() {
  const name = document.getElementById('guild-name').value.trim();
  const desc = document.getElementById('guild-desc').value.trim();
  try {
    await api('POST', '/api/guild', { name, description: desc });
    guildMsg('Guilda criada!');
    await loadGuild();
    await updateHeader();
  } catch(e) {
    guildMsg(e.message || 'Erro ao criar guilda.', false);
  }
}

async function guildJoin(id) {
  try {
    await api('POST', `/api/guild/join/${id}`);
    guildMsg('Você entrou na guilda!');
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao entrar.', false);
  }
}

async function guildLeave() {
  if (!confirm('Sair da guilda?')) return;
  try {
    await api('POST', '/api/guild/leave');
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao sair.', false);
  }
}

async function guildDisband() {
  if (!confirm('Dissolver a guilda? Todos os membros serão removidos.')) return;
  try {
    await api('DELETE', '/api/guild');
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao dissolver.', false);
  }
}

async function guildKick(playerId) {
  if (!confirm('Expulsar este membro?')) return;
  try {
    await api('POST', `/api/guild/kick/${playerId}`);
    guildMsg('Membro expulso.');
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao expulsar.', false);
  }
}

async function guildTransfer(playerId) {
  if (!confirm('Transferir liderança para este membro?')) return;
  try {
    await api('POST', `/api/guild/transfer/${playerId}`);
    guildMsg('Liderança transferida.');
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao transferir.', false);
  }
}

async function guildDonate() {
  const amount = parseInt(document.getElementById('donate-amount').value);
  if (!amount || amount <= 0) { guildMsg('Informe um valor válido.', false); return; }
  try {
    const r = await api('POST', '/api/guild/donate', { amount });
    guildMsg(`Doação feita! Gold da guilda: ${r.guildGold}`);
    await loadGuild();
    await updateHeader();
  } catch(e) {
    guildMsg(e.message || 'Erro ao doar.', false);
  }
}

async function guildLevelUp() {
  if (!confirm('Gastar gold da guilda para subir de nível?')) return;
  try {
    const r = await api('POST', '/api/guild/levelup');
    guildMsg(r.message);
    await loadGuild();
  } catch(e) {
    guildMsg(e.message || 'Erro ao subir nível.', false);
  }
}
