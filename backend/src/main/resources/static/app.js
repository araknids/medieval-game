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

// Countdown longo (reset de daily quest, até 12h): "7h 12m" / "45m". [DAILY_QUESTS]
function fmtResetCountdown(seconds) {
  seconds = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
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
  // Sempre atualiza as skills — exibidas no card do personagem (nível de Pesca/Mineração/Garimpo/Forja). [PROFISSAO]
  if (token) {
    const s = await api('GET', '/api/gathering/skills');
    if (Array.isArray(s)) skillsData = s;
  }

  document.getElementById('hdr-username').textContent = warrior.name;
  document.getElementById('hdr-currency').innerHTML =
    formatCurrency(warrior.bronze ?? 0, warrior.silver ?? 0, warrior.gold ?? 0);
  document.getElementById('hdr-rank').textContent = (warrior.rankPoints ?? '–') + ' pts';

  const xpPct = Math.floor((warrior.experience / warrior.expNeeded) * 100);
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

  // Montaria equipada (Estábulo): −X% estamina + stats. [ESTABULO]
  const mountLine = warrior.equippedMount ? (() => {
    const m = warrior.equippedMount;
    const stats = [
      m.attackBonus  > 0 ? `+${m.attackBonus} ATK`  : '',
      m.defenseBonus > 0 ? `+${m.defenseBonus} DEF` : '',
      m.healthBonus  > 0 ? `+${m.healthBonus} HP`   : '',
    ].filter(Boolean).join(' ');
    return `<div style="margin-top:.3rem;font-size:.75rem;padding:3px 6px;
                         background:#13241f;border:1px solid #4db8a8;border-radius:4px;
                         color:#7fd1b9;display:inline-block">
              ${m.icon} ${m.displayName}
              <span style="color:#aaa;font-size:.7em">−${m.staminaReductionPct}% ⚡${stats ? ' · ' + stats : ''}</span>
            </div>`;
  })() : '';

  // Pet equipado (companheiro). [PETS]
  const petLine = warrior.equippedPet ? (() => {
    const p = warrior.equippedPet;
    const bonus = [
      p.hpBonusPercent > 0 ? `+${p.hpBonusPercent}% HP` : '',
      p.dexBonus       > 0 ? `+${p.dexBonus} AGI`       : '',
    ].filter(Boolean).join(' · ');
    return `<div style="margin-top:.3rem;font-size:.75rem;padding:3px 6px;
                         background:#241a0f;border:1px solid #d8a14d;border-radius:4px;
                         color:#e0b878;display:inline-block">
              ${p.icon} ${p.displayName}
              <span style="color:#aaa;font-size:.7em">${bonus}</span>
            </div>`;
  })() : '';

  // [ELEMENTOS] Selos de encantamento ativo (arma/armadura) + tempo restante.
  const ELEM_ICON = { FIRE:'🔥', WATER:'💧', EARTH:'🪨', AIR:'💨' };
  const enchantLine = (() => {
    const seal = (label, elem, secs) => !elem ? '' :
      `<span style="font-size:.72rem;padding:2px 6px;margin-right:4px;background:#1a2433;border:1px solid #4a7;border-radius:4px;color:#bfe">
         ${label}${ELEM_ICON[elem]||''} ${elem} <span style="color:#888">${Math.floor((secs||0)/60)}m</span></span>`;
    const w = seal('🗡', warrior.weaponElement, warrior.weaponElementSecondsLeft);
    const a = seal('🛡', warrior.armorElement,  warrior.armorElementSecondsLeft);
    return (w || a) ? `<div style="margin-top:.3rem">${w}${a}</div>` : '';
  })();

  const hpColor      = (warrior.hpPercent ?? 100) <= 0 ? '#cf6679'
                     : (warrior.hpPercent ?? 100) < 50  ? '#c9a84c' : '#4caf82';
  const staminaColor = stamina < 30 ? '#cf6679' : stamina < 60 ? '#c9a84c' : '#4caf82';

  // Profissões (Pesca/Mineração/Garimpo/Forja) — nível + onde o próximo tier desbloqueia. [PROFISSAO]
  const skillsHtml = skillsData.length ? `
    <div style="margin-top:.5rem;border-top:1px solid #333;padding-top:.4rem">
      <div style="font-size:.72rem;color:#888;margin-bottom:.2rem">⚒ Professions</div>
      ${skillsData.map(s => `
        <div class="warrior-stat-row" style="font-size:.8rem">
          <span class="label">${s.icon} ${s.displayName}</span>
          <span class="value">Lv.${s.level}${(s.nextTierLevel ?? 0) > 0
            ? ` <span style="color:#ffd700;font-size:.72em" title="Next resource tier at Lv.${s.nextTierLevel}">🔓${s.nextTierLevel}</span>` : ''}</span>
        </div>`).join('')}
    </div>` : '';

  document.getElementById('warrior-card').innerHTML = `
    <div class="warrior-name">${escapeHtml(warrior.name)}</div>
    <div class="warrior-class">${warrior.warriorClass}</div>
    ${warrior.warriorClassId === 'RECRUIT' ? ((warrior.level ?? 1) >= 10
      ? `<div onclick="openClassTrial()" style="margin:.3rem 0;padding:5px 8px;background:#2a1a3a;border:1px solid #a855f7;border-radius:6px;color:#d8b4fe;font-size:.78rem;cursor:pointer;text-align:center;font-weight:600">⚔ Choose your Path</div>`
      : `<div style="margin:.3rem 0;padding:4px 8px;background:#1a1a2a;border:1px dashed #555;border-radius:6px;color:#888;font-size:.72rem;text-align:center">⚔ Choose your path at level 10</div>`) : ''}
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
    ${mountLine}
    ${petLine}
    ${enchantLine}

    <div style="margin-top:.4rem">
      ${warrior.isKnockedOut
        ? `<span class="status-badge status-busy">💀 ${t('status.knocked_out')}</span>`
        : `<span class="status-badge status-available">✓ ${t('status.available')}</span>`}
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
    ${skillsHtml}
    ${warrior.isVip ? `<div style="font-size:.72rem;background:#3b0764;color:#c4b5fd;padding:2px 6px;border-radius:4px;margin-top:.3rem;display:inline-block">
      👑 VIP${warrior.vipExpiresAt ? ' · ' + warrior.vipExpiresAt.substring(0,10) : ''}
    </div>` : ''}
    ${(warrior.soulStones ?? 0) > 0 ? `<div style="font-size:.72rem;color:#a78bfa;margin-top:.2rem;font-weight:600">
      💎 ${warrior.soulStones} SoulStone${warrior.soulStones !== 1 ? 's' : ''}
    </div>` : ''}`;
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
  if (loc === 'inventory'){ renderAttributes(); loadAbilities(); loadInventory(); }
  if (loc === 'work')     { loadWork(); }
  if (loc === 'guild')     { loadGuild(); }
  if (loc === 'world')      { loadWorld(); }
  if (loc === 'mail')      { loadMail(); }
}

// ── COMÉRCIO: loja ──
function switchCommerceTab(tab) {
  document.getElementById('panel-shop').style.display      = tab === 'shop'      ? 'block' : 'none';
  document.getElementById('panel-sell').style.display      = tab === 'sell'      ? 'block' : 'none';
  document.getElementById('panel-smith').style.display     = tab === 'smith'     ? 'block' : 'none';
  document.getElementById('panel-cooking').style.display   = tab === 'cooking'   ? 'block' : 'none';
  document.getElementById('panel-estabulo').style.display  = tab === 'estabulo'  ? 'block' : 'none';
  document.getElementById('panel-vipshop').style.display   = tab === 'vipshop'   ? 'block' : 'none';
  document.getElementById('panel-auction').style.display   = tab === 'auction'   ? 'block' : 'none';
  document.getElementById('tab-shop').classList.toggle('active',      tab === 'shop');
  document.getElementById('tab-sell').classList.toggle('active',      tab === 'sell');
  document.getElementById('tab-smith').classList.toggle('active',     tab === 'smith');
  document.getElementById('tab-cooking').classList.toggle('active',   tab === 'cooking');
  document.getElementById('tab-estabulo').classList.toggle('active',  tab === 'estabulo');
  document.getElementById('tab-vipshop').classList.toggle('active',   tab === 'vipshop');
  document.getElementById('tab-auction').classList.toggle('active',   tab === 'auction');
  if (tab === 'sell')      loadSellList();
  if (tab === 'smith')     loadSmithingInCommerce();
  if (tab === 'cooking')   loadCooking();
  if (tab === 'estabulo')  loadEstabulo();
  if (tab === 'vipshop')   loadVipShop();
  if (tab === 'auction')   loadAuctionHouse();
}

// ── Casa de Leilão (Auction House) — preço fixo (buyout). [LEILAO] ──
function aucTimeLeft(s) {
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600);
  return d > 0 ? `${d}d ${h}h` : `${h}h ${Math.floor((s % 3600) / 60)}m`;
}
function aucCard(a, isMineSection) {
  const stats = [a.attackBonus > 0 ? `+${a.attackBonus} ATK` : '', a.defenseBonus > 0 ? `+${a.defenseBonus} DEF` : '',
                 a.healthBonus > 0 ? `+${a.healthBonus} HP` : ''].filter(Boolean).join(' · ');
  const action = isMineSection
    ? `<button class="btn-buy" style="background:#8b0000" onclick="auctionCancel(${a.listingId})">Cancel</button>`
    : `<button class="btn-buy" ${a.isMine ? 'disabled style="opacity:.5"' : ''} onclick="auctionBuy(${a.listingId})">Buy</button>`;
  return `
    <div class="shop-card">
      <div class="shop-item-info">
        <h3 class="rarity-${a.rarity}">${escapeHtml(a.name)} ${a.sockets ? `<span style="color:#888;font-size:.7em">◇${a.sockets}</span>` : ''}</h3>
        <div class="shop-stats">${escapeHtml(a.typeDisplay)}${stats ? ' · ' + stats : ''} · 🔧${a.durability}% · ⏳ ${aucTimeLeft(a.secondsLeft)}</div>
        ${a.affixes && a.affixes.length ? `<div style="font-size:.7rem;color:#8bc34a">${a.affixes.map(escapeHtml).join(' · ')}</div>` : ''}
        <div style="font-size:.7rem;color:#888">Seller: ${escapeHtml(a.sellerName)}${isMineSection ? ` · you get ${fmtBronze(a.sellerPayout)} on sale` : ''}</div>
      </div>
      <span class="shop-price">${fmtBronze(a.price)}</span>
      ${action}
    </div>`;
}

