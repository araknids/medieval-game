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

  const hpColor = (warrior.hpPercent ?? 100) <= 0 ? '#cf6679'
                : (warrior.hpPercent ?? 100) < 50  ? '#c9a84c' : '#4caf82';

  document.getElementById('warrior-card').innerHTML = `
    <div class="warrior-name">${warrior.name}</div>
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
      <span class="label">${t('stat.evasion')}</span>
      <span class="value">${warrior.baseEvasion ?? warrior.evasionChance}%${
        (warrior.buffBonusEvasion ?? 0) > 0
          ? `<span style="color:#ffd700;font-size:.8em"> +${warrior.buffBonusEvasion}%</span>`
          : ''}</span>
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
    ${busy ? `<button class="btn-cancel-work" onclick="freeWarrior()" style="margin-top:.4rem;font-size:.72rem">
      🔓 ${t('status.free_btn')}
    </button>` : ''}`;
}

// ── Navegação de locais ──
function goTo(loc) {
  ['tavern','inventory','commerce','temple','zones','skills','work','tower','arena','guild','world','mail'].forEach(l => {
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
  if (loc === 'guild')     { loadGuild(); }
  if (loc === 'territory') { loadTerritories(); }
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
  document.getElementById('panel-shop').style.display  = tab === 'shop'  ? 'block' : 'none';
  document.getElementById('panel-sell').style.display  = tab === 'sell'  ? 'block' : 'none';
  document.getElementById('panel-smith').style.display = tab === 'smith' ? 'block' : 'none';
  document.getElementById('tab-shop').classList.toggle('active',  tab === 'shop');
  document.getElementById('tab-sell').classList.toggle('active',  tab === 'sell');
  document.getElementById('tab-smith').classList.toggle('active', tab === 'smith');
  if (tab === 'sell')  loadSellList();
  if (tab === 'smith') loadSmithingInCommerce();
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
              <h3 class="rarity-${i.rarity}">${i.name}</h3>
              <div class="shop-stats">${(t('item.type.'+i.type)||i.typeDisplay)} · ${(t('inventory.rarity.'+i.rarity)||i.rarityName)} · ${stats}</div>
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
  { id:'HELMET'  }, { id:'ARMOR'    },
  { id:'WEAPON'  }, { id:'SHIELD'   },
  { id:'PANTS'   }, { id:'BOOTS'    },
  { id:'GLOVES'  }, { id:'SHOULDER' },
  { id:'NECKLACE'}, { id:'RING'     },
];

const ATTR_INFO = {
  STRENGTH:     { icon: '⚔',  labelKey: 'attr.strength',     effectKey: 'attr.strength.effect' },
  DEXTERITY:    { icon: '🏹', labelKey: 'attr.dexterity',    effectKey: 'attr.dexterity.effect' },
  CONSTITUTION: { icon: '🛡',  labelKey: 'attr.constitution', effectKey: 'attr.constitution.effect' },
  LUCK:         { icon: '🍀', labelKey: 'attr.luck',         effectKey: 'attr.luck.effect' },
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
        <span class="attr-label">${t(info.labelKey)||info.labelKey}</span>
        <span class="attr-effect">${t(info.effectKey)||info.effectKey}</span>
        <span class="attr-val">${val}</span>
        <button class="btn-attr" ${pts <= 0 ? 'disabled' : ''} onclick="spendPoint('${id}')">+</button>
      </div>`;
  }).join('');

  el.innerHTML = `
    <div class="attr-section">
      <div class="attr-header">
        <span>${t('char.attributes')}</span>
        ${pts > 0 ? `<span class="attr-points-badge">⬆ ${t('char.points_available', {n: pts})}</span>` : ''}
      </div>
      ${rows}
      <div class="attr-stats-summary">
        ${t('stat.evasion')}: <strong>${warrior.evasionChance ?? 10}%</strong>
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
            <div class="slot-label">${t('inventory.slot.'+slot.id)}</div>
            <div class="slot-item-name rarity-${item.rarity}">${item.name}</div>
            <div class="slot-item-stats">${statsText(item)}</div>
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
  if (!bag.length) { bagEl.innerHTML = `<p style="color:#555;font-size:.8rem">${t('inventory.bag_empty')}</p>`; return; }
  bagEl.innerHTML = bag.map(item => `
    <div class="bag-item" style="flex-direction:column;align-items:flex-start;gap:.3rem">
      <div style="display:flex;justify-content:space-between;width:100%;align-items:center">
        <div>
          <div class="bag-item-name rarity-${item.rarity}">${item.name}</div>
          <div class="bag-item-type">${(t('item.type.'+item.type)||item.typeDisplay)} · ${(t('inventory.rarity.'+item.rarity)||item.rarityName)}</div>
          <div class="bag-item-stats">${statsText(item)}</div>
          ${item.sockets > 0 ? renderSockets(item) : ''}
        </div>
        <button class="btn-equip" onclick="equipItem(${item.id})">${t('inventory.btn.equip')}</button>
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
  const hpLabel   = data.isKnockedOut ? '💀 Inconsciente' : `❤ ${data.hpPercent}%`;
  const healLabel = data.healFree ? t('temple.heal_free_btn') : `${t('temple.heal_paid', {cost: fmtBronze(100)})}`;

  const buffActive = data.activeBuff
    ? `<div class="temple-buff-active">
        Bênção ativa: <strong>${data.activeBuff}</strong>
        — ${Math.floor(data.buffSecondsLeft / 60)}min restantes
       </div>`
    : `<div class="temple-buff-active" style="color:#888">${t('temple.no_buff')}</div>`;

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
        ${data.hpPercent >= 100 ? '✓ HP Cheio' : healLabel}
      </button>
    </div>

    <div class="sk-section">
      <div class="sk-title">${t('temple.buffs')}</div>
      ${buffActive}
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
          <span class="zone-name" style="color:${color}">${icon} ${t('zones.zone.'+z.id)||z.displayName}</span>
          ${locked ? `<span class="wj-lock">🔒 Lv.${z.minLevel}</span>` : ''}
          ${pvp ? `<span class="zone-pvp-badge">⚔ PvP</span>` : ''}
        </div>
        <p class="zone-desc">${z.description}</p>
        <div class="zone-stats">
          <span>×${z.multiplier} ${t('zones.multiplier')||'resources'}</span>
          ${z.npcEncounterChancePerHour > 0 ? `<span style="color:#c9a84c">🐉 ${z.npcEncounterChancePerHour}%/h NPC</span>` : ''}
          ${pvp ? `<span class="stamina-low">⚔ ${z.encounterChancePerHour}%/h PvP</span>` : ''}
        </div>
        ${!locked ? `
          <div class="zone-roles">
            <div class="zone-role-section">
              <div class="sk-title" style="margin-bottom:.4rem">🎣 ${t('zone.gathering_fish')||'Gather (Fishing)'}</div>
              <div class="sk-duration-btns">
                ${durations.map(d => `
                  <button class="btn-hour" ${busy ? 'disabled' : ''}
                          onclick="enterZone('${z.id}','GATHERING','FISHING',${d})">
                    ${d >= 60 ? d/60+'h' : d+'m'}
                  </button>`).join('')}
              </div>
            </div>
            <div class="zone-role-section" style="margin-top:.5rem">
              <div class="sk-title" style="margin-bottom:.4rem">⛏ ${t('zone.gathering_mine')||'Gather (Mining)'}</div>
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
              <div class="sk-title" style="margin-bottom:.4rem">🗡 ${t('zone.hunt_section')||'Hunt (Hunter)'}</div>
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
                state.skillType === 'FISHING' ? t('zone.active_fish') : t('zone.active_mine');

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
          ? `${t('zone.attacked', {name: state.attackerName})}`
          : state.attacked
          ? `${t('zone.survived', {name: state.attackerName})}`
          : ''}
      </p>
      <div style="display:flex;gap:.5rem;margin-top:.6rem;flex-wrap:wrap">
        <button class="btn-collect" id="zone-collect-btn"
                ${state.readyToCollect ? '' : 'disabled'}
                onclick="collectZone(${state.id})">
          ${state.readyToCollect ? t('zone.collect_btn') : t('zone.in_progress')}
        </button>
        ${!state.readyToCollect ? `
          <button class="btn-cancel-work" onclick="cancelZone(${state.id})">${t('btn.cancel')}</button>
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
        if (b) { b.disabled = false; b.textContent = t('zone.collect_btn'); }
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
        <button class="btn-send" onclick="loadZones()" style="margin-top:.8rem">${t('btn.back')}</button>
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
  if (!confirm(t('zone.cancel_confirm'))) return;
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
  SMALL_FISH:     '+10 stamina',
  SALMON:         '+25 stamina',
  TUNA:           '+40 stamina',
  SHARK:          '+60 stamina',
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
        <p class="sk-desc">${t('skills.duration')} (${t('skills.tab.fish').toLowerCase()})</p>
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
          <button class="btn-equip" onclick="consumeFish('${r.type}')">${t('btn.consume')||'Consume'}</button>
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
        <p class="sk-desc">${t('skills.duration')}</p>
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
        el.textContent = t('quest.ready_short');
        el.classList.add('done');
        document.getElementById('gathering-collect-btn').disabled = false;
        document.getElementById('gathering-collect-btn').textContent = t('zone.collect_btn');
        clearInterval(gatheringTimer);
      } else {
        el.textContent = formatTime(s);
      }
    }, 1000);
  }

  return `
    <div class="gathering-active-box">
      <div class="gathering-active-title">${t('skills.tab.'+(gatheringState.skillType||'').toLowerCase())||gatheringState.displayName} — ${gatheringState.durationMinutes}min</div>
      <div class="qp-timer ${done ? 'done' : ''}" id="gathering-timer">
        ${done ? t('quest.ready_short') : formatTime(secs)}
      </div>
      <div style="display:flex;gap:.5rem;margin-top:.5rem">
        <button class="btn-collect" id="gathering-collect-btn"
                ${done ? '' : 'disabled'}
                onclick="collectGathering(${gatheringState.id})">
          ${done ? t('zone.collect_btn') : t('skills.in_progress')}
        </button>
        ${!done ? `<button class="btn-cancel-work" onclick="cancelGathering(${gatheringState.id})">${t('btn.cancel')}</button>` : ''}
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
      ${refineHtml || `<p style="color:#888;font-size:.8rem">${t('skills.no_recipes')}</p>`}
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
  if (!confirm(t('skills.cancel_confirm'))) return;
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
                <span>${t('work.hours') || 'Hours:'}</span>
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
        t.textContent = 'Concluído!';
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
              <td class="rank-name">${r.warriorName}</td>
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
      <thead><tr><th>#</th><th>${t('tower.col.warrior')}</th><th>${t('tower.col.floor')}</th></tr></thead>
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
          Cost: <span class="stamina-cost">⚡ 25 stamina</span> &nbsp;·&nbsp; ${t('tower.your_stamina')} <strong>${stamina}/100</strong>
        </p>
        <p style="color:#888;font-size:.83rem;margin-bottom:.8rem">
          Batalha dura 1 minuto. Vitória: +25 rank, ${fmtBronze(200)}.
        </p>
        <button class="btn-fight" ${noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''} onclick="startFight()">
          ${noStamina ? t('tower.no_stamina') : '⚔ Lutar'}
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
        te.textContent = t('quest.ready_short');
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
      <td>${m.warriorName}${badge}${m.isMe ? ' <em>(you)</em>' : ''}</td>
      <td style="text-align:right">${kickBtn} ${transferBtn}</td>
    </tr>`;
  }).join('');

  el.innerHTML = `
    <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px;margin-bottom:12px">
      <h3 style="margin:0 0 4px">${g.name} <span style="font-size:12px;color:#aaa">Lv.${g.level}</span></h3>
      <p style="color:#aaa;margin:0 0 8px;font-size:13px">${g.description || 'No description.'}</p>
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
      <td style="padding:4px 0">${medal} ${r.warriorName}${r.isMe ? ' (you)' : ''}</td>
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
            <strong>${g.name}</strong> <span style="font-size:11px;color:#aaa">Nv.${g.level}</span><br>
            <span style="font-size:12px;color:#888">${g.description || ''}</span><br>
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

    const bonusLabel = ter.territory === 'FORTALEZA_MALDITA' ? t('territory.bonus.quest_xp')
                     : ter.territory === 'MINAS_DE_FERRO_NEGRO' ? t('territory.bonus.mining')
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
  const el = document.getElementById('territory-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

async function territoryDeclare(territory) {
  const r = await api('POST', `/api/territory/${territory}/declare`);
  if (r.error) { territoryMsg(r.error, false); return; }
  territoryMsg(r.message);
  await loadTerritories();
}

async function territoryCancel() {
  const r = await api('POST', '/api/territory/cancel');
  if (r.error) { territoryMsg(r.error, false); return; }
  territoryMsg(r.message);
  await loadTerritories();
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
            <strong style="color:${m.isRead ? '#ccc' : '#fff'}">${m.from}</strong>
            ${!m.isRead ? '<span style="color:#5c6bc0;font-size:.75em;margin-left:6px">● NEW</span>' : ''}
            ${m.goldAmount > 0 && !m.isCollected ? '<span style="color:#ffd700;font-size:.75em;margin-left:6px">💰 ' + m.goldAmount + ' gold</span>' : ''}
            <div style="color:#888;font-size:.8em;margin-top:2px">
              ${m.message.length > 60 ? m.message.substring(0, 60) + '…' : m.message}
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

  panel.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:flex-start">
      <strong>From: ${r.from}</strong>
      <button onclick="mailDelete(${id})" style="background:#333;font-size:11px;padding:3px 8px">🗑 Delete</button>
    </div>
    <div style="color:#888;font-size:.75em;margin:.3rem 0">${r.sentAt.substring(0, 16).replace('T', ' ')}</div>
    <div style="background:#111;border-radius:4px;padding:10px;margin-top:8px;
                white-space:pre-wrap;font-size:13px;line-height:1.5">${r.message}</div>
    ${goldBtn}
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
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

// ═══════════════════════════════════════════════════════════════════
// WORLD — 3 KINGDOMS
// ═══════════════════════════════════════════════════════════════════

let worldCurrentKingdom = null;

async function loadWorld() {
  const el = document.getElementById('world-content');
  el.innerHTML = '<p>Loading...</p>';
  try {
    const kingdoms = await api('GET', '/api/world');
    renderWorldOverview(kingdoms);
  } catch(e) {
    el.innerHTML = '<p style="color:red">Error loading world.</p>';
  }
}

function renderWorldOverview(kingdoms) {
  const el = document.getElementById('world-content');
  const ZONE_LABELS = {
    FISHING: ['Safe Shore','Wild Coast','Deep Sea'],
    MINING:  ['Open Mine','Deep Tunnels','Forbidden Mines'],
    COMBAT:  ['Training Hall','Battlefield','War Zone']
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

    return `<div onclick="enterKingdom('${k.kingdom}')" style="background:#1a1a2e;border:1px solid ${k.isMine ? '#4caf50' : '#444'};border-radius:10px;padding:16px;margin-bottom:12px;cursor:pointer">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <div>
          <h3 style="margin:0 0 4px;font-size:16px">${k.icon} ${k.displayName}</h3>
          ${ctrl}${bonus}
        </div>
        <div style="text-align:right;font-size:11px;color:#666">Next war<br><strong style="color:#eee">${secsH}h ${secsM}m</strong></div>
      </div>
      <p style="color:#888;font-size:12px;margin:8px 0 0">${k.lore}</p>
      <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap">${zoneHtml}</div>
    </div>`;
  }).join('');

  el.innerHTML = cards + '<div id="kingdom-detail" style="margin-top:16px"></div>';
}

async function enterKingdom(kingdom) {
  worldCurrentKingdom = kingdom;
  const el = document.getElementById('kingdom-detail');
  if (!el) return;
  el.innerHTML = '<p>Loading kingdom...</p>';
  try {
    const [quests, activeQuests, training, gatherSession, zoneSession] = await Promise.all([
      api('GET', `/api/world/${kingdom}/quests`),
      api('GET', `/api/world/${kingdom}/quests/active`),
      kingdom === 'COMBAT' ? api('GET', '/api/world/COMBAT/training') : Promise.resolve(null),
      (kingdom === 'FISHING' || kingdom === 'MINING') ? api('GET', '/api/gathering/current') : Promise.resolve(null),
      (kingdom === 'FISHING' || kingdom === 'MINING') ? api('GET', '/api/zones/current') : Promise.resolve(null)
    ]);
    renderKingdomDetail(kingdom, quests, activeQuests, training, gatherSession, zoneSession);
  } catch(e) {
    el.innerHTML = '<p style="color:red">Error loading kingdom.</p>';
  }
}

function renderKingdomDetail(kingdom, quests, activeQuests, training, gatherSession, zoneSession) {
  const el = document.getElementById('kingdom-detail');
  const NAMES = { FISHING:'Desfiladeiro do Osso', MINING:'Minas de Ferro Negro', COMBAT:'Fortaleza Maldita' };
  const ICONS = { FISHING:'🎣', MINING:'⛏', COMBAT:'⚔' };

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
          <strong style="color:#7986cb">🏋 Training in Progress</strong>
          <div style="font-size:13px;color:#aaa;margin-top:4px">+${training.xpReward} XP · ${Math.floor(training.secondsRemaining/60)}m remaining</div>
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
          <p style="font-size:12px;color:#888;margin:4px 0 8px">Pay bronze to earn pure XP. Cost: ${lvl*10} bronze/h · Reward: ${lvl*25} XP/h</p>
          <div style="display:flex;gap:6px;flex-wrap:wrap">
            ${[1,2,4,6,8,12].map(h => `<button onclick="startTraining(${h})" style="font-size:12px">${h}h</button>`).join('')}
          </div>
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

  // Gathering section for FISHING and MINING kingdoms — 3 zones per kingdom
  let gatheringHtml = '';
  if (kingdom === 'FISHING' || kingdom === 'MINING') {
    const skillType = kingdom === 'FISHING' ? 'FISHING' : 'MINING';
    const wLevel    = warrior ? warrior.level : 1;

    const zones = kingdom === 'FISHING' ? [
      { name:'🏖 Safe Shore',   minLv:1,  pvp:false, durations:[5,10,20,30,40],    zone:null,        color:'#4caf50', desc:'Safe fishing — no PvP' },
      { name:'🌊 Wild Coast',   minLv:10, pvp:true,  durations:[30,60,180,360,720], zone:'PVP',       color:'#ffc107', desc:'PvP zone — hunters may attack' },
      { name:'🦈 Deep Sea',     minLv:20, pvp:true,  durations:[30,60,180,360,720], zone:'HIGH_RISK', color:'#ef5350', desc:'High risk — rare fish, PvP + monsters. Items at stake!' }
    ] : [
      { name:'⛏ Open Mine',      minLv:1,  pvp:false, durations:[10,20,30,45,60],    zone:null,        color:'#4caf50', desc:'Safe mining — no PvP' },
      { name:'🪨 Deep Tunnels',  minLv:10, pvp:true,  durations:[30,60,180,360,720], zone:'PVP',       color:'#ffc107', desc:'PvP zone — hunters may attack' },
      { name:'💎 Forbidden Mines',minLv:20, pvp:true,  durations:[30,60,180,360,720], zone:'HIGH_RISK', color:'#ef5350', desc:'High risk — rare ores, PvP + monsters. Items at stake!' }
    ];

    // Check if warrior is busy based on fresh API data (not stale warrior cache)
    // Only block PvP zones for active gathering/zone/kingdom-quest sessions
    const isBusy = (gatherSession && gatherSession.active)
                || (zoneSession   && zoneSession.active)
                || activeQuests.length > 0;

    gatheringHtml = zones.map(z => {
      const locked = wLevel < z.minLv;
      const busyAndPvp = isBusy && z.pvp; // can't start PvP while busy
      return `
        <div style="background:#1a1a2e;border:1px solid ${locked?'#333':z.color+'44'};border-radius:8px;padding:12px;margin-bottom:8px;opacity:${locked?'0.5':'1'}">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <strong style="color:${z.color}">${z.name}</strong>
            ${locked ? `<span style="font-size:11px;color:#888">🔒 Lv.${z.minLv}+</span>` : z.pvp ? '<span style="font-size:11px;color:#ef5350">⚔ PvP</span>' : '<span style="font-size:11px;color:#4caf50">✓ Safe</span>'}
          </div>
          <p style="font-size:11px;color:#888;margin:3px 0 6px">${z.desc}</p>
          ${locked ? '<p style="font-size:11px;color:#555;margin:0">Reach level '+z.minLv+' to unlock.</p>'
            : busyAndPvp ? '<p style="font-size:11px;color:#e57373;margin:0">⚠ Warrior busy — collect or cancel the active session above first.</p>'
            : `<div style="display:flex;gap:5px;flex-wrap:wrap">
            ${z.durations.map(d => {
              const label = d >= 60 ? (d/60)+'h' : d+'min';
              const onclick = z.zone
                ? `enterKingdomZone('${z.zone}','${skillType}',${d})`
                : `startKingdomGathering('${skillType}',${d})`;
              return `<button onclick="${onclick}" style="font-size:11px;padding:3px 8px">${label}</button>`;
            }).join('')}
          </div>`}
        </div>`;
    }).join('');
  }

  const questCards = quests.map(q => {
    const busy = activeQuests.length > 0;
    const disabled = busy || !q.canStart;
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
        <button onclick="startKingdomQuest('${kingdom}','${q.id}')"
          ${disabled ? 'disabled style="opacity:.5"' : ''}
          style="margin-top:8px;font-size:12px">
          ${busy ? 'Warrior busy' : !q.canStart ? 'Low stamina' : 'Start Quest'}
        </button>
      </div>`;
  }).join('');

  el.innerHTML = `
    <div style="background:#111;border-radius:10px;padding:16px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <h3 style="margin:0">${ICONS[kingdom]} ${NAMES[kingdom]}</h3>
        <button onclick="document.getElementById('kingdom-detail').innerHTML=''" style="background:#333;font-size:12px">✕ Close</button>
      </div>
      ${activeHtml}
      ${activeGatherHtml}
      ${trainingHtml}
      <h4 style="margin:0 0 8px;color:#aaa;font-size:13px">QUESTS</h4>
      ${questCards}
      ${gatheringHtml}
      <div id="world-msg" style="margin-top:8px;min-height:20px"></div>
    </div>`;
}

function worldMsg(text, ok = true) {
  const el = document.getElementById('world-msg');
  if (el) el.innerHTML = `<span style="color:${ok ? '#4caf50' : '#f44336'}">${text}</span>`;
}

async function startKingdomQuest(kingdom, questTypeId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/start`, { questType: questTypeId });
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Quest started! Return when the timer ends.');
  await enterKingdom(kingdom);
}

async function collectKingdomQuest(kingdom, questId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/${questId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  const drop = r.droppedItem ? ' · Item: ' + r.droppedItem.name : '';
  worldMsg(`Collected! ${fmtBronze(r.bronzeEarned)} · +${r.xpEarned} XP${drop}`);
  await enterKingdom(kingdom);
  loadWarrior();
}

async function abandonKingdomQuest(kingdom, questId) {
  if (!confirm('Abandon quest? You receive no reward.')) return;
  const r = await api('POST', `/api/world/${kingdom}/quests/${questId}/abandon`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Quest abandoned.');
  await enterKingdom(kingdom);
  loadWarrior();
}

async function startTraining(hours) {
  const r = await api('POST', '/api/world/COMBAT/training/start', { hours });
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg(`Training started! ${hours}h · +${r.xpReward} XP on completion.`);
  await enterKingdom('COMBAT');
  loadWarrior();
}

async function cancelTraining(sessionId) {
  if (!confirm('Cancel training? You will not receive any XP.')) return;
  const r = await api('POST', `/api/world/COMBAT/training/${sessionId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg(r.message);
  await enterKingdom('COMBAT');
  loadWarrior();
}

async function collectTraining(sessionId) {
  const r = await api('POST', `/api/world/COMBAT/training/${sessionId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg(r.message);
  await enterKingdom('COMBAT');
  loadWarrior();
}

// Safe zone gathering: /api/gathering/start
async function startKingdomGathering(skillType, durationMinutes) {
  const r = await api('POST', '/api/gathering/start', { skillType, durationMinutes });
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg(`${skillType === 'FISHING' ? 'Fishing' : 'Mining'} started! ${durationMinutes}min session.`);
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}

// PvP / High-Risk zone gathering: /api/zones/enter (Gatherer role)
async function enterKingdomZone(zone, skillType, durationMinutes) {
  const r = await api('POST', '/api/zones/enter', {
    zone,
    role: 'GATHERING',
    skillType,
    durationMinutes
  });
  if (r.error) { worldMsg(r.error, false); return; }
  const label = zone === 'HIGH_RISK' ? 'High Risk' : 'PvP';
  worldMsg(`Entered ${label} zone! ${skillType === 'FISHING' ? 'Fishing' : 'Mining'} for ${durationMinutes >= 60 ? durationMinutes/60+'h' : durationMinutes+'min'}. Watch out for hunters!`);
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}

// Kingdom gathering session helpers
async function collectKingdomGather(sessionId) {
  const r = await api('POST', `/api/gathering/${sessionId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Gathering collected!');
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}

async function cancelKingdomGather(sessionId) {
  if (!confirm('Cancel gathering session? You lose all collected resources.')) return;
  const r = await api('POST', `/api/gathering/${sessionId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Gathering cancelled.');
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}

async function collectKingdomZoneSession(activityId) {
  const r = await api('POST', `/api/zones/${activityId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Expedition loot collected!');
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}

async function cancelKingdomZoneSession(activityId) {
  if (!confirm('Cancel expedition? You lose all resources gathered so far.')) return;
  const r = await api('POST', `/api/zones/${activityId}/cancel`);
  if (r.error) { worldMsg(r.error, false); return; }
  worldMsg('Expedition cancelled.');
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
  loadWarrior();
}
