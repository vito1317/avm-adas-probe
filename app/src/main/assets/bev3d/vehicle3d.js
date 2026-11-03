/*
 * vehicle3d.js — 車身訊號的 3D 顯示（ES5、three.js r128）
 *  1. BMW 式雷達（PDC）：前後各 4 個感測扇區，畫在保險桿外的地面，依距離分段上色
 *     （綠 > 1.2 m、黃 0.6–1.2、紅 < 0.6、< 0.3 連續紅），每顆只亮最近那一段；背景有往外擴散的掃描波。
 *     HUD 顯示最近距離（例如「後 0.42 m」）。
 *  2. 輔助線：倒車（R）從後保險桿；其他檔位在車速 < 15 km/h 或不明時從前保險桿。
 *     隨方向盤彎曲，單車模型與 VehicleSignals.kt 的 BicycleModel 同一套算式（改一邊要改另一邊）。
 *     0.5 / 1 / 2 / 3 m 距離標記（紅 / 黃 / 綠），兩線之間半透明行進範圍。
 *  3. 方向燈：HUD 左右兩側琥珀色箭頭 + 自車四角的燈，1.5 Hz 閃；雙黃燈兩邊一起閃。
 *
 * 用法：
 *   AVM_VEH.init(scene, THREE, hudParent, egoGroup[, opts]);   // hudParent 要是 position:relative 的容器
 *   每個 render 迴圈：AVM_VEH.update(lastPayload.veh, performance.now());
 *   veh 沒換就傳同一個物件；超過 staleMs 沒收到新物件、或 veh 為空 → 全部隱藏。
 * 座標：車身座標 x 右、z 前（公尺）；場景 x = x、z = -z。顏色假設 renderer.outputEncoding = sRGB。
 * guides() 等幾何函式不依賴 THREE / DOM，node 也能 require（一致性測試用）。
 */
