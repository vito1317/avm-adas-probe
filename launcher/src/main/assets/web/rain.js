/* 下雨動畫（蓋在 3D 畫面上的 2D 畫布，不吃 3D 的效能）：斜斜的雨絲、地上的水花、玻璃上滑落的水珠。
 * Rain.set(強度 0～3)：0 = 停；1 小雨、2 中雨、3 大雨（跟雨刷段數）。 */
(function () {
  'use strict';
  var cv = document.createElement('canvas');
  cv.id = 'rain'; cv.setAttribute('aria-hidden', 'true');
  cv.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;z-index:4;pointer-events:none;opacity:0;transition:opacity 1.2s';
  document.body.appendChild(cv);
  var g = cv.getContext('2d'), W = 0, H = 0, level = 0, cur = 0, drops = [], splashes = [], beads = [], last = 0, running = false;

  function size() { var r = Math.min(window.devicePixelRatio || 1, 1.25); W = cv.width = innerWidth * r; H = cv.height = innerHeight * r; }
  addEventListener('resize', size); size();

  function spawnDrop() {
    var z = Math.random();                               // 0 遠、1 近：近的長、亮、快
    drops.push({ x: Math.random() * W * 1.2 - W * 0.1, y: -Math.random() * H * 0.3, z: z,
      len: 18 + z * 46, sp: (900 + z * 1500) / 1000, w: 0.6 + z * 1.4 });
  }
  function spawnBead() {
    beads.push({ x: Math.random() * W, y: Math.random() * H * 0.7, r: 3 + Math.random() * 7, vy: 0, life: 4000 + Math.random() * 6000, t: 0 });
  }

  function frame(now) {
    if (!running) return;
    requestAnimationFrame(frame);
    var dt = Math.min(50, now - (last || now)); last = now;
    cur += (level - cur) * 0.02;                          // 雨勢慢慢變化
    if (cur < 0.02 && level === 0) { running = false; cv.style.opacity = 0; drops.length = splashes.length = beads.length = 0; return; }
    g.clearRect(0, 0, W, H);
    var want = Math.round(cur * 140), wind = 0.18 + cur * 0.05;
    while (drops.length < want) spawnDrop();
    // 雨絲
    g.lineCap = 'round';
    for (var i = drops.length - 1; i >= 0; i--) {
      var d = drops[i];
      d.y += d.sp * dt; d.x += d.sp * dt * wind;
      g.strokeStyle = 'rgba(190,215,240,' + (0.12 + d.z * 0.35) + ')';
      g.lineWidth = d.w;
      g.beginPath(); g.moveTo(d.x, d.y); g.lineTo(d.x - d.len * wind, d.y - d.len); g.stroke();
      // 落地（畫面下半）濺起水花
      if (d.y > H * (0.55 + d.z * 0.45)) {
        if (Math.random() < 0.5) splashes.push({ x: d.x, y: d.y, r: 1, a: 0.5 * (0.4 + d.z) });
        drops.splice(i, 1);
      }
    }
    for (var j = splashes.length - 1; j >= 0; j--) {
      var s = splashes[j];
      s.r += dt * 0.03; s.a -= dt * 0.0016;
      if (s.a <= 0) { splashes.splice(j, 1); continue; }
      g.strokeStyle = 'rgba(200,225,250,' + s.a + ')'; g.lineWidth = 1;
      g.beginPath(); g.ellipse(s.x, s.y, s.r * 2.2, s.r * 0.7, 0, 0, Math.PI * 2); g.stroke();
    }
    // 玻璃上的水珠：偶爾出現、有的慢慢往下滑
    if (beads.length < cur * 14 && Math.random() < 0.05 * cur) spawnBead();
    for (var k = beads.length - 1; k >= 0; k--) {
      var b = beads[k];
      b.t += dt;
      if (b.t > 1500 && Math.random() < 0.004) b.vy = 0.05 + Math.random() * 0.1;
      b.y += b.vy * dt;
      var a = Math.min(1, b.t / 400) * Math.max(0, 1 - b.t / b.life);
      if (a <= 0 || b.y > H) { beads.splice(k, 1); continue; }
      var gr = g.createRadialGradient(b.x - b.r * 0.3, b.y - b.r * 0.3, 0, b.x, b.y, b.r);
      gr.addColorStop(0, 'rgba(255,255,255,' + 0.55 * a + ')'); gr.addColorStop(0.6, 'rgba(160,200,235,' + 0.18 * a + ')'); gr.addColorStop(1, 'rgba(120,170,220,0)');
      g.fillStyle = gr; g.beginPath(); g.arc(b.x, b.y, b.r, 0, Math.PI * 2); g.fill();
      if (b.vy > 0) { g.strokeStyle = 'rgba(180,215,245,' + 0.12 * a + ')'; g.lineWidth = b.r * 0.5; g.beginPath(); g.moveTo(b.x, b.y - b.r); g.lineTo(b.x, b.y - b.r - 40); g.stroke(); }
    }
  }

  window.Rain = {
    set: function (lv) {
      level = Math.max(0, Math.min(3, lv || 0)) / 3;
      if (level > 0 && !running) { running = true; last = 0; cv.style.opacity = 1; requestAnimationFrame(frame); }
    }
  };
})();
