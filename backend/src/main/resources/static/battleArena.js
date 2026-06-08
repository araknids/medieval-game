// battleArena.js — [BATALHA_ANIMADA] Replay 2D COSMÉTICO de um combate já decidido pelo backend.
// O front não simula nada: só toca os BattleEvent (spawn/attack/crit/miss/dodge/heal/.../victory)
// no canvas, com placeholder gráfico (formas), sangue por partículas, fundo por cena, ≤10s e ⏩/⏭.
// API: playBattle(canvasEl, events, { scene, onDone }) → { stop() }.  Trocar por sprites depois = só
// mexer no drawFighter/fundo. Plano: docs/PLANO_BATALHA_ANIMADA.md
(function () {
  // Fundos placeholder por cena (gradiente). Sprites/arte reais entram depois.
  const SCENES = {
    arena:    ['#3a2f1e', '#6b5836'], coast: ['#15384a', '#2a6b6b'], sea: ['#0f2a4a', '#1f6b8a'],
    cave:     ['#1a1a22', '#33333f'], fortress: ['#241018', '#4a1a2a'], tower: ['#1a1424', '#3a2450'],
  };
  const ELEM = { SUPER: { c: '#ffd24a', s: '✦' }, RESIST: { c: '#7fb0ff', s: '🛡' } };
  const HIT_TYPES = new Set(['attack', 'crit', 'volley', 'extra']);

  function zoneY(zone, top, h) {
    if (zone === 'head') return top + h * 0.16;
    if (zone === 'legs') return top + h * 0.82;
    return top + h * 0.48; // body (default)
  }
  function roundRect(c, x, y, w, h, r) {
    c.beginPath(); c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  }

  window.playBattle = function (canvas, events, opts) {
    opts = opts || {};
    const ctx = canvas.getContext('2d');
    if (!ctx || !Array.isArray(events)) return { stop() {} };
    const W = canvas.width, H = canvas.height, ground = H - 16;

    const spawns = events.filter(e => e.type === 'spawn');
    if (spawns.length < 2) return { stop() {} };
    const mk = (sp, side) => ({
      name: sp.actor, maxHp: Math.max(1, sp.targetMaxHp || 1), hp: Math.max(1, sp.targetMaxHp || 1),
      shownHp: Math.max(1, sp.targetMaxHp || 1), side,
      x: side < 0 ? W * 0.27 : W * 0.73, color: side < 0 ? '#5b9bd5' : '#e0556b',
      flinch: 0, dead: false,
    });
    let left = mk(spawns[0], -1), right = mk(spawns[1], 1);
    const F = {}; F[left.name] = left; F[right.name] = right;

    // mantém os spawns no stream → gauntlet (Torre): cada novo monstro re-inicia o lado direito.
    const steps = events.slice();
    const BUDGET = 8500; // ms — caber em ≤10s mesmo com muitos turnos [Requisito #1]
    const stepDur = Math.max(110, Math.min(600, steps.length ? BUDGET / steps.length : 600));

    let particles = [], floaters = [], shake = 0;
    let speed = 1, idx = 0, impacted = false, stepStart = 0, done = false, raf = 0;

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
      if (HIT_TYPES.has(e.type) && tgt) {
        const y = zoneY(e.hitZone, ground - 64, 64), big = e.type === 'crit';
        blood(tgt.x, y, big ? 26 : Math.min(22, 6 + (e.damage || 0))); tgt.flinch = 1;
        floaters.push({ x: tgt.x, y: y - 8, vy: -0.8, life: 1, text: '-' + (e.damage || 0),
                        color: big ? '#ff5252' : '#fff', size: big ? 20 : 14 });
        if (ELEM[e.element]) floaters.push({ x: tgt.x, y: y - 26, vy: -0.6, life: 1,
                        text: ELEM[e.element].s, color: ELEM[e.element].c, size: 14 });
        if (big) shake = 10;
      } else if (e.type === 'miss' || e.type === 'dodge') {
        const who = e.type === 'dodge' ? act : tgt;
        if (who) floaters.push({ x: who.x, y: ground - 76, vy: -0.7, life: 1,
                        text: e.type === 'dodge' ? 'DODGE' : 'MISS', color: '#9fd0ff', size: 13 });
      } else if (e.type === 'heal' && act) {
        floaters.push({ x: act.x, y: ground - 86, vy: -0.7, life: 1, text: '+' + (e.damage || 0),
                        color: '#7cfc9a', size: 14 });
      }
    }
    function stepEnd(e) {
      const tgt = F[e.target], act = F[e.actor];
      if (HIT_TYPES.has(e.type) && tgt) { tgt.hp = e.targetHp; if (e.targetHp <= 0) tgt.dead = true; }
      else if (e.type === 'dodge' && tgt) { tgt.hp = e.targetHp; }   // alvo = atacante que levou o reflect
      else if (e.type === 'heal' && act) { act.hp = e.targetHp; }
      else if (e.type === 'victory' && tgt) { tgt.dead = true; }
      else if (e.type === 'spawn') { // [gauntlet] re-init de lutador no meio do stream (Torre)
        if (e.actor === left.name) { left.hp = left.shownHp = Math.min(left.maxHp, e.targetMaxHp || left.hp); left.dead = false; }
        else if (e.actor !== right.name) { right = mk(e, 1); F[right.name] = right; } // novo monstro
        else { right.hp = right.shownHp = right.maxHp = e.targetMaxHp || right.maxHp; right.dead = false; }
      }
    }

    function frame(now) {
      if (!stepStart) stepStart = now;
      if (!done) {
        const e = steps[idx];
        if (!e) { finish(); }
        else {
          const t = Math.min(1, (now - stepStart) * speed / stepDur);
          if (t >= 0.45 && !impacted) { impact(e); impacted = true; }
          if (t >= 1) { stepEnd(e); idx++; impacted = false; stepStart = now; if (idx >= steps.length) finish(); }
        }
      }
      [left, right].forEach(f => { f.shownHp += (f.hp - f.shownHp) * 0.25; f.flinch *= 0.85; });
      draw();
      raf = requestAnimationFrame(frame);
    }
    function finish() { if (!done) { done = true; if (typeof opts.onDone === 'function') opts.onDone(); } }

    function draw() {
      ctx.save();
      if (shake > 0.3) { ctx.translate((Math.random() - 0.5) * shake, (Math.random() - 0.5) * shake); shake *= 0.8; }
      const sc = SCENES[opts.scene] || SCENES.fortress;
      const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, sc[0]); g.addColorStop(1, sc[1]);
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = 'rgba(0,0,0,.28)'; ctx.fillRect(0, ground, W, H - ground);
      particles.forEach(p => { p.x += p.vx; p.y += p.vy; p.vy += 0.18; p.life -= 0.02;
        ctx.globalAlpha = Math.max(0, p.life); ctx.fillStyle = '#b5121b'; ctx.fillRect(p.x, p.y, p.size, p.size); });
      ctx.globalAlpha = 1; particles = particles.filter(p => p.life > 0 && p.y < ground + 6);
      drawFighter(left); drawFighter(right);
      drawHp(left, 10); drawHp(right, W - 130);
      floaters.forEach(f => { f.y += f.vy; f.life -= 0.018; ctx.globalAlpha = Math.max(0, f.life);
        ctx.fillStyle = f.color; ctx.font = 'bold ' + f.size + 'px system-ui'; ctx.textAlign = 'center';
        ctx.fillText(f.text, f.x, f.y); });
      ctx.globalAlpha = 1; floaters = floaters.filter(f => f.life > 0);
      ctx.restore();
    }
    function drawFighter(f) {
      const x = f.x + (f.flinch > 0.02 ? f.side * -6 * f.flinch : 0), h = 60, w = 20;
      ctx.save();
      if (f.dead) { ctx.translate(x, ground - 8); ctx.rotate(f.side * 1.4); ctx.translate(-x, -(ground - 8)); ctx.globalAlpha = 0.85; }
      ctx.fillStyle = f.flinch > 0.3 ? '#ffffff' : f.color;
      roundRect(ctx, x - w / 2, ground - h, w, h - 14, 6); ctx.fill();
      ctx.beginPath(); ctx.arc(x, ground - h - 2, 8, 0, 6.283); ctx.fill();
      ctx.fillRect(x - 7, ground - 14, 5, 14); ctx.fillRect(x + 2, ground - 14, 5, 14);
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
      const skip = mkBtn('⏭ Skip', () => { while (idx < steps.length) { stepEnd(steps[idx]); idx++; } particles = []; floaters = []; finish(); });
      bar.appendChild(spd); bar.appendChild(skip); host.appendChild(bar);
    }

    raf = requestAnimationFrame(frame);
    const ctrl = { stop() { cancelAnimationFrame(raf); done = true; } };
    window._battleCtrl = ctrl; // p/ o closeCollectModal parar o loop ao fechar
    return ctrl;
  };
})();
