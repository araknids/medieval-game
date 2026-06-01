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

  document.getElementById('hdr-username').textContent = warrior.name;
  document.getElementById('hdr-gold').textContent = (warrior.gold ?? '–') + ' ouro';
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
    <span class="status-badge ${busy ? 'status-busy' : 'status-available'}">
      ${busy ? '⚔ Ocupado' : '✓ Disponível'}
    </span>`;
}

// ── Navegação de locais ──
function goTo(loc) {
  ['tavern','inventory','commerce','work','arena'].forEach(l => {
    document.getElementById('loc-panel-' + l).style.display = l === loc ? 'block' : 'none';
    document.getElementById('loc-' + l).classList.toggle('active', l === loc);
  });
  if (loc === 'arena')    { loadRank(); loadCurrentFight(); }
  if (loc === 'commerce') { loadShop(); }
  if (loc === 'inventory'){ renderAttributes(); loadInventory(); }
  if (loc === 'work')     { loadWork(); }
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
        <span>💰 ${q.goldReward}</span>
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
        <span>💰 ${q.goldReward} ouro</span>
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

function renderQuestProgress(quest) {
  const done = quest.secondsRemaining <= 0;
  document.getElementById('qp-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">${quest.questType}</div>
      <div class="qp-rewards-preview">
        💰 ${quest.goldReward} ouro &nbsp;&nbsp; ⭐ ${quest.expReward} exp
      </div>
      <div class="qp-timer ${done ? 'done' : ''}" id="qp-timer">
        ${done ? 'Completo!' : formatTime(quest.secondsRemaining)}
      </div>
      <button class="btn-collect qp-collect-btn" id="qp-btn"
              ${done ? '' : 'disabled'}
              onclick="collectFromProgress(${quest.id})">
        ${done ? '🎁 Coletar' : 'Aguardando...'}
      </button>
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
      <span class="cr-gold">+${data.goldEarned} ouro</span>
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
    let line = `+${data.goldEarned} ouro   +${data.expEarned} exp`;
    if (data.droppedItem) {
      const d = data.droppedItem;
      const stats = [
        d.attackBonus  > 0 ? `+${d.attackBonus} ATK`  : '',
        d.defenseBonus > 0 ? `+${d.defenseBonus} DEF` : '',
        d.healthBonus  > 0 ? `+${d.healthBonus} HP`   : '',
      ].filter(Boolean).join(' ');
      line += `\n✨ ${d.name} (${stats})`;
    }
    card.innerHTML = `<div class="collect-result">${line.replace('\n', '<br>')}</div>`;
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
          <div class="shop-card">
            <div class="shop-item-info">
              <h3 class="rarity-${i.rarity}">${i.name}</h3>
              <div class="shop-stats">${i.typeDisplay} · ${i.rarityName} · ${stats}</div>
            </div>
            <span class="shop-price">💰 ${i.price}</span>
            <button class="btn-buy" onclick="buyItem(${i.id})">Comprar</button>
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
  showMessage(`${data.message} +${data.goldEarned} ouro`);
  loadSellList();
  loadWarrior();
}

async function buyItem(shopItemId) {
  const data = await api('POST', `/api/shop/buy/${shopItemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  loadWarrior();
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
    <div class="bag-item">
      <div>
        <div class="bag-item-name rarity-${item.rarity}">${item.name}</div>
        <div class="bag-item-type">${item.typeDisplay} · ${item.rarityName}</div>
        <div class="bag-item-stats">${statsText(item)}</div>
      </div>
      <button class="btn-equip" onclick="equipItem(${item.id})">Equipar</button>
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
      <span class="shop-price">💰 ${item.sellPrice}</span>
      <button class="btn-buy" onclick="sellItem(${item.id})">Vender</button>
    </div>`).join('');
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
              <span>💰 ${job.goldPerHourWithBonus}/h</span>
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
                      <span class="hour-gold">${Math.round(job.goldPerHourWithBonus * h)} 💰</span>
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
        <span>💰 ${session.goldReward} ouro</span>
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
        <span class="cr-gold">+${data.goldEarned} ouro</span>
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
    ? `Trabalho cancelado. +${data.goldEarned} ouro e +${data.xpEarned} xp pelas horas completas.`
    : 'Trabalho cancelado. Nenhuma hora completa — nada recebido.';

  document.getElementById('work-progress-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">Trabalho Cancelado</div>
      <div class="qp-result-row">
        ${data.goldEarned > 0
          ? `<span class="cr-gold">+${data.goldEarned} ouro</span>
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
          Batalha dura 1 minuto. Vitória: +25 rank, +200 ouro.
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

  const result = data.won ? '🏆 Vitória!' : '💀 Derrota';
  const log = (data.log || []).map(line => {
    if (line.includes('vence'))   return `<span class="log-win">${line}</span>`;
    if (line.includes('esquiva')) return `<span class="log-evade">${line}</span>`;
    if (line.includes('───'))     return `<span class="log-separator">${line}</span>`;
    return `<span class="log-hit">${line}</span>`;
  }).join('\n');

  document.getElementById('fight-area').innerHTML = `
    <div class="fight-box">
      <h3>${result} vs ${data.opponent}</h3>
      <p style="font-size:.82rem;color:#aaa;margin-bottom:.5rem">
        ${data.won ? '+' : ''}${data.rankChange} pontos de rank &nbsp;·&nbsp; +${data.goldEarned} ouro
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
