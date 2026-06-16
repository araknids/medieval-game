// battleArena.js — [BATALHA_ANIMADA] Replay 2D COSMÉTICO de um combate já decidido pelo backend.
// O front não simula nada: só toca os BattleEvent (spawn/attack/crit/miss/dodge/heal/.../victory).
// Lutadores = SPRITES reais (CraftPix Knight, frames 128×128): idle/walk/attack/hurt/dead. Sangue por
// partículas, fundo por cena, replay ≤10s e controles ⏩/⏭.
// API: playBattle(canvasEl, events, { scene, onDone }) → { stop() }.  Plano: docs/PLANO_BATALHA_ANIMADA.md
(function () {
  const lerp = (a, b, t) => a + (b - a) * t;

  // Fundos placeholder por cena (gradiente). Arte de cenário real entra depois.
  const SCENES = {
    arena:    ['#3a2f1e', '#6b5836'], coast: ['#15384a', '#2a6b6b'], sea: ['#0f2a4a', '#1f6b8a'],
    cave:     ['#1a1a22', '#33333f'], fortress: ['#241018', '#4a1a2a'], tower: ['#1a1424', '#3a2450'],
  };
  const ELEM = { SUPER: { c: '#ffd24a', s: '✦' }, RESIST: { c: '#7fb0ff', s: '🛡' } };
  const HIT_TYPES = new Set(['attack', 'crit', 'volley', 'extra']);
  const SWING_TYPES = new Set(['attack', 'crit', 'volley', 'extra', 'miss', 'dodge']); // atacante balança a arma

  // ── Sprites em strips horizontais. Cada set tem frame size próprio (fw×fh) + padding de pé. ──
  // [PAPER_DOLL] player = guerreiro PixelLab (careca, SEM armadura ainda, 143×123). Inimigo = CraftPix Knight (128×128).
  const DRAW_H = 150;
  const FRAME_MS = { idle: 130, walk: 80, attack: 55, hurt: 110, dead: 110, jump: 90 }; // jump suave (~11fps); repete no taunt
  // fw×fh = frame real; sh = altura usada SÓ p/ escala (decoupla "tamanho na tela" do frame). Knight preenche
  // ~metade do frame (sh=fh); o guerreiro é recorte colado, então sh maior o encolhe p/ casar a altura do corpo.
  const W_BASE = '/assets/warrior/'; // warrior: só idle+attack por ora → walk/hurt/dead/jump reusam idle (anim real entra depois).
  const SETS = {
    warrior: { fw: 143, fh: 123, sh: 240, foot: 15,
      idle: W_BASE + 'idle.png', walk: W_BASE + 'idle.png', attack: W_BASE + 'attack.png',
      hurt: W_BASE + 'idle.png', dead: W_BASE + 'idle.png', jump: W_BASE + 'idle.png' },
    purple: { fw: 128, fh: 128, foot: 8,
      idle: '/assets/knights/purple/idle.png',   walk: '/assets/knights/purple/walk.png',
      attack: '/assets/knights/purple/attack.png', hurt: '/assets/knights/purple/hurt.png',
      dead: '/assets/knights/purple/dead.png',     jump: '/assets/knights/purple/jump.png' },
    blue: { fw: 128, fh: 128, foot: 8,
      idle: '/assets/knights/blue/idle.png',   walk: '/assets/knights/blue/walk.png',
      attack: '/assets/knights/blue/attack.png', hurt: '/assets/knights/blue/hurt.png',
      dead: '/assets/knights/blue/dead.png',     jump: '/assets/knights/blue/jump.png' },
  };
  // [BATALHA_ANIMADA] Fundos de cenário (CraftPix "castelo"). Por enquanto: 1 aleatório por luta.
  const BACKGROUNDS = ['/assets/backgrounds/bg1.png', '/assets/backgrounds/bg2.png',
                       '/assets/backgrounds/bg3.png', '/assets/backgrounds/bg4.png'];

  const _img = {};
  function sprite(url) { let im = _img[url]; if (!im) { im = new Image(); im.src = url; _img[url] = im; } return im; }
  Object.values(SETS).forEach(set => Object.values(set).forEach(v => { if (typeof v === 'string') sprite(v); })); // preload (ignora fw/fh/foot)
  BACKGROUNDS.forEach(sprite);

  function roundRect(c, x, y, w, h, r) {
    c.beginPath(); c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  }

  window.playBattle = function (canvas, events, opts) {
    opts = opts || {};
    const ctx = canvas.getContext('2d');
    if (!ctx || !Array.isArray(events)) return { stop() {} };
    const W = canvas.width, H = canvas.height, ground = H - 14;
    const bgUrl = BACKGROUNDS[Math.floor(Math.random() * BACKGROUNDS.length)]; // fundo aleatório por luta

    const combatX = s => s < 0 ? W * 0.40 : W * 0.60; // perto: corpo-a-corpo (era 0.30/0.70)
    const entryX  = s => s < 0 ? W * 0.13 : W * 0.87;

    const spawns = events.filter(e => e.type === 'spawn');
    if (spawns.length < 2) return { stop() {} };
    const mk = (sp, side) => {
      const max = Math.max(1, sp.targetMaxHp || 1);
      const cur = Math.max(1, Math.min(max, sp.targetHp || max));   // [HP_SPAWN] HP atual ≤ máximo (barra reflete entrar machucado)
      return {
      name: sp.actor, maxHp: max, hp: cur, shownHp: cur, side,
      x: combatX(side), x0: entryX(side), color: side < 0 ? '#5b8dd6' : '#cf5b5b',
      set: side < 0 ? 'warrior' : 'purple', // [PAPER_DOLL] player (esquerda) = guerreiro PixelLab; inimigo = knight

      anim: 'idle', animStart: 0, animOnce: false, moving: false, flinch: 0, dead: false,
    }; };
    let left = mk(spawns[0], -1), right = mk(spawns[1], 1);
    const F = {}; F[left.name] = left; F[right.name] = right;

    const steps = events.slice();
    const BUDGET = 8500;   // ms — caber em ≤10s mesmo com muitos turnos
    const TAUNT_MS = 1150; // taunt no início (~2 pulos suaves de 6×90ms) antes de andar pro combate
    const INTRO_MS = 600;  // entrada andando até o corpo-a-corpo
    const stepDur = Math.max(110, Math.min(600, steps.length ? BUDGET / steps.length : 600));

    let particles = [], floaters = [], shake = 0;
    let speed = 1, idx = 0, impacted = false, stepStart = 0, done = false, raf = 0, t0 = 0;
    let curEvent = null, curT = 0, introP = 0, introDone = false, taunting = false, stepRef = null, nowTs = 0;

    const zoneY = zone => zone === 'head' ? ground - 118 : zone === 'legs' ? ground - 32 : ground - 78;

    function setAnim(f, name, once) {
      if (!once && f.anim === name) return; // looping (idle/walk): só troca se mudou
      f.anim = name; f.animStart = nowTs; f.animOnce = !!once; // one-shot (attack/hurt): sempre reinicia
    }

    function blood(x, y, n) {
      for (let i = 0; i < n; i++) {
        const a = Math.random() * 6.283, sp = 1 + Math.random() * 3.5;
        particles.push({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1.6, life: 1,
                         size: 1.5 + Math.random() * 2.5 });
      }
      if (particles.length > 260) particles = particles.slice(-260);
    }
    function impact(e) {
      const tgt = F[e.target], act = F[e.actor];
      if (HIT_TYPES.has(e.type) && tgt && (e.damage || 0) > 0) {
        const y = zoneY(e.hitZone), big = e.type === 'crit';
        blood(tgt.x, y, big ? 26 : Math.min(22, 6 + (e.damage || 0))); tgt.flinch = 1;
        setAnim(tgt, 'hurt', true);
        floaters.push({ x: tgt.x, y: y - 8, vy: -0.8, life: 1, text: '-' + (e.damage || 0),
                        color: big ? '#ff5252' : '#fff', size: big ? 20 : 14 });
        if (ELEM[e.element]) floaters.push({ x: tgt.x, y: y - 26, vy: -0.6, life: 1,
                        text: ELEM[e.element].s, color: ELEM[e.element].c, size: 14 });
        if (big) shake = 10;
      } else if (e.type === 'miss' || e.type === 'dodge') {
        const who = e.type === 'dodge' ? act : tgt;
        if (who) floaters.push({ x: who.x, y: ground - 120, vy: -0.7, life: 1,
                        text: e.type === 'dodge' ? 'DODGE' : 'MISS', color: '#9fd0ff', size: 13 });
      } else if (e.type === 'heal' && act) {
        floaters.push({ x: act.x, y: ground - 128, vy: -0.7, life: 1, text: '+' + (e.damage || 0),
                        color: '#7cfc9a', size: 14 });
      }
    }
    function stepEnd(e) {
      const tgt = F[e.target], act = F[e.actor];
      if (HIT_TYPES.has(e.type) && tgt) { tgt.hp = e.targetHp; if (e.targetHp <= 0) tgt.dead = true; }
      else if (e.type === 'dodge' && tgt) { tgt.hp = e.targetHp; }
      else if (e.type === 'heal' && act) { act.hp = e.targetHp; }
      else if (e.type === 'victory' && tgt) { tgt.dead = true; }
      else if (e.type === 'spawn') { // [gauntlet] re-init de lutador no meio do stream (Torre)
        if (e.actor === left.name) { left.maxHp = Math.max(1, e.targetMaxHp || left.maxHp); left.hp = left.shownHp = Math.min(left.maxHp, e.targetHp || left.maxHp); left.dead = false; setAnim(left, 'idle', false); } // [HP_SPAWN] atual, não máximo
        else if (e.actor !== right.name) { right = mk(e, 1); right.x0 = right.x; F[right.name] = right; }
        else { right.maxHp = Math.max(1, e.targetMaxHp || right.maxHp); right.hp = right.shownHp = Math.min(right.maxHp, e.targetHp || right.maxHp); right.dead = false; setAnim(right, 'idle', false); } // [HP_SPAWN] atual, não máximo
      }
    }

    function frame(now) {
      nowTs = now;
      if (!t0) t0 = now;
      const elapsed = (now - t0) * speed;            // taunt → walk → fight
      taunting  = !done && elapsed < TAUNT_MS;
      introP    = Math.min(1, Math.max(0, (elapsed - TAUNT_MS) / INTRO_MS));
      introDone = done || elapsed >= TAUNT_MS + INTRO_MS;
      curEvent = null;

      if (introDone && !done) {
        if (!stepStart) stepStart = now;
        const e = steps[idx];
        if (!e) { finish(); }
        else {
          if (e !== stepRef) { // novo passo → dispara o golpe do atacante
            stepRef = e;
            if (SWING_TYPES.has(e.type) && F[e.actor] && !F[e.actor].dead) setAnim(F[e.actor], 'attack', true);
          }
          curEvent = e;
          curT = Math.min(1, (now - stepStart) * speed / stepDur);
          if (curT >= 0.45 && !impacted) { impact(e); impacted = true; }
          if (curT >= 1) { stepEnd(e); idx++; impacted = false; stepStart = now; if (idx >= steps.length) finish(); }
        }
      }

      [left, right].forEach(f => {
        f.moving = !introDone && !taunting && !f.dead;  // anda só na fase de walk
        f.shownHp += (f.hp - f.shownHp) * 0.25;
        f.flinch *= 0.86;
        // gestão de animação: morte > taunt(jump) > one-shot em andamento > base (walk/idle)
        if (f.dead) {
          if (f.anim !== 'dead') { f.anim = 'dead'; f.animStart = nowTs; f.animOnce = true; }
        } else if (taunting) {
          setAnim(f, 'jump', false); // loop suave do pulo durante o taunt (não congela = não trava)
        } else if (f.animOnce) {
          const st = SETS[f.set];
          const im = sprite(st[f.anim]);
          const frames = im.naturalWidth ? Math.floor(im.naturalWidth / st.fw) : 1;
          if (nowTs - f.animStart >= frames * FRAME_MS[f.anim]) setAnim(f, f.moving ? 'walk' : 'idle', false);
        } else {
          setAnim(f, f.moving ? 'walk' : 'idle', false);
        }
      });
      draw();
      raf = requestAnimationFrame(frame);
    }
    function finish() { if (!done) { done = true; if (typeof opts.onDone === 'function') opts.onDone(); } }

    function draw() {
      ctx.save();
      if (shake > 0.3) { ctx.translate((Math.random() - 0.5) * shake, (Math.random() - 0.5) * shake); shake *= 0.8; }
      const bg = sprite(bgUrl);
      if (bg.complete && bg.naturalWidth) { // fundo de cenário (cobre o canvas, ancorado embaixo)
        const s = Math.max(W / bg.naturalWidth, H / bg.naturalHeight);
        const dw = bg.naturalWidth * s, dh = bg.naturalHeight * s;
        ctx.drawImage(bg, (W - dw) / 2, H - dh, dw, dh);
      } else {                              // fallback: gradiente por cena enquanto a imagem carrega
        const sc = SCENES[opts.scene] || SCENES.fortress;
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, sc[0]); g.addColorStop(1, sc[1]);
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      }
      ctx.fillStyle = 'rgba(0,0,0,.18)'; ctx.fillRect(0, ground, W, H - ground); // sombra do chão
      drawFighter(left); drawFighter(right);
      particles.forEach(p => { p.x += p.vx; p.y += p.vy; p.vy += 0.18; p.life -= 0.02;
        ctx.globalAlpha = Math.max(0, p.life); ctx.fillStyle = '#b5121b'; ctx.fillRect(p.x, p.y, p.size, p.size); });
      ctx.globalAlpha = 1; particles = particles.filter(p => p.life > 0 && p.y < ground + 6);
      drawHp(left, 10); drawHp(right, W - 130);
      floaters.forEach(f => { f.y += f.vy; f.life -= 0.018; ctx.globalAlpha = Math.max(0, f.life);
        ctx.fillStyle = f.color; ctx.font = 'bold ' + f.size + 'px system-ui'; ctx.textAlign = 'center';
        ctx.fillText(f.text, f.x, f.y); });
      ctx.globalAlpha = 1; floaters = floaters.filter(f => f.life > 0);
      ctx.restore();
    }

    function drawFighter(f) {
      const drawX = introDone ? f.x : lerp(f.x0, f.x, introP);
      const faceRight = f.side < 0;                       // sprites encaram a DIREITA por padrão
      const knock = f.flinch > 0.02 ? (f.side < 0 ? -1 : 1) * 6 * f.flinch : 0;
      const st = SETS[f.set];
      const im = sprite(st[f.anim] || st.idle);
      if (!im.complete || !im.naturalWidth) {            // placeholder enquanto a imagem carrega
        ctx.fillStyle = f.color; roundRect(ctx, drawX - 12, ground - 70, 24, 66, 6); ctx.fill(); return;
      }
      const frames = Math.max(1, Math.floor(im.naturalWidth / st.fw));
      const dur = FRAME_MS[f.anim] || 120;
      let fi = Math.floor((nowTs - f.animStart) / dur);
      fi = f.animOnce ? Math.min(fi, frames - 1) : (fi % frames);
      // [BATALHA_ANIMADA] lunge: avança em direção ao inimigo durante o golpe (vende o corpo-a-corpo)
      let lunge = 0;
      if (f.anim === 'attack') {
        const p = Math.min(1, (nowTs - f.animStart) / (frames * dur));
        lunge = Math.sin(p * Math.PI) * 22 * -f.side; // -side = sentido do inimigo na tela
      }
      const scale = DRAW_H / (st.sh || st.fh), dw = st.fw * scale, dh = st.fh * scale, footPad = scale * st.foot;
      ctx.save();
      ctx.translate(drawX + knock + lunge, ground + footPad);
      if (!faceRight) ctx.scale(-1, 1);
      ctx.drawImage(im, fi * st.fw, 0, st.fw, st.fh, -dw / 2, -dh, dw, dh);
      ctx.restore();
    }

    function drawHp(f, x) {
      const w = 120, y = 9, pct = Math.max(0, f.shownHp) / f.maxHp;
      ctx.fillStyle = 'rgba(0,0,0,.5)'; roundRect(ctx, x, y, w, 9, 4); ctx.fill();
      ctx.fillStyle = pct > 0.5 ? '#4caf82' : pct > 0.25 ? '#c9a84c' : '#e0556b';
      roundRect(ctx, x, y, Math.max(0, w * pct), 9, 4); ctx.fill();
      ctx.fillStyle = '#ECE3D2'; ctx.font = '10px system-ui'; ctx.textAlign = x < W / 2 ? 'left' : 'right';
      ctx.fillText(f.name, x < W / 2 ? x : x + w, y + 20);
    }

    // Controles ⏩/⏭ (bar abaixo do canvas)
    const host = canvas.parentElement;
    if (host && !host.querySelector('.ba-ctrl')) {
      const bar = document.createElement('div');
      bar.className = 'ba-ctrl';
      bar.style.cssText = 'display:flex;gap:8px;justify-content:center;margin-top:6px';
      const mkBtn = (label, fn) => { const b = document.createElement('button'); b.textContent = label;
        b.style.cssText = 'background:#2a2a3a;color:#ddd;border:none;border-radius:6px;padding:5px 12px;cursor:pointer;font-size:12px';
        b.onclick = fn; return b; };
      const spd = mkBtn('⏩ 1×', () => { speed = speed >= 4 ? 1 : speed * 2; spd.textContent = '⏩ ' + speed + '×'; });
      const skip = mkBtn('⏭ Skip', () => { introP = 1; introDone = true; while (idx < steps.length) { stepEnd(steps[idx]); idx++; } particles = []; floaters = []; finish(); });
      bar.appendChild(spd); bar.appendChild(skip); host.appendChild(bar);
    }

    raf = requestAnimationFrame(frame);
    const ctrl = { stop() { cancelAnimationFrame(raf); done = true; } };
    window._battleCtrl = ctrl; // p/ o closeCollectModal parar o loop ao fechar
    return ctrl;
  };
})();
