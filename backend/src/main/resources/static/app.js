// ── i18n ──────────────────────────────────────────────────────────────────────
let _lang = {};
let _currentLang = localStorage.getItem('lang') || 'en';

async function loadLanguage(lang) {
  try {
    const res = await fetch(`/lang/${lang}.json`);
    _lang = await res.json();
    _currentLang = lang;
    localStorage.setItem('lang', lang);
    applyStaticTranslations();
  } catch(e) {
    console.warn('Failed to load language:', lang, e);
  }
}

// Translate key with optional {param} interpolation
// Returns '' if lang not loaded yet — never shows raw keys to the user
function t(key, params) {
  const val = _lang[key];
  if (val === undefined) return '';   // lang not loaded or missing key
  let s = val;
  if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, v);
  return s;
}

// Escapa texto controlado por jogador (nome de guerreiro/guild, mensagem de mail) ANTES de
// interpolar em innerHTML. Sem isto, um jogador pode injetar HTML/script via mail ou nome e
// roubar o token de quem visualiza (XSS armazenado). Use em TODO valor vindo de outro usuário.
function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

// Apply translations to elements with data-i18n attribute
function applyStaticTranslations() {
  document.querySelectorAll('[data-i18n]').forEach(el => {
    el.textContent = t(el.dataset.i18n);
  });
  // Show CURRENT language — clicking switches to the other
  const btn = document.getElementById('lang-toggle');
  if (btn) {
    btn.textContent = _currentLang === 'en' ? '🌐 EN' : '🌐 PT';
    btn.title = _currentLang === 'en' ? 'Switch to Português' : 'Switch to English';
  }
}

async function toggleLanguage() {
  const next = _currentLang === 'en' ? 'pt' : 'en';
  await loadLanguage(next);

  // Re-render sidebar (always visible, uses t() for labels)
  if (warrior) loadWarrior();

  // Re-render current active panel using its loc id
  const activeBtn = document.querySelector('.loc-btn.active');
  if (activeBtn && activeBtn.id) {
    const loc = activeBtn.id.replace('loc-', '');
    goTo(loc);
  }
}

// ── Global state ──────────────────────────────────────────────────────────────
let token    = localStorage.getItem('token');
let player   = null;
let warrior  = null;
let questTypes = [];
let timerIntervals = {};
let fightTimerInterval = null;
let currentUsername = '';