(function (root) {
  'use strict';

  var CFG = {
    length: 4.841, width: 1.846, wheelbase: 2.888, rearOverhang: 1.12, track: 1.56,
    steerRatio: 15, maxSteerDeg: 522,           // 2.9 圈 lock-to-lock
    guideLen: 5, guideStep: 0.1, markers: [0.5, 1, 2, 3],
    fwdMaxKmh: 15,                              // 前進輔助線只在這個車速以下顯示
    fwdInPark: true,                            // P 檔也顯示前進輔助線（false = 只在 D/N/不明時顯示）
    staleMs: 1500,
    blinkHz: 1.5,
    lineW: 0.07,
    pdcCornerR: 0.35, pdcInnerX: 0.45, pdcSide: 0.3   // 雷達扇區外形：保險桿圓角、內外側分界、包到側面的長度
  };
  var PDC_SEG = [0.3, 0.45, 0.6, 0.8, 1.0, 1.2, 1.45, 1.7, 2.0];   // 距離分段（m）；< 0.3 = 連續紅；≥ 2.0 視為無障礙
  var STRAIGHT_K = 1e-12;

  function num(v) { return typeof v === 'number' && isFinite(v) ? v : null; }
  function pick(o, k) { return o && o[k] != null ? o[k] : CFG[k]; }

  // ---------- 單車模型（與 BicycleModel 同步） ----------
  function wheelAngleDeg(steerDeg, o) {
    if (num(steerDeg) === null) return 0;
    var ratio = pick(o, 'steerRatio'), maxW = pick(o, 'maxSteerDeg') / ratio, w = steerDeg / ratio;
    return w < -maxW ? -maxW : (w > maxW ? maxW : w);
  }
  function curvature(steerDeg, o) {
    return Math.tan(wheelAngleDeg(steerDeg, o) * Math.PI / 180) / pick(o, 'wheelbase');
  }
  function turningRadius(steerDeg, o) {
    var k = curvature(steerDeg, o);
    return Math.abs(k) < STRAIGHT_K ? Infinity : 1 / k;
  }
  // 保險桿中心沿弧線走 s 後，左右保險桿角 → out = [lx, lz, rx, rz]（車身座標）
  function corners(s, k, dir, bz, zra, hw, out) {
    var q = bz * k;
    var sA = dir * s / Math.sqrt(1 + q * q);
    var psi = k * sA, ax, az;
    if (Math.abs(k) < STRAIGHT_K) {
      ax = 0; az = sA;
    } else {
      var h = Math.sin(psi / 2);
      ax = 2 * h * h / k;
      az = Math.sin(psi) / k;
    }
    var c = Math.cos(psi), sn = Math.sin(psi), lx = -hw;
    out[0] = ax + lx * c + bz * sn;
    out[1] = zra + az - lx * sn + bz * c;
    out[2] = ax + hw * c + bz * sn;
    out[3] = zra + az - hw * sn + bz * c;
  }
  function guideParams(steerDeg, reverse, o) {
    var L = pick(o, 'length'), zra = -L / 2 + pick(o, 'rearOverhang'), len = Math.max(pick(o, 'guideLen'), 0);
    return {
      k: curvature(steerDeg, o), dir: reverse ? -1 : 1, zra: zra,
      bz: (reverse ? -L / 2 : L / 2) - zra, hw: pick(o, 'width') / 2, len: len,
      n: Math.max(1, Math.ceil(len / pick(o, 'guideStep') - 1e-9))
    };
  }
  /** 輔助線（車身座標）：{ reverse, dist[], left[[x,z]], right[[x,z]], markers[{ d, l:[x,z], r:[x,z] }] } */
  function guides(steerDeg, reverse, o) {
    var p = guideParams(steerDeg, !!reverse, o), out = [0, 0, 0, 0], i;
    var r = { reverse: !!reverse, dist: [], left: [], right: [], markers: [] };
    for (i = 0; i <= p.n; i++) {
      var s = p.len * i / p.n;
      corners(s, p.k, p.dir, p.bz, p.zra, p.hw, out);
      r.dist.push(s);
      r.left.push([out[0], out[1]]);
      r.right.push([out[2], out[3]]);
    }
    var mk = pick(o, 'markers');
    for (i = 0; i < mk.length; i++) {
      var m = mk[i];
      if (m < 0 || m > p.len + 1e-9) continue;
      corners(m, p.k, p.dir, p.bz, p.zra, p.hw, out);
      r.markers.push({ d: m, l: [out[0], out[1]], r: [out[2], out[3]] });
    }
    return r;
  }

  // ---------- 雷達分段 ----------
  function pdcSeg(d) {            // -1 = 不顯示；0 = 連續紅；1..8 = PDC_SEG 區段
    if (num(d) === null || d >= PDC_SEG[PDC_SEG.length - 1]) return -1;
    if (d < PDC_SEG[0]) return 0;
    for (var k = 1; k < PDC_SEG.length; k++) if (d < PDC_SEG[k]) return k;
    return -1;
  }
  function pdcZone(d) { return d < 0.3 ? 'c' : d < 0.6 ? 'r' : d < 1.2 ? 'y' : 'g'; }

  // ---------- 3D / HUD ----------
  var T = null, R3 = null, inited = false;
  var HEX = { green: 0x5ef08a, amber: 0xffc24b, red: 0xff5a4d, cont: 0xff2a1f, path: 0x9fe8ff, grid: 0x8fb4d0, lampOff: 0x4a3612 };
  var Y_PATH = 0.045, Y_LINE = 0.05, Y_PDC = 0.056;
  var st = { lastVeh: null, lastVehT: -1e12, lastNow: 0, live: false, steer: 0, mode: null, built: '',
    turn: null, turnT0: 0, blinkOn: false, readout: '', pdcMin: null };
  var pdc = { bg: [], bgMat: null, lit: [[], []], geo: [[], []], cur: [[], []] };
  var gd = {};
  var LC = {};   // 雷達分區顏色（線性）
  var lamp = { list: [], on: null, off: null };
  var hud = { l: null, r: null, pdc: null, s: null, v: null, u: null, cls: '', lOn: null, rOn: null };

  function lin(hex) { var c = new T.Color(hex); return c.convertSRGBToLinear ? c.convertSRGBToLinear() : c; }

  function geom(pos, idx, extra) {
    var g = new T.BufferGeometry();
    g.setAttribute('position', new T.BufferAttribute(pos, 3));
    if (extra) for (var k in extra) g.setAttribute(k, extra[k]);
    g.setIndex(idx);
    return g;
  }

  // 雷達扇區外形：圓角保險桿的一端（sg = +1 前、-1 後），參數 tau ∈ [-half, half]（左側 → 右側；直線段上 tau = x）
  function pdcContour(sg) {
    var hw = CFG.width / 2, rc = Math.min(CFG.pdcCornerR, hw - 0.05), xc = hw - rc, side = CFG.pdcSide;
    var ze = sg * CFG.length / 2, zc = sg * (CFG.length / 2 - rc), arc = Math.PI / 2 * rc;
    var half = side + arc + xc;
    return {
      half: half, xc: xc,
      at: function (tau, out) {    // out = [px, pz, nx, nz]：保險桿上的點與向外法線
        var t = half - Math.abs(tau), px, pz, nx, nz;
        if (t <= side) { px = -hw; pz = zc - sg * (side - t); nx = -1; nz = 0; }
        else if (t <= side + arc) {
          var a = (t - side) / rc;
          nx = -Math.cos(a); nz = sg * Math.sin(a);
          px = -xc + rc * nx; pz = zc + rc * nz;
        } else { px = -xc + (t - side - arc); pz = ze; nx = 0; nz = sg; }
        if (tau > 0) { px = -px; nx = -nx; }
        out[0] = px; out[1] = pz; out[2] = nx; out[3] = nz;
      }
    };
  }
  // 一條扇形帶：tau ∈ [t0, t1]、離保險桿 d0..d1 → 加進 acc
  function addBand(acc, ct, t0, t1, d0, d1) {
    var n = Math.max(2, Math.ceil((t1 - t0) / 0.04)), o = [0, 0, 0, 0], base = acc.pos.length / 3;
    for (var i = 0; i <= n; i++) {
      ct.at(t0 + (t1 - t0) * i / n, o);
      acc.pos.push(o[0] + o[2] * d0, Y_PDC, -(o[1] + o[3] * d0), o[0] + o[2] * d1, Y_PDC, -(o[1] + o[3] * d1));
      acc.d.push(d0, d1);
      if (i < n) { var a = base + i * 2; acc.idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    }
  }
  function buildPdc() {
    var GAP_T = 0.03, GAP_D = 0.02;
    pdc.bgMat = new T.ShaderMaterial({
      uniforms: { uPhase: { value: 0 }, uColor: { value: new T.Color(HEX.grid) } },
      vertexShader: 'attribute float aD; varying float vD;' +
        'void main(){ vD = aD; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
      fragmentShader: 'uniform float uPhase; uniform vec3 uColor; varying float vD;' +
        'void main(){ float w = vD - uPhase; float a = 0.07 + 0.3 * exp(-w * w * 30.0) * (1.0 - vD / 2.2);' +
        ' gl_FragColor = vec4(uColor, a); }',
      transparent: true, depthWrite: false, side: T.DoubleSide
    });
    for (var si = 0; si < 2; si++) {           // 0 = 前、1 = 後
      var ct = pdcContour(si === 0 ? 1 : -1), xi = Math.min(CFG.pdcInnerX, ct.xc);
      var secs = [[-ct.half, -xi], [-xi, 0], [0, xi], [xi, ct.half]];
      var bg = { pos: [], d: [], idx: [] };
      for (var j = 0; j < 4; j++) {
        var t0 = secs[j][0] + GAP_T, t1 = secs[j][1] - GAP_T;
        pdc.geo[si][j] = [];
        for (var k = 0; k < PDC_SEG.length; k++) {
          var d0 = (k === 0 ? 0.02 : PDC_SEG[k - 1] + GAP_D), d1 = (k === 0 ? PDC_SEG[0] : PDC_SEG[k]) - GAP_D;
          var one = { pos: [], d: [], idx: [] };
          addBand(one, ct, t0, t1, d0, d1);
          pdc.geo[si][j][k] = geom(new Float32Array(one.pos), one.idx);
          addBand(bg, ct, t0, t1, d0, d1);
        }
        var mat = new T.MeshBasicMaterial({ color: lin(HEX.green), transparent: true, opacity: 0.8,
          depthWrite: false, side: T.DoubleSide, toneMapped: false });
        var m = new T.Mesh(pdc.geo[si][j][1], mat);
        m.renderOrder = 24; m.frustumCulled = false; m.visible = false;
        R3.add(m);
        pdc.lit[si][j] = m;
        pdc.cur[si][j] = -2;
      }
      var bgm = new T.Mesh(geom(new Float32Array(bg.pos), bg.idx, { aD: new T.BufferAttribute(new Float32Array(bg.d), 1) }), pdc.bgMat);
      bgm.renderOrder = 23; bgm.frustumCulled = false; bgm.visible = false;
      R3.add(bgm);
      pdc.bg[si] = bgm;
    }
  }

  function zoneHex(s) { return s < 0.5 ? HEX.red : s < 1.0 ? HEX.amber : HEX.green; }
  function buildGuides() {
    var len = Math.max(CFG.guideLen, 0), n = Math.max(1, Math.ceil(len / CFG.guideStep - 1e-9)), N = n + 1, i, e, c;
    gd.n = n; gd.L = new Float64Array(N * 2); gd.R = new Float64Array(N * 2); gd.o = [0, 0, 0, 0];
    var mat = function () {
      return new T.MeshBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false,
        side: T.DoubleSide, toneMapped: false });
    };
    var dyn = function (mesh) {
      var a = mesh.geometry.getAttribute('position');
      if (a.setUsage) a.setUsage(T.DynamicDrawUsage);
      return a;
    };
    // 左右兩條線（帶狀）：顏色依距離分區，3 m 之後淡出
    var rPos = new Float32Array(2 * N * 2 * 3), rCol = new Float32Array(2 * N * 2 * 4), rIdx = [];
    for (var ln = 0; ln < 2; ln++) {
      for (i = 0; i < N; i++) {
        var s = len * i / n, a = s <= 3 || len <= 3 ? 0.95 : 0.95 - 0.8 * (s - 3) / (len - 3), v = (ln * N + i) * 2;
        c = lin(zoneHex(s));
        for (e = 0; e < 2; e++) { rCol[(v + e) * 4] = c.r; rCol[(v + e) * 4 + 1] = c.g; rCol[(v + e) * 4 + 2] = c.b; rCol[(v + e) * 4 + 3] = a; }
        if (i < n) rIdx.push(v, v + 1, v + 2, v + 1, v + 3, v + 2);
      }
    }
    gd.lines = new T.Mesh(geom(rPos, rIdx, { color: new T.BufferAttribute(rCol, 4) }), mat());
    gd.rPos = dyn(gd.lines);
    // 行進範圍
    var cPos = new Float32Array(N * 2 * 3), cCol = new Float32Array(N * 2 * 4), cIdx = [];
    c = lin(HEX.path);
    for (i = 0; i < N; i++) {
      var ca = 0.2 * (1 - i / n) + 0.02;
      for (e = 0; e < 2; e++) { cCol[(i * 2 + e) * 4] = c.r; cCol[(i * 2 + e) * 4 + 1] = c.g; cCol[(i * 2 + e) * 4 + 2] = c.b; cCol[(i * 2 + e) * 4 + 3] = ca; }
      if (i < n) cIdx.push(i * 2, i * 2 + 1, i * 2 + 2, i * 2 + 1, i * 2 + 3, i * 2 + 2);
    }
    gd.path = new T.Mesh(geom(cPos, cIdx, { color: new T.BufferAttribute(cCol, 4) }), mat());
    gd.cPos = dyn(gd.path);
    // 距離標記：每個標記左右各一段往內的短橫
    var M = CFG.markers.length, mPos = new Float32Array(M * 8 * 3), mCol = new Float32Array(M * 8 * 4), mIdx = [];
    for (i = 0; i < M; i++) {
      c = lin(CFG.markers[i] <= 0.5 ? HEX.red : CFG.markers[i] <= 1 ? HEX.amber : HEX.green);
      for (e = 0; e < 8; e++) { mCol[(i * 8 + e) * 4] = c.r; mCol[(i * 8 + e) * 4 + 1] = c.g; mCol[(i * 8 + e) * 4 + 2] = c.b; mCol[(i * 8 + e) * 4 + 3] = 0.95; }
      for (e = 0; e < 2; e++) { var q = i * 8 + e * 4; mIdx.push(q, q + 1, q + 2, q + 1, q + 3, q + 2); }
    }
    gd.marks = new T.Mesh(geom(mPos, mIdx, { color: new T.BufferAttribute(mCol, 4) }), mat());
    gd.mPos = dyn(gd.marks);
    [gd.path, gd.lines, gd.marks].forEach(function (m, k) {
      m.renderOrder = 20 + k; m.frustumCulled = false; m.visible = false; R3.add(m);
    });
  }
  function ribbon(P, ln) {
    var w = CFG.lineW / 2, N = gd.n + 1, pos = gd.rPos.array;
    for (var i = 0; i < N; i++) {
      var i0 = i > 0 ? i - 1 : i, i1 = i < N - 1 ? i + 1 : i;
      var tx = P[i1 * 2] - P[i0 * 2], tz = -(P[i1 * 2 + 1] - P[i0 * 2 + 1]), l = Math.sqrt(tx * tx + tz * tz) || 1;
      var nx = -tz / l * w, nz = tx / l * w, x = P[i * 2], z = -P[i * 2 + 1], v = (ln * N + i) * 6;
      pos[v] = x + nx; pos[v + 1] = Y_LINE; pos[v + 2] = z + nz;
      pos[v + 3] = x - nx; pos[v + 4] = Y_LINE; pos[v + 5] = z - nz;
    }
  }
  function layoutGuides(steer, reverse) {
    var p = guideParams(steer, reverse, null), o = gd.o, i;
    for (i = 0; i <= p.n; i++) {
      corners(p.len * i / p.n, p.k, p.dir, p.bz, p.zra, p.hw, o);
      gd.L[i * 2] = o[0]; gd.L[i * 2 + 1] = o[1]; gd.R[i * 2] = o[2]; gd.R[i * 2 + 1] = o[3];
    }
    ribbon(gd.L, 0); ribbon(gd.R, 1);
    var cp = gd.cPos.array;
    for (i = 0; i <= p.n; i++) {
      cp[i * 6] = gd.L[i * 2]; cp[i * 6 + 1] = Y_PATH; cp[i * 6 + 2] = -gd.L[i * 2 + 1];
      cp[i * 6 + 3] = gd.R[i * 2]; cp[i * 6 + 4] = Y_PATH; cp[i * 6 + 5] = -gd.R[i * 2 + 1];
    }
    var mp = gd.mPos.array, TL = 0.32, TH = 0.07;
    for (i = 0; i < CFG.markers.length; i++) {
      var m = CFG.markers[i], b = i * 8 * 3;
      if (m > p.len + 1e-9) { for (var z = 0; z < 24; z++) mp[b + z] = 0; continue; }
      corners(m, p.k, p.dir, p.bz, p.zra, p.hw, o);
      var lx = o[0], lz = -o[1], rx = o[2], rz = -o[3], ux = rx - lx, uz = rz - lz, ul = Math.sqrt(ux * ux + uz * uz) || 1;
      ux /= ul; uz /= ul;
      var tx = -uz * TH / 2, tz = ux * TH / 2;
      var q = [lx, lz, ux, uz, rx, rz, -ux, -uz];
      for (var e = 0; e < 2; e++) {
        var sx = q[e * 4], sz = q[e * 4 + 1], dx = q[e * 4 + 2] * TL, dz = q[e * 4 + 3] * TL, v = b + e * 12;
        mp[v] = sx + tx; mp[v + 1] = Y_LINE; mp[v + 2] = sz + tz;
        mp[v + 3] = sx - tx; mp[v + 4] = Y_LINE; mp[v + 5] = sz - tz;
        mp[v + 6] = sx + dx + tx; mp[v + 7] = Y_LINE; mp[v + 8] = sz + dz + tz;
        mp[v + 9] = sx + dx - tx; mp[v + 10] = Y_LINE; mp[v + 11] = sz + dz - tz;
      }
    }
    gd.rPos.needsUpdate = true; gd.cPos.needsUpdate = true; gd.mPos.needsUpdate = true;
  }

  // 自車模型外框（略過影子等扁平/半透明貼片）；沒有模型就用車身尺寸
  function modelBox(ego) {
    var box = new T.Box3(), tmp = new T.Box3(), any = false;
    if (ego) {
      ego.updateMatrixWorld(true);
      ego.traverse(function (ob) {
        if (!ob.isMesh || !ob.geometry || (ob.material && ob.material.transparent)) return;
        if (!ob.geometry.boundingBox) ob.geometry.computeBoundingBox();
        tmp.copy(ob.geometry.boundingBox).applyMatrix4(ob.matrixWorld);
        if (tmp.max.y - tmp.min.y < 0.02) return;
        if (any) box.union(tmp); else box.copy(tmp);
        any = true;
      });
    }
    if (!any) box.set(new T.Vector3(-CFG.width / 2, 0, -CFG.length / 2), new T.Vector3(CFG.width / 2, 1.45, CFG.length / 2));
    return box;
  }
  function glowTexture() {
    var c = document.createElement('canvas'); c.width = c.height = 64;
    var g = c.getContext('2d'), r = g.createRadialGradient(32, 32, 1, 32, 32, 31);
    r.addColorStop(0, 'rgba(255,255,255,1)'); r.addColorStop(0.25, 'rgba(255,255,255,.55)'); r.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = r; g.fillRect(0, 0, 64, 64);
    return new T.CanvasTexture(c);
  }
  function buildLamps(ego) {
    var b = modelBox(ego), y = b.min.y + 0.39 * (b.max.y - b.min.y);
    var geo = new T.BoxGeometry(0.16, 0.08, 0.12);
    lamp.on = new T.MeshBasicMaterial({ color: lin(HEX.amber), toneMapped: false });
    lamp.off = new T.MeshBasicMaterial({ color: lin(HEX.lampOff), toneMapped: false });
    var glow = new T.SpriteMaterial({ map: glowTexture(), color: lin(HEX.amber), blending: T.AdditiveBlending,
      transparent: true, depthWrite: false, toneMapped: false });
    // 場景裡車頭朝 -Z：前角 z = min.z、後角 z = max.z；左 = -x
    [[-1, -1], [-1, 1], [1, -1], [1, 1]].forEach(function (cz) {
      var sx = cz[0], sz = cz[1];
      var x = sx < 0 ? b.min.x + 0.06 : b.max.x - 0.06, z = sz < 0 ? b.min.z + 0.04 : b.max.z - 0.04;
      var m = new T.Mesh(geo, lamp.off);
      m.position.set(x - R3.position.x, y, z - R3.position.z);
      var sp = new T.Sprite(glow);
      sp.position.set(x + sx * 0.12 - R3.position.x, y, z + sz * 0.12 - R3.position.z);
      sp.scale.set(1.1, 1.1, 1);
      m.visible = sp.visible = false;
      R3.add(m); R3.add(sp);
      lamp.list.push({ left: sx < 0, mesh: m, glow: sp });
    });
  }

  var CSS =
    '.vh-off{display:none!important}' +
    '.vh-turn{position:absolute;top:50%;width:58px;height:58px;margin-top:-29px;z-index:6;pointer-events:none;color:var(--amber,#ffc24b);opacity:.14}' +
    '.vh-turn.vh-l{left:12px}.vh-turn.vh-r{right:12px}' +
    '.vh-turn.vh-on{opacity:1;filter:drop-shadow(0 0 9px rgba(255,194,75,.9))}' +
    '.vh-turn svg{display:block;width:100%;height:100%}' +
    '.vh-pdc{position:absolute;left:50%;bottom:64px;transform:translateX(-50%);z-index:6;pointer-events:none;display:flex;align-items:baseline;gap:6px;' +
    'padding:5px 14px;border-radius:999px;background:var(--panel,rgba(14,20,28,.82));border:1px solid var(--line,rgba(120,160,200,.18));' +
    'color:var(--fg,#e8f1f8);font-weight:700;white-space:nowrap;font-variant-numeric:tabular-nums}' +
    '.vh-pdc .vh-s{font-size:12px;color:var(--muted,#8ba1b4);letter-spacing:.12em}' +
    '.vh-pdc b{font-size:20px;line-height:1}.vh-pdc i{font-style:normal;font-size:12px;color:var(--muted,#8ba1b4)}' +
    '.vh-pdc.z-g b{color:var(--green,#5ef08a)}.vh-pdc.z-y b{color:var(--amber,#ffc24b)}' +
    '.vh-pdc.z-r b,.vh-pdc.z-c b{color:#ff8278}.vh-pdc.z-r{border-color:rgba(255,90,77,.6)}' +
    '.vh-pdc.z-c{border-color:var(--red,#ff5a4d);background:rgba(255,90,77,.26);animation:vhPulse .5s infinite}' +
    '@keyframes vhPulse{50%{background:rgba(255,90,77,.06)}}';
  function arrowSvg(right) {
    var d = right ? 'M60 32 L34 8 V21 H6 V43 H34 V56 Z' : 'M4 32 L30 8 V21 H58 V43 H30 V56 Z';
    return '<svg viewBox="0 0 64 64" aria-hidden="true"><path d="' + d + '" fill="currentColor"/></svg>';
  }
  function buildHud(parent) {
    if (!parent || typeof document === 'undefined') return;
    if (!document.getElementById('vh-style')) {
      var s = document.createElement('style');
      s.id = 'vh-style'; s.textContent = CSS;
      document.head.appendChild(s);
    }
    function mk(cls, html) {
      var e = document.createElement('div');
      e.className = cls; e.innerHTML = html;
      parent.appendChild(e);
      return e;
    }
    hud.l = mk('vh-turn vh-l vh-off', arrowSvg(false));
    hud.r = mk('vh-turn vh-r vh-off', arrowSvg(true));
    hud.l.setAttribute('role', 'img'); hud.l.setAttribute('aria-label', '左轉方向燈');
    hud.r.setAttribute('role', 'img'); hud.r.setAttribute('aria-label', '右轉方向燈');
    hud.pdc = mk('vh-pdc vh-off', '<span class="vh-s"></span><b></b><i>m</i>');
    hud.pdc.setAttribute('role', 'status'); hud.pdc.setAttribute('aria-label', '雷達最近距離');
    hud.s = hud.pdc.children[0]; hud.v = hud.pdc.children[1]; hud.u = hud.pdc.children[2];
  }
  function showEl(el, on) {
    if (!el) return;
    var c = el.classList;
    if (on === c.contains('vh-off')) c.toggle('vh-off', !on);
  }

  // ---------- 每幀 ----------
  function hasData(v) {
    return v.turn != null || v.gear != null || num(v.steer) !== null || num(v.speed) !== null ||
      !!(v.pdc && (v.pdc.f || v.pdc.r));
  }
  function hideAll() {
    st.live = false; st.mode = null; st.turn = null; st.blinkOn = false; st.readout = ''; st.pdcMin = null;
    if (!inited) return;
    for (var si = 0; si < 2; si++) {
      pdc.bg[si].visible = false;
      for (var j = 0; j < 4; j++) { pdc.lit[si][j].visible = false; pdc.cur[si][j] = -2; }
    }
    gd.path.visible = gd.lines.visible = gd.marks.visible = false;
    lamp.list.forEach(function (l) { l.mesh.visible = l.glow.visible = false; });
    showEl(hud.l, false); showEl(hud.r, false); showEl(hud.pdc, false);
    hud.lOn = hud.rOn = null;
  }

  function updateTurn(turn, now) {
    if (turn !== 'L' && turn !== 'R' && turn !== 'H') turn = null;
    if (turn !== st.turn) { st.turn = turn; st.turnT0 = now; }
    var L = turn === 'L' || turn === 'H', R = turn === 'R' || turn === 'H';
    var per = 1000 / CFG.blinkHz, on = !!turn && ((now - st.turnT0) % per) < per / 2;
    st.blinkOn = on;
    for (var i = 0; i < lamp.list.length; i++) {
      var l = lamp.list[i], act = l.left ? L : R;
      l.mesh.visible = act;
      l.mesh.material = act && on ? lamp.on : lamp.off;
      l.glow.visible = act && on;
    }
    showEl(hud.l, L); showEl(hud.r, R);
    var lOn = L && on, rOn = R && on;
    if (hud.l && lOn !== hud.lOn) { hud.l.classList.toggle('vh-on', lOn); hud.lOn = lOn; }
    if (hud.r && rOn !== hud.rOn) { hud.r.classList.toggle('vh-on', rOn); hud.rOn = rOn; }
  }

  function updatePdc(p, now) {
    var best = Infinity, bestSide = -1, active = '', sec = now / 1000;
    for (var si = 0; si < 2; si++) {
      var arr = p ? (si === 0 ? p.f : p.r) : null, on = !!(arr && arr.length);
      pdc.bg[si].visible = on;
      if (on) active += si === 0 ? '前' : '後';
      for (var j = 0; j < 4; j++) {
        var m = pdc.lit[si][j], d = on ? num(arr[j]) : null, k = d === null ? -1 : pdcSeg(d);
        if (k < 0) { m.visible = false; pdc.cur[si][j] = -1; continue; }
        if (d < best) { best = d; bestSide = si; }
        if (pdc.cur[si][j] !== k) { m.geometry = pdc.geo[si][j][k]; pdc.cur[si][j] = k; }
        var z = pdcZone(d), mt = m.material;
        mt.color.copy(LC[z]);
        mt.opacity = z === 'c' ? 0.7 + 0.3 * Math.abs(Math.sin(sec * Math.PI * 4)) :
          z === 'r' ? 0.6 + 0.35 * Math.abs(Math.sin(sec * Math.PI * 2.5)) : z === 'y' ? 0.85 : 0.75;
        m.visible = true;
      }
    }
    pdc.bgMat.uniforms.uPhase.value = (now % 1300) / 1300 * 2.3;
    // HUD：最近距離
    st.pdcMin = bestSide < 0 ? null : { side: bestSide === 0 ? 'f' : 'r', d: best };
    if (!active) { showEl(hud.pdc, false); st.readout = ''; return; }
    var txt, cls, s, v;
    if (bestSide < 0) { s = active; v = '無障礙'; cls = 'z-g'; }
    else { s = bestSide === 0 ? '前' : '後'; v = best.toFixed(2); cls = 'z-' + pdcZone(best); }
    txt = s + ' ' + v + (bestSide < 0 ? '' : ' m');
    if (hud.pdc) {
      showEl(hud.pdc, true);
      if (txt !== st.readout) { hud.s.textContent = s; hud.v.textContent = v; showEl(hud.u, bestSide >= 0); }
      if (cls !== hud.cls) { hud.pdc.className = 'vh-pdc ' + cls; hud.cls = cls; }
    }
    st.readout = txt;
  }

  function updateGuides(v, dt) {
    var mode = v.gear === 'R' ? 'rev' :
      (v.gear === 'P' && !CFG.fwdInPark) ? null :
      (num(v.speed) === null || v.speed < CFG.fwdMaxKmh ? 'fwd' : null);
    var target = num(v.steer) === null ? 0 : v.steer;
    if (mode !== st.mode) st.steer = target;           // 剛出現時不要從舊角度滑過來
    else st.steer += (target - st.steer) * (1 - Math.exp(-dt / 0.12));
    st.mode = mode;
    gd.path.visible = gd.lines.visible = gd.marks.visible = !!mode;
    if (!mode) return;
    var key = mode + (Math.round(st.steer * 20) / 20);
    if (key !== st.built) { layoutGuides(st.steer, mode === 'rev'); st.built = key; }
  }

  function init(scene, THREE, hudParent, egoGroup, opts) {
    if (inited) return API;
    T = THREE;
    if (opts) for (var k in opts) if (Object.prototype.hasOwnProperty.call(opts, k)) CFG[k] = opts[k];
    LC = { g: lin(HEX.green), y: lin(HEX.amber), r: lin(HEX.red), c: lin(HEX.cont) };
    R3 = new T.Group();
    R3.name = 'avm-veh';
    if (egoGroup) R3.position.set(egoGroup.position.x, 0, egoGroup.position.z);   // 車身座標原點 = 自車模型位置
    scene.add(R3);
    buildPdc();
    buildGuides();
    buildLamps(egoGroup);
    buildHud(hudParent);
    inited = true;
    API.root = R3;
    hideAll();
    return API;
  }

  function update(veh, now) {
    if (!inited) return;
    if (now == null) now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    var dt = st.lastNow ? Math.min(Math.max((now - st.lastNow) / 1000, 0), 0.2) : 0;
    st.lastNow = now;
    if (veh !== st.lastVeh) { st.lastVeh = veh; if (veh) st.lastVehT = now; }
    if (!veh || typeof veh !== 'object' || now - st.lastVehT > CFG.staleMs || !hasData(veh)) {
      if (st.live || st.mode || st.turn) hideAll();
      st.live = false;
      return;
    }
    st.live = true;
    updateTurn(veh.turn, now);
    updatePdc(veh.pdc, now);
    updateGuides(veh, dt);
  }

  /** 目前顯示狀態（除錯 / 測試用） */
  function state() {
    return { live: st.live, turn: st.turn, blinkOn: st.blinkOn, guide: st.mode, steer: st.steer,
      pdcMin: st.pdcMin, readout: st.readout };
  }

  var API = {
    CFG: CFG, PDC_SEG: PDC_SEG,
    wheelAngleDeg: wheelAngleDeg, curvature: curvature, turningRadius: turningRadius, guides: guides,
    pdcSeg: pdcSeg, pdcZone: pdcZone,
    init: init, update: update, state: state, root: null
  };
  if (root) root.AVM_VEH = API;
  if (typeof module === 'object' && module && module.exports) module.exports = API;
})(typeof window !== 'undefined' ? window : null);
