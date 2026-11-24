/*
 * 3D 道路畫面：開車路徑預估線（像特斯拉的藍色路徑）＋畫面風格（標準／FSD 簡潔）。
 *
 * 路徑（跟 360 端 DrivePath.kt 同一套）：車頭附近照方向盤角度（腳踏車模型：曲率 = tan(前輪角) / 軸距），
 * 往前漸漸併到自己車道的中線（左右最近的白黃線中間）；打方向燈、方向盤打很大、看不到車道線 → 只照方向盤；
 * 長度約 3 秒會走到的距離（8～40 m），倒車往後畫；停著不畫。
 * FSD 簡潔風格：深灰背景、別台車統一淺灰、不顯示文字標籤與統計面板，只留路徑、車道線、太近變紅。
 * 座標：three.js 世界 x 右、y 上、−z 前（payload 的 z 前 → 世界 −z）。只用來顯示，不拿去控制車子。
 */
(function () {
  var GEO = { length: 4.841, width: 1.846, wheelbase: 2.888, rearOverhang: 1.12, steerRatio: 15, maxSteerDeg: 522 };
  var REAR_AXLE_Z = -GEO.length / 2 + GEO.rearOverhang;
  var N = 24, HW = 0.5;

  function laneOf(lines) {
    var L = null, R = null;
    (lines || []).forEach(function (l) {
      if (!l || l.k === 'r') return;
      var dz = l.z2 - l.z1; if (Math.abs(dz) < 1) return;
      var sl = (l.x2 - l.x1) / dz; if (Math.abs(sl) > 0.35) return;
      var x0 = l.x1 + sl * (0 - l.z1);
      if (x0 >= -2.6 && x0 <= -0.4 && (!L || x0 > L.x0)) L = { x0: x0, sl: sl };
      if (x0 >= 0.4 && x0 <= 2.6 && (!R || x0 < R.x0)) R = { x0: x0, sl: sl };
    });
    if (L && R && (R.x0 - L.x0 < 2.4 || R.x0 - L.x0 > 4.6)) R = null;
    if (!L && !R) return null;
    var xl = L ? L.x0 : R.x0 - 3.4, xr = R ? R.x0 : L.x0 + 3.4;
    var sl = L && R ? (L.sl + R.sl) / 2 : (L || R).sl;
    return { c: (xl + xr) / 2, sl: sl };
  }

  function smooth(t) { return t * t * (3 - 2 * t); }

  /** → [[x, z], …]（車身座標，z 前）；停著回 null。 */
  function predict(veh, lane) {
    if (!veh) return null;
    var sp = veh.speed == null ? 0 : +veh.speed, gear = veh.gear, turn = veh.turn;
    if (gear === 'P' || (sp < 1 && gear !== 'R')) return null;
    var rev = gear === 'R', v = sp / 3.6, len = Math.min(40, Math.max(8, v * 3));
    var maxW = GEO.maxSteerDeg / GEO.steerRatio;
    var w = Math.max(-maxW, Math.min(maxW, (veh.steer == null ? 0 : +veh.steer) / GEO.steerRatio)) * Math.PI / 180;
    var k = Math.tan(w) / GEO.wheelbase, dir = rev ? -1 : 1;
    var z0 = rev ? -GEO.length / 2 : GEO.length / 2;
    var useLane = lane && !rev && turn !== 'L' && turn !== 'R' && Math.abs(k) < 1 / 25;
    var pts = [];
    for (var i = 0; i <= N; i++) {
      var s = len * i / N, sa = s + (z0 - REAR_AXLE_Z) * dir, x, z;
      if (Math.abs(k) < 1e-4) { x = 0; z = REAR_AXLE_Z + sa * dir; }
      else { var r = 1 / k, a = sa / r; x = r * (1 - Math.cos(a)); z = REAR_AXLE_Z + r * Math.sin(a) * dir; }
      if (useLane) { var c = lane.c + lane.sl * z, f = smooth(Math.min(1, s / (len * 0.6))); x = x * (1 - f) + c * f; }
      pts.push([x, z]);
    }
    return pts;
  }

  // ── 智慧規劃（跟 360 端 DrivePath.Planner 同一套）：每 1.5 m 一站、17 個橫向位置的網格＋動態規劃 ──
  // 成本：照速度推算的碰撞（撞到不行、太近加成本）、壓線（白虛小、白實大、黃實很大、路緣不行）、偏離、急轉、跟上一刻差多少。
  // 同車道前方在動的車 → 跟車；每條都過不去 → 停在擋住的地方前、變橘紅。只用來顯示，不拿去控制車子。
  var EGO_HW = 0.95, MARGIN = 0.3, DS = 1.5, OFFS = [];
  for (var oi = 0; oi < 17; oi++) OFFS.push(-2 + oi * 0.25);
  var prevOffs = null;
  var HALF = { truck: [1.25, 5], bus: [1.25, 5], car: [0.9, 2.3], van: [0.9, 2.3], moto: [0.4, 1], bigbike: [0.4, 1], bike: [0.4, 1], ped: [0.3, 0.3], cone: [0.2, 0.2] };

  function agentsOf(p, ego) {
    var a = [];
    (p.objs || []).forEach(function (o) {
      var h = HALF[o.t] || [0.6, 0.6];
      var vx = typeof o.vx === 'number' ? o.vx : 0, vz = typeof o.vz === 'number' ? o.vz + ego : 0;   // 相對速度 → 對地
      a.push({ x: o.x, z: o.z, vx: vx, vz: vz, hw: h[0], hl: h[1], ped: o.t === 'ped' });
    });
    (p.obs || []).forEach(function (o) { a.push({ x: o.x, z: o.z, vx: 0, vz: 0, hw: (o.w || 0.6) / 2, hl: (o.l || 0.6) / 2, ped: false }); });
    return a;
  }

  function plan(base, p, ego) {
    var cum = [0], i, k, j;
    for (i = 1; i < base.length; i++) cum.push(cum[i - 1] + Math.hypot(base[i][0] - base[i - 1][0], base[i][1] - base[i - 1][1]));
    var n = Math.floor(cum[cum.length - 1] / DS) + 1;
    if (n < 3) return { pts: base, blocked: false };
    var px = [], pz = [], nx = [], nz = [];
    k = 0;
    for (i = 0; i < n; i++) {
      var s = i * DS;
      while (k < base.length - 2 && cum[k + 1] < s) k++;
      var f = Math.max(0, Math.min(1, (s - cum[k]) / Math.max(1e-6, cum[k + 1] - cum[k])));
      px.push(base[k][0] + (base[k + 1][0] - base[k][0]) * f); pz.push(base[k][1] + (base[k + 1][1] - base[k][1]) * f);
      var tx = base[k + 1][0] - base[k][0], tz = base[k + 1][1] - base[k][1], l = Math.hypot(tx, tz) || 1e-6;
      nx.push(tz / l); nz.push(-tx / l);
    }
    var agents = agentsOf(p, ego), L = OFFS.length, INF = 1e18, horizon = n, following = false;
    agents.forEach(function (a) {          // 跟車
      if (a.ped || Math.hypot(a.vx, a.vz) < 1.5 || a.z <= 0 || a.vz <= 0) return;
      var bi = 0, bd = 1e18;
      for (var q = 0; q < n; q++) { var d = Math.hypot(a.x - px[q], a.z - pz[q]); if (d < bd) { bd = d; bi = q; } }
      var lat = (a.x - px[bi]) * nx[bi] + (a.z - pz[bi]) * nz[bi];
      if (Math.abs(lat) < 1.2) { var h = Math.max(1, Math.floor((bi * DS - a.hl - 2) / DS)); if (h < horizon) { horizon = h; following = true; } }
    });
    var road = p.road || {}, lines = (road.lines || []).filter(function (l) { return l.k !== 'r'; });
    var el = typeof road.el === 'number' ? road.el : null, er = typeof road.er === 'number' ? road.er : null;
    var v = Math.max(ego, 2), node = [];
    for (i = 0; i < horizon; i++) {
      var t = i * DS / v, row = [];
      for (j = 0; j < L; j++) {
        var x = px[i] + OFFS[j] * nx[i], z = pz[i] + OFFS[j] * nz[i], c = 0.4 * Math.abs(OFFS[j]);
        for (var ai = 0; ai < agents.length && c < INF; ai++) {
          var A = agents[ai], ax = A.x + A.vx * t, az = A.z + A.vz * t;
          if (az < 0.5 && A.z < 0.5) continue;
          var dLong = Math.abs(az - z) - (A.hl + 2.4), dLat = Math.abs(ax - x) - (A.hw + EGO_HW);
          if (dLong < 0 && dLat < 0) c = INF;
          else if (dLong < 1 && dLat < 2 * MARGIN) c += (2 * MARGIN - dLat) * 30;
        }
        if (c < INF && ((el !== null && x - EGO_HW < el + 0.2) || (er !== null && x + EGO_HW > er - 0.2))) c = INF;
        if (c < INF) lines.forEach(function (ln) {
          var dz = ln.z2 - ln.z1; if (Math.abs(dz) < 0.5) return;
          if (z < Math.min(ln.z1, ln.z2) - 20 || z > Math.max(ln.z1, ln.z2) + 20) return;
          var lx = ln.x1 + (ln.x2 - ln.x1) * (z - ln.z1) / dz;
          if (lx > x - EGO_HW && lx < x + EGO_HW) c += ln.k === 'y' ? (ln.d ? 20 : 60) : (ln.d ? 2 : 10);
        });
        if (c < INF && prevOffs && i < prevOffs.length) c += 0.6 * Math.abs(OFFS[j] - prevOffs[i]);
        row.push(c);
      }
      node.push(row);
    }
    var cost = [], from = [], j0 = (L - 1) / 2;
    for (i = 0; i < horizon; i++) { cost.push(new Array(L).fill(INF)); from.push(new Array(L).fill(-1)); }
    cost[0][j0] = 0;
    for (i = 1; i < horizon; i++) for (j = 0; j < L; j++) {
      if (node[i][j] >= INF) continue;
      for (var q2 = Math.max(0, j - 3); q2 <= Math.min(L - 1, j + 3); q2++) {
        if (cost[i - 1][q2] >= INF) continue;
        var dd = OFFS[j] - OFFS[q2], cc = cost[i - 1][q2] + node[i][j] + 8 * dd * dd;
        if (cc < cost[i][j]) { cost[i][j] = cc; from[i][j] = q2; }
      }
    }
    var last = horizon - 1;
    while (last > 0 && cost[last].every(function (c) { return c >= INF; })) last--;
    var blocked = last < horizon - 1, bj = j0, bc = INF;
    for (j = 0; j < L; j++) if (cost[last][j] < bc) { bc = cost[last][j]; bj = j; }
    var offs = new Array(last + 1);
    for (i = last; i >= 0; i--) { offs[i] = OFFS[bj]; if (i > 0) bj = Math.max(0, from[i][bj]); }
    prevOffs = offs;
    var keep = blocked ? Math.max(1, last - 1) : last, out = [];
    for (i = 0; i <= keep; i++) out.push([px[i] + offs[i] * nx[i], pz[i] + offs[i] * nz[i]]);
    return { pts: out, blocked: blocked && !following };
  }

  /** 等弧長重取樣成 n+1 點（網格點數固定）。 */
  function resample(pts, n) {
    var cum = [0], i, out = [];
    for (i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    var tot = cum[cum.length - 1], k = 0;
    for (i = 0; i <= n; i++) {
      var s = tot * i / n;
      while (k < pts.length - 2 && cum[k + 1] < s) k++;
      var f = Math.max(0, Math.min(1, (s - cum[k]) / Math.max(1e-6, cum[k + 1] - cum[k])));
      out.push([pts[k][0] + (pts[k + 1][0] - pts[k][0]) * f, pts[k][1] + (pts[k + 1][1] - pts[k][1]) * f]);
    }
    return out;
  }

  var mesh = null, line = null, lastLane = null, laneAt = -1e9, sm = null, blockedNow = false;

  function init(THREE, scene) {
    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array((N + 1) * 2 * 3), 3));
    geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array((N + 1) * 2 * 4), 4));
    var idx = [];
    for (var i = 0; i < N; i++) { var a = i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    geo.setIndex(idx);
    mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false, side: THREE.DoubleSide, toneMapped: false }));
    mesh.renderOrder = 5; mesh.frustumCulled = false; mesh.visible = false;
    scene.add(mesh);
    var lg = new THREE.BufferGeometry();
    lg.setAttribute('position', new THREE.BufferAttribute(new Float32Array((N + 1) * 3), 3));
    line = new THREE.Line(lg, new THREE.LineBasicMaterial({ color: 0x9fd4ff, transparent: true, opacity: 0.85, toneMapped: false }));
    line.renderOrder = 6; line.frustumCulled = false; line.visible = false;
    scene.add(line);
  }

  /** 每幀：[veh] payload 的車況、[lines] 最近一次的道路線（payload road.lines）、[now] ms。 */
  function update(veh, lines, now, enabled, payload) {
    if (!mesh) return;
    if (lines) { var ln = laneOf(lines); if (ln) { lastLane = ln; laneAt = now; } }
    var lane = now - laneAt < 1500 ? lastLane : null;
    var pts = enabled ? predict(veh, lane) : null;
    if (!pts) { mesh.visible = line.visible = false; sm = null; prevOffs = null; return; }
    // 避開障礙物（倒車不做）
    blockedNow = false;
    if (payload && veh && veh.gear !== 'R') {
      var r = plan(pts, payload, (veh.speed || 0) / 3.6);
      if (r.pts.length >= 2) pts = resample(r.pts, N);
      blockedNow = r.blocked;
    }
    // 平滑（每點指數平均），路徑才不會一幀一跳
    if (!sm || sm.length !== pts.length) sm = pts.map(function (p) { return p.slice(); });
    else for (var i = 0; i < pts.length; i++) { sm[i][0] += 0.3 * (pts[i][0] - sm[i][0]); sm[i][1] += 0.3 * (pts[i][1] - sm[i][1]); }
    var pos = mesh.geometry.attributes.position.array, col = mesh.geometry.attributes.color.array, lp = line.geometry.attributes.position.array;
    for (var j = 0; j <= N; j++) {
      var a = sm[Math.max(0, j - 1)], b = sm[Math.min(N, j + 1)];
      var dx = b[0] - a[0], dz = b[1] - a[1], L = Math.hypot(dx, dz) || 1, nx = dz / L * HW, nz = -dx / L * HW;
      var p = sm[j], Y = 0.035, f = j / N, al = 0.62 * (1 - f) + 0.06;
      pos.set([p[0] - nx, Y, -(p[1] - nz), p[0] + nx, Y, -(p[1] + nz)], j * 6);
      if (blockedNow) col.set([1.0, 0.43, 0.16, al, 1.0, 0.43, 0.16, al], j * 8);      // 過不去：橘紅
      else col.set([0.16, 0.55, 1.0, al, 0.16, 0.55, 1.0, al], j * 8);
      lp.set([p[0], Y + 0.005, -p[1]], j * 3);
    }
    mesh.geometry.attributes.position.needsUpdate = true; mesh.geometry.attributes.color.needsUpdate = true;
    line.geometry.attributes.position.needsUpdate = true;
    mesh.visible = line.visible = true;
  }

  // ── 風格 ──
  var style = 'std', saved = null;
  var GRAY = null;
  function applyStyle(THREE, scene, labelsEl, name) {
    style = name;
    document.documentElement.setAttribute('data-style', name);
    if (labelsEl) labelsEl.style.display = name === 'fsd' ? 'none' : '';
    if (name === 'fsd') {
      if (!saved) saved = { bg: scene.background.clone(), fog: scene.fog ? scene.fog.color.clone() : null };
      scene.background.set(0x111418); if (scene.fog) scene.fog.color.set(0x111418);
    } else if (saved) {
      scene.background.copy(saved.bg); if (scene.fog && saved.fog) scene.fog.color.copy(saved.fog);
    }
    try { localStorage.setItem('bev3dStyle', name); } catch (e) {}
  }
  /**
   * FSD 風格：把別台車、行人的材質換成淺灰（每台一份，太近時才能只有那台變紅；原本的材質留著，切回來還原）。
   * 寫實模型（roadusers.js）是背景載入、載完會換掉子物件 → 每幀都檢查一次（車輛數十台以內，很便宜）。
   */
  function dressObject(THREE, g) {
    if (style !== 'fsd' || !g) return;
    var m = g.userData.fsdMat;
    if (!m) {
      m = g.userData.fsdMat = new THREE.MeshStandardMaterial({ color: 0x3c434c, roughness: 0.8, metalness: 0.05, envMapIntensity: 0.35 });
      m.userData.baseEm = 0x000000; m.userData.baseEmI = 0;
    }
    if (g.userData.paint !== m) { g.userData.origPaint = g.userData.paint; g.userData.paint = m; }
    g.traverse(function (o) {
      if (o.isMesh && o.material && o.material !== m && !o.userData.keepMat) { o.userData.origMat = o.material; o.material = m; }
    });
  }
  function undressObject(g) {
    if (!g || !g.userData.fsdMat) return;
    g.traverse(function (o) { if (o.isMesh && o.userData.origMat) { o.material = o.userData.origMat; o.userData.origMat = null; } });
    if (g.userData.origPaint) { g.userData.paint = g.userData.origPaint; g.userData.origPaint = null; }
  }
  function savedStyle() {
    if (location.hash.indexOf('fsd') >= 0) return 'fsd';     // 預覽：index.html#demo-fsd
    try { return localStorage.getItem('bev3dStyle') || 'std'; } catch (e) { return 'std'; }
  }

  window.AVM_FSD = { init: init, update: update, predict: predict, plan: plan, applyStyle: applyStyle, dressObject: dressObject,
    undressObject: undressObject, style: function () { return style; }, savedStyle: savedStyle };
})();