async function loadAuctionHouse() {
  const el = document.getElementById('auction-content');
  el.innerHTML = '<p>Loading...</p>';
  const [listings, mine, inv] = await Promise.all([
    api('GET', '/api/auction'),
    api('GET', '/api/auction/mine'),
    api('GET', '/api/inventory')
  ]);
  const myBag = (Array.isArray(inv) ? inv : []).filter(i => !i.equipped);

  const browseHtml = (Array.isArray(listings) && listings.length)
    ? listings.map(a => aucCard(a, false)).join('')
    : '<p style="color:#888;font-size:.82rem">No items listed right now.</p>';
  const mineHtml = (Array.isArray(mine) && mine.length)
    ? mine.map(a => aucCard(a, true)).join('')
    : '<p style="color:#888;font-size:.82rem">You have no active listings.</p>';
  const pickerHtml = myBag.length ? myBag.map(i => `
    <div class="shop-card">
      <div class="shop-item-info">
        <h3 class="rarity-${i.rarity}">${escapeHtml(i.name)}</h3>
        <div class="shop-stats">${(t('item.type.' + i.type) || i.typeDisplay)} · ${statsText(i)}</div>
      </div>
      <input id="auc-price-${i.id}" type="number" min="1" placeholder="price"
        style="width:90px;padding:4px;background:#111;color:#eee;border:1px solid #555;border-radius:4px">
      <button class="btn-buy" onclick="auctionList(${i.id})">List</button>
    </div>`).join('') : '<p style="color:#888;font-size:.82rem">No items to list.</p>';

  el.innerHTML = `
    <div style="font-size:.75rem;color:#888;margin-bottom:8px">Fixed-price market. Fee: 5% upfront (kept) + 15% on sale → you get 80%. Listings last 2 days, max 10.</div>
    <h4 style="margin:6px 0">🛒 Browse</h4>${browseHtml}
    <h4 style="margin:14px 0 6px">📋 My listings (${Array.isArray(mine) ? mine.length : 0}/10)</h4>${mineHtml}
    <h4 style="margin:14px 0 6px">➕ List an item</h4>${pickerHtml}
    <div id="auction-msg" style="margin-top:8px;min-height:18px"></div>`;
}