// ── API helper ──
// 409 = conflito de concorrência (optimistic locking). Como toda regra de negócio
// rejeitada virou 400, um 409 é SEMPRE seguro de repetir — então fazemos 1 retry
// automático e transparente (cobre duplo-clique e emboscada simultânea). [AUDITORIA A8 / BL-1]
async function api(method, path, body) {
  const opts = {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': 'Bearer ' + token } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  };
  let res = await fetch(path, opts);
  if (res.status === 409) {
    await new Promise(r => setTimeout(r, 150)); // deixa a transação concorrente terminar
    res = await fetch(path, opts);
  }
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

// ── Quest narratives ──
const QUEST_NARRATIVES = {
  PATROL: [
    'Your warrior patrolled the surroundings, keeping the peace and driving off bandits.',
    'A quiet round through the city outskirts. The night was calm.',
    'The warrior crossed every street with care, ensuring the region\'s safety.',
  ],
  DUNGEON: [
    'The darkness of the dungeon was swept aside with determination. Enemies fell along the way.',
    'Battles in the depths echoed through the caverns. The warrior emerged victorious.',
    'Shadow creatures tried to block the path, but were defeated one by one.',
  ],
  RAID: [
    'The raid was intense — multiple enemies were defeated in open combat.',
    'Blood and glory: the raid was a resounding success.',
    'Leading the assault, the warrior left a trail of victories across the field.',
  ],
  BOSS_HUNT: [
    'The boss roared menacingly, but fell before the warrior\'s determination.',
    'An epic battle that will be remembered. The boss was slain.',
    'After a legendary confrontation, the boss was finally defeated.',
  ],
};

const DROP_NARRATIVES = [
  'While searching through the enemy\'s remains, something shiny caught the eye...',
  'In a forgotten corner of the dungeon, an item lay abandoned for years...',
  'Victory brought an unexpected surprise hidden among the enemy\'s belongings...',
  'Among the rubble of battle, a glimmer caught the warrior\'s attention...',
  'Carefully examining the fallen enemy, something valuable was discovered...',
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
  if (seconds <= 0) return t('quest.ready_short');
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

// Colors: item bonus = green (uncommon), buff bonus = gold
function statRow(label, base, itemBonus, buffBonus) {
  let display = `${base}`;
  if (itemBonus > 0) display += `<span style="color:#4caf50;font-size:.8em"> +${itemBonus}</span>`;
  if (buffBonus > 0) display += `<span style="color:#ffd700;font-size:.8em"> +${buffBonus}</span>`;
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
    document.getElementById('auth-error').textContent = 'Passwords do not match';
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
    warriorName: document.getElementById('reg-warrior').value.trim()
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
  loadWorld(); // Default to World tab
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

  // Buff display with color and remaining time
  const buffLine = warrior.activeBuff ? (() => {
    const secsLeft = warrior.buffSecondsLeft ?? 0;
    const timeStr  = secsLeft > 3600
      ? `${Math.floor(secsLeft / 3600)}h ${Math.floor((secsLeft % 3600) / 60)}m`
      : `${Math.floor(secsLeft / 60)}m`;
    return `<div style="margin-top:.3rem;font-size:.75rem;padding:3px 6px;
                         background:#2a2510;border:1px solid #ffd700;border-radius:4px;
                         color:#ffd700;display:inline-block">
              ${warrior.activeBuff} <span style="color:#aaa;font-size:.7em">(${timeStr})</span>
            </div>`;
  })() : '';

  // Buff de refeição (slot Bem Alimentado). [COZINHA]
  const mealBuffLine = warrior.mealBuff ? (() => {
    const secsLeft = warrior.mealBuffSecondsLeft ?? 0;
    const timeStr  = secsLeft > 3600
      ? `${Math.floor(secsLeft / 3600)}h ${Math.floor((secsLeft % 3600) / 60)}m`
      : `${Math.floor(secsLeft / 60)}m`;
    return `<div style="margin-top:.3rem;font-size:.75rem;padding:3px 6px;
                         background:#1f1226;border:1px solid #ba68c8;border-radius:4px;
                         color:#ce93d8;display:inline-block">
              🍽 ${warrior.mealBuff} <span style="color:#aaa;font-size:.7em">(${timeStr})</span>
            </div>`;
  })() : '';

  const hpColor      = (warrior.hpPercent ?? 100) <= 0 ? '#cf6679'
                     : (warrior.hpPercent ?? 100) < 50  ? '#c9a84c' : '#4caf82';
  const staminaColor = stamina < 30 ? '#cf6679' : stamina < 60 ? '#c9a84c' : '#4caf82';

  document.getElementById('warrior-card').innerHTML = `
    <div class="warrior-name">${escapeHtml(warrior.name)}</div>
    <div class="warrior-class">${warrior.warriorClass}</div>
    <div class="warrior-stat-row"><span class="label">${t('stat.level')}</span><span class="value">${warrior.level}</span></div>
    <div class="xp-bar-wrap">
      <div class="xp-bar-bg"><div class="xp-bar-fill" style="width:${xpPct}%"></div></div>
      <div class="xp-label">${t('stat.exp')} ${warrior.experience} / ${warrior.expNeeded}</div>
    </div>

    ${statRow(t('stat.attack'),  warrior.baseAttack,  warrior.itemBonusAttack  ?? 0, warrior.buffBonusAttack  ?? 0)}
    ${statRow(t('stat.defense'), warrior.baseDefense, warrior.itemBonusDefense ?? 0, warrior.buffBonusDefense ?? 0)}
    ${statRow(t('stat.hp'),      warrior.baseHealth,  warrior.itemBonusHealth  ?? 0, warrior.buffBonusHealth  ?? 0)}
    <div class="warrior-stat-row">
      <span class="label">Armor Class (AC)</span>
      <span class="value">${warrior.evasionChance ?? 10}
        <span style="color:#888;font-size:.75em">(d20 hit needs ≥ AC)</span>
      </span>
    </div>
    <div class="warrior-stat-row">
      <span class="label">${t('stat.luck')}</span>
      <span class="value">${warrior.luck ?? 0}
        <span style="color:#888;font-size:.75em">(${t('stat.drop_hint', {n: warrior.luck ?? 0})})</span>
      </span>
    </div>
    <div class="warrior-stat-row">
      <span class="label">${t('stat.stamina')}</span>
      <span class="value ${stamina < 30 ? 'stamina-low' : ''}">${stamina}/100${staminaInfo}</span>
    </div>

    ${buffLine}
    ${mealBuffLine}

    <div style="margin-top:.4rem">
      ${warrior.isKnockedOut
        ? `<span class="status-badge status-busy">💀 ${t('status.knocked_out')}</span>`
        : `<span class="status-badge ${busy ? 'status-busy' : 'status-available'}">
             ${busy ? `⚔ ${t('status.busy')}` : `✓ ${t('status.available')}`}
           </span>`}
    </div>
    <div class="xp-bar-bg" style="margin-top:.3rem">
      <div class="xp-bar-fill" style="width:${warrior.hpPercent ?? 100}%;background:${hpColor}"></div>
    </div>
    <div style="font-size:.7rem;color:#888;margin-top:.1rem">
      ❤ ${t('stat.hp')} ${warrior.hpPercent ?? 100}%
    </div>
    <div class="xp-bar-bg" style="margin-top:.3rem">
      <div class="xp-bar-fill" style="width:${stamina}%;background:${staminaColor}"></div>
    </div>
    <div style="font-size:.7rem;color:#888;margin-top:.1rem">
      ⚡ ${t('stat.stamina')} ${stamina}/100
    </div>
    ${warrior.isVip ? `<div style="font-size:.72rem;background:#3b0764;color:#c4b5fd;padding:2px 6px;border-radius:4px;margin-top:.3rem;display:inline-block">
      👑 VIP${warrior.vipExpiresAt ? ' · ' + warrior.vipExpiresAt.substring(0,10) : ''}
    </div>` : ''}
    ${(warrior.soulStones ?? 0) > 0 ? `<div style="font-size:.72rem;color:#a78bfa;margin-top:.2rem;font-weight:600">
      💎 ${warrior.soulStones} SoulStone${warrior.soulStones !== 1 ? 's' : ''}
    </div>` : ''}
    ${busy ? `<button class="btn-cancel-work" onclick="freeWarrior()" style="margin-top:.4rem;font-size:.72rem">
      🔓 ${t('status.free_btn')}
    </button>` : ''}`;
}

// ── Navegação de locais ──
function goTo(loc) {
  ['inventory','commerce','temple','work','tower','arena','guild','world','mail'].forEach(l => {
    document.getElementById('loc-panel-' + l).style.display = l === loc ? 'block' : 'none';
    document.getElementById('loc-' + l).classList.toggle('active', l === loc);
  });
  if (loc === 'temple')   { loadTemple(); }
  if (loc === 'tower')    { loadTower(); }
  if (loc === 'arena')    { loadRank(); loadCurrentFight(); }
  if (loc === 'commerce') { loadShop(); }
  if (loc === 'inventory'){ renderAttributes(); loadInventory(); }
  if (loc === 'work')     { loadWork(); }
  if (loc === 'guild')     { loadGuild(); }
  if (loc === 'world')      { loadWorld(); }
  if (loc === 'mail')      { loadMail(); }
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
  if (!questTypes.length) { el.innerHTML = t('misc.loading'); return; }
  const stamina = warrior?.stamina ?? 100;
  el.innerHTML = questTypes.map(q => {
    const noStamina = stamina < q.staminaCost;
    const disabled  = busy || noStamina;
    const btnLabel  = busy ? t('status.busy') : noStamina ? `⚡ ${stamina}/${q.staminaCost}` : t('quest.btn.send');
    return `
    <div class="quest-card">
      <h3>${t('quest.type.'+q.id)||q.displayName}</h3>
      <div class="quest-rewards">
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
  if (!quests.length) { el.innerHTML = `<p style="color:#888;font-size:.82rem">${t('quest.none_active')}</p>`; return; }

  el.innerHTML = quests.map(q => `
    <div class="quest-card" id="quest-card-${q.id}">
      <div class="quest-card-top">
        <h3>${t('quest.type.'+q.questType)||q.questType}</h3>
        <span class="timer ${q.secondsRemaining <= 0 ? 'done' : ''}" id="timer-${q.id}">
          ${q.secondsRemaining <= 0 ? t('quest.ready_short') : formatTime(q.secondsRemaining)}
        </span>
      </div>
      <div class="quest-rewards">
        <span>${fmtBronze(q.goldReward)}</span>
        <span>⭐ ${q.expReward} exp</span>
      </div>
      <button class="btn-collect" id="btn-collect-${q.id}" ${q.secondsRemaining > 0 ? 'disabled' : ''} onclick="collectReward(${q.id})">
        ${q.secondsRemaining > 0 ? t('quest.waiting') : t('quest.btn.collect_icon')}
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
        te.textContent = t('quest.ready_short'); te.classList.add('done');
        be.disabled = false; be.textContent = t('quest.btn.collect_icon');
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
  if (!confirm(t('quest.confirm_abandon'))) return;
  const data = await api('POST', `/api/quests/${questId}/abandon`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(t('quest.abandoned'));
  closeQuestProgress();
  await loadWarrior();
}

function renderQuestProgress(quest) {
  const done = quest.secondsRemaining <= 0;
  document.getElementById('qp-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">${t('quest.type.'+quest.questType)||quest.questType}</div>
      <div class="qp-rewards-preview">
        ${fmtBronze(quest.goldReward)} &nbsp;&nbsp; ⭐ ${quest.expReward} exp
      </div>
      <div class="qp-timer ${done ? 'done' : ''}" id="qp-timer">
        ${done ? t('quest.complete') : formatTime(quest.secondsRemaining)}
      </div>
      <button class="btn-collect qp-collect-btn" id="qp-btn"
              ${done ? '' : 'disabled'}
              onclick="collectFromProgress(${quest.id})">
        ${done ? t('quest.btn.collect_icon') : t('quest.waiting')}
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
        t.textContent = t('quest.complete');
        t.classList.add('done');
        b.disabled = false;
        b.textContent = t('quest.btn.collect_icon');
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
        <span class="drop-stats">${(t('item.type.'+d.type)||d.typeDisplay)} · ${stats}</span>
      </div>`;
  }

  document.getElementById('qp-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">${t('quest.complete_title')}</div>
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
  document.getElementById('panel-shop').style.display      = tab === 'shop'      ? 'block' : 'none';
  document.getElementById('panel-sell').style.display      = tab === 'sell'      ? 'block' : 'none';
  document.getElementById('panel-smith').style.display     = tab === 'smith'     ? 'block' : 'none';
  document.getElementById('panel-cooking').style.display   = tab === 'cooking'   ? 'block' : 'none';
  document.getElementById('panel-vipshop').style.display   = tab === 'vipshop'   ? 'block' : 'none';
  document.getElementById('tab-shop').classList.toggle('active',      tab === 'shop');
  document.getElementById('tab-sell').classList.toggle('active',      tab === 'sell');
  document.getElementById('tab-smith').classList.toggle('active',     tab === 'smith');
  document.getElementById('tab-cooking').classList.toggle('active',   tab === 'cooking');
  document.getElementById('tab-vipshop').classList.toggle('active',   tab === 'vipshop');
  if (tab === 'sell')      loadSellList();
  if (tab === 'smith')     loadSmithingInCommerce();
  if (tab === 'cooking')   loadCooking();
  if (tab === 'vipshop')   loadVipShop();
}

// ── Cozinha (Sistema de Cozinha): peixe → refeição → buff de combate ──
async function loadCooking() {
  const el = document.getElementById('cooking-content');
  el.innerHTML = '<p>Carregando cozinha...</p>';
  try {
    const [recipes, meals] = await Promise.all([
      api('GET', '/api/cooking/recipes'),
      api('GET', '/api/cooking/meals')
    ]);
    const mealCount = {};
    (meals || []).forEach(m => { mealCount[m.id] = m.quantity; });

    const recipeCards = (recipes || []).map(r => {
      const owned = `${r.fishOwned}/${r.ingredientQty}`;
      const have  = mealCount[r.id] || 0;
      return `
        <div style="background:#1a1a2e;border:1px solid ${r.canCook ? '#4caf50' : '#333'};border-radius:8px;padding:12px;margin-bottom:8px">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <strong>${r.icon} ${r.displayName}</strong>
            <span style="font-size:12px;color:#9c27b0">${r.effect} · ${r.durationMinutes}min</span>
          </div>
          <div style="font-size:12px;color:#888;margin:4px 0 8px">Ingrediente: ${r.ingredient} ×${r.ingredientQty} (tem ${owned})${have ? ` · in stock: ${have}` : ''}</div>
          <div style="display:flex;gap:6px">
            <button onclick="cookMeal('${r.id}')" ${r.canCook ? '' : 'disabled style="opacity:.5"'} style="font-size:12px">🍳 Cozinhar</button>
            ${have ? `<button onclick="eatMeal('${r.id}')" style="font-size:12px;background:#7b1fa2">🍽 Eat (${have})</button>` : ''}
          </div>
        </div>`;
    }).join('');

    el.innerHTML = `
      <p style="font-size:13px;color:#aaa;margin:0 0 12px">Turn fish into meals that grant a combat buff (<strong>Well Fed</strong> slot, stacks with Temple buffs).</p>
      ${recipeCards}
      <div id="cooking-msg" style="margin-top:8px;min-height:20px"></div>`;
  } catch (e) {
    el.innerHTML = '<p style="color:red">Erro ao carregar a cozinha: ' + e.message + '</p>';
  }
}

async function cookMeal(meal) {
  const r = await api('POST', '/api/cooking/cook', { meal });
  cookingMsg(r.error ? r.error : r.message, !r.error);
  if (!r.error) { await loadResources(); await loadCooking(); }
}

async function eatMeal(meal) {
  const r = await api('POST', '/api/cooking/eat', { meal });
  if (r.error) { cookingMsg(r.error, false); return; }
  await loadWarrior();
  cookingMsg(r.message, true);
  await loadCooking();
}

function cookingMsg(text, ok = true) {
  const el = document.getElementById('cooking-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

// Loads smithing content into the Commerce tab smithing panel
async function loadSmithingInCommerce() {
  const el = document.getElementById('smith-content');
  el.innerHTML = '<p>Loading smithing...</p>';
  // Ensure resources and skills are loaded
  if (!resourcesData.length || !skillsData.length) {
    [skillsData, resourcesData] = await Promise.all([
      api('GET', '/api/gathering/skills'),
      api('GET', '/api/gathering/resources')
    ]);
  }
  // Render smithing into sk-smith-content (hidden), then copy HTML
  await renderSmithing();
  const src = document.getElementById('sk-smith-content');
  if (src) el.innerHTML = src.innerHTML;
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
    if (el) el.textContent = `🛒 ${t('shop.next_rotation')} ${timeStr}`;
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
              <h3 class="rarity-${i.rarity}">${i.name} <span style="font-size:.7rem;color:#888">Lv.${i.itemLevel}</span></h3>
              <div class="shop-stats">${(t('item.type.'+i.type)||i.typeDisplay)} · ${(t('inventory.rarity.'+i.rarity)||i.rarityName)} · ${stats}${i.itemLevel > (warrior?.level||1) ? ` · <span style="color:#ef5350">🔒 Req. Lv.${i.itemLevel}</span>` : ''}</div>
            </div>
            <span class="shop-price">${fmtBronze(i.price)}</span>
            ${i.purchased
              ? `<button class="btn-bought" disabled>✓ ${t('shop.purchased')}</button>`
              : `<button class="btn-buy" onclick="buyItem(${i.id})">${t('shop.btn.buy')}</button>`
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
      if (el) el.textContent = '🛒 The cart has arrived! New items available!';
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
  { id:'HELMET'  }, { id:'ARMOR'    },
  { id:'WEAPON'  }, { id:'SHIELD'   },
  { id:'PANTS'   }, { id:'BOOTS'    },
  { id:'GLOVES'  }, { id:'SHOULDER' },
  { id:'NECKLACE'}, { id:'RING'     },
];

const ATTR_INFO = {
  STRENGTH:     { icon: '⚔',  label: 'Strength (STR)',     cap: 60, effect: '+1 ATK/pt · Attack Roll floor(STR/20)' },
  DEXTERITY:    { icon: '🛡',  label: 'Dexterity (DEX)',    cap: 40, effect: '+1 AC/pt · AC = 10 + DEX (defends hits)' },
  CONSTITUTION: { icon: '❤',  label: 'Constitution (CON)', cap: null, effect: '+8 HP/pt · no cap — grows infinitely' },
  LUCK:         { icon: '🍀', label: 'Luck (LUK)',         cap: 50, effect: '+1% drop · widens crit · Fortune Save' },
  INTELLECT:    { icon: '📚', label: 'Intellect (INT)',    cap: 40, effect: '+0.5% Smithing · -0.2% training cost · +0.3% gathering yield' },
};

function renderAttributes() {
  if (!warrior) return;
  const pts = warrior.availablePoints ?? 0;
  const el = document.getElementById('attributes-panel');

  const rows = Object.entries(ATTR_INFO).map(([id, info]) => {
    const val = warrior[id.toLowerCase()] ?? 0;
    const atCap = info.cap !== null && val >= info.cap;
    const capLabel = info.cap === null ? '∞' : `/${info.cap}`;
    return `
      <div class="attr-row">
        <span class="attr-icon">${info.icon}</span>
        <div style="flex:1;min-width:0">
          <span class="attr-label">${info.label}</span>
          <span class="attr-effect" style="display:block;font-size:.75rem;color:#888">${info.effect}</span>
        </div>
        <span class="attr-val" style="color:${atCap ? '#ffd700' : '#eee'}">${val}${capLabel}</span>
        <button class="btn-attr" ${pts <= 0 || atCap ? 'disabled' : ''} onclick="spendPoint('${id}')">+</button>
      </div>`;
  }).join('');

  const ac    = warrior.evasionChance ?? 10;
  const bonus = warrior.attackBonus   ?? 0;

  el.innerHTML = `
    <div class="attr-section">
      <div class="attr-header">
        <span>${t('char.attributes')} <span style="font-size:.75rem;color:#888;font-weight:normal">(d20 system)</span></span>
        ${pts > 0 ? `<span class="attr-points-badge">⬆ ${pts} point${pts !== 1 ? 's' : ''} available</span>` : ''}
      </div>
      ${rows}
      <div class="attr-stats-summary" style="margin-top:8px;padding:8px;background:#1a1a2e;border-radius:6px;font-size:.8rem">
        🛡 <strong>AC ${ac}</strong> (attackers need d20 ≥ ${ac} to hit you)
        &nbsp;·&nbsp;
        ⚔ <strong>Attack +${bonus}</strong> (added to your d20 attack rolls)
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

async function expandInventory() {
  const r = await api('POST', '/api/inventory/expand');
  if (r.error) { showMessage(r.error, true); return; }
  showMessage(r.message);
  await loadWarrior();
  loadInventory();
}

async function loadInventory() {
  const [items, slots, resources] = await Promise.all([
    api('GET', '/api/inventory'),
    api('GET', '/api/inventory/slots'),
    api('GET', '/api/gathering/resources')
  ]);
  if (!Array.isArray(items)) return;

  // Barra de slots da bag
  const slotEl = document.getElementById('bag-slot-info');
  if (slotEl && slots && !slots.error) {
    const pct   = Math.min(100, Math.round(slots.bagSize / slots.maxSlots * 100));
    const color = pct >= 90 ? '#ef5350' : pct >= 70 ? '#ffc107' : '#4caf50';
    const expandBtn = !slots.inventoryExpanded && (slots.soulStones ?? 0) >= 3
      ? `<button onclick="expandInventory()" style="font-size:11px;padding:3px 8px;background:#5b21b6;margin-left:8px">💎 Expand (3 SS)</button>`
      : slots.inventoryExpanded
      ? '<span style="font-size:11px;color:#a78bfa;margin-left:8px">💎 VIP — 50 slots</span>'
      : '';
    slotEl.innerHTML = `
      <div style="display:flex;align-items:center;gap:6px;margin-bottom:8px;flex-wrap:wrap">
        <span style="font-size:12px;color:#888">Bag: ${slots.bagSize}/${slots.maxSlots}</span>
        <div style="flex:1;min-width:80px;height:6px;background:#333;border-radius:3px">
          <div style="width:${pct}%;height:100%;background:${color};border-radius:3px"></div>
        </div>
        <button onclick="openStash()" style="font-size:11px;padding:3px 8px;background:#00695c">🏛 Stash</button>
        ${expandBtn}
      </div>`;
  }

  const equipped = {};
  const bag = [];
  items.forEach(i => { if (i.equipped) equipped[i.type] = i; else bag.push(i); });

  document.getElementById('equipment-slots').innerHTML = `
    <div class="equipment-grid">
      ${ALL_SLOTS.map(slot => {
        const item = equipped[slot.id];
        if (item) return `
          <div class="equip-slot filled">
            <div class="slot-label">${t('inventory.slot.'+slot.id)}</div>
            <div class="slot-item-name rarity-${item.rarity}">${item.name}</div>
            <div class="slot-item-stats">${statsText(item)}</div>
            ${affixLines(item)}
            ${durabilityBar(item)}
            <button class="btn-unequip" onclick="unequipItem(${item.id})">${t('inventory.btn.unequip')}</button>
          </div>`;
        return `
          <div class="equip-slot empty">
            <div class="slot-label">${t('inventory.slot.'+slot.id)}</div>
            <div class="slot-empty-text">— vazio —</div>
          </div>`;
      }).join('')}
    </div>`;

  const bagEl = document.getElementById('bag-items');
  const resList = Array.isArray(resources) ? resources.filter(r => r.quantity > 0) : [];
  if (!bag.length && !resList.length) { bagEl.innerHTML = `<p style="color:#555;font-size:.8rem">${t('inventory.bag_empty')}</p>`; return; }
  const itemsHtml = bag.map(item => `
    <div class="bag-item" style="flex-direction:column;align-items:flex-start;gap:.3rem">
      <div style="display:flex;justify-content:space-between;width:100%;align-items:center">
        <div>
          <div class="bag-item-name rarity-${item.rarity}">${item.name} <span style="font-size:.7rem;color:#888">Lv.${item.itemLevel}</span></div>
          <div class="bag-item-type">${(t('item.type.'+item.type)||item.typeDisplay)} · ${(t('inventory.rarity.'+item.rarity)||item.rarityName)}</div>
          <div class="bag-item-stats">${statsText(item)}</div>
          ${affixLines(item)}
          ${durabilityBar(item)}
          ${item.sockets > 0 ? renderSockets(item) : ''}
        </div>
        ${item.itemLevel > (warrior?.level || 1)
          ? `<button class="btn-equip" disabled style="opacity:.5;cursor:not-allowed" title="Requires level ${item.itemLevel}">🔒 Lv.${item.itemLevel}</button>`
          : `<button class="btn-equip" onclick="equipItem(${item.id})">${t('inventory.btn.equip')}</button>`}
      </div>
      ${item.description ? `<p class="item-lore">"${item.description}"</p>` : ''}
      ${item.origin ? `<p class="item-origin">📍 ${item.origin}</p>` : ''}
    </div>`).join('');
  // Inventário V2: recursos compartilham a bag (cada unidade = 1 slot).
  const resHtml = resList.length ? `
    <div style="margin-top:10px;border-top:1px solid #2a2a3a;padding-top:8px">
      <div style="font-size:11px;color:#888;margin-bottom:4px">📦 Resources (count toward bag slots)</div>
      ${resList.map(r => `
        <div class="sk-resource-row">
          <span>${RESOURCE_ICONS[r.type]||'📦'} ${r.displayName} ×${r.quantity}</span>
          ${r.category === 'FISH' ? `<button class="btn-equip" onclick="consumeFish('${r.type}')">${t('btn.consume')||'Consume'}</button>` : ''}
        </div>`).join('')}
    </div>` : '';
  bagEl.innerHTML = itemsHtml + resHtml;
}

async function loadSellList() {
  const items = await api('GET', '/api/inventory');
  if (!Array.isArray(items)) return;

  const bag = items.filter(i => !i.equipped);
  const el = document.getElementById('sell-list');

  if (!bag.length) {
    el.innerHTML = `<p style="color:#888;font-size:.82rem">${t('inventory.no_sell')}</p>`;
    return;
  }

  el.innerHTML = bag.map(item => `
    <div class="shop-card">
      <div class="shop-item-info">
        <h3 class="rarity-${item.rarity}">${item.name}</h3>
        <div class="shop-stats">${(t('item.type.'+item.type)||item.typeDisplay)} · ${statsText(item)}</div>
      </div>
      <span class="shop-price">${fmtBronze(item.sellPrice)}</span>
      <button class="btn-buy" onclick="sellItem(${item.id})">${t('inventory.btn.sell')}</button>
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
        slots += `<span class="socket-slot empty" title="Empty socket (no gems)">◯</span>`;
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

// Itens V2: linhas de afixo do item (prefixo/sufixo + bônus). Vazio se não tiver.
function affixLines(item) {
  const a = item.affixes || [];
  if (!a.length) return '';
  return `<div class="item-affixes">${a.map(x =>
    `<span class="affix-line">✦ ${x.word} <b>+${x.magnitude} ${x.stat}</b></span>`
  ).join('')}</div>`;
}

// ── Stash (Inventário V2): 100 slots, taxa fixa por operação ──
async function openStash() {
  const [s, invItems, resources] = await Promise.all([
    api('GET', '/api/stash'),
    api('GET', '/api/inventory'),
    api('GET', '/api/gathering/resources')
  ]);
  if (s.error) { showMessage(s.error, true); return; }
  const bagItems = (Array.isArray(invItems) ? invItems : []).filter(i => !i.equipped);
  const bagRes   = (Array.isArray(resources) ? resources : []).filter(r => r.quantity > 0);

  const itemRow = (i, fn, label) => `<div class="sk-resource-row">
      <span class="rarity-${i.rarity}">${i.name} <span style="color:#888;font-size:.72rem">Lv.${i.itemLevel} (${statsText(i)})</span></span>
      <button class="btn-equip" onclick="${fn}(${i.id})">${label}</button></div>`;
  const resRow = (r, fn, label) => `<div class="sk-resource-row">
      <span>${RESOURCE_ICONS[r.type]||'📦'} ${r.displayName} ×${r.quantity}</span>
      <button class="btn-equip" onclick="${fn}('${r.type}',${r.quantity})">${label}</button></div>`;

  const bagBody = bagItems.map(i => itemRow(i, 'stashDepositItem', '→ Stash')).join('')
                + bagRes.map(r => resRow(r, 'stashDepositResource', '→ Stash')).join('') || '<p style="color:#555;font-size:.8rem">empty</p>';
  const stBody  = (s.items||[]).map(i => itemRow(i, 'stashWithdrawItem', '→ Bag')).join('')
                + (s.resources||[]).map(r => resRow(r, 'stashWithdrawResource', '→ Bag')).join('') || '<p style="color:#555;font-size:.8rem">empty</p>';

  const col = (title, sub, body) => `<div style="flex:1;min-width:250px;background:#1a1a2e;border:1px solid #333;border-radius:8px;padding:12px">
      <div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px"><strong>${title}</strong><span style="font-size:11px;color:#888">${sub}</span></div>
      <div style="max-height:48vh;overflow:auto">${body}</div></div>`;

  let modal = document.getElementById('stash-modal');
  if (!modal) { modal = document.createElement('div'); modal.id = 'stash-modal'; document.body.appendChild(modal); }
  modal.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.7);display:flex;align-items:center;justify-content:center;z-index:1000;padding:16px';
  modal.innerHTML = `<div style="background:#12121e;border:1px solid #444;border-radius:10px;padding:16px;max-width:780px;width:100%">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">
        <h3 style="margin:0">🏛 Stash</h3>
        <button onclick="closeStash()" style="background:#333;padding:3px 10px">✕</button>
      </div>
      <p style="font-size:11px;color:#ffc107;margin:0 0 10px">Fee: ${s.fee} bronze per move (deposit/withdraw)</p>
      <div style="display:flex;gap:12px;flex-wrap:wrap">
        ${col('Bag', s.bagUsed + '/' + s.bagMax, bagBody)}
        ${col('Stash', s.used + '/' + s.max, stBody)}
      </div></div>`;
  modal.style.display = 'flex';
}
function closeStash() { const m = document.getElementById('stash-modal'); if (m) m.style.display = 'none'; }

async function stashDepositItem(id)  { _afterStash(await api('POST', `/api/stash/deposit/item/${id}`)); }
async function stashWithdrawItem(id) { _afterStash(await api('POST', `/api/stash/withdraw/item/${id}`)); }
async function stashDepositResource(type, max)  { const q = _askQty(max); if (q) _afterStash(await api('POST', `/api/stash/deposit/resource/${type}`,  { quantity: q })); }
async function stashWithdrawResource(type, max) { const q = _askQty(max); if (q) _afterStash(await api('POST', `/api/stash/withdraw/resource/${type}`, { quantity: q })); }
function _askQty(max) { const v = prompt('Quantity to move:', max); const q = parseInt(v); return (q > 0) ? Math.min(q, max) : 0; }
async function _afterStash(r) {
  if (r && r.error) { showMessage(r.error, true); return; }
  await loadWarrior();
  await openStash(); // re-render com os números atualizados
}

// Barra de durabilidade do item (verde→amarelo→vermelho). Quebrado (0) = aviso.
function durabilityBar(item) {
  const dur = item.durability ?? 100;
  const color = dur === 0 ? '#c0392b' : dur <= 25 ? '#e67e22' : dur <= 60 ? '#d4b106' : '#4caf82';
  const label = dur === 0 ? '⚠ BROKEN — no bonus' : `Durabilidade ${dur}%`;
  return `
    <div class="item-durability" title="${label}" style="margin-top:.25rem">
      <div style="display:flex;justify-content:space-between;font-size:.65rem;color:${dur===0?'#c0392b':'#888'}">
        <span>${dur===0 ? '⚠ Broken' : 'Durabilidade'}</span><span>${dur}%</span>
      </div>
      <div style="height:5px;background:#222;border-radius:3px;overflow:hidden">
        <div style="height:100%;width:${dur}%;background:${color}"></div>
      </div>
    </div>`;
}

async function equipItem(itemId) {
  const data = await api('POST', `/api/inventory/${itemId}/equip`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.name} ${t('inventory.item_equipped')}`);
  await Promise.all([loadWarrior(), loadInventory()]);
}
async function unequipItem(itemId) {
  const data = await api('POST', `/api/inventory/${itemId}/unequip`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.name} ${t('inventory.item_unequipped')}`);
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
  const hpLabel   = data.isKnockedOut ? '💀 Unconscious' : `❤ ${data.hpPercent}%`;
  const healLabel = data.healFree ? t('temple.heal_free_btn') : `${t('temple.heal_paid', {cost: fmtBronze(data.healCost ?? 100)})}`;

  const buffActive = data.activeBuff
    ? `<div class="temple-buff-active">
        Bênção slot 1: <strong>${data.activeBuff}</strong>
        — ${Math.floor(data.buffSecondsLeft / 60)}min restantes
       </div>`
    : `<div class="temple-buff-active" style="color:#888">${t('temple.no_buff')}</div>`;

  const buff2Active = data.activeBuff2
    ? `<div class="temple-buff-active" style="color:#c4b5fd">
        👑 Bênção slot 2 (VIP): <strong>${data.activeBuff2}</strong>
        — ${Math.floor(data.buff2SecondsLeft / 60)}min restantes
       </div>`
    : data.isVip
    ? `<div class="temple-buff-active" style="color:#7c3aed">👑 VIP slot 2 available</div>`
    : '';

  const buffsHtml = data.buffs.map(b => `
    <div class="sk-recipe-card">
      <div class="sk-recipe-title">${b.icon} ${t('temple.buff.'+b.id)||b.displayName} — <span style="color:#888">${b.effect}</span></div>
      <div style="font-size:.75rem;color:#888;margin-bottom:.4rem">${fmtBronze(b.bronzeCost)}</div>
      <button class="btn-equip" onclick="applyBuff('${b.id}')">${t('temple.bless_btn')}</button>
    </div>`).join('');

  el.innerHTML = `
    <div class="sk-section">
      <div class="sk-title">${t('temple.warrior_state')}</div>
      <div class="temple-hp-bar">
        <span style="color:${hpColor};font-weight:bold">${hpLabel}</span>
        <div class="xp-bar-bg" style="margin-top:.3rem">
          <div class="xp-bar-fill" style="width:${data.hpPercent}%;background:${hpColor}"></div>
        </div>
        <div style="font-size:.72rem;color:#888;margin-top:.2rem">
          ${data.isKnockedOut
            ? t('temple.ko_warning')
            : data.hpPercent < 100
            ? 'Regenerando HP... o templo pode curar instantaneamente.'
            : 'HP cheio!'}
        </div>
      </div>
      <button class="btn-collect" onclick="healWarrior()"
              style="margin-top:.6rem"
              ${data.hpPercent >= 100 ? 'disabled' : ''}>
        ${data.hpPercent >= 100 ? '✓ Full HP' : healLabel}
      </button>
      ${data.isVip ? (() => {
        const cdSecs = data.vipHealCooldownSecs || 0;
        const disabled = data.hpPercent >= 100 || cdSecs > 0;
        const label = data.hpPercent >= 100
          ? '✓ Full HP'
          : cdSecs > 0
          ? `⏳ VIP Heal CD ${Math.floor(cdSecs/60)}m ${cdSecs%60}s`
          : '👑 VIP Heal (free)';
        return `<button class="btn-collect" onclick="vipHeal()"
                  style="margin-top:.4rem;background:#7c3aed"
                  ${disabled ? 'disabled' : ''}>${label}</button>
                <div style="font-size:.7rem;color:#c4b5fd;margin-top:.2rem">👑 VIP · CD 10 min · grátis</div>`;
      })() : ''}
      ${(() => {
        if (!data.soulStones) return '';
        const cdSecs = data.ssHealCooldownSecs || 0;
        const disabled = data.hpPercent >= 100 || cdSecs > 0;
        const label = data.hpPercent >= 100
          ? '✓ Full HP'
          : cdSecs > 0
          ? `⏳ Cooldown ${Math.floor(cdSecs/60)}m ${cdSecs%60}s`
          : '💎 Instant Heal (1 SoulStone)';
        return `<button class="btn-collect" onclick="soulstoneHeal()"
                  style="margin-top:.4rem;background:#5b21b6"
                  ${disabled ? 'disabled' : ''}>${label}</button>
                <div style="font-size:.7rem;color:#a78bfa;margin-top:.2rem">
                  💎 ${data.soulStones} SoulStone${data.soulStones!==1?'s':''} · CD 30 min
                </div>`;
      })()}
    </div>

    <div class="sk-section">
      <div class="sk-title">${t('temple.buffs')}</div>
      ${buffActive}
      ${buff2Active}
      <div style="margin-top:.5rem">${buffsHtml}</div>
    </div>

    <div class="sk-section">
      <div class="sk-title">Proteção de Itens (${data.protectedCount}/${data.maxProtected})</div>
      <p class="zone-desc">Protected items are not lost in PvP combat. Cost: ${fmtBronze(50)}/item.</p>
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
    el.innerHTML = `<p style="color:#888;font-size:.8rem">${t('temple.no_items')}</p>`;
    return;
  }

  el.innerHTML = equipped.map(i => `
    <div class="sk-resource-row">
      <span class="rarity-${i.rarity}">${i.name}</span>
      ${i.guarded
        ? `<button class="btn-unequip" onclick="unprotectItem(${i.id})">${t('btn.remove')||'Remove'}</button>`
        : `<button class="btn-equip"   onclick="protectItem(${i.id})">${t('temple.protect_btn')}</button>`}
    </div>`).join('');
}

async function healWarrior() {
  const data = await api('POST', '/api/temple/heal');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadTemple();
}

async function soulstoneHeal() {
  const data = await api('POST', '/api/temple/soulstone-heal');
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

// Banner de status PvP: exposto (alvo de raid) ou protegido (escudo pós-derrota). [PVP_FLAG]
function pvpStatusBanner(pvp) {
  if (!pvp) return '';
  if (pvp.shielded) {
    return `<div class="zone-pvp-status" style="border-color:#4caf50;background:#1b3a1b">
      🛡 ${t('zones.shielded')||'Protegido'} — ${pvp.shieldMinutesLeft} min. ${t('zones.shielded_desc')||'Imune a raids enquanto durar.'}
    </div>`;
  }
  if (pvp.flagged) {
    return `<div class="zone-pvp-status" style="border-color:#c0392b;background:#3a1b1b">
      ⚠ ${t('zones.exposed')||'Exposto'} (${escapeHtml(pvp.flaggedZone)}) — ${pvp.flagMinutesLeft} min.
      ${t('zones.exposed_desc')||'Bolsa + equipados (não-guardados) podem ser saqueados. Guarde no Stash/Templo.'}
    </div>`;
  }
  return '';
}

// ── HABILIDADES (Pesca / Mineração / Forja) ──

let skillsData     = [];
let resourcesData  = [];
let gatheringState = null;
let gatheringTimer = null;

const FISH_DESCRIPTIONS = {
  SMALL_FISH:     '+10 stamina',
  SALMON:         '+25 stamina',
  TUNA:           '+40 stamina',
  SHARK:          '+60 stamina',
  LEGENDARY_FISH: '+80 stamina + temporary XP buff',
};

const RESOURCE_ICONS = {
  SMALL_FISH:'🐟', SALMON:'🐠', TUNA:'🐡', SHARK:'🦈', LEGENDARY_FISH:'🐉',
  COPPER_ORE:'🟤', IRON_ORE:'⬛', SILVER_ORE:'⬜', GOLD_ORE:'🟡', MITHRIL_ORE:'🔷',
  RUBY_FRAGMENT:'💠', SAPPHIRE_FRAGMENT:'🔹', EMERALD_FRAGMENT:'💚', DIAMOND_FRAGMENT:'🔶', AMETHYST_FRAGMENT:'🟣',
  COPPER_BAR:'🟫', IRON_BAR:'⚫', SILVER_BAR:'🪨', GOLD_BAR:'🌟', MITHRIL_BAR:'💎',
  RUBY:'🔴', SAPPHIRE:'🔵', EMERALD:'💚', DIAMOND:'💎', AMETHYST:'🟣',
  LEATHER:'🟫',
};

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

// ── FORJA ──
function repairCostFor(item) { return (100 - (item.durability ?? 100)) * item.rarity * 5; }
function reforgeCostFor(item) { return item.rarity * item.rarity * item.rarity * 500; }

async function renderSmithing() {
  const smithSkill = getSkill('SMITHING');
  const recipes = await api('GET', '/api/smithing/recipes');
  const bars = resourcesData.filter(r => r.category === 'BAR');
  const frags = resourcesData.filter(r => r.category === 'FRAGMENT');

  // Itens do jogador (equipados + mochila) para reparo/reforja
  const inv = await api('GET', '/api/inventory');
  const equip = Array.isArray(inv) ? inv : [];
  const maintHtml = equip.map(item => {
    const dur = item.durability ?? 100;
    const repairCost = repairCostFor(item);
    const reforgeCost = reforgeCostFor(item);
    return `
      <div class="sk-recipe-card">
        <div class="sk-recipe-title rarity-${item.rarity}">${item.name} ${item.equipped ? '· ⚔ equipped' : ''}</div>
        <div style="font-size:.75rem;color:#aaa">${statsText(item)}</div>
        ${durabilityBar(item)}
        <div style="display:flex;gap:.4rem;margin-top:.4rem;flex-wrap:wrap">
          ${dur < 100
            ? `<button class="btn-equip" onclick="repairItem(${item.id})">🔧 Repair (${fmtBronze(repairCost)})</button>`
            : `<button class="btn-equip" disabled style="opacity:.5">🔧 Intact</button>`}
          <button class="btn-equip" onclick="reforgeItem(${item.id})">♻ Reforjar (${fmtBronze(reforgeCost)})</button>
        </div>
      </div>`;
  }).join('') || `<p style="color:#888;font-size:.8rem">No items to maintain.</p>`;

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
      ${refineHtml || `<p style="color:#888;font-size:.8rem">${t('skills.no_recipes')}</p>`}
    </div>
    <div class="sk-section">
      <div class="sk-title">Craftar Equipamento</div>
      ${craftHtml}
    </div>
    <div class="sk-section">
      <div class="sk-title">Criar Joias</div>
      ${gemHtml}
    </div>
    <div class="sk-section">
      <div class="sk-title">🔧 Manutenção (Reparar / Reforjar)</div>
      ${maintHtml}
    </div>`;
}

// ── INVENTÁRIO DE RECURSOS ──
function renderBag() {
  if (resourcesData.length === 0) {
    document.getElementById('sk-bag-content').innerHTML = `<p style="color:#888;font-size:.82rem">${t('skills.no_resources')}</p>`;
    return;
  }
  const categories = {FISH:t('skills.cat.FISH'), ORE:t('skills.cat.ORE'), FRAGMENT:t('skills.cat.GEM_FRAGMENT'), BAR:t('skills.cat.BAR'), GEM:t('skills.cat.GEM'), MATERIAL:t('skills.cat.MATERIAL')};
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
        ${cat === 'FISH' ? `<button class="btn-equip" onclick="consumeFish('${r.type}')">${t('btn.consume')||'Consume'}</button>` : ''}
      </div>`).join('');
    html += '</div>';
  }
  document.getElementById('sk-bag-content').innerHTML = html;
}

// ── AÇÕES ──
async function consumeFish(resourceType) {
  const data = await api('POST', `/api/gathering/consume/${resourceType}`);
  if (data.error) { showMessage(data.error, true); return; }
  const hpPart = data.newHpPercent != null ? ` · ❤ HP: ${data.newHpPercent}%` : '';
  showMessage(`${data.message} ⚡ Stamina: ${data.newStamina}/100${hpPart}`);
  resourcesData = await api('GET', '/api/gathering/resources');
  await loadWarrior();
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

async function repairItem(itemId) {
  const data = await api('POST', `/api/smithing/repair/${itemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  renderSmithing();
}

async function reforgeItem(itemId) {
  if (!confirm('Reforging re-rolls the item\'s stats (keeps the rarity). Continue?')) return;
  const data = await api('POST', `/api/smithing/reforge/${itemId}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(`${data.message} (+${data.attackBonus} ATK · +${data.defenseBonus} DEF · +${data.healthBonus} HP)`);
  await loadWarrior();
  renderSmithing();
}

async function freeWarrior() {
  if (!confirm(t('warrior.free_confirm'))) return;
  const data = await api('POST', '/api/warrior/free');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message || t('warrior.freed'));
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
        const locked    = !job.meetsLevelReq;  // locked = warrior level too low
        const busy      = warrior?.onMission && !locked;
        const disabled  = locked || busy;
        const xpPct     = Math.floor((job.profXp / job.profXpNeeded) * 100);

        return `
          <div class="work-job-card ${locked ? 'locked' : ''}">
            <div class="wj-header">
              <span class="wj-name">${_lang['work.job.'+job.id] || jot('temple.buff.'+b.id)||b.displayName}</span>
              <span class="wj-prof-level">Lv.${job.profLevel}${job.bonusPct > 0 ? ` <span class="wl-bonus">+${job.bonusPct}%</span>` : ''}</span>
            </div>
            <div class="xp-bar-bg" style="margin-bottom:.4rem"><div class="xp-bar-fill" style="width:${xpPct}%"></div></div>
            <p class="wj-desc">${job.description}</p>
            <div class="wj-stats">
              <span>${fmtBronze(job.goldPerHourWithBonus)}/h</span>
              <span>⭐ ${job.xpPerHour} xp/h</span>
              ${locked ? `<span class="wj-req">🔒 ${t('work.min_level', {n: job.minWorkLevel})}</span>` : ''}
            </div>
            ${!locked ? `
              <div class="wj-hours">
                <div class="hours-btns">
                  ${(() => {
                    const h = 2; // ação fixa instantânea — a estamina é o gate, sem timer
                    return `<button class="btn-hour" onclick="startWork('${job.id}', ${h})" ${disabled ? 'disabled' : ''}>
                      <span class="stamina-cost">⚡ ${h * 5}</span>
                      <span class="hour-gold">${fmtBronze(Math.round(job.goldPerHourWithBonus * h))}</span>
                    </button>`;
                  })()}
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
  await collectWork(data.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
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
        <span class="stamina-cost">⚡ ${session.hours * 5}</span>
      </div>
      <div class="qp-timer ${done ? 'done' : ''}" id="work-timer">
        ${done ? 'Done!' : formatTime(session.secondsRemaining)}
      </div>
      <button class="btn-collect qp-collect-btn" id="work-btn"
              ${done ? '' : 'disabled'}
              onclick="collectWork(${session.id})">
        ${done ? t('work.collect_money') : t('work.in_progress')}
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
        t.textContent = 'Done!';
        t.classList.add('done');
        b.disabled = false;
        b.textContent = t('work.collect_money');
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
  showCollectModal({ title: '⚒ Work Complete!', color: '#8d6e63', rows: [
    { icon:'🪙', label:'Bronze',       value:fmtBronze(data.goldEarned), color:'#cd7f32' },
    { icon:'⭐', label:data.jobName || 'XP', value:`+${data.xpEarned} XP`, color:'#ffd700' },
  ]});
  await loadWarrior();
  showWorkJobList(); // atualiza a lista (guerreiro livre de novo)
}

async function cancelWork(sessionId) {
  if (!confirm(t('work.cancel_confirm'))) return;

  const data = await api('POST', `/api/work/${sessionId}/cancel`);
  if (data.error) { showMessage(data.error, true); return; }

  clearInterval(workTimerInterval);

  const msg = data.goldEarned > 0
    ? `${t('work.cancelled_partial', {bronze: fmtBronze(data.goldEarned), xp: data.xpEarned})}`
    : t('work.cancelled_none');

  document.getElementById('work-progress-content').innerHTML = `
    <div class="qp-box">
      <div class="qp-quest-name">Trabalho Cancelado</div>
      <div class="qp-result-row">
        ${data.goldEarned > 0
          ? `<span class="cr-gold">${fmtBronze(data.goldEarned)}</span>
             <span class="cr-exp">+${data.xpEarned} xp trabalho</span>`
          : `<span style="color:#888">${t('work.no_hours')}</span>`}
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
    ? `<p style="color:#888;font-size:.82rem">${t('tower.no_entries')}</p>`
    : `<table class="rank-table">
        <thead><tr><th>#</th><th>Guerreiro</th><th>Andar</th></tr></thead>
        <tbody>
          ${ranking.map((r, i) => `
            <tr class="${r.warriorName === warrior?.name ? 'me' : ''}">
              <td class="rank-pos">${i + 1}</td>
              <td class="rank-name">${escapeHtml(r.warriorName)}</td>
              <td class="rank-pts">🏰 ${r.bestFloor}</td>
            </tr>`).join('')}
        </tbody>
      </table>`;

  document.getElementById('tower-ranking-panel').innerHTML = `
    <div class="tower-enter-box">
      <div class="tower-enter-title">${t('tower.enter_btn')}</div>
      <p style="color:#888;font-size:.82rem;margin:.4rem 0">
        Cost: <span class="stamina-cost">⚡ 25 stamina</span>
        &nbsp;·&nbsp; ${t('tower.your_stamina')} <strong>${stamina}/100</strong>
      </p>
      <p style="color:#888;font-size:.8rem;margin-bottom:.8rem">
        Fight floor by floor. If you lose, you are expelled. Go as far as you can!
      </p>
      <button class="btn-fight"
              ${busy || noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''}
              onclick="enterTower()">
        ${busy ? t('tower.warrior_busy') : noStamina ? t('tower.no_stamina') : t('tower.enter_btn')}
      </button>
    </div>
    <h3 style="color:#c9a84c;margin:1rem 0 .5rem;font-size:.85rem;text-transform:uppercase;letter-spacing:.05em">
      Best Floors Ranking
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
      ${state.highestFloor > 0 ? `<div class="tower-cleared">✓ Highest floor cleared: ${state.highestFloor}</div>` : ''}
      <div class="tower-boss-card">
        <div class="tower-boss-name">${state.bossName}</div>
        ${state.recommendedLevel ? `<div style="font-size:11px;color:#e6a23c;margin:2px 0">⚑ Recommended Lv.${state.recommendedLevel}+</div>` : ''}
        <div class="tower-boss-stats">
          <span>❤ ${state.bossHp} HP</span>
          <span>⚔ ${state.bossAtk} ATK</span>
          <span>🛡 ${state.bossDef} DEF</span>
          <span>🎯 AC ${state.bossAc}</span>
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

  const title   = result.won ? `🏆 Floor ${result.floor} Completed!` : `💀 Defeated on Floor ${result.floor}`;
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
  if (!confirm('Leave the tower? You keep the gains from floors already completed.')) return;
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
      <thead><tr><th>#</th><th>${t('tower.col.warrior')}</th><th>${t('tower.col.floor')}</th></tr></thead>
      <tbody>
        ${rank.map((r, i) => `
          <tr class="${r.warriorName === warrior?.name ? 'me' : ''}">
            <td class="rank-pos">${i + 1}</td>
            <td class="rank-name">${escapeHtml(r.warriorName)}</td>
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

function renderFightArea() {
  const el = document.getElementById('fight-area');
  const stamina = warrior?.stamina ?? 100;
  const noStamina = stamina < 25;
  el.innerHTML = `
      <div class="fight-box">
        <h3>Entrar em batalha</h3>
        <p style="color:#888;font-size:.83rem;margin-bottom:.5rem">
          Cost: <span class="stamina-cost">⚡ 25 stamina</span> &nbsp;·&nbsp; ${t('tower.your_stamina')} <strong>${stamina}/100</strong>
        </p>
        <p style="color:#888;font-size:.83rem;margin-bottom:.8rem">
          Duelo instantâneo. Vitória: +25 rank, ${fmtBronze(200)}.
        </p>
        <button class="btn-fight" ${noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''} onclick="startFight()">
          ${noStamina ? t('tower.no_stamina') : '⚔ Lutar'}
        </button>
      </div>`;
}

// [SEM_TIMER] Duelo instantâneo: o /fight já resolve e retorna o resultado completo.
async function startFight() {
  const data = await api('POST', '/api/arena/fight');
  if (data.error) { showMessage(data.error, true); return; }
  switchArenaTab('fight');

  const result = data.won
    ? `🏆 Vitória contra ${escapeHtml(data.opponent)}!`
    : `💀 Defeat to ${escapeHtml(data.opponent)}`;
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

// ── Init — load language FIRST, then check token ──
loadLanguage(_currentLang).finally(() => {
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
      // Language already loaded above, just enter game
      document.getElementById('login-screen').style.display = 'none';
      document.getElementById('game-screen').style.display = 'block';
      loadWarrior();
      loadQuestTypes();
      loadActiveQuests();
      setInterval(loadActiveQuests, 10000);
    });
  } else {
    document.getElementById('login-screen').style.display = 'flex';
    showLogin();
  }
});

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

  const treasuryFmt   = fmtBronze(g.treasuryBronze ?? 0);
  const costFmt       = fmtBronze(g.levelUpCost ?? 0);
  const canLevelUp    = (g.treasuryBronze ?? 0) >= (g.levelUpCost ?? Infinity);

  const levelUpBtn = g.isLeader
    ? `<button onclick="guildLevelUp()" ${canLevelUp ? '' : 'disabled title="Tesouro insuficiente"'}
         style="margin-top:8px">
         ⬆ Level Up (precisa ${costFmt})
       </button>`
    : '';

  const disbandBtn = g.isLeader
    ? `<button onclick="guildDisband()" style="background:#8b0000;margin-top:8px">💀 Dissolve Guild</button>`
    : `<button onclick="guildLeave()" style="background:#555;margin-top:8px">🚪 Leave Guild</button>`;

  const bonusLine = (g.xpBonus || g.dropBonus || g.bronzeBonus)
    ? `<div style="font-size:12px;color:#8bc34a;margin-top:4px">
         Bonuses: +${g.xpBonus}% XP · +${g.dropBonus}% drop · +${g.bronzeBonus}% bronze
       </div>`
    : '';

  const memberRows = g.members.map(m => {
    const badge       = m.isLeader ? ' 👑' : '';
    const kickBtn     = g.isLeader && !m.isMe && !m.isLeader
      ? `<button onclick="guildKick(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#8b0000">Kick</button>`
      : '';
    const transferBtn = g.isLeader && !m.isMe
      ? `<button onclick="guildTransfer(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#555">Transfer</button>`
      : '';
    return `<tr>
      <td>${escapeHtml(m.warriorName)}${badge}${m.isMe ? ' <em>(you)</em>' : ''}</td>
      <td style="text-align:right">${kickBtn} ${transferBtn}</td>
    </tr>`;
  }).join('');

  el.innerHTML = `
    <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px;margin-bottom:12px">
      <h3 style="margin:0 0 4px">${escapeHtml(g.name)} <span style="font-size:12px;color:#aaa">Lv.${g.level}</span></h3>
      <p style="color:#aaa;margin:0 0 8px;font-size:13px">${escapeHtml(g.description || 'No description.')}</p>
      <div style="display:flex;gap:24px;font-size:13px;flex-wrap:wrap">
        <span>🏦 Treasury: <strong>${treasuryFmt}</strong></span>
        <span>👥 Members: <strong>${g.members.length}/${g.maxMembers}</strong></span>
      </div>
      ${bonusLine}
      ${levelUpBtn}
    </div>

    <h4 style="margin:0 0 8px">Members</h4>
    <table style="width:100%;border-collapse:collapse;font-size:13px">
      ${memberRows}
    </table>

    <div style="margin-top:16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <input id="donate-amount" type="number" min="1" placeholder="Amount in bronze"
        style="width:160px;padding:6px;background:#111;color:#eee;border:1px solid #555;border-radius:4px">
      <button onclick="guildDonate()">💰 Donate</button>
      ${disbandBtn}
    </div>
    <div id="guild-msg" style="margin-top:8px;min-height:20px"></div>

    ${renderDonationRank(g.donationRank ?? [], player => player.isMe)}
  `;
}

function renderDonationRank(rank) {
  if (!rank || rank.length === 0) {
    return `
      <div style="margin-top:20px">
        <h4 style="margin:0 0 8px">🏆 Top Donors</h4>
        <p style="color:#aaa;font-size:13px">No donations yet.</p>
      </div>`;
  }
  const rows = rank.map((r, i) => {
    const medal  = i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : `${i+1}.`;
    const style  = r.isMe ? 'color:#ffd700;font-weight:bold' : '';
    return `<tr style="${style}">
      <td style="padding:4px 0">${medal} ${escapeHtml(r.warriorName)}${r.isMe ? ' (you)' : ''}</td>
      <td style="text-align:right;padding:4px 0">${fmtBronze(r.donatedBronze)}</td>
    </tr>`;
  }).join('');
  return `
    <div style="margin-top:20px">
      <h4 style="margin:0 0 8px">🏆 Top Donors</h4>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        ${rows}
      </table>
    </div>`;
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
            <strong>${escapeHtml(g.name)}</strong> <span style="font-size:11px;color:#aaa">Nv.${g.level}</span><br>
            <span style="font-size:12px;color:#888">${escapeHtml(g.description || '')}</span><br>
            <span style="font-size:12px">👥 ${g.members}/${g.maxMembers}</span>
          </div>
          <button onclick="guildJoin(${g.id})" ${g.members >= g.maxMembers ? 'disabled' : ''}>
            ${g.members >= g.maxMembers ? 'Cheia' : t('guild.join_btn')}
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
      <input id="guild-desc"  type="text" placeholder="Description (optional)" maxlength="120"
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
  const r = await api('POST', '/api/guild', { name, description: desc });
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg(t('guild.created'));
  await loadGuild();
  loadWarrior();
}

async function guildJoin(id) {
  const r = await api('POST', `/api/guild/join/${id}`);
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg(t('guild.joined'));
  await loadGuild();
}

async function guildLeave() {
  if (!confirm('Leave the guild?')) return;
  const r = await api('POST', '/api/guild/leave');
  if (r.error) { guildMsg(r.error, false); return; }
  await loadGuild();
}

async function guildDisband() {
  if (!confirm('Disband the guild? All members will be removed.')) return;
  const r = await api('DELETE', '/api/guild');
  if (r.error) { guildMsg(r.error, false); return; }
  await loadGuild();
}

async function guildKick(playerId) {
  if (!confirm('Kick this member?')) return;
  const r = await api('POST', `/api/guild/kick/${playerId}`);
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg('Member kicked.');
  await loadGuild();
}

async function guildTransfer(playerId) {
  if (!confirm('Transfer leadership to this member?')) return;
  const r = await api('POST', `/api/guild/transfer/${playerId}`);
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg('Leadership transferred.');
  await loadGuild();
}

async function guildDonate() {
  const amount = parseInt(document.getElementById('donate-amount').value);
  if (!amount || amount <= 0) { guildMsg('Enter a valid amount.', false); return; }
  const r = await api('POST', '/api/guild/donate', { amount });
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg(`Donated! Treasury: ${fmtBronze(r.guildGold ?? 0)}`);
  await loadGuild();
  loadWarrior();
}

async function guildLevelUp() {
  if (!confirm('Spend guild gold to level up?')) return;
  const r = await api('POST', '/api/guild/levelup');
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg(r.message);
  await loadGuild();
}

// ═══════════════════════════════════════════════════════════════════
// TERRITORY WAR
// ═══════════════════════════════════════════════════════════════════

async function loadTerritories() {
  const el = document.getElementById('territory-content');
  el.innerHTML = '<p>Loading...</p>';
  try {
    const [territories, myStatus] = await Promise.all([
      api('GET', '/api/territory'),
      api('GET', '/api/territory/my')
    ]);
    renderTerritories(territories, myStatus);
  } catch(e) {
    el.innerHTML = '<p style="color:red">Error loading territories.</p>';
  }
}

function renderTerritories(territories, myStatus) {
  const el = document.getElementById('territory-content');
  const myTerritory = myStatus.hasTerritory ? myStatus.territory : null;

  const bonusBar = myStatus.hasTerritory ? `
    <div style="background:#1a2a1a;border:1px solid #4caf50;border-radius:8px;padding:12px;margin-bottom:16px;font-size:13px">
      <strong style="color:#4caf50">🏆 Your guild controls: ${myStatus.displayName}</strong><br>
      <span style="color:#aaa">Bonuses: +${myStatus.xpBonus}% XP · +${myStatus.bronzeBonus}% bronze
        ${myStatus.miningBonus > 0 ? ` · +${myStatus.miningBonus}% mining` : ''}
        ${myStatus.fishingBonus > 0 ? ` · +${myStatus.fishingBonus}% fishing` : ''}
        ${myStatus.questXpBonus > 0 ? ` · +${myStatus.questXpBonus}% quest XP` : ''}
      </span><br>
      <span style="color:#f44336;font-size:12px">Defense streak: ${myStatus.defenseStreak}
        ${myStatus.debuffPercent > 0 ? ` (next battle: -${myStatus.debuffPercent}% ATK/DEF)` : ''}
      </span>
    </div>` : '';

  const cards = territories.map(ter => {
    const isMine    = ter.territory === myTerritory;
    const secsH     = Math.floor(ter.secsUntilBattle / 3600);
    const secsM     = Math.floor((ter.secsUntilBattle % 3600) / 60);
    const timerStr  = `${secsH}h ${secsM}m`;
    const terName   = t('territory.name.' + ter.territory) !== 'territory.name.' + ter.territory
                      ? t('territory.name.' + ter.territory)
                      : ter.displayName;

    const controlLine = ter.isNeutral
      ? `<span style="color:#aaa">⚪ ${t('territory.neutral')}</span>`
      : `<span style="color:${isMine ? '#4caf50' : '#ef5350'}">${isMine ? `🛡 ${t('territory.your_guild')}` : '⚔ ' + ter.controllingGuild}</span>`;

    const streakLine = !ter.isNeutral
      ? `<div style="font-size:11px;color:#888">
           ${t('territory.streak', {n: ter.defenseStreak})} · ${t('territory.debuff', {n: ter.debuffPercent})}
         </div>`
      : '';

    const declarers = (ter.declaringGuilds || []);
    const declarersLine = declarers.length > 0
      ? `<div style="margin-top:6px;font-size:12px;color:#ff9800">
           ⚔ ${t('territory.declared', {guilds: declarers.join(', ')})}
         </div>`
      : '';

    const canDeclare = !myTerritory && !ter.myGuildDeclared;
    const hasDeclared = ter.myGuildDeclared;

    const bonusLabel = ter.territory === 'COMBAT' ? t('territory.bonus.quest_xp')
                     : ter.territory === 'MINING' ? t('territory.bonus.mining')
                     : t('territory.bonus.fishing');

    const declareBtn = canDeclare && !isMine
      ? `<button onclick="territoryDeclare('${ter.territory}')" style="margin-top:8px;font-size:12px">
           ⚔ ${t('territory.declare_btn')}
         </button>`
      : hasDeclared
        ? `<button onclick="territoryCancel()" style="margin-top:8px;font-size:12px;background:#7a3b00">
             ✖ ${t('territory.cancel_btn')}
           </button>`
        : '';

    const historyBtn = `<button onclick="territoryHistory('${ter.territory}', '${terName}')"
        style="margin-top:8px;font-size:12px;background:#333;margin-left:4px">📜 ${t('territory.history_btn')}</button>`;

    const borderColor = isMine ? '#4caf50' : hasDeclared ? '#ff9800' : '#444';

    return `
      <div style="background:#1a1a2e;border:1px solid ${borderColor};border-radius:8px;
                  padding:14px;margin-bottom:12px">
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <strong style="font-size:15px">${terName}</strong><br>
            ${controlLine}
            ${streakLine}
          </div>
          <div style="text-align:right;font-size:12px;color:#888">
            ${t('territory.next_battle')}<br><strong style="color:#eee">${timerStr}</strong>
          </div>
        </div>
        <div style="margin-top:6px;font-size:12px;color:#888">
          Exclusive: +${ter.exclusiveBonus}% ${bonusLabel}
        </div>
        ${declarersLine}
        <div>${declareBtn}${historyBtn}</div>
      </div>`;
  }).join('');

  el.innerHTML = bonusBar + cards + '<div id="territory-msg" style="margin-top:8px;min-height:20px"></div>';
}

function territoryMsg(text, ok = true) {
  const el = document.getElementById('world-territory-msg') || document.getElementById('territory-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

async function territoryDeclare(territory) {
  const r = await api('POST', `/api/territory/${territory}/declare`);
  await loadWorld();
  if (r.error) { territoryMsg(r.error, false); return; }
  territoryMsg(r.message);
}

async function territoryCancel() {
  const r = await api('POST', '/api/territory/cancel');
  await loadWorld();
  if (r.error) { territoryMsg(r.error, false); return; }
  territoryMsg(r.message);
}

async function territoryHistory(territory, name) {
  const logs = await api('GET', `/api/territory/${territory}/history`);
  if (!logs || !logs.length) { territoryMsg('No battles recorded yet.'); return; }
  const msg = logs.slice(0, 3).map(l =>
    `${l.resolvedAt.substring(0, 16)} — ${l.attacker} vs ${l.defender} → 🏆 ${l.winner}`
  ).join('<br>');
  territoryMsg(`<strong>${name} — Recent battles:</strong><br>${msg}`, true);
}

// ═══════════════════════════════════════════════════════════════════
// MAIL SYSTEM
// ═══════════════════════════════════════════════════════════════════

async function loadMail() {
  const el = document.getElementById('mail-content');
  el.innerHTML = '<p>Loading...</p>';
  try {
    const data = await api('GET', '/api/mail/inbox');
    updateMailBadge(data.unread);
    renderMailPanel(data.letters, data.unread);
  } catch(e) {
    el.innerHTML = '<p style="color:red">Error loading mail.</p>';
  }
}

function updateMailBadge(unread) {
  const badge = document.getElementById('mail-badge');
  if (!badge) return;
  if (unread > 0) {
    badge.textContent = unread;
    badge.style.display = 'inline';
  } else {
    badge.style.display = 'none';
  }
}

function renderMailPanel(letters, unread) {
  const el = document.getElementById('mail-content');

  const inbox = letters.length === 0
    ? '<p style="color:#aaa;text-align:center;padding:20px">No letters yet.</p>'
    : letters.map(m => `
        <div onclick="mailOpen(${m.id})" style="
          background:#1a1a2e;border:1px solid ${m.isRead ? '#333' : '#5c6bc0'};
          border-radius:6px;padding:10px 12px;margin-bottom:8px;cursor:pointer;
          display:flex;justify-content:space-between;align-items:center">
          <div>
            <strong style="color:${m.isRead ? '#ccc' : '#fff'}">${escapeHtml(m.from)}</strong>
            ${!m.isRead ? '<span style="color:#5c6bc0;font-size:.75em;margin-left:6px">● NEW</span>' : ''}
            ${m.goldAmount > 0 && !m.isCollected ? '<span style="color:#ffd700;font-size:.75em;margin-left:6px">💰 ' + m.goldAmount + ' gold</span>' : ''}
            ${m.hasItem && !m.itemCollected && !m.isExpired ? '<span style="color:#a78bfa;font-size:.75em;margin-left:6px">📦 ITEM</span>' : ''}
            ${m.hasItem && m.isExpired ? '<span style="color:#ef5350;font-size:.75em;margin-left:6px">⏰ EXPIRED</span>' : ''}
            <div style="color:#888;font-size:.8em;margin-top:2px">
              ${escapeHtml(m.message.length > 60 ? m.message.substring(0, 60) + '…' : m.message)}
            </div>
          </div>
          <div style="font-size:.7em;color:#666;text-align:right;white-space:nowrap;margin-left:8px">
            ${m.sentAt.substring(0, 10)}
          </div>
        </div>`).join('');

  el.innerHTML = `
    <div style="display:flex;gap:8px;margin-bottom:16px">
      <button id="mail-tab-inbox" class="tab active" onclick="mailShowTab('inbox')">📥 Inbox ${unread > 0 ? '(' + unread + ')' : ''}</button>
      <button id="mail-tab-send"  class="tab"        onclick="mailShowTab('send')">✉ Send Letter</button>
    </div>

    <div id="mail-inbox-panel">${inbox}</div>

    <div id="mail-send-panel" style="display:none">
      <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px">
        <div style="margin-bottom:8px;font-size:13px;color:#aaa">
          Cost: <strong style="color:#ffd700">1 silver (100 bronze)</strong> per letter (+ bronze attached)
        </div>
        <input id="mail-to" type="text" placeholder="Recipient warrior name (exact)"
          style="width:100%;padding:8px;background:#111;color:#eee;border:1px solid #555;
                 border-radius:4px;margin-bottom:8px;box-sizing:border-box">
        <textarea id="mail-msg" placeholder="Your message (max 500 chars)" maxlength="500" rows="4"
          style="width:100%;padding:8px;background:#111;color:#eee;border:1px solid #555;
                 border-radius:4px;margin-bottom:8px;box-sizing:border-box;resize:vertical"></textarea>
        <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px">
          <label style="font-size:13px;color:#aaa">💰 Bronze to attach:</label>
          <input id="mail-gold" type="number" min="0" value="0"
            style="width:80px;padding:6px;background:#111;color:#eee;border:1px solid #555;border-radius:4px">
        </div>
        <button onclick="mailSend()">✉ Send Letter</button>
      </div>
    </div>

    <div id="mail-msg-area" style="margin-top:10px;min-height:20px"></div>

    <div id="mail-open-panel" style="display:none;margin-top:12px;
      background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px"></div>
  `;
}

function mailShowTab(tab) {
  document.getElementById('mail-inbox-panel').style.display = tab === 'inbox' ? 'block' : 'none';
  document.getElementById('mail-send-panel').style.display  = tab === 'send'  ? 'block' : 'none';
  document.getElementById('mail-open-panel').style.display  = 'none';
  document.getElementById('mail-tab-inbox').classList.toggle('active', tab === 'inbox');
  document.getElementById('mail-tab-send').classList.toggle('active',  tab === 'send');
}

async function mailOpen(id) {
  const r = await api('POST', `/api/mail/${id}/read`);
  if (r.error) { mailMsg(r.error, false); return; }

  const panel = document.getElementById('mail-open-panel');
  panel.style.display = 'block';

  const goldBtn = r.hasGold
    ? `<button onclick="mailCollect(${id})" style="margin-top:10px;background:#7a5f00">
         💰 Collect ${fmtBronze(r.goldAmount)}
       </button>`
    : r.goldAmount > 0 ? `<span style="color:#888;font-size:12px">💰 ${fmtBronze(r.goldAmount)} (already collected)</span>` : '';

  let itemBtn = '';
  if (r.hasItem) {
    if (r.isExpired) {
      itemBtn = `<div style="color:#ef5350;font-size:12px;margin-top:10px">⏰ This item has expired and was lost.</div>`;
    } else if (r.itemCollected) {
      itemBtn = `<div style="color:#888;font-size:12px;margin-top:10px">📦 ${r.itemName} (already claimed)</div>`;
    } else {
      const exp = r.expiresAt ? r.expiresAt.substring(0, 10) : '';
      itemBtn = `
        <div style="background:#1a0a2e;border:1px solid #a78bfa;border-radius:6px;padding:10px;margin-top:10px">
          <div style="color:#a78bfa;font-weight:bold">📦 ${r.itemName}</div>
          ${exp ? `<div style="color:#888;font-size:11px">Expires: ${exp}</div>` : ''}
          <button onclick="mailClaimItem(${id})" style="margin-top:6px;background:#5b21b6;font-size:12px">
            📦 Add to Bag
          </button>
        </div>`;
    }
  }

  panel.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:flex-start">
      <strong>From: ${escapeHtml(r.from)}</strong>
      <button onclick="mailDelete(${id})" style="background:#333;font-size:11px;padding:3px 8px">🗑 Delete</button>
    </div>
    <div style="color:#888;font-size:.75em;margin:.3rem 0">${r.sentAt.substring(0, 16).replace('T', ' ')}</div>
    <div style="background:#111;border-radius:4px;padding:10px;margin-top:8px;
                white-space:pre-wrap;font-size:13px;line-height:1.5">${escapeHtml(r.message)}</div>
    ${goldBtn}
    ${itemBtn}
  `;

  // Refresh unread badge
  const data = await api('GET', '/api/mail/inbox');
  updateMailBadge(data.unread);
}

async function mailCollect(id) {
  const r = await api('POST', `/api/mail/${id}/collect`);
  if (r.error) { mailMsg(r.error, false); return; }
  mailMsg(r.message);
  await loadMail();
  loadWarrior();
}

async function mailClaimItem(id) {
  const r = await api('POST', `/api/mail/${id}/claim-item`);
  if (r.error) { mailMsg(r.error, false); return; }
  mailMsg(`📦 ${r.message}`);
  await loadMail();
  loadWarrior();
  loadInventory();
}

async function mailDelete(id) {
  if (!confirm('Delete this letter?')) return;
  const r = await api('DELETE', `/api/mail/${id}`);
  if (r.error) { mailMsg(r.error, false); return; }
  await loadMail();
}

async function mailSend() {
  const to   = document.getElementById('mail-to').value.trim();
  const msg  = document.getElementById('mail-msg').value.trim();
  const gold = parseInt(document.getElementById('mail-gold').value) || 0;
  if (!to)  { mailMsg(t('mail.err_no_to'), false); return; }
  if (!msg) { mailMsg(t('mail.err_no_msg'), false); return; }
  const r = await api('POST', '/api/mail/send', { recipientWarriorName: to, message: msg, goldAmount: gold });
  if (r.error) { mailMsg(r.error, false); return; }
  mailMsg(r.message);
  document.getElementById('mail-to').value   = '';
  document.getElementById('mail-msg').value  = '';
  document.getElementById('mail-gold').value = '0';
  loadWarrior(); // update gold display
}

function mailMsg(text, ok = true) {
  const el = document.getElementById('mail-msg-area');
  // escapeHtml: a msg "Letter sent to <nome>!" ecoa o nome digitado (innerHTML aqui).
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${escapeHtml(text)}</span>`;
}

// ═══════════════════════════════════════════════════════════════════
// WORLD — 3 KINGDOMS
// ═══════════════════════════════════════════════════════════════════

let worldCurrentKingdom = null;

async function loadWorld() {
  const el = document.getElementById('world-content');
  el.innerHTML = '<p>Loading...</p>';
  try {
    const [kingdoms, territories] = await Promise.all([
      api('GET', '/api/world'),
      api('GET', '/api/territory')
    ]);
    renderWorldOverview(kingdoms, territories);
  } catch(e) {
    el.innerHTML = '<p style="color:red">Error loading world.</p>';
  }
}

// Reinos V2: reino e território são o mesmo id agora (unificação Kingdom/Territory).
// Reinos sem guild-war (Grutas/Mar) simplesmente não têm entrada em `territories`.

function renderWorldOverview(kingdoms, territories) {
  const el = document.getElementById('world-content');
  const ZONE_LABELS = {
    FISHING: ['Safe Shore','Wild Coast','Deep Sea'],
    MINING:  ['Open Mine','Deep Tunnels','Forbidden Mines'],
    COMBAT:  ['Training Hall','Battlefield','War Zone'],
    GRUTAS_DE_CRISTAL: ['Shallow Vein','Deep Grottoes','Forbidden Cavern'],
    MAR_ABENCOADO: ['Sacred Cove','Deep Reef','Blessed Abyss']
  };

  const cards = kingdoms.map(k => {
    const ctrl = k.controllingGuild
      ? `<span style="color:#4caf50">🛡 ${k.controllingGuild}</span>`
      : `<span style="color:#aaa">Neutral</span>`;
    const bonus = k.isMine ? `<div style="font-size:12px;color:#4caf50;margin-top:4px">Your guild: +${k.xpBonus}% XP · +${k.bronzeBonus}% bronze · +${k.exclusiveBonus}% bonus</div>` : '';
    const secsH = Math.floor(k.secsUntilBattle / 3600);
    const secsM = Math.floor((k.secsUntilBattle % 3600) / 60);
    const zones = ZONE_LABELS[k.kingdom] || [];
    const zoneColors = ['#4caf50','#ffc107','#ef5350'];
    const zoneBgs    = ['#1a3a1a','#2a2a1a','#3a1a1a'];
    const zoneHtml = zones.map((z,i) =>
      `<span style="font-size:11px;padding:2px 8px;border-radius:12px;background:${zoneBgs[i]};color:${zoneColors[i]}">${z}</span>`
    ).join('');

    const terKey = k.kingdom;
    const ter = (territories || []).find(t => t.territory === terKey);

    const declarersLine = ter && ter.declaringGuilds && ter.declaringGuilds.length > 0
      ? `<div style="font-size:11px;color:#ffc107;margin-top:4px">⚔ Declared: ${ter.declaringGuilds.join(', ')}</div>`
      : '';

    const warBtn = ter && !ter.isMine && !ter.myGuildDeclared
      ? `<button onclick="event.stopPropagation();territoryDeclare('${terKey}')" style="font-size:11px;padding:4px 10px;background:#7a1f1f">⚔ Declare War</button>`
      : ter && ter.myGuildDeclared
      ? `<button onclick="event.stopPropagation();territoryCancel()" style="font-size:11px;padding:4px 10px;background:#7a3b00">✖ Cancel Declaration</button>`
      : '';

    const histBtn = `<button onclick="event.stopPropagation();territoryHistory('${terKey}','${k.displayName}')" style="font-size:11px;padding:4px 10px;background:#333">📜 History</button>`;

    // Reinos sem guerra de guild (ex.: Grutas de Cristal) não mostram a seção de guerra. [REINOS_V2]
    const warSection = terKey ? `
      <div onclick="event.stopPropagation()" style="border-top:1px solid #333;margin-top:10px;padding-top:8px">
        <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap">
          <span style="font-size:11px;color:#666">Territory War:</span>
          ${warBtn}
          ${histBtn}
        </div>
        ${declarersLine}
      </div>` : '';
    const nextWar = terKey
      ? `<div style="text-align:right;font-size:11px;color:#666">Next war<br><strong style="color:#eee">${secsH}h ${secsM}m</strong></div>`
      : '';

    return `<div onclick="enterKingdom('${k.kingdom}')" style="background:#1a1a2e;border:1px solid ${k.isMine ? '#4caf50' : '#444'};border-radius:10px;padding:16px;margin-bottom:12px;cursor:pointer">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <div>
          <h3 style="margin:0 0 4px;font-size:16px">${k.icon} ${k.displayName}</h3>
          ${ctrl}${bonus}
        </div>
        ${nextWar}
      </div>
      <p style="color:#888;font-size:12px;margin:8px 0 0">${k.lore}</p>
      <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap">${zoneHtml}</div>
      ${warSection}
    </div>`;
  }).join('');

  el.innerHTML = cards + '<div id="kingdom-detail" style="margin-top:16px"></div>' +
                 '<div id="world-territory-msg" style="margin-top:8px;min-height:20px"></div>';
}

async function enterKingdom(kingdom) {
  worldCurrentKingdom = kingdom;
  const el = document.getElementById('kingdom-detail');
  if (!el) return;
  el.innerHTML = '<p>Loading kingdom...</p>';
  try {
    const [, quests, activeQuests, training, gatherSession, zoneSession, pvpStatus] = await Promise.all([
      loadWarrior(),
      api('GET', `/api/world/${kingdom}/quests`),
      api('GET', `/api/world/${kingdom}/quests/active`),
      kingdom === 'COMBAT' ? api('GET', '/api/world/COMBAT/training') : Promise.resolve(null),
      (kingdom === 'FISHING' || kingdom === 'MINING' || kingdom === 'GRUTAS_DE_CRISTAL' || kingdom === 'MAR_ABENCOADO') ? api('GET', '/api/gathering/current') : Promise.resolve(null),
      (kingdom === 'FISHING' || kingdom === 'MINING' || kingdom === 'COMBAT') ? api('GET', '/api/zones/current') : Promise.resolve(null),
      api('GET', '/api/zones/pvp-status').catch(() => null)
    ]);
    console.log('[WORLD] enterKingdom data:', {kingdom, gatherSession, zoneSession, activeQuests: activeQuests.length});
    renderKingdomDetail(kingdom, quests, activeQuests, training, gatherSession, zoneSession, pvpStatus);
  } catch(e) {
    console.error('[WORLD] enterKingdom ERROR:', e);
    el.innerHTML = '<p style="color:red">Error loading kingdom: ' + e.message + '</p>';
  }
}

function renderKingdomDetail(kingdom, quests, activeQuests, training, gatherSession, zoneSession, pvpStatus) {
  const el = document.getElementById('kingdom-detail');
  const NAMES = { FISHING:'Bone Gorge', MINING:'Black Iron Mines', COMBAT:'Cursed Fortress', GRUTAS_DE_CRISTAL:'Crystal Grottoes', MAR_ABENCOADO:'Blessed Sea' };
  const ICONS = { FISHING:'🎣', MINING:'⛏', COMBAT:'⚔', GRUTAS_DE_CRISTAL:'🔎', MAR_ABENCOADO:'🐟' };
  const busy = activeQuests.length > 0
    || !!(warrior && warrior.onMission)
    || !!(gatherSession && gatherSession.active)
    || !!(zoneSession && zoneSession.active)
    || !!(training && training.active && !training.readyToCollect);

  const activeHtml = activeQuests.length === 0 ? '' : `
    <div style="background:#0f1f0f;border:1px solid #2e7d32;border-radius:8px;padding:12px;margin-bottom:12px">
      <strong style="color:#4caf50">Active Quests</strong>
      ${activeQuests.map(q => `
        <div style="margin-top:8px;display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:13px">${q.displayName}</span>
          <div style="display:flex;gap:6px">
            ${q.readyToCollect
              ? `<button onclick="collectKingdomQuest('${kingdom}',${q.id})" style="font-size:12px;background:#2e7d32">Collect</button>`
              : `<span style="color:#888;font-size:12px">${Math.floor(q.secondsRemaining/60)}m</span>`}
            <button onclick="abandonKingdomQuest('${kingdom}',${q.id})" style="font-size:11px;background:#555;padding:3px 8px">✕</button>
          </div>
        </div>`).join('')}
    </div>`;

  let trainingHtml = '';
  if (kingdom === 'COMBAT') {
    if (training && training.active) {
      trainingHtml = `
        <div style="background:#1a1a2e;border:1px solid #5c6bc0;border-radius:8px;padding:12px;margin-bottom:12px">
          <strong style="color:#7986cb">🏋 Training Ready</strong>
          <div style="font-size:13px;color:#aaa;margin-top:4px">+${training.xpReward} XP — collect it</div>
          <div style="display:flex;gap:8px;margin-top:8px">
            ${training.readyToCollect ? `<button onclick="collectTraining(${training.id})" style="background:#3949ab">⭐ Collect XP</button>` : ''}
            <button onclick="cancelTraining(${training.id})" style="background:#555;font-size:12px">✕ Cancel</button>
          </div>
        </div>`;
    } else {
      const lvl = warrior ? warrior.level : 1;
      trainingHtml = `
        <div style="background:#1a1a2e;border:1px solid #333;border-radius:8px;padding:12px;margin-bottom:12px">
          <strong style="color:#7986cb">🏋 Training Hall</strong>
          <p style="font-size:12px;color:#888;margin:4px 0 8px">Pague bronze por XP puro. Custo: ${fmtBronze(lvl*20)} · Recompensa: ${lvl*50} XP</p>
          ${busy
            ? `<p style="font-size:12px;color:#f44336;margin:0">⚔ Warrior is busy</p>`
            : `<div style="display:flex;gap:6px;flex-wrap:wrap">
            ${(() => { const h = 2; return `<button onclick="startTraining(${h})" style="font-size:12px;padding:4px 14px">🏋 Treinar · ${fmtBronze(lvl*10*h)}</button>`; })()}
          </div>`}
        </div>`;
    }
  }

  // Active gathering / zone session banner
  let activeGatherHtml = '';
  if (gatherSession && gatherSession.active) {
    const secsLeft = gatherSession.secondsRemaining || 0;
    const timeStr  = secsLeft > 3600 ? `${Math.floor(secsLeft/3600)}h ${Math.floor((secsLeft%3600)/60)}m` : `${Math.floor(secsLeft/60)}m`;
    activeGatherHtml = `
      <div style="background:#0f2f2f;border:1px solid #00897b;border-radius:8px;padding:12px;margin-bottom:12px">
        <strong style="color:#4db6ac">🎣 Gathering in Progress</strong>
        <div style="font-size:13px;color:#aaa;margin-top:4px">${gatherSession.displayName} · ${secsLeft <= 0 ? 'Ready!' : timeStr + ' remaining'}</div>
        ${secsLeft <= 0
          ? `<button onclick="collectKingdomGather(${gatherSession.id})" style="margin-top:8px;background:#00695c">Collect</button>`
          : `<button onclick="cancelKingdomGather(${gatherSession.id})" style="margin-top:8px;background:#555;font-size:12px">✕ Cancel</button>`}
      </div>`;
  } else if (zoneSession && zoneSession.active) {
    const secsLeft = zoneSession.secondsRemaining || 0;
    const timeStr  = secsLeft > 3600 ? `${Math.floor(secsLeft/3600)}h ${Math.floor((secsLeft%3600)/60)}m` : `${Math.floor(secsLeft/60)}m`;
    activeGatherHtml = `
      <div style="background:#2f0f0f;border:1px solid #ef5350;border-radius:8px;padding:12px;margin-bottom:12px">
        <strong style="color:#ef9a9a">⚔ Expedition in Progress (${zoneSession.zoneName || zoneSession.zone})</strong>
        <div style="font-size:13px;color:#aaa;margin-top:4px">${secsLeft <= 0 ? 'Ready to collect!' : timeStr + ' remaining'}</div>
        ${secsLeft <= 0
          ? `<button onclick="collectKingdomZoneSession(${zoneSession.id})" style="margin-top:8px;background:#c62828">Collect Loot</button>`
          : `<button onclick="cancelKingdomZoneSession(${zoneSession.id})" style="margin-top:8px;background:#555;font-size:12px">✕ Cancel</button>`}
      </div>`;
  }

  // Combat zones for COMBAT kingdom — Campo de Batalha (PVP) + Zona de Guerra (HIGH_RISK)
  let combatZonesHtml = '';
  if (kingdom === 'COMBAT') {
    const wLevel = warrior ? warrior.level : 1;
    const combatZones = [
      { name:'⚔ Battlefield', zone:'PVP',       minLv:10, color:'#ffc107',
        desc:'Monsters and hunters — earn XP and bronze over time.' },
      { name:'🔥 War Zone',    zone:'HIGH_RISK',  minLv:20, color:'#ef5350',
        desc:'Intense combat — high rewards, risk of losing an item.' }
    ];
    combatZonesHtml = `<h4 style="margin:12px 0 8px;color:#aaa;font-size:13px">COMBAT ZONES</h4>` +
      combatZones.map(z => {
        const locked = wLevel < z.minLv;
        return `
          <div style="background:#1a1a2e;border:1px solid ${locked?'#333':z.color+'44'};border-radius:8px;padding:12px;margin-bottom:8px;opacity:${locked?'0.5':'1'}">
            <div style="display:flex;justify-content:space-between;align-items:center">
              <strong style="color:${z.color}">${z.name}</strong>
              ${locked ? `<span style="font-size:11px;color:#888">🔒 Lv.${z.minLv}+</span>` : '<span style="font-size:11px;color:#ef5350">⚔ PvP</span>'}
            </div>
            <p style="font-size:11px;color:#888;margin:3px 0 6px">${z.desc}</p>
            ${locked
              ? `<p style="font-size:11px;color:#555;margin:0">Reach level ${z.minLv} to unlock.</p>`
              : busy
              ? '<p style="font-size:11px;color:#f44336;margin:0">⚔ Warrior is busy</p>'
              : `<div style="display:flex;gap:5px;flex-wrap:wrap">
                ${(() => {
                  const d = 120; const stamCost = Math.min(100, Math.max(5, Math.round(d/8))); // ação fixa instantânea
                  return `<button onclick="enterCombatZone('${z.zone}',${d})" style="font-size:12px;padding:4px 14px">⚔ Farmar · ⚡${stamCost}</button>`;
                })()}
              </div>`}
          </div>`;
      }).join('');
  }

  // Gathering section for FISHING, MAR_ABENCOADO, MINING and GARIMPO kingdoms — 3 zones per kingdom
  let gatheringHtml = '';
  if (kingdom === 'FISHING' || kingdom === 'MAR_ABENCOADO' || kingdom === 'MINING' || kingdom === 'GRUTAS_DE_CRISTAL') {
    const isFishing = kingdom === 'FISHING' || kingdom === 'MAR_ABENCOADO';
    const skillType = isFishing ? 'FISHING'
                    : kingdom === 'MINING' ? 'MINING'
                    : 'GARIMPO';
    const wLevel    = warrior ? warrior.level : 1;

    // All zones use /api/gathering/start (max 60min). PvP risk = cosmetic for now.
    const fishDurations = [5, 10, 20, 30, 40];
    const mineDurations = [10, 20, 30, 45, 60];
    const dur = isFishing ? fishDurations : mineDurations;

    const zones = kingdom === 'FISHING' ? [
      { name:'🏖 Safe Shore', minLv:1,  pvp:false, durations:dur, color:'#4caf50', desc:'Safe fishing — restores stamina' },
      { name:'🌊 Wild Coast', minLv:10, pvp:true,  durations:dur, color:'#ffc107', desc:'PvP zone — hunters may attack (coming soon)' },
      { name:'🦈 Deep Sea',   minLv:20, pvp:true,  durations:dur, color:'#ef5350', desc:'High risk — rare fish (coming soon)' }
    ] : kingdom === 'MAR_ABENCOADO' ? [
      { name:'🌅 Sacred Cove',   minLv:1,  pvp:false, durations:dur, color:'#4caf50', desc:'Safe fishing — fish that restore LIFE' },
      { name:'🐠 Deep Reef',     minLv:10, pvp:true,  durations:dur, color:'#ffc107', desc:'PvP zone — hunters may attack (coming soon)' },
      { name:'🔱 Blessed Abyss', minLv:20, pvp:true,  durations:dur, color:'#ef5350', desc:'High risk — legendary life fish (coming soon)' }
    ] : kingdom === 'MINING' ? [
      { name:'⛏ Open Mine',       minLv:1,  pvp:false, durations:dur, color:'#4caf50', desc:'Safe mining — no PvP' },
      { name:'🪨 Deep Tunnels',   minLv:10, pvp:true,  durations:dur, color:'#ffc107', desc:'PvP zone — hunters may attack (coming soon)' },
      { name:'💎 Forbidden Mines', minLv:20, pvp:true,  durations:dur, color:'#ef5350', desc:'High risk — rare ores (coming soon)' }
    ] : [
      { name:'🔎 Shallow Vein',     minLv:1,  pvp:false, durations:dur, color:'#4caf50', desc:'Safe prospecting — no PvP' },
      { name:'💠 Deep Grottoes',    minLv:10, pvp:true,  durations:dur, color:'#ffc107', desc:'PvP zone — hunters may attack (coming soon)' },
      { name:'💎 Forbidden Cavern', minLv:20, pvp:true,  durations:dur, color:'#ef5350', desc:'High risk — rare gems (coming soon)' }
    ];

    gatheringHtml = zones.map(z => {
      const locked = wLevel < z.minLv;
      return `
        <div style="background:#1a1a2e;border:1px solid ${locked?'#333':z.color+'44'};border-radius:8px;padding:12px;margin-bottom:8px;opacity:${locked?'0.5':'1'}">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <strong style="color:${z.color}">${z.name}</strong>
            ${locked ? `<span style="font-size:11px;color:#888">🔒 Lv.${z.minLv}+</span>` : z.pvp ? '<span style="font-size:11px;color:#ef5350">⚔ PvP</span>' : '<span style="font-size:11px;color:#4caf50">✓ Safe</span>'}
          </div>
          <p style="font-size:11px;color:#888;margin:3px 0 6px">${z.desc}</p>
          ${locked
            ? '<p style="font-size:11px;color:#555;margin:0">Reach level '+z.minLv+' to unlock.</p>'
            : busy
              ? '<p style="font-size:11px;color:#f44336;margin:0">⚔ Warrior is busy</p>'
              : `<div style="display:flex;gap:5px;flex-wrap:wrap">
              ${(() => {
                const d = 20; // ação instantânea de tamanho fixo — a estamina é o gate, sem timer
                const stamCost = Math.max(5, Math.floor(d/2));
                const verb = isFishing ? '🎣 Pescar' : skillType === 'MINING' ? '⛏ Minerar' : '🔎 Garimpar';
                return `<button onclick="startKingdomGathering('${skillType}',${d},'${kingdom}')" style="font-size:12px;padding:4px 14px">${verb} · ⚡${stamCost}</button>`;
              })()}
            </div>`}
        </div>`;
    }).join('');
  }

  const vipInstantLeft = warrior && warrior.isVip
    ? Math.max(0, 2 - (warrior.instantQuestsToday ?? 0)) : 0;

  const questCards = quests.map(q => {
    const disabled = busy || !q.canStart;
    const canInstant = warrior && warrior.isVip && !busy && q.canStart && vipInstantLeft > 0;
    const instantBtn = warrior && warrior.isVip && !busy
      ? `<button onclick="instantStartQuest('${kingdom}','${q.id}')"
           style="margin-top:8px;font-size:12px;background:#7c3aed;margin-left:6px"
           ${!canInstant ? 'disabled style="opacity:.5;margin-left:6px"' : ''}>
           ⚡ Instant${vipInstantLeft > 0 ? ' (' + vipInstantLeft + ')' : ' (0)'}
         </button>`
      : '';
    return `
      <div style="background:#1a1a2e;border:1px solid #333;border-radius:8px;padding:12px;margin-bottom:8px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong style="font-size:14px">${q.displayName}</strong>
          <div style="display:flex;gap:8px;align-items:center;font-size:12px;color:#888">
            <span>⏱ ${q.durationMinutes}m</span>
            <span>${fmtBronze(q.bronzeReward)}</span>
            <span>⭐ ${q.expReward} XP</span>
            <span>⚡ ${q.staminaCost}</span>
          </div>
        </div>
        <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:8px">
          <button onclick="startKingdomQuest('${kingdom}','${q.id}')"
            ${disabled ? 'disabled style="opacity:.5"' : ''}
            style="font-size:12px">
            ${busy ? 'Warrior busy' : !q.canStart ? 'Low stamina' : 'Start Quest'}
          </button>
          ${instantBtn}
        </div>
      </div>`;
  }).join('');

  // Fortaleza Maldita — caçada PvE repetível (antigo Covil das Feras). [REINOS_V2]
  let raidHtml = '';
  if (kingdom === 'COMBAT') {
    const lvl = warrior ? warrior.level : 1;
    raidHtml = `
      <div style="background:#2a1010;border:1px solid #7a1f1f;border-radius:8px;padding:12px;margin-bottom:12px">
        <strong style="color:#ef5350">👹 Hunt Beasts</strong>
        <p style="font-size:12px;color:#aaa;margin:4px 0 8px">Mobs scale with your level (Lv.${lvl}). A win pays ~${fmtBronze(lvl*10)}, ${lvl*12} XP and materials (Monster Core). Costs 15⚡.</p>
        ${busy
          ? '<p style="font-size:11px;color:#f44336;margin:0">⚔ Warrior busy or wounded</p>'
          : `<button onclick="raidCombat()" style="background:#7a1f1f">⚔ Hunt</button>`}
      </div>`;
  }

  const questsSection = quests.length > 0
    ? `<h4 style="margin:0 0 8px;color:#aaa;font-size:13px">QUESTS</h4>${questCards}`
    : '';

  el.innerHTML = `
    <div style="background:#111;border-radius:10px;padding:16px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <h3 style="margin:0">${ICONS[kingdom]} ${NAMES[kingdom]}</h3>
        <button onclick="document.getElementById('kingdom-detail').innerHTML=''" style="background:#333;font-size:12px">✕ Close</button>
      </div>
      ${pvpStatusBanner(pvpStatus)}
      ${activeHtml}
      ${activeGatherHtml}
      ${trainingHtml}
      ${raidHtml}
      ${questsSection}
      ${combatZonesHtml}
      ${gatheringHtml}
      <div id="world-msg" style="margin-top:8px;min-height:20px"></div>
    </div>`;
}

// Fortaleza Maldita — dispara uma caçada PvE e mostra o resultado. [REINOS_V2]
async function raidCombat() {
  const r = await api('POST', '/api/world/COMBAT/raid');
  if (r.error) { worldMsg(r.error, false); return; }
  await loadWarrior();
  const mats = (r.materials || []).map(m => `${m.displayName} ×${m.quantity}`).join(', ');
  const msg = r.won
    ? `🏆 You defeated ${r.beast}! +${fmtBronze(r.goldEarned)}, +${r.xpEarned} XP${mats ? ', ' + mats : ''}.`
    : `💀 ${r.beast} defeated you. Heal at the Temple.`;
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  worldMsg(msg, r.won);
}

function worldMsg(text, ok = true) {
  const el = document.getElementById('world-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

// ── Collect Modal ──────────────────────────────────────────────────────────────
// rows: [{icon, label, value, color}]
// log:  string[] (battle log lines)
// note: string (narrative/lore paragraph shown above the rows)
function showCollectModal({ title, color = '#4caf50', rows = [], log = [], note = '' }) {
  closeCollectModal();

  const GATHER_ICONS = { FISH:'🐟', ORE:'🪨', GEM:'💎', BAR:'🔩', CRYSTAL:'🔮' };

  const noteHtml = note ? `
    <div style="background:#0d0d18;border-left:3px solid ${color};border-radius:6px;padding:10px 12px;margin-bottom:14px;font-size:13px;color:#cdd;font-style:italic;line-height:1.5">${note}</div>` : '';

  const rowsHtml = rows.map(r => `
    <div style="display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #2a2a3a">
      <span style="font-size:18px;min-width:24px;text-align:center">${r.icon}</span>
      <span style="font-size:13px;color:#bbb;flex:1">${r.label}</span>
      <span style="font-weight:bold;color:${r.color || '#fff'};font-size:13px">${r.value}</span>
    </div>`).join('');

  const logHtml = log.length > 0 ? `
    <details style="margin-top:14px">
      <summary style="cursor:pointer;color:#888;font-size:12px;user-select:none">
        📜 Battle Log (${log.length} lines)
      </summary>
      <div style="margin-top:8px;background:#0d0d0d;border-radius:6px;padding:10px;max-height:220px;overflow-y:auto;font-size:11px;font-family:monospace;color:#aaa;white-space:pre-wrap;line-height:1.5">${log.join('\n')}</div>
    </details>` : '';

  const el = document.createElement('div');
  el.id = 'collect-modal-overlay';
  el.setAttribute('style',
    'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.82);' +
    'z-index:9999;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box');
  el.onclick = closeCollectModal;
  el.innerHTML = `
    <div onclick="event.stopPropagation()" style="background:#16162a;border:2px solid ${color};border-radius:14px;
      padding:24px;max-width:460px;width:100%;max-height:85vh;overflow-y:auto;position:relative;box-shadow:0 8px 32px rgba(0,0,0,0.6)">
      <button onclick="closeCollectModal()" style="position:absolute;top:10px;right:10px;background:#333;
        border:none;color:#aaa;padding:4px 10px;border-radius:4px;cursor:pointer;font-size:13px">✕</button>
      <h3 style="margin:0 0 16px;color:${color};font-size:17px">${title}</h3>
      ${noteHtml}
      ${rowsHtml || '<div style="color:#888;font-size:13px">Nothing this time.</div>'}
      ${logHtml}
      <button onclick="closeCollectModal()" style="margin-top:18px;width:100%;background:${color};color:#000;
        font-weight:bold;padding:10px;border-radius:8px;cursor:pointer;font-size:14px;border:none">Continue</button>
    </div>`;
  document.body.appendChild(el);
}

function closeCollectModal() {
  document.getElementById('collect-modal-overlay')?.remove();
}

const GATHER_CATEGORY_ICON = { FISH:'🐟', ORE:'🪨', GEM:'💎', BAR:'🔩', CRYSTAL:'🔮' };

async function startKingdomQuest(kingdom, questTypeId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/start`, { questType: questTypeId });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomQuest(kingdom, r.questId); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

async function collectKingdomQuest(kingdom, questId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/${questId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  showQuestResultModal(r);
  await enterKingdom(kingdom);
}

// Modal de resultado da quest: narrativa + (se houve) combate; derrota = sem recompensa.
function showQuestResultModal(r) {
  const lost = r.monsterEncountered && !r.monsterDefeated;
  if (lost) {
    showCollectModal({
      title: `💀 Defeated by the ${r.monsterName || 'monster'}!`,
      color: '#ef5350',
      note:  r.narrative,
      rows:  [{ icon:'☠', label:'Reward', value:'None — you were beaten', color:'#ef5350' }],
      log:   r.battleLog || []
    });
    return;
  }
  const rows = [
    { icon:'⭐', label:'Experience', value:`+${r.xpEarned} XP`,    color:'#ffd700' },
    { icon:'🪙', label:'Bronze',     value:fmtBronze(r.bronzeEarned), color:'#cd7f32' },
  ];
  if (r.droppedItem) rows.push({ icon:'🎁', label:'Item Drop', value:r.droppedItem.name, color:'#a855f7' });
  const title = r.monsterEncountered ? `⚔ ${r.monsterName} slain!` : '⚔ Quest Completed!';
  showCollectModal({ title, color:'#4caf50', note:r.narrative, rows, log:r.battleLog || [] });
}

async function abandonKingdomQuest(kingdom, questId) {
  if (!confirm('Abandon quest? You receive no reward.')) return;
  const r = await api('POST', `/api/world/${kingdom}/quests/${questId}/abandon`);
  if (r.error) { worldMsg(r.error, false); return; }
  await enterKingdom(kingdom);
  worldMsg('Quest abandoned.');
}

async function startTraining(hours) {
  const r = await api('POST', '/api/world/COMBAT/training/start', { hours });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectTraining(r.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

async function cancelTraining(sessionId) {
  if (!confirm('Cancel training? You will not receive any XP.')) return;
  const r = await api('POST', `/api/world/COMBAT/training/${sessionId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  const msg = r.message;
  await enterKingdom('COMBAT');
  worldMsg(msg);
}

async function collectTraining(sessionId) {
  const r = await api('POST', `/api/world/COMBAT/training/${sessionId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  showCollectModal({ title: '🏋 Training Complete!', color: '#5c6bc0',
    rows: [{ icon:'⭐', label:'Experience', value:`+${r.xpEarned} XP`, color:'#ffd700' }] });
  await enterKingdom('COMBAT');
}

// Safe zone gathering: /api/gathering/start. kingdom define o pool de drops (ex.: Mar Abençoado = peixe de vida).
async function startKingdomGathering(skillType, durationMinutes, kingdom) {
  const body = { skillType, durationMinutes };
  if (kingdom) body.kingdom = kingdom;
  const r = await api('POST', '/api/gathering/start', body);
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomGather(r.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

// PvP / High-Risk zone gathering: /api/zones/enter (Gatherer role)
async function enterKingdomZone(zone, skillType, durationMinutes) {
  const r = await api('POST', '/api/zones/enter', { zone, role: 'GATHERING', skillType, durationMinutes });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomZoneSession(r.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

async function enterCombatZone(zone, durationMinutes) {
  const r = await api('POST', '/api/zones/enter', { zone, role: 'COMBAT', durationMinutes });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomZoneSession(r.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

// Kingdom gathering session helpers
async function collectKingdomGather(sessionId) {
  const r = await api('POST', `/api/gathering/${sessionId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  const rows = (r.drops && r.drops.length > 0)
    ? r.drops.map(d => ({
        icon: GATHER_CATEGORY_ICON[d.category] || '📦',
        label: d.displayName,
        value: `x${d.quantity}`,
        color: '#4db6ac'
      }))
    : [];
  showCollectModal({ title:'🎣 Gathering Results!', color:'#00897b', note:r.narrative, rows });
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
}

async function cancelKingdomGather(sessionId) {
  if (!confirm('Cancel gathering session? You lose all collected resources.')) return;
  const r = await api('POST', `/api/gathering/${sessionId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  worldMsg('Gathering cancelled.');
}

async function collectKingdomZoneSession(activityId) {
  const r = await api('POST', `/api/zones/${activityId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  let title, color, rows = [];

  if (r.wasAttacked && !r.survived) {
    title = '💀 Defeated in Expedition!';
    color = '#ef5350';
    if (r.attackerName) rows.push({ icon:'⚔', label:'Defeated by', value:escapeHtml(r.attackerName), color:'#ef9a9a' });
    if (r.lostItemName) rows.push({ icon:'💸', label:'Item stolen', value:r.lostItemName,  color:'#ef5350' });
  } else {
    title = r.wasAttacked ? '⚔ Survived the Expedition!' : '✅ Expedition Completed!';
    color = r.wasAttacked ? '#ffc107' : '#4caf50';
    if (r.wasAttacked && r.attackerName)
      rows.push({ icon:'⚔', label:'Survived attack by', value:escapeHtml(r.attackerName), color:'#ffc107' });
    (r.drops || []).forEach(d =>
      rows.push({ icon:'📦', label:d.displayName, value:`x${d.quantity}`, color:'#4db6ac' }));
    if (r.bronzeGained > 0)
      rows.push({ icon:'🪙', label:'Bronze', value:fmtBronze(r.bronzeGained), color:'#cd7f32' });
    if (r.xpGained > 0)
      rows.push({ icon:'⭐', label:'Experience', value:`+${r.xpGained} XP`, color:'#ffd700' });
  }

  showCollectModal({ title, color, rows, log: r.battleLog || [] });
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
}

async function cancelKingdomZoneSession(activityId) {
  if (!confirm('Cancel expedition? You lose all resources gathered so far.')) return;
  const r = await api('POST', `/api/zones/${activityId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  worldMsg('Expedition cancelled.');
}

// ═══════════════════════════════════════════════════════════════════
// VIP SHOP
// ═══════════════════════════════════════════════════════════════════

async function loadVipShop() {
  const el = document.getElementById('vipshop-content');
  el.innerHTML = '<p>Loading VIP Shop...</p>';
  const [status, slots] = await Promise.all([
    api('GET', '/api/vip/status'),
    api('GET', '/api/inventory/slots')
  ]);

  const isVip = status.isVip;
  const daysLeft = isVip && status.vipExpiresAt
    ? Math.ceil((new Date(status.vipExpiresAt) - Date.now()) / 86400000)
    : 0;
  const ss = warrior ? warrior.soulStones : 0;

  const vipBanner = isVip
    ? `<div style="background:#3b0764;border:1px solid #7c3aed;border-radius:8px;padding:12px;margin-bottom:12px">
        <div style="color:#c4b5fd;font-weight:bold">👑 VIP Ativo — ${daysLeft} dias restantes</div>
        <div style="font-size:12px;color:#a78bfa;margin-top:4px">Expira em ${status.vipExpiresAt ? status.vipExpiresAt.substring(0,10) : ''}</div>
        <div style="font-size:12px;color:#888;margin-top:6px">
          ⚡ Missões instantâneas hoje: ${2 - status.instantQuestsRemaining}/2
          &nbsp;·&nbsp;
          ⚔ Lutas de arena: ${status.arenaFightLimit - status.arenaFightsRemaining}/${status.arenaFightLimit}
        </div>
      </div>`
    : `<div style="background:#1a0a2e;border:1px solid #7c3aed;border-radius:8px;padding:12px;margin-bottom:12px">
        <div style="color:#aaa;font-size:13px">Você não tem VIP ativo.</div>
      </div>`;

  const canBuyVip = ss >= 15;
  const vipLabel = isVip ? `👑 Renovar VIP (+30 dias)` : `👑 Ativar VIP`;

  const bagExpanded = slots && slots.inventoryExpanded;

  el.innerHTML = `
    <div style="padding:4px">
      ${vipBanner}

      <div style="background:#1a1a2e;border:1px solid #7c3aed;border-radius:8px;padding:16px;margin-bottom:12px">
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <div style="font-size:15px;font-weight:bold;color:#c4b5fd">👑 Status VIP — 30 dias</div>
            <div style="font-size:12px;color:#888;margin-top:4px">Inclui: bag 20 slots · cura grátis · 2 missões instantâneas/dia · 10 lutas arena/dia · 2 buffs simultâneos</div>
          </div>
          <span style="color:#a78bfa;font-weight:bold;font-size:14px">15 💎</span>
        </div>
        <div style="display:flex;align-items:center;gap:8px;margin-top:10px">
          <button onclick="buyVip()" ${!canBuyVip ? 'disabled style="opacity:.5"' : 'style="background:#7c3aed"'}>
            ${vipLabel}
          </button>
          ${!canBuyVip ? `<span style="font-size:12px;color:#888">Need 15 💎 (you have ${ss})</span>` : ''}
        </div>
      </div>

      <h4 style="color:#aaa;font-size:13px;margin:12px 0 8px">COMPRAS PERMANENTES</h4>

      <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:12px;margin-bottom:8px;
                  opacity:${bagExpanded || isVip ? '0.5' : '1'}">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <div style="font-size:13px;font-weight:bold">🎒 Expandir Bag (10→20 slots)</div>
            <div style="font-size:11px;color:#888">Permanente. Incluso no VIP.</div>
          </div>
          <span style="color:#a78bfa;font-size:13px">3 💎</span>
        </div>
        ${bagExpanded || isVip
          ? '<div style="color:#4caf50;font-size:12px;margin-top:6px">✓ Already active</div>'
          : `<button onclick="expandInventory()" style="margin-top:8px;font-size:12px" ${ss < 3 ? 'disabled style="opacity:.5"' : ''}>
               Comprar (3 💎)
             </button>`}
      </div>

      <div style="font-size:11px;color:#666;margin-top:12px;text-align:center">
        💎 Saldo atual: ${ss} SoulStone${ss !== 1 ? 's' : ''}
      </div>
      <div id="vipshop-msg" style="margin-top:8px;min-height:20px"></div>
    </div>`;
}

async function buyVip() {
  const r = await api('POST', '/api/vip/buy');
  if (r.error) {
    document.getElementById('vipshop-msg').innerHTML = `<span style="color:#f44336">${r.error}</span>`;
    return;
  }
  await loadWarrior();
  loadVipShop();
  document.getElementById('vipshop-msg').innerHTML = `<span style="color:#4caf50">👑 ${r.message}</span>`;
}

// ═══════════════════════════════════════════════════════════════════
// INSTANT QUEST (VIP)
// ═══════════════════════════════════════════════════════════════════

async function instantStartQuest(kingdom, questTypeId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/instant-start`, { questType: questTypeId });
  if (r.error) { worldMsg(r.error, false); return; }
  // Instantânea também pode encontrar monstro — usa o mesmo modal de resultado.
  showQuestResultModal(r);
  await enterKingdom(kingdom);
}

// VIP Heal (grátis, CD 10min)
async function vipHeal() {
  const data = await api('POST', '/api/temple/vip-heal');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadTemple();
}

// Resources tab in Commerce — shows all gathered items (fish, ores, gems, bars)
// (removido) loadResourcesInCommerce — recursos agora aparecem na bag unificada (Inventário V2)