async function auctionBuy(id) {
  const r = await api('POST', `/api/auction/buy/${id}`);
  if (r.error) { const m = document.getElementById('auction-msg'); if (m) m.innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();
  await loadAuctionHouse();
  const m = document.getElementById('auction-msg'); if (m) m.innerHTML = `<span style="color:#4caf50">✅ ${r.message}</span>`;
}

async function auctionCancel(id) {
  if (!confirm('Cancel this listing? (the 5% fee is not refunded)')) return;
  const r = await api('POST', `/api/auction/cancel/${id}`);
  if (r.error) { const m = document.getElementById('auction-msg'); if (m) m.innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadAuctionHouse();
}

async function auctionList(itemId) {
  const price = parseInt(document.getElementById(`auc-price-${itemId}`)?.value);
  const m = document.getElementById('auction-msg');
  if (!price || price < 1) { if (m) m.innerHTML = '<span style="color:#f44336">Enter a valid price.</span>'; return; }
  const r = await api('POST', '/api/auction/list', { itemId, price });
  if (r.error) { if (m) m.innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();
  await loadAuctionHouse();
  const m2 = document.getElementById('auction-msg'); if (m2) m2.innerHTML = `<span style="color:#4caf50">✅ Listed! (5% fee charged)</span>`;
}

// ── Estábulo: montarias que reduzem estamina (ver docs/PLANO_ESTABULO.md) ──
async function loadEstabulo() {
  const el = document.getElementById('estabulo-content');
  el.innerHTML = '<p>Loading stable...</p>';
  const data = await api('GET', '/api/stable');
  if (!data || data.error) { el.innerHTML = `<p style="color:#cf6679">${data?.error || 'Failed to load.'}</p>`; return; }

  const gold = data.gold ?? 0;
  const cards = data.mounts.map(m => {
    // Buy: gold (Stable) or — for the VIP mount — a note pointing to the VIP Shop
    let action;
    if (m.equipped) {
      action = `<button onclick="unequipMount()" style="font-size:12px;background:#555">Unequip</button>
                <span style="color:#4caf50;font-size:12px;margin-left:6px">✓ Equipped</span>`;
    } else if (m.owned) {
      action = `<button onclick="equipMount('${m.id}')" style="font-size:12px;background:#2e7d32">Equip</button>`;
    } else if (m.vipOnly) {
      action = `<span style="font-size:12px;color:#a78bfa">💎 ${m.priceSoulStones} · buy it in the 💎 VIP Shop tab</span>`;
    } else {
      const canBuy = gold >= m.priceGold;
      action = `<button onclick="buyMount('${m.id}')" ${canBuy ? '' : 'disabled style="opacity:.5"'} style="font-size:12px">
                  Buy · ${m.priceGold} 🪙
                </button>`;
    }
    const border = m.equipped ? '#2e7d32' : m.owned ? '#555' : m.vipOnly ? '#7c3aed' : '#333';
    const mstats = [
      m.attackBonus  > 0 ? `<span style="color:#e57373">+${m.attackBonus} ATK</span>`  : '',
      m.defenseBonus > 0 ? `<span style="color:#64b5f6">+${m.defenseBonus} DEF</span>` : '',
      m.healthBonus  > 0 ? `<span style="color:#81c784">+${m.healthBonus} HP</span>`   : '',
    ].filter(Boolean).join(' &nbsp; ');
    const statsLine = mstats
      ? `<div style="font-size:12px;margin-top:4px">${mstats}</div>`
      : `<div style="font-size:11px;color:#777;margin-top:4px">No stat bonus — pure stamina</div>`;
    return `
      <div style="background:#1a1a2e;border:1px solid ${border};border-radius:8px;padding:12px;margin-bottom:8px${m.owned && !m.equipped ? ';opacity:.85' : ''}">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong style="font-size:14px">${m.icon} ${m.displayName}${m.vipOnly ? ' <span style="color:#a78bfa;font-size:11px">VIP</span>' : ''}</strong>
          <span style="color:#7fd1b9;font-size:13px;font-weight:bold">−${m.staminaReductionPct}% ⚡</span>
        </div>
        ${statsLine}
        <div style="margin-top:8px">${action}</div>
      </div>`;
  }).join('');

  el.innerHTML = `
    <div style="padding:4px">
      <p style="font-size:12px;color:#888;margin:0 0 10px">
        Equip a mount to spend <strong>less stamina</strong> on actions (quests, zones, work, tower, arena).
        You own the horse forever — equip whichever you like. Balance: <strong>${gold} 🪙</strong>
      </p>
      ${cards}
    </div>`;
}

async function buyMount(mountType) {
  const r = await api('POST', `/api/stable/buy/${mountType}`);
  if (r.error) { showMessage(r.error, true); return; }
  showMessage(r.message || 'Mount bought!');
  await Promise.all([loadWarrior(), loadEstabulo()]);
}

async function equipMount(mountType) {
  const r = await api('POST', `/api/stable/equip/${mountType}`);
  if (r.error) { showMessage(r.error, true); return; }
  showMessage(r.message || 'Mount equipped!');
  await loadEstabulo();
}

async function unequipMount() {
  const r = await api('POST', '/api/stable/unequip');
  if (r.error) { showMessage(r.error, true); return; }
  await loadEstabulo();
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

  // [POSTURE] Seletor de postura de combate (tradeoff ATK/DEF, vale em todo combate).
  const cur = warrior.combatPosture ?? 'BALANCED';
  const POSTURES = [
    { id:'OFFENSIVE', icon:'⚔️', label:'Offensive', desc:'+20% ATK · −15% DEF' },
    { id:'BALANCED',  icon:'⚖️', label:'Balanced',  desc:'+5% ATK · +5% DEF'  },
    { id:'DEFENSIVE', icon:'🛡️', label:'Defensive', desc:'−15% ATK · +20% DEF' },
  ];
  const postureBtns = POSTURES.map(p => `
    <button onclick="setPosture('${p.id}')" ${p.id === cur ? 'disabled' : ''}
      style="flex:1;min-width:0;padding:6px 4px;border-radius:6px;cursor:${p.id===cur?'default':'pointer'};
             border:1px solid ${p.id===cur?'#ffd700':'#444'};background:${p.id===cur?'#2a2a40':'#1a1a2e'};color:#eee">
      <div style="font-size:1.1rem">${p.icon}</div>
      <div style="font-weight:bold;font-size:.78rem">${p.label}</div>
      <div style="color:#999;font-size:.66rem">${p.desc}</div>
    </button>`).join('');

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
    </div>
    <div class="attr-section" style="margin-top:10px">
      <div class="attr-header"><span>⚔ Combat Stance</span></div>
      <div style="font-size:.72rem;color:#888;margin:2px 0 6px">Applies to all combat (PvE & PvP). Free to switch.</div>
      <div style="display:flex;gap:6px;text-align:center">${postureBtns}</div>
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

// [POSTURE] Troca a postura de combate (toggle livre).
async function setPosture(postureId) {
  const data = await api('POST', `/api/warrior/posture/${postureId}`);
  if (data.error) { showMessage(data.error, true); return; }
  warrior = data;
  renderAttributes();
  await loadWarrior();
}

// [HABILIDADES] Árvore de habilidades da classe (passivas + ativas).
async function loadAbilities() {
  const el = document.getElementById('abilities-panel');
  if (!el) return;
  const data = await api('GET', '/api/abilities');
  if (data.error) { el.innerHTML = ''; return; }
  if (!data.abilities || data.abilities.length === 0) {
    el.innerHTML = `
      <div class="attr-section" style="margin-top:10px">
        <div class="attr-header"><span>✨ Abilities</span></div>
        <div style="font-size:.78rem;color:#888;padding:4px 0">
          Choose a class (Path Trial at Lv.10) to unlock its abilities. You have
          <strong>${data.abilityPoints}</strong> banked ability point${data.abilityPoints!==1?'s':''}.
        </div>
      </div>`;
    return;
  }
  const pts = data.abilityPoints ?? 0;
  const rows = data.abilities.map(a => {
    const maxed = a.level >= a.maxLevel;
    const kindTag = a.active
      ? `<span style="color:#7bb0ff">⚡ Active${a.cooldown ? ' · CD ' + a.cooldown + ' rounds' : ''}</span>`
      : `<span style="color:#9c9">🪨 Passive</span>`;
    return `
      <div class="attr-row">
        <span class="attr-icon">${a.icon}</span>
        <div style="flex:1;min-width:0">
          <span class="attr-label">${a.displayName} ${kindTag}</span>
          <span class="attr-effect" style="display:block;font-size:.72rem;color:#888">${a.description}</span>
        </div>
        <span class="attr-val" style="color:${maxed?'#ffd700':'#eee'}">${a.level}/${a.maxLevel}</span>
        <button class="btn-attr" ${pts <= 0 || maxed ? 'disabled' : ''} onclick="learnAbility('${a.id}')">+</button>
      </div>`;
  }).join('');
  el.innerHTML = `
    <div class="attr-section" style="margin-top:10px">
      <div class="attr-header">
        <span>✨ ${escapeHtml(data.class)} Abilities</span>
        ${pts > 0 ? `<span class="attr-points-badge">⬆ ${pts} point${pts!==1?'s':''}</span>` : ''}
      </div>
      ${rows}
      <button onclick="respecAbilities()" style="margin-top:8px;font-size:.74rem;background:#444;padding:5px 10px">
        ↺ Reset abilities (${fmtBronze(data.respecCost)})
      </button>
    </div>`;
}

async function learnAbility(id) {
  const data = await api('POST', `/api/abilities/learn/${id}`);
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadAbilities();
  renderAttributes();
}

async function respecAbilities() {
  if (!confirm('Reset all abilities and refund the points? This costs bronze.')) return;
  const data = await api('POST', '/api/abilities/respec');
  if (data.error) { showMessage(data.error, true); return; }
  showMessage(data.message);
  await loadWarrior();
  loadAbilities();
  renderAttributes();
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
            <div class="slot-label">${t('inventory.slot.'+slot.id)}${item.pvpLocked ? ' <span class="pvp-lock-badge" title="Exposto no PvP">🔒 PvP</span>' : ''}</div>
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
          <div class="bag-item-name rarity-${item.rarity}">${item.name} <span style="font-size:.7rem;color:#888">Lv.${item.itemLevel}</span>${item.pvpLocked ? ` <span class="pvp-lock-badge" title="${t('inventory.pvp_locked')||'Exposto no PvP — pode ser saqueado; não pode vender/guardar enquanto flagged'}">🔒 PvP</span>` : ''}</div>
          <div class="bag-item-type">${(t('item.type.'+item.type)||item.typeDisplay)} · ${(t('inventory.rarity.'+item.rarity)||item.rarityName)}${weaponTag(item)}</div>
          <div class="bag-item-stats">${statsText(item)}</div>
          ${affixLines(item)}
          ${durabilityBar(item)}
          ${item.sockets > 0 ? renderSockets(item) : ''}
        </div>
        ${item.itemLevel > (warrior?.level || 1)
          ? `<button class="btn-equip" disabled style="opacity:.5;cursor:not-allowed" title="Requires level ${item.itemLevel}">🔒 Lv.${item.itemLevel}</button>`
          : !weaponUsable(item)
          ? `<button class="btn-equip" disabled style="opacity:.5;cursor:not-allowed" title="${warrior?.warriorClassId === 'ARCHER' ? 'Archers can only wield bows' : 'This class can only wield melee weapons'}">🚫 ${item.weaponCategory === 'RANGED' ? '🏹' : '🗡'}</button>`
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
  if (item.strBonus     > 0) parts.push(`+${item.strBonus} STR`); // [CLASSES_ARMAS] perfil da arma
  if (item.dexBonus     > 0) parts.push(`+${item.dexBonus} DEX`);
  if (item.lukBonus     > 0) parts.push(`+${item.lukBonus} LUK`);
  return parts.join('  ') || '–';
}

// [CLASSES_ARMAS] Badge de categoria da arma (melee/ranged) + se a classe atual pode equipar.
function weaponTag(item) {
  if (!item || item.type !== 'WEAPON' || !item.weaponCategory) return '';
  const ranged = item.weaponCategory === 'RANGED';
  return ` · <span style="color:${ranged ? '#7bb0ff' : '#d9a05b'}">${ranged ? '🏹 Ranged' : '🗡 Melee'}</span>`;
}
function weaponUsable(item) {
  if (!item || item.type !== 'WEAPON' || !item.weaponCategory) return true;
  return (warrior?.warriorClassId === 'ARCHER') === (item.weaponCategory === 'RANGED');
}

// Raridade → cor/nome (espelha .rarity-N do style.css). [ITENS_V2]
const RARITY_COLOR = { 1:'#aaa', 2:'#4caf82', 3:'#5b9bd5', 4:'#c97ddb', 5:'#e6a23c' };
const RARITY_NAME  = { 1:'Common', 2:'Uncommon', 3:'Rare', 4:'Epic', 5:'Legendary' };
function rarityColor(r) { return RARITY_COLOR[r] || '#aaa'; }
function rarityName(r)  { return t('inventory.rarity.'+r) || RARITY_NAME[r] || '?'; }

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

  // [ELEMENTOS] Encantamento elemental (arma/armadura, buff 1h, custa essência + bronze).
  const enchantHtml = (() => {
    const els = data.elements || [];
    const slot = (label, current, secs, kind) => `
      <div style="margin-bottom:.55rem">
        <div style="font-size:.8rem;color:#bbb;margin-bottom:.25rem">${label}: ${
          current ? `<strong>${(els.find(e => e.id === current)?.icon) || ''} ${current}</strong> <span style="color:#888">(${Math.floor((secs||0)/60)}min)</span>`
                  : '<span style="color:#888">none</span>'}</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap">
          ${els.map(e => `
            <button onclick="enchant('${kind}','${e.id}')" ${e.owned < 1 ? 'disabled style="opacity:.45;cursor:not-allowed"' : ''}
              title="Beats ${e.beats} · you have ${e.owned} ${e.essenceName}"
              style="font-size:.78rem;padding:4px 8px">${e.icon} ${e.displayName} <span style="color:#888">(${e.owned})</span></button>`).join('')}
        </div>
      </div>`;
    return `
      <div class="sk-section">
        <div class="sk-title">⚗ Enchanting <span style="font-size:.7rem;color:#888">(1h · ${fmtBronze(data.enchantCost || 100)} + 1 essence)</span></div>
        <p class="zone-desc">Weapon = element you deal · Armor = your defense. Wheel: 🔥→💨→🪨→💧→🔥 (each beats the next). Match-up = ±25% in combat. Farm element areas for essences. Enchants are lost on KO.</p>
        ${slot('🗡 Weapon', data.weaponElement, data.weaponElementSecondsLeft, 'weapon')}
        ${slot('🛡 Armor',  data.armorElement,  data.armorElementSecondsLeft,  'armor')}
      </div>`;
  })();

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

    ${enchantHtml}

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

// [ELEMENTOS] Encanta arma/armadura (kind = 'weapon'|'armor') com um elemento.
async function enchant(kind, element) {
  const data = await api('POST', `/api/temple/enchant/${kind}/${element}`);
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
    const red = pvp.flaggedZoneId === 'HIGH_RISK';
    const risk = red
      ? '🔴 Recursos + bronze + um item (travado) + XP em risco se for derrotado. Não pode vender/stashar/guardar os itens travados.'
      : '🟡 50% dos recursos + bronze em risco. Itens e XP estão seguros. Não pode stashar recursos enquanto exposto.';
    return `<div class="zone-pvp-status" style="border-color:${red?'#c0392b':'#c9a84c'};background:#3a1b1b">
      ⚠ ${t('zones.exposed')||'Exposto'} (${escapeHtml(pvp.flaggedZone)}) — ${pvp.flagMinutesLeft} min. ${risk}
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
  // [PROFISSAO_SUCCESS] mostra onde o próximo tier de recurso desbloqueia (só p/ coleta)
  const tier = (skill.nextTierLevel ?? 0) > 0
    ? `<span class="xp-label" style="margin-left:.4rem;color:#ffd700" title="Next resource tier unlocks here">🔓 Lv.${skill.nextTierLevel}</span>`
    : '';
  return `
    <div class="sk-skill-row">
      <span class="sk-skill-label">Lv.${skill.level}</span>
      <div class="xp-bar-bg" style="flex:1"><div class="xp-bar-fill" style="width:${pct}%"></div></div>
      <span class="xp-label" style="margin-left:.4rem">${skill.experience}/${skill.expNeeded} XP</span>
      ${tier}
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
        ${r.atk > 0 ? ` · +${r.atk} ATK` : ''}${r.def > 0 ? ` · +${r.def} DEF` : ''}${r.hp > 0 ? ` · +${r.hp} HP` : ''}${r.str > 0 ? ` · +${r.str} STR` : ''}${r.dex > 0 ? ` · +${r.dex} DEX` : ''}${r.luk > 0 ? ` · +${r.luk} LUK` : ''}
      </div>
      <div style="font-size:.75rem;color:#888">Forja Lv.${r.levelRequired} ${!r.canCraft ? '🔒' : ''}</div>
      ${r.canCraft ? `
        <div style="font-size:.72rem;color:#8bc34a;margin-top:.2rem">🎲 Success: ${r.successPct}% · Fee: ${fmtBronze(r.bronzeCost)}</div>
        <button class="btn-equip" onclick="craftEquipment('${r.id}')" style="margin-top:.3rem">Craftar</button>` : ''}
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
  // [PROFISSAO_SUCCESS] craft pode falhar — falha = mensagem vermelha (200, não é erro de API)
  const msg = data.success
    ? `✅ ${data.message}${data.sockets ? ` (${data.sockets} socket${data.sockets !== 1 ? 's' : ''})` : ''}`
    : `❌ ${data.message}`;
  showMessage(msg, !data.success);
  resourcesData = await api('GET', '/api/gathering/resources');
  await loadWarrior();
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

async function socketGem(itemId, gemType) {
  const data = await api('POST', `/api/smithing/socket/${itemId}/${gemType}`);
  if (data.error) { showMessage(data.error, true); return; }
  // [PROFISSAO_SUCCESS] encaixe pode falhar — falha = vermelho, joia preservada
  showMessage(`${data.success ? '✅' : '❌'} ${data.message}`, !data.success);
  resourcesData = await api('GET', '/api/gathering/resources');
  await loadWarrior();
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
        const disabled  = locked;              // [SEM_TIMER] sem 'busy' cruzado — só trava por nível
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
              ${noStamina ? 'disabled style="opacity:.5;cursor:not-allowed"' : ''}
              onclick="enterTower()">
        ${noStamina ? t('tower.no_stamina') : t('tower.enter_btn')}
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
  await loadTower();             // atualiza a tela atrás do modal (próximo andar OU lobby se perdeu)
  showTowerFightModal(data);     // resultado no modal padrão (igual zonas)
}

// Resultado da luta da torre no modal padrão. Sem "sair com os ganhos" — os ganhos já foram
// creditados por andar; pra continuar é só ⚔ Lutar no próximo andar; pra parar, fecha o modal.
function showTowerFightModal(r) {
  const rows = [];
  if (r.won) {
    rows.push({ icon:'🪙', label:'Bronze',     value:fmtBronze(r.bronzeEarned), color:'#cd7f32' });
    rows.push({ icon:'⭐', label:'Experience', value:`+${r.expEarned} XP`,       color:'#ffd700' });
  } else {
    rows.push({ icon:'☠', label:'Result', value:'Defeated — heal at the Temple', color:'#ef5350' });
  }
  const title = r.won ? `🏆 Floor ${r.floor} cleared!` : `💀 Defeated on Floor ${r.floor}`;
  const color = r.won ? '#4caf82' : '#ef5350';
  const note  = r.won && !r.runOver ? 'Boss down! Climb to the next floor whenever you like.' : '';
  showCollectModal({ title, color, rows, note, log: r.log || [] });
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
// Mostra no modal compartilhado (overlay) — não é sobrescrito pelo refresh da tela da arena.
async function startFight() {
  const data = await api('POST', '/api/arena/fight');
  if (data.error) { showMessage(data.error, true); return; }
  showCollectModal({
    title: data.won ? `🏆 Victory vs ${escapeHtml(data.opponent)}!`
                    : `💀 Defeat to ${escapeHtml(data.opponent)}`,
    color: data.won ? '#4caf50' : '#ef5350',
    rows: [
      { icon:'🏅', label:'Rank',   value:`${data.won ? '+' : ''}${data.rankChange} pts`, color: data.won ? '#4caf50' : '#ef5350' },
      { icon:'🪙', label:'Bronze', value:fmtBronze(data.goldEarned), color:'#cd7f32' },
    ],
    log: data.log || []
  });
  await loadWarrior();
  loadRank();
  renderFightArea(); // atualiza a tela de luta (estamina/limite) atrás do modal
}

// ═══════════════════════════════════════════════════════════════════
// PATH TRIAL — escolha de classe no Lv10 (RECRUIT → WARRIOR/ARCHER). [CLASSES]
// ═══════════════════════════════════════════════════════════════════

async function openClassTrial() {
  const info = await api('GET', '/api/class');
  if (info.error) { showMessage(info.error, true); return; }
  if (!info.available) {
    showMessage((info.level ?? 1) < (info.trialLevel ?? 10)
      ? `Reach level ${info.trialLevel} to choose your path.`
      : 'You have already chosen your path.', true);
    return;
  }
  closeCollectModal();
  const color = '#a855f7';
  const CLASS_ICON = { WARRIOR: '🛡', ARCHER: '🏹', MERCHANT: '💰' }; // [MERCADOR]
  const cardFor = (p) => `
    <div style="background:#0d0d18;border:1px solid #3a2a4a;border-radius:10px;padding:14px;margin-bottom:12px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
        <span style="font-weight:bold;color:#e0c8ff;font-size:15px">${CLASS_ICON[p.id] || '⚔'} ${escapeHtml(p.displayName)}</span>
        <span style="font-size:11px;color:#888">ATK ${p.baseAttack} · DEF ${p.baseDefense} · HP ${p.baseHealth}</span>
      </div>
      <div style="font-size:12px;color:#aaa;line-height:1.5;margin-bottom:8px">${escapeHtml(p.description)}</div>
      <div style="font-size:11px;color:#777;margin-bottom:10px">Caps — STR ${p.strCap} · DEX ${p.dexCap} · LUK ${p.lukCap}</div>
      <button onclick="attemptClassTrial('${p.id}')" style="width:100%;background:${color};color:#000;font-weight:bold;padding:9px;border-radius:7px;cursor:pointer;font-size:13px;border:none">
        ⚔ Attempt ${escapeHtml(p.trialName)}
      </button>
    </div>`;
  const el = document.createElement('div');
  el.id = 'collect-modal-overlay';
  el.setAttribute('style',
    'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.82);' +
    'z-index:9999;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box');
  el.onclick = closeCollectModal;
  el.innerHTML = `
    <div onclick="event.stopPropagation()" style="background:#16162a;border:2px solid ${color};border-radius:14px;
      padding:24px;max-width:480px;width:100%;max-height:85vh;overflow-y:auto;position:relative;box-shadow:0 8px 32px rgba(0,0,0,0.6)">
      <button onclick="closeCollectModal()" style="position:absolute;top:10px;right:10px;background:#333;
        border:none;color:#aaa;padding:4px 10px;border-radius:4px;cursor:pointer;font-size:13px">✕</button>
      <h3 style="margin:0 0 6px;color:${color};font-size:17px">⚔ Choose your Path</h3>
      <p style="color:#999;font-size:12px;margin:0 0 16px;line-height:1.5">Defeat the trial guardian of your chosen path to specialize. <strong style="color:#d8b4fe">This is permanent.</strong> Your spent attribute points are refunded so you can rebuild for the new class.</p>
      ${(info.paths || []).map(cardFor).join('')}
    </div>`;
  document.body.appendChild(el);
}

async function attemptClassTrial(path) {
  const data = await api('POST', '/api/class/trial/' + path);
  if (data.error) { showMessage(data.error, true); return; }
  showCollectModal({
    title: data.won ? `🎖 Trial passed — you are now ${escapeHtml(data.className)}!` : `✖ Trial failed`,
    color: data.won ? '#4caf50' : '#ef5350',
    rows: data.won ? [
      { icon: ({WARRIOR:'🛡',ARCHER:'🏹',MERCHANT:'💰'})[data.classId] || '⚔', label: 'New class', value: escapeHtml(data.className), color: '#4caf50' },
      { icon: '🔄',                                      label: 'Attribute points', value: 'refunded',                 color: '#a855f7' },
    ] : [
      { icon: '💀', label: 'Result', value: 'Knocked out — heal and retry', color: '#ef5350' },
    ],
    log: data.log || []
  });
  await loadWarrior();
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
      // Language already loaded above, just enter game.
      // enterGame() também chama loadWorld() — sem ele a aba World ficava presa em "Loading..."
      // no auto-login (só destravava ao trocar de aba e voltar). [FIX_WORLD_LOADING]
      enterGame();
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
  const lifetimeFmt   = fmtBronze(g.lifetimeGold ?? 0);

  // [GUILD_LEVEL_GOLD] Nível é derivado do gold acumulado (doações) — sem botão de level-up.
  const maxed = (g.level ?? 1) >= (g.maxLevel ?? 10);
  const levelProgress = maxed
    ? `<div style="font-size:12px;color:#ffd700;margin-top:8px">⭐ Max level (Lv.${g.maxLevel}) — total contributed: ${lifetimeFmt}</div>`
    : `<div style="margin-top:8px">
         <div style="display:flex;justify-content:space-between;font-size:12px;color:#aaa">
           <span>Lv.${g.level} → Lv.${(g.level ?? 1) + 1}</span>
           <span>${fmtBronze(g.goldToNextLevel ?? 0)} to go</span>
         </div>
         <div style="background:#0d0d1a;border:1px solid #444;border-radius:6px;height:10px;margin-top:3px;overflow:hidden">
           <div style="background:#ffd700;height:100%;width:${g.levelProgressPct ?? 0}%"></div>
         </div>
         <div style="font-size:11px;color:#888;margin-top:2px">Accumulated ${lifetimeFmt} · next level at ${fmtBronze(g.nextLevelGold ?? 0)}</div>
       </div>`;

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
    const fatigue     = (m.fatiguePct > 0)
      ? ` <span title="War fatigue — will fight at -${m.fatiguePct}% in the next territory battle"
             style="color:#e57373;font-size:11px">😓 -${m.fatiguePct}%</span>`
      : '';
    // [GUERRA_FORMACAO] O roster virou a grade 3×5 abaixo (posicionamento). Sem checkbox por linha.
    const rosterCell  = '';
    const kickBtn     = g.isLeader && !m.isMe && !m.isLeader
      ? `<button onclick="guildKick(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#8b0000">Kick</button>`
      : '';
    const transferBtn = g.isLeader && !m.isMe
      ? `<button onclick="guildTransfer(${m.playerId})" style="font-size:11px;padding:2px 6px;background:#555">Transfer</button>`
      : '';
    return `<tr>
      ${rosterCell}
      <td>${escapeHtml(m.warriorName)}${badge}${fatigue}${m.isMe ? ' <em>(you)</em>' : ''}</td>
      <td style="text-align:right">${kickBtn} ${transferBtn}</td>
    </tr>`;
  }).join('');

  // [GUERRA_FORMACAO] Formação 3×5: o líder posiciona os 15 nas 3 lanes (cada lane é um gauntlet).
  const placedAt = {};
  g.members.forEach(m => { if (m.warLane >= 0 && m.warDepth >= 0) placedAt[m.warLane + ':' + m.warDepth] = m.playerId; });
  const memberOpts = (selId) => g.members.map(m =>
    `<option value="${m.playerId}" ${m.playerId === selId ? 'selected' : ''}>${escapeHtml(m.warriorName)}${m.fatiguePct > 0 ? ' (-' + m.fatiguePct + '%)' : ''}</option>`).join('');
  const fcell = (l, d) => {
    const sel = placedAt[l + ':' + d];
    return `<td style="padding:2px"><select class="formation-cell" data-lane="${l}" data-depth="${d}" style="width:100%;font-size:11px;padding:3px;background:#0d0d18;color:#ddd;border:1px solid #444">
      <option value="" ${sel ? '' : 'selected'}>—</option>${memberOpts(sel)}</select></td>`;
  };
  let frows = '';
  for (let d = 0; d < 5; d++) {
    const label = d === 0 ? 'Front' : d === 4 ? 'Back' : ('R' + (d + 1));
    frows += `<tr><td style="font-size:10px;color:#888;padding-right:6px">${label}</td>${fcell(0, d)}${fcell(1, d)}${fcell(2, d)}</tr>`;
  }
  const rosterPanel = g.isLeader ? `
    <div style="background:#16213e;border:1px solid #444;border-radius:6px;padding:10px;margin-top:10px">
      <div style="font-size:13px;margin-bottom:6px">
        ⚔ <strong>War Formation (3×5)</strong> — place fighters in the 3 lanes.
        <span style="color:#aaa">Each lane is a gauntlet: the front fights first and the winner carries its <strong>remaining HP</strong> to the next. Win <strong>2 of 3 lanes</strong>. Empty cells auto-fill with your freshest members; −10% per consecutive war cycle (max −50%).</span>
      </div>
      <table style="border-collapse:collapse">
        <thead><tr><th></th><th style="font-size:10px;color:#888">Lane 1</th><th style="font-size:10px;color:#888">Lane 2</th><th style="font-size:10px;color:#888">Lane 3</th></tr></thead>
        <tbody>${frows}</tbody>
      </table>
      <button onclick="guildSaveFormation()" style="margin-top:8px">💾 Save formation</button>
    </div>` : '';

  el.innerHTML = `
    <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:16px;margin-bottom:12px">
      <h3 style="margin:0 0 4px">${escapeHtml(g.name)} <span style="font-size:12px;color:#aaa">Lv.${g.level}</span></h3>
      <p style="color:#aaa;margin:0 0 8px;font-size:13px">${escapeHtml(g.description || 'No description.')}</p>
      <div style="display:flex;gap:24px;font-size:13px;flex-wrap:wrap">
        <span>🏦 Treasury: <strong>${treasuryFmt}</strong></span>
        <span>👥 Members: <strong>${g.members.length}/${g.maxMembers}</strong></span>
      </div>
      ${bonusLine}
      ${levelProgress}
    </div>

    <h4 style="margin:0 0 8px">Members</h4>
    <table style="width:100%;border-collapse:collapse;font-size:13px">
      ${memberRows}
    </table>
    ${rosterPanel}

    <div id="guild-war-section" style="margin-top:14px"></div>

    <div style="margin-top:16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <input id="donate-amount" type="number" min="1" placeholder="Amount in bronze"
        style="width:160px;padding:6px;background:#111;color:#eee;border:1px solid #555;border-radius:4px">
      <button onclick="guildDonate()">💰 Donate</button>
      ${disbandBtn}
    </div>
    <div id="guild-msg" style="margin-top:8px;min-height:20px"></div>

    ${renderDonationRank(g.donationRank ?? [], player => player.isMe)}
  `;

  updateRosterUi(); // sincroniza contador + trava checkboxes acima de 15. [GUERRA_ROSTER]
  loadGuildWar(g.isLeader); // seção de Guerra de Guilda. [GUERRA_GUILDA]
}

// ── Guerra de Guilda (declarar / atacar / status). [GUERRA_GUILDA] ──
async function loadGuildWar(isLeader) {
  const el = document.getElementById('guild-war-section');
  if (!el) return;
  let s;
  try { s = await api('GET', '/api/guild/war'); } catch (e) { el.innerHTML = ''; return; }
  if (!s || s.error) { el.innerHTML = ''; return; }

  if (s.atWar) {
    const days  = Math.floor(s.secondsLeft / 86400);
    const hours = Math.floor((s.secondsLeft % 86400) / 3600);
    const winning = s.myKills > s.enemyKills, losing = s.myKills < s.enemyKills;
    const enemyRows = (s.enemies || []).map(e => {
      const action = e.knockedOut ? '<span style="color:#cf6679">💀 down</span>'
                   : e.shielded   ? '<span style="color:#90caf9">🛡 shielded</span>'
                   : `<button onclick="guildWarAttack(${e.playerId})" style="font-size:11px;padding:2px 8px;background:#8b0000">⚔ Attack</button>`;
      return `<tr>
        <td>${escapeHtml(e.warriorName)} <span style="color:#888;font-size:11px">Lv.${e.level} · ${e.hpPercent}% HP</span></td>
        <td style="text-align:right">${action}</td>
      </tr>`;
    }).join('');
    el.innerHTML = `
      <div style="background:#2a1010;border:1px solid #8b0000;border-radius:8px;padding:12px">
        <div style="font-weight:bold;color:#ff8a80">⚔ At War with ${escapeHtml(s.enemyGuildName)}</div>
        <div style="font-size:13px;margin-top:4px">
          Kills: <strong style="color:${winning ? '#7fd1b9' : '#eee'}">${s.myKills}</strong>
          × <strong style="color:${losing ? '#ff8a80' : '#eee'}">${s.enemyKills}</strong> them
          · <span style="color:#aaa">${days}d ${hours}h left</span>
        </div>
        <h4 style="margin:10px 0 6px;font-size:13px">Enemy members</h4>
        <table style="width:100%;border-collapse:collapse;font-size:13px">${enemyRows || '<tr><td style="color:#888">No members.</td></tr>'}</table>
        <div id="guild-war-msg" style="margin-top:6px;min-height:18px"></div>
      </div>`;
  } else if (isLeader) {
    el.innerHTML = `
      <div style="background:#16213e;border:1px solid #444;border-radius:8px;padding:12px">
        <div style="font-size:13px;margin-bottom:6px">⚔ <strong>Guild War</strong> — declare a 7-day war on a rival. Most kills wins 25% of their gold (can de-level them). Both guilds must have held a territory.</div>
        <button onclick="loadGuildWarTargets()">⚔ Declare War</button>
        <div id="guild-war-targets" style="margin-top:8px"></div>
        <div id="guild-war-msg" style="margin-top:6px;min-height:18px"></div>
      </div>`;
  } else {
    el.innerHTML = '';
  }
}

async function loadGuildWarTargets() {
  const box = document.getElementById('guild-war-targets');
  if (!box) return;
  box.innerHTML = 'Loading...';
  const targets = await api('GET', '/api/guild/war/targets');
  if (!Array.isArray(targets) || targets.length === 0) {
    box.innerHTML = '<span style="color:#888;font-size:12px">No eligible rival guilds (must have held a territory and not be at war).</span>';
    return;
  }
  box.innerHTML = targets.map(t => `
    <div style="display:flex;justify-content:space-between;align-items:center;padding:4px 0;border-top:1px solid #333">
      <span>${escapeHtml(t.name)} <span style="color:#888;font-size:11px">Lv.${t.level}</span></span>
      <button onclick="guildWarDeclare(${t.id})" style="font-size:11px;padding:2px 8px;background:#8b0000">Declare</button>
    </div>`).join('');
}

async function guildWarDeclare(guildId) {
  if (!confirm('Declare a 7-day guild war on this guild?')) return;
  const r = await api('POST', `/api/guild/war/declare/${guildId}`);
  if (r.error) { const m = document.getElementById('guild-war-msg'); if (m) m.innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadGuild();
}

async function guildWarAttack(playerId) {
  const r = await api('POST', `/api/guild/war/attack/${playerId}`);
  if (r.error) { const m = document.getElementById('guild-war-msg'); if (m) m.innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();        // HP/estamina/bronze mudaram
  await loadGuildWar(false);  // re-renderiza kills + estados (escudo/KO)
  const m = document.getElementById('guild-war-msg');
  const txt = r.won
    ? `✅ You won! Looted ${r.loot}. Kills ${r.myKills}–${r.enemyKills}`
    : `❌ You lost — they raided you (${r.loot}). Kills ${r.myKills}–${r.enemyKills}`;
  if (m) m.innerHTML = `<span style="color:${r.won ? '#7fd1b9' : '#ff8a80'}">${txt}</span>`;
}

// War roster: atualiza o contador "X/15" e desabilita os não-marcados ao bater 15. [GUERRA_ROSTER]
function updateRosterUi() {
  const boxes = Array.from(document.querySelectorAll('.war-roster-cb'));
  if (boxes.length === 0) return;
  const checked = boxes.filter(b => b.checked).length;
  const countEl = document.getElementById('roster-count');
  if (countEl) countEl.textContent = `Selected: ${checked}/15`;
  const atMax = checked >= 15;
  boxes.forEach(b => { b.disabled = atMax && !b.checked; });
}

async function guildSaveRoster() {
  const memberIds = Array.from(document.querySelectorAll('.war-roster-cb'))
    .filter(b => b.checked).map(b => parseInt(b.value, 10));
  const r = await api('POST', '/api/guild/roster', { memberIds });
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg('Battle roster saved.');
  await loadGuild();
}

// [GUERRA_FORMACAO] Salva a grade 3×5: lê os selects, monta os slots (ignora vazios), valida duplicata.
async function guildSaveFormation() {
  const slots = [];
  const seen = new Set();
  for (const sel of document.querySelectorAll('.formation-cell')) {
    const pid = parseInt(sel.value, 10);
    if (!sel.value || isNaN(pid)) continue;
    if (seen.has(pid)) { guildMsg('A member is placed in more than one cell.', false); return; }
    seen.add(pid);
    slots.push({ playerId: pid, lane: parseInt(sel.dataset.lane, 10), depth: parseInt(sel.dataset.depth, 10) });
  }
  const r = await api('POST', '/api/guild/war-formation', { slots });
  if (r.error) { guildMsg(r.error, false); return; }
  guildMsg('War formation saved.');
  await loadGuild();
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
  // [GUILD_LEVEL_GOLD] doar pode subir o nível da guild automaticamente
  guildMsg(r.leveledUp ? `🎉 Donation pushed the guild to level ${r.level}!`
                       : `Donated! Treasury: ${fmtBronze(r.guildGold ?? 0)}`);
  await loadGuild();
  loadWarrior();
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
let selectedZoneElement = 'FIRE'; // [ELEMENTOS] área de elemento selecionada nas zonas de coleta
function selectZoneElement(el) { selectedZoneElement = el; if (worldCurrentKingdom) enterKingdom(worldCurrentKingdom); }

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

    return `<div id="kingdom-card-${k.kingdom}" onclick="enterKingdom('${k.kingdom}')" style="background:#1a1a2e;border:1px solid ${k.isMine ? '#4caf50' : '#444'};border-radius:10px;padding:16px;margin-bottom:12px;cursor:pointer">
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

async function enterKingdom(kingdom, _depth = 0) {
  worldCurrentKingdom = kingdom;
  const el = document.getElementById('kingdom-detail');
  if (!el) return;
  // Abre o painel logo ABAIXO do card clicado (não lá no fim da lista). Só move/scrolla na abertura
  // inicial — refresh pós-coleta não fica re-scrollando. [FIX_KINGDOM_PANEL]
  const card = document.getElementById('kingdom-card-' + kingdom);
  if (card && el.previousElementSibling !== card) {
    card.insertAdjacentElement('afterend', el);
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
  el.innerHTML = '<p>Loading kingdom...</p>';
  try {
    const [, quests, activeQuests, training, zoneSession, pvpStatus] = await Promise.all([
      loadWarrior(),
      api('GET', `/api/world/${kingdom}/quests`),
      api('GET', `/api/world/${kingdom}/quests/active`),
      kingdom === 'COMBAT' ? api('GET', '/api/world/COMBAT/training') : Promise.resolve(null),
      api('GET', '/api/zones/current'), // [UNIFICAÇÃO_ZONA] coleta de todo reino (pesca/mineração/combate) é zona
      api('GET', '/api/zones/pvp-status').catch(() => null)
    ]);
    // [SEM_TIMER] Auto-coleta sessão de zona pendurada pronta (sem banner/Collect manual). Recursão limitada.
    if (_depth < 2 && zoneSession?.active && zoneSession.readyToCollect) {
      await api('POST', `/api/zones/${zoneSession.id}/collect`).catch(() => {});
      return enterKingdom(kingdom, _depth + 1);
    }
    renderKingdomDetail(kingdom, quests, activeQuests, training, zoneSession, pvpStatus);
  } catch(e) {
    console.error('[WORLD] enterKingdom ERROR:', e);
    el.innerHTML = '<p style="color:red">Error loading kingdom: ' + e.message + '</p>';
  }
}

function renderKingdomDetail(kingdom, quests, activeQuests, training, zoneSession, pvpStatus) {
  const el = document.getElementById('kingdom-detail');
  const NAMES = { FISHING:'Bone Gorge', MINING:'Black Iron Mines', COMBAT:'Cursed Fortress', GRUTAS_DE_CRISTAL:'Crystal Grottoes', MAR_ABENCOADO:'Blessed Sea' };
  const ICONS = { FISHING:'🎣', MINING:'⛏', COMBAT:'⚔', GRUTAS_DE_CRISTAL:'🔎', MAR_ABENCOADO:'🐟' };
  // [SEM_TIMER] "tem tarefa ativa pra coletar" neste reino (não é mais o flag global 'busy').
  // Bloqueia começar outra quest/treino/coleta até coletar a pendente (espelha os checks do backend).
  const hasActiveTask = activeQuests.length > 0
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
          ${hasActiveTask
            ? `<p style="font-size:12px;color:#f44336;margin:0">⏳ Collect your active task first</p>`
            : `<div style="display:flex;gap:6px;flex-wrap:wrap">
            ${(() => { const h = 2; return `<button onclick="startTraining(${h})" style="font-size:12px;padding:4px 14px">🏋 Treinar · ${fmtBronze(lvl*10*h)}</button>`; })()}
          </div>`}
        </div>`;
    }
  }

  // Active zone session banner (coleta/expedição unificada em zona)
  let activeGatherHtml = '';
  if (zoneSession && zoneSession.active) {
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
              : hasActiveTask
              ? '<p style="font-size:11px;color:#f44336;margin:0">⏳ Collect your active task first</p>'
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

    // [UNIFICAÇÃO_ZONA] As 3 zonas viram tiers reais (SAFE/PVP/HIGH_RISK) via /api/zones/enter, com PvP.
    const SAFE_DESC = '🟢 Sem PvP. 20% de monstros (PvE).';
    const PVP_DESC  = '🟡 PvP: ao perder, −50% recursos + 10% bronze. Gear e XP seguros. Lv.10+';
    const RED_DESC  = '🔴 PvP duro: recursos + 15% bronze + 1 item + XP. Itens TRAVADOS. Lv.20+';

    const zones = kingdom === 'FISHING' ? [
      { name:'🏖 Safe Shore', minLv:1,  tier:'SAFE',      color:'#4caf50', desc:SAFE_DESC },
      { name:'🌊 Wild Coast', minLv:10, tier:'PVP',       color:'#ffc107', desc:PVP_DESC },
      { name:'🦈 Deep Sea',   minLv:20, tier:'HIGH_RISK', color:'#ef5350', desc:RED_DESC }
    ] : kingdom === 'MAR_ABENCOADO' ? [
      { name:'🌅 Sacred Cove',   minLv:1,  tier:'SAFE',      color:'#4caf50', desc:'🟢 Peixe que restaura VIDA. Sem PvP.' },
      { name:'🐠 Deep Reef',     minLv:10, tier:'PVP',       color:'#ffc107', desc:PVP_DESC },
      { name:'🔱 Blessed Abyss', minLv:20, tier:'HIGH_RISK', color:'#ef5350', desc:RED_DESC }
    ] : kingdom === 'MINING' ? [
      { name:'⛏ Open Mine',       minLv:1,  tier:'SAFE',      color:'#4caf50', desc:SAFE_DESC },
      { name:'🪨 Deep Tunnels',   minLv:10, tier:'PVP',       color:'#ffc107', desc:PVP_DESC },
      { name:'💎 Forbidden Mines', minLv:20, tier:'HIGH_RISK', color:'#ef5350', desc:RED_DESC }
    ] : [
      { name:'🔎 Shallow Vein',     minLv:1,  tier:'SAFE',      color:'#4caf50', desc:SAFE_DESC },
      { name:'💠 Deep Grottoes',    minLv:10, tier:'PVP',       color:'#ffc107', desc:PVP_DESC },
      { name:'💎 Forbidden Cavern', minLv:20, tier:'HIGH_RISK', color:'#ef5350', desc:RED_DESC }
    ];

    // [ELEMENTOS] Picker de área de elemento — dropa a essência do elemento + monstros desse elemento.
    const ZONE_ELEMENTS = [
      { id:'FIRE', icon:'🔥', name:'Fire' }, { id:'WATER', icon:'💧', name:'Water' },
      { id:'EARTH', icon:'🪨', name:'Earth' }, { id:'AIR', icon:'💨', name:'Air' },
    ];
    const elemPicker = `
      <div style="margin-bottom:10px">
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">⚗ Element area <span style="color:#888">— drops its essence + monsters of that element</span></div>
        <div style="display:flex;gap:6px;flex-wrap:wrap">
          ${ZONE_ELEMENTS.map(e => `
            <button onclick="selectZoneElement('${e.id}')" style="font-size:12px;padding:4px 10px;border-radius:6px;
              border:1px solid ${selectedZoneElement===e.id?'#4caf82':'#444'};
              background:${selectedZoneElement===e.id?'#16352a':'#1a1a2e'};
              color:${selectedZoneElement===e.id?'#bfe':'#ccc'}">${e.icon} ${e.name}</button>`).join('')}
        </div>
      </div>`;

    gatheringHtml = elemPicker + zones.map(z => {
      const locked = wLevel < z.minLv;
      return `
        <div style="background:#1a1a2e;border:1px solid ${locked?'#333':z.color+'44'};border-radius:8px;padding:12px;margin-bottom:8px;opacity:${locked?'0.5':'1'}">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <strong style="color:${z.color}">${z.name}</strong>
            ${locked ? `<span style="font-size:11px;color:#888">🔒 Lv.${z.minLv}+</span>` : z.tier !== 'SAFE' ? '<span style="font-size:11px;color:#ef5350">⚔ PvP</span>' : '<span style="font-size:11px;color:#4caf50">✓ Safe</span>'}
          </div>
          <p style="font-size:11px;color:#888;margin:3px 0 6px">${z.desc}</p>
          ${locked
            ? '<p style="font-size:11px;color:#555;margin:0">Reach level '+z.minLv+' to unlock.</p>'
            : hasActiveTask
              ? '<p style="font-size:11px;color:#f44336;margin:0">⏳ Collect your active task first</p>'
              : `<div style="display:flex;gap:5px;flex-wrap:wrap">
              ${(() => {
                const d = 20; // ação instantânea de tamanho fixo (10⚡ via d/2)
                const stamCost = Math.max(5, Math.floor(d/2));
                const verb = isFishing ? '🎣 Pescar' : skillType === 'MINING' ? '⛏ Minerar' : '🔎 Garimpar';
                // [UNIFICAÇÃO_ZONA] coleta pelo sistema de zona (tem PvP) + drops do reino
                return `<button onclick="enterKingdomZone('${z.tier}','${skillType}',${d},'${kingdom}','${selectedZoneElement}')" style="font-size:12px;padding:4px 14px">${verb} · ⚡${stamCost}</button>`;
              })()}
            </div>`}
        </div>`;
    }).join('');
  }

  const questCards = quests.map(q => {
    const done = !!q.doneToday;                              // [DAILY_QUESTS] limite da janela atingido
    const disabled = hasActiveTask || !q.canStart;
    const actionHtml = done
      ? `<span style="font-size:12px;color:#4caf50;display:inline-flex;align-items:center;gap:6px">
           ✓ Done <span style="color:#888">· resets in ${fmtResetCountdown(q.secondsUntilReset)}</span>
         </span>`
      : `<button onclick="startKingdomQuest('${kingdom}','${q.id}')"
            ${disabled ? 'disabled style="opacity:.5"' : ''}
            style="font-size:12px">
            ${hasActiveTask ? 'Finish your task' : !q.canStart ? 'Low stamina' : (q.interactive ? '📜 Begin' : 'Start Quest')}
          </button>`;
    return `
      <div style="background:#1a1a2e;border:1px solid ${done ? '#2e4d2e' : '#333'};border-radius:8px;padding:12px;margin-bottom:8px${done ? ';opacity:.7' : ''}">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong style="font-size:14px">${q.displayName}</strong>
          <div style="display:flex;gap:8px;align-items:center;font-size:12px;color:#888">
            <span>${fmtBronze(q.bronzeReward)}</span>
            <span>⭐ ${q.expReward} XP</span>
            <span>⚡ ${q.staminaCost}</span>
          </div>
        </div>
        <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:8px;align-items:center">
          ${actionHtml}
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
        ${warrior && warrior.isKnockedOut
          ? '<p style="font-size:11px;color:#f44336;margin:0">⚔ Warrior wounded — heal at the Temple</p>'
          : `<button onclick="raidCombat()" style="background:#7a1f1f">⚔ Hunt</button>`}
      </div>`;
  }

  const questsSection = quests.length > 0
    ? `<h4 style="margin:0 0 8px;color:#aaa;font-size:13px">🗓 DAILY QUESTS <span style="color:#666;font-weight:normal">· reset in ${fmtResetCountdown(quests[0].secondsUntilReset)}</span></h4>${questCards}`
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
  if (r.won) {
    const rows = [
      { icon:'🪙', label:'Bronze',     value:fmtBronze(r.goldEarned), color:'#cd7f32' },
      { icon:'⭐', label:'Experience', value:`+${r.xpEarned} XP`,      color:'#ffd700' },
    ];
    (r.materials || []).forEach(m => rows.push({ icon:'🧩', label:m.displayName, value:`x${m.quantity}`, color:'#4db6ac' }));
    showCollectModal({ title:`🏆 ${escapeHtml(r.beast)} slain!`, color:'#4caf50', rows, log:r.log || [] });
  } else {
    showCollectModal({ title:`💀 Defeated by ${escapeHtml(r.beast)}!`, color:'#ef5350',
      rows:[{ icon:'☠', label:'Result', value:'You were beaten — heal at the Temple', color:'#ef5350' }],
      log:r.log || [] });
  }
  if (worldCurrentKingdom) await enterKingdom(worldCurrentKingdom);
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
      <span style="display:flex;flex-direction:column;align-items:flex-end;text-align:right;min-width:0">
        <span style="font-weight:bold;color:${r.color || '#fff'};font-size:13px">${r.value}</span>
        ${r.sub ? `<span style="font-size:11px;color:#9a9aae;margin-top:2px">${r.sub}</span>` : ''}
      </span>
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

async function startKingdomQuest(kingdom, questTypeId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/start`, { questType: questTypeId });
  if (r.error) { worldMsg(r.error, false); return; }
  if (r.interactive && r.dialog) {
    showQuestDialogModal(kingdom, r.id, r.dialog);  // [QUESTS_INTERATIVAS] escolhe antes de resolver
  } else {
    await collectKingdomQuest(kingdom, r.id);       // não-interativa: resolve direto
  }
}

// Modal de diálogo (livro-jogo): narrativa + opções. Tem que escolher (ou ✕ pra desistir → abandona).
function showQuestDialogModal(kingdom, questId, dialog) {
  closeCollectModal();
  const optsHtml = (dialog.options || []).map(o => `
    <button onclick="chooseQuestOption('${kingdom}', ${questId}, '${o.id}')"
            style="display:block;width:100%;text-align:left;margin-top:8px;padding:10px 12px;
                   background:#1f1f33;border:1px solid #3a3a52;border-radius:8px;color:#e6e6f2;
                   cursor:pointer;font-size:13px">
      ${o.label}${o.hint ? ` <span style="color:#7fd1b9;font-size:11px">· 🎲 ${o.hint}</span>` : ''}
    </button>`).join('');
  const el = document.createElement('div');
  el.id = 'collect-modal-overlay';
  el.setAttribute('style',
    'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.82);' +
    'z-index:9999;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box');
  el.innerHTML = `
    <div style="background:#16162a;border:2px solid #c9a84c;border-radius:14px;padding:24px;
      max-width:460px;width:100%;max-height:85vh;overflow-y:auto;position:relative;box-shadow:0 8px 32px rgba(0,0,0,0.6)">
      <button onclick="abandonQuestFromDialog('${kingdom}', ${questId})" title="Give up"
        style="position:absolute;top:10px;right:10px;background:#333;border:none;color:#aaa;
               padding:4px 10px;border-radius:4px;cursor:pointer;font-size:13px">✕</button>
      <h3 style="margin:0 0 14px;color:#c9a84c;font-size:16px">📜 Choose your path</h3>
      <div style="background:#0d0d18;border-left:3px solid #c9a84c;border-radius:6px;padding:10px 12px;
        margin-bottom:6px;font-size:13px;color:#cdd;font-style:italic;line-height:1.5">${dialog.intro}</div>
      ${optsHtml}
    </div>`;
  document.body.appendChild(el);
}

async function chooseQuestOption(kingdom, questId, optionId) {
  closeCollectModal();
  await collectKingdomQuest(kingdom, questId, optionId);
}

async function abandonQuestFromDialog(kingdom, questId) {
  closeCollectModal();
  await api('POST', `/api/world/${kingdom}/quests/${questId}/abandon`).catch(() => {});
  await enterKingdom(kingdom);
}

async function collectKingdomQuest(kingdom, questId, optionId) {
  const r = await api('POST', `/api/world/${kingdom}/quests/${questId}/collect`, optionId ? { optionId } : undefined);
  if (r.error) { worldMsg(r.error, false); return; }
  showQuestResultModal(r);
  await enterKingdom(kingdom);
  await loadWarrior(); // refresca o card (XP/HP/bronze + pet novo). [PETS]
}

// Modal de resultado da quest: roll (se houve) + narrativa + combate; derrota = sem recompensa.
function showQuestResultModal(r) {
  // [PETS] ganhou um pet na quest rara → celebração
  if (r.acquiredPet) {
    showCollectModal({
      title: '🎉 A new companion!',
      color: '#ffd700',
      note:  r.narrative,
      rows:  [{ icon:'🐶', label:'Pet', value:`${r.acquiredPet} — equipped (+10% HP)`, color:'#e0b878' }],
      log:   []
    });
    return;
  }
  const rollRow = r.roll ? [{
    icon: '🎲',
    label: `${r.roll.attr} check`,
    value: `${r.roll.rolled} + ${r.roll.mod} = ${r.roll.rolled + r.roll.mod} vs DC ${r.roll.dc}`,
    sub: r.roll.passed ? '✓ Success' : '✗ Failure',
    color: r.roll.passed ? '#7fd1b9' : '#ef9a9a'
  }] : [];

  const lost = r.monsterEncountered && !r.monsterDefeated;
  if (lost) {
    showCollectModal({
      title: `💀 Defeated by the ${r.monsterName || 'monster'}!`,
      color: '#ef5350',
      note:  r.narrative,
      rows:  [...rollRow, { icon:'☠', label:'Reward', value:'None — you were beaten', color:'#ef5350' }],
      log:   r.battleLog || []
    });
    return;
  }
  const rows = [...rollRow,
    { icon:'⭐', label:'Experience', value:`+${r.xpEarned} XP`,    color:'#ffd700' },
    { icon:'🪙', label:'Bronze',     value:fmtBronze(r.bronzeEarned), color:'#cd7f32' },
  ];
  if (r.droppedItem) {
    const d = r.droppedItem;
    const typeName = t('item.type.'+d.type) || d.type;
    rows.push({
      icon:  '🎁',
      label: 'Item Drop',
      value: d.name,
      color: rarityColor(d.rarity),
      sub:   `${rarityName(d.rarity)} · ${typeName} · ${statsText(d)}`
    });
  }
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

// [UNIFICAÇÃO_ZONA] Coleta por zona (SAFE/PVP/HIGH_RISK) com drops do reino — /api/zones/enter GATHERING.
async function enterKingdomZone(zone, skillType, durationMinutes, kingdom, element) {
  const r = await api('POST', '/api/zones/enter', { zone, role: 'GATHERING', skillType, durationMinutes, kingdom, element: element || null });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomZoneSession(r.id); // instantâneo: resolve e abre o resultado direto
}

async function enterCombatZone(zone, durationMinutes) {
  const r = await api('POST', '/api/zones/enter', { zone, role: 'COMBAT', durationMinutes });
  if (r.error) { worldMsg(r.error, false); return; }
  await collectKingdomZoneSession(r.id); // [SEM_TIMER] instantâneo: resolve e abre o resultado direto
}

async function collectKingdomZoneSession(activityId) {
  const r = await api('POST', `/api/zones/${activityId}/collect`);
  if (r.error) { worldMsg(r.error, false); return; }
  if (r.bossPending) { showBossModal(activityId, r); return; } // [ZONA_CHEFE] pausa: fugir/encarar
  await renderZoneResult(r);
}

// [ZONA_CHEFE] Um chefe da Torre escapou e está rondando a área: fugir (teste de stat) ou encarar.
function showBossModal(activityId, r) {
  closeCollectModal();
  const color = '#b71c1c';
  const el = document.createElement('div');
  el.id = 'collect-modal-overlay';
  el.setAttribute('style',
    'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.88);' +
    'z-index:9999;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box');
  el.innerHTML = `
    <div onclick="event.stopPropagation()" style="background:#16162a;border:2px solid ${color};border-radius:14px;
      padding:24px;max-width:460px;width:100%;position:relative;box-shadow:0 8px 32px rgba(0,0,0,0.7)">
      <h3 style="margin:0 0 8px;color:${color};font-size:18px">💀 A Roaming Boss Appears!</h3>
      <div style="background:#0d0d18;border-left:3px solid ${color};border-radius:6px;padding:10px 12px;margin-bottom:16px;font-size:13px;color:#cdd;font-style:italic;line-height:1.5">
        A boss escaped the Tower and now stalks this area. <b style="color:#fff">${escapeHtml(r.bossName)}</b> (Lv ${r.bossLevel}) blocks your path. Flee, or face it for rare spoils?
      </div>
      <div style="display:flex;gap:10px">
        <button id="boss-flee-btn" style="flex:1;background:#455a64;color:#fff;font-weight:bold;padding:12px;border-radius:8px;cursor:pointer;font-size:14px;border:none">🏃 Flee (${r.fleeChance}%)</button>
        <button id="boss-fight-btn" style="flex:1;background:${color};color:#fff;font-weight:bold;padding:12px;border-radius:8px;cursor:pointer;font-size:14px;border:none">⚔ Fight</button>
      </div>
      <div style="margin-top:10px;font-size:11px;color:#888;text-align:center">Fleeing rolls your class stat — fail and you're forced to fight.</div>
    </div>`;
  document.body.appendChild(el);
  document.getElementById('boss-flee-btn').onclick  = () => resolveZoneBoss(activityId, 'flee');
  document.getElementById('boss-fight-btn').onclick = () => resolveZoneBoss(activityId, 'fight');
}

async function resolveZoneBoss(activityId, action) {
  const flee = document.getElementById('boss-flee-btn'), fight = document.getElementById('boss-fight-btn');
  if (flee)  { flee.disabled = true;  flee.style.opacity = '0.5'; }
  if (fight) { fight.disabled = true; fight.style.opacity = '0.5'; }
  const r = await api('POST', `/api/zones/${activityId}/boss/${action}`);
  if (r.error) { worldMsg(r.error, false); closeCollectModal(); return; }
  await renderZoneResult(r);
}

async function renderZoneResult(r) {
  let title, color, rows = [];

  if (r.wasAttacked && !r.survived) {
    title = '💀 Defeated in Expedition!';
    color = '#ef5350';
    if (r.attackerName) rows.push({ icon:'⚔', label:'Defeated by', value:escapeHtml(r.attackerName), color:'#ef9a9a' });
    if (r.lostItemName) rows.push({ icon:'💸', label:'Item stolen', value:r.lostItemName,  color:'#ef5350' });
  } else {
    const slewBoss = r.wasAttacked && r.survived && r.lootItemName;
    title = slewBoss ? '🏆 Roaming Boss Slain!'
          : r.wasAttacked ? '⚔ Survived the Expedition!' : '✅ Expedition Completed!';
    color = slewBoss ? '#ffca28' : r.wasAttacked ? '#ffc107' : '#4caf50';
    if (r.wasAttacked && r.attackerName)
      rows.push({ icon:'⚔', label: slewBoss ? 'Boss slain' : 'Survived attack by', value:escapeHtml(r.attackerName), color: slewBoss ? '#ffca28' : '#ffc107' });
    if (r.lootItemName)
      rows.push({ icon:'🎁', label:'Boss loot', value:escapeHtml(r.lootItemName), color:'#ffca28' });
    (r.drops || []).forEach(d =>
      rows.push({ icon:'📦', label:d.displayName, value:`x${d.quantity}`, color:'#4db6ac' }));
    if (r.bronzeGained > 0)
      rows.push({ icon:'🪙', label:'Bronze', value:fmtBronze(r.bronzeGained), color:'#cd7f32' });
    if (r.xpGained > 0)
      rows.push({ icon:'⭐', label:'Experience', value:`+${r.xpGained} XP`, color:'#ffd700' });
  }

  showCollectModal({ title, color, rows, note: r.narrative || '', log: r.battleLog || [] });
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
  const [status, slots, stable, pets] = await Promise.all([
    api('GET', '/api/vip/status'),
    api('GET', '/api/inventory/slots'),
    api('GET', '/api/stable'),
    api('GET', '/api/pets')
  ]);

  const isVip = status.isVip;
  const celestial = (stable && stable.mounts || []).find(m => m.id === 'CELESTIAL_MOUNT');
  // Pet comprável no mercado VIP (gato). [PETS]
  const cat = (Array.isArray(pets) ? pets : []).find(p => p.soulStoneCost > 0);
  const catBonus = cat ? [
    cat.hpBonusPercent > 0 ? `+${cat.hpBonusPercent}% HP` : '',
    cat.dexBonus       > 0 ? `+${cat.dexBonus} AGI`       : '',
  ].filter(Boolean).join(' · ') : '';
  const daysLeft = isVip && status.vipExpiresAt
    ? Math.ceil((new Date(status.vipExpiresAt) - Date.now()) / 86400000)
    : 0;
  const ss = warrior ? warrior.soulStones : 0;

  const vipBanner = isVip
    ? `<div style="background:#3b0764;border:1px solid #7c3aed;border-radius:8px;padding:12px;margin-bottom:12px">
        <div style="color:#c4b5fd;font-weight:bold">👑 VIP Active — ${daysLeft} days left</div>
        <div style="font-size:12px;color:#a78bfa;margin-top:4px">Expires ${status.vipExpiresAt ? status.vipExpiresAt.substring(0,10) : ''}</div>
        <div style="font-size:12px;color:#888;margin-top:6px">
          ⚔ Arena fights: ${status.arenaFightLimit - status.arenaFightsRemaining}/${status.arenaFightLimit}
        </div>
      </div>`
    : `<div style="background:#1a0a2e;border:1px solid #7c3aed;border-radius:8px;padding:12px;margin-bottom:12px">
        <div style="color:#aaa;font-size:13px">You don't have VIP active.</div>
      </div>`;

  const canBuyVip = ss >= 15;
  const vipLabel = isVip ? `👑 Renew VIP (+30 days)` : `👑 Activate VIP`;

  const bagExpanded = slots && slots.inventoryExpanded;

  el.innerHTML = `
    <div style="padding:4px">
      ${vipBanner}

      <div style="background:#1a1a2e;border:1px solid #7c3aed;border-radius:8px;padding:16px;margin-bottom:12px">
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <div style="font-size:15px;font-weight:bold;color:#c4b5fd">👑 VIP Status — 30 days</div>
            <div style="font-size:12px;color:#888;margin-top:4px">Includes: 20-slot bag · free heal · 10 arena fights/day · 2 simultaneous buffs · 1 extra daily quest run</div>
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

      <h4 style="color:#aaa;font-size:13px;margin:12px 0 8px">PERMANENT PURCHASES</h4>

      <div style="background:#1a1a2e;border:1px solid #444;border-radius:8px;padding:12px;margin-bottom:8px;
                  opacity:${bagExpanded || isVip ? '0.5' : '1'}">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <div style="font-size:13px;font-weight:bold">🎒 Expand Bag (10→20 slots)</div>
            <div style="font-size:11px;color:#888">Permanent. Included with VIP.</div>
          </div>
          <span style="color:#a78bfa;font-size:13px">3 💎</span>
        </div>
        ${bagExpanded || isVip
          ? '<div style="color:#4caf50;font-size:12px;margin-top:6px">✓ Already active</div>'
          : `<button onclick="expandInventory()" style="margin-top:8px;font-size:12px" ${ss < 3 ? 'disabled style="opacity:.5"' : ''}>
               Buy (3 💎)
             </button>`}
      </div>

      ${celestial ? `
      <div style="background:#1a1a2e;border:1px solid #7c3aed;border-radius:8px;padding:12px;margin-bottom:8px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <div style="font-size:13px;font-weight:bold">${celestial.icon} ${celestial.displayName} <span style="color:#7fd1b9">−${celestial.staminaReductionPct}% ⚡</span></div>
            <div style="font-size:11px;color:#888">VIP mount — the biggest stamina cut. Equip it in the 🐴 Stable.</div>
          </div>
          <span style="color:#a78bfa;font-size:13px">${celestial.priceSoulStones} 💎</span>
        </div>
        ${celestial.equipped
          ? '<div style="color:#4caf50;font-size:12px;margin-top:6px">✓ Equipped</div>'
          : celestial.owned
            ? `<button onclick="equipMountFromVip('CELESTIAL_MOUNT')" style="margin-top:8px;font-size:12px;background:#2e7d32">Equip</button>`
            : !isVip
              ? '<div style="color:#888;font-size:12px;margin-top:6px">🔒 Requires active VIP</div>'
              : `<button onclick="buyMountFromVip('CELESTIAL_MOUNT')" style="margin-top:8px;font-size:12px;background:#7c3aed" ${ss < celestial.priceSoulStones ? 'disabled style="opacity:.5"' : ''}>
                   Buy (${celestial.priceSoulStones} 💎)
                 </button>`}
      </div>` : ''}

      ${cat ? `
      <div style="background:#1a1a2e;border:1px solid #d8a14d;border-radius:8px;padding:12px;margin-bottom:8px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <div style="font-size:13px;font-weight:bold">${cat.icon} ${cat.displayName} <span style="color:#e0b878">${catBonus}</span></div>
            <div style="font-size:11px;color:#888">A nimble companion. Bought with SoulStones — not stray like Luna.</div>
          </div>
          <span style="color:#a78bfa;font-size:13px">${cat.soulStoneCost} 💎</span>
        </div>
        ${cat.equipped
          ? '<div style="color:#4caf50;font-size:12px;margin-top:6px">✓ Equipped</div>'
          : cat.owned
            ? `<button onclick="equipPet('${cat.type}')" style="margin-top:8px;font-size:12px;background:#2e7d32">Equip</button>`
            : `<button onclick="buyPet('${cat.type}')" style="margin-top:8px;font-size:12px;background:#7c3aed" ${ss < cat.soulStoneCost ? 'disabled style="opacity:.5"' : ''}>
                 Adopt (${cat.soulStoneCost} 💎)
               </button>`}
      </div>` : ''}

      <div style="font-size:11px;color:#666;margin-top:12px;text-align:center">
        💎 Balance: ${ss} SoulStone${ss !== 1 ? 's' : ''}
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

// Montaria Celestial comprada/equipada pela VIP Shop (recarrega a própria VIP Shop). [ESTABULO]
async function buyMountFromVip(mountType) {
  const r = await api('POST', `/api/stable/buy/${mountType}`);
  if (r.error) { document.getElementById('vipshop-msg').innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();
  loadVipShop();
  document.getElementById('vipshop-msg').innerHTML = `<span style="color:#4caf50">${r.message}</span>`;
}

async function equipMountFromVip(mountType) {
  const r = await api('POST', `/api/stable/equip/${mountType}`);
  if (r.error) { document.getElementById('vipshop-msg').innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  loadVipShop();
}

// Pet comprado/equipado pela VIP Shop (SoulStone). [PETS]
async function buyPet(petType) {
  const r = await api('POST', `/api/pets/buy/${petType}`);
  if (r.error) { document.getElementById('vipshop-msg').innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();
  loadVipShop();
  document.getElementById('vipshop-msg').innerHTML = `<span style="color:#4caf50">🐾 ${r.message}</span>`;
}
async function equipPet(petType) {
  const r = await api('POST', `/api/pets/equip/${petType}`);
  if (r.error) { document.getElementById('vipshop-msg').innerHTML = `<span style="color:#f44336">${r.error}</span>`; return; }
  await loadWarrior();
  loadVipShop();
}

// [QUESTS_INTERATIVAS] instantStartQuest removido (instant-start aposentado; dailies viraram interativas).

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
