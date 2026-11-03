/*
 * vehicle3d.js — 車身訊號的 3D 顯示（ES5、three.js r128）
 *  1. BMW 式雷達（PDC）：前後各 4 個感測扇區，畫在保險桿外的地面，依距離分段上色
 *     （綠 > 1.2 m、黃 0.6–1.2、紅 < 0.6、< 0.3 連續紅），每顆只亮最近那一段；背景有往外擴散的掃描波。
 *     感測器故障（null）畫灰色斜線。HUD 顯示最近距離（例如「後 0.42 m」，R 檔優先顯示後方）。
 *  2. 輔助線：倒車（R）從後保險桿；D/N（fwdInPark 時含 P）且車速 < 15 km/h 或不明時從前保險桿；
 *     檔位不明時要車速 < 15 km/h 且方向盤角度已知才畫前進線。
 *     隨方向盤彎曲，單車模型與 VehicleSignals.kt 的 BicycleModel 同一套算式（改一邊要改另一邊）。
 *     0.5 / 1 / 2 / 3 m 距離標記（紅 / 黃 / 綠）；轉彎時另畫內側車身（後軸處）軌跡 = 內輪差邊界，
 *     行進範圍填在內側軌跡與外側保險桿角線之間；倒車時另畫外側車頭的甩出弧。
 *     雷達亮段、輔助線、標記被自車模型擋住的部分以半透明 x-ray 再畫一次。
 *  3. 方向燈：HUD 左右兩側琥珀色箭頭 + 自車四角的燈；veh.lit 有值時跟著實車燈的亮暗，否則自己 1.5 Hz 閃；
 *     雙黃燈兩邊一起閃。
 *
 * 用法：
 *   AVM_VEH.init(scene, THREE, hudParent, egoGroup[, opts]);   // hudParent 要是 position:relative 的容器
 *   每個 render 迴圈：AVM_VEH.update(lastPayload.veh, performance.now());
 *   veh 沒換就傳同一個物件；超過 staleMs 沒收到新物件、或 veh 為空 → 全部隱藏。
 *   自車模型縮放 / 換模型後呼叫 AVM_VEH.relayout(egoGroup) 重新擺方向燈。
 *   AVM_VEH.suggestView()：'rear'（倒車）/ 'top'（前方雷達 < 1.2 m）/ null，整合端可據此切視角。
 * 座標：車身座標 x 右、z 前（公尺）；場景 x = x、z = -z。顏色假設 renderer.outputEncoding = sRGB。
 * guides() 等幾何函式不依賴 THREE / DOM，node 也能 require（一致性測試用）。
 */
(function (root) {
  'use strict';

  var CFG = {
    length: 4.841, width: 1.846, wheelbase: 2.888, rearOverhang: 1.12, track: 1.56,
    steerRatio: 15, maxSteerDeg: 522,           // 2.9 圈 lock-to-lock（假設值；校正後由 VehicleGeometry.jsOpts() 帶入）
    guideLen: 5, guideStep: 0.1, markers: [0.5, 1, 2, 3],
    swingM: 2.5,                                // 倒車時車頭甩出弧的長度（保險桿中心走的距離）
    innerMaxR: 80,                              // 轉彎半徑小於這個才畫內側軌跡 / 甩出弧（幾乎直行時會跟邊線重疊、左右亂翻）
    fwdMaxKmh: 15,                              // 前進輔助線只在這個車速以下顯示
    fwdInPark: false,                           // P 檔也顯示前進輔助線
    staleMs: 1500,
    blinkHz: 1.5,
    lineW: 0.12, subLineW: 0.09,                // 邊線 / 內側軌跡與甩出弧的寬度（m）
    markL: 0.45, markW: 0.1,                    // 距離標記：往內的長度、寬度（m）
    xray: true, xrayOpacity: 0.5,               // 被自車擋住的部分以這個不透明度再畫一次（白色車身上 0.35 太淡）
    readoutTop: 48, readoutBottom: null,        // 讀數膠囊的位置（px）；readoutTop = null 時改用 readoutBottom
    pdcCornerR: 0.35, pdcInnerX: 0.45, pdcSide: 0.3   // 雷達扇區外形：保險桿圓角、內外側分界、包到側面的長度
  };
  // 距離分段（m），分界對齊顏色（0.3 連續紅 / 0.6 紅 / 1.2 黃）；每段亮帶至少 0.24 m；≥ 2.0 視為無障礙
  var PDC_SEG = [0.3, 0.6, 0.9, 1.2, 1.6, 2.0];
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
  // 保險桿中心（相對後軸 z = bz）沿弧線走 s 後的姿態 → out = [ax, az, cos ψ, sin ψ]
  function pose(s, k, dir, bz, out) {
    var q = bz * k;
    var sA = dir * s / Math.sqrt(1 + q * q);
    var psi = k * sA;
    if (Math.abs(k) < STRAIGHT_K) {
      out[0] = 0; out[1] = sA;
    } else {
      var h = Math.sin(psi / 2);
      out[0] = 2 * h * h / k;
      out[1] = Math.sin(psi) / k;
    }
    out[2] = Math.cos(psi);
    out[3] = Math.sin(psi);
  }
  // 車身點（px 右、pz 相對後軸往前）在姿態 p 下的位置 → out[o], out[o + 1] = x, z（車身座標）
  function bodyPoint(p, zra, px, pz, out, o) {
    out[o] = p[0] + px * p[2] + pz * p[3];
    out[o + 1] = zra + p[1] - px * p[3] + pz * p[2];
  }
  function guideParams(steerDeg, reverse, o) {
    var L = pick(o, 'length'), zra = -L / 2 + pick(o, 'rearOverhang'), len = Math.max(pick(o, 'guideLen'), 0);
    var k = curvature(steerDeg, o);
    return {
      k: k, dir: reverse ? -1 : 1, zra: zra,
      bz: (reverse ? -L / 2 : L / 2) - zra, fz: L / 2 - zra, hw: pick(o, 'width') / 2, len: len,
      n: Math.max(1, Math.ceil(len / pick(o, 'guideStep') - 1e-9)),
      side: Math.abs(k) < STRAIGHT_K ? 0 : (k > 0 ? 1 : -1), swingM: pick(o, 'swingM')
    };
  }
  /**
   * 輔助線（車身座標）：{ reverse, dist[], left[[x,z]], right[[x,z]], markers[{ d, l:[x,z], r:[x,z] }],
   *   innerSide（+1 右 / -1 左 / 0 直行）, inner[[x,z]]|null（內側車身在後軸處的軌跡）,
   *   swing[[x,z]]|null（倒車：外側前保險桿角的甩出弧，dist ≤ swingM） }
   */
  function guides(steerDeg, reverse, o) {
    var p = guideParams(steerDeg, !!reverse, o), ps = [0, 0, 0, 0], t = [0, 0], i;
    var r = { reverse: !!reverse, dist: [], left: [], right: [], markers: [], innerSide: p.side,
      inner: p.side !== 0 ? [] : null, swing: p.side !== 0 && reverse && p.swingM > 0 ? [] : null };
    for (i = 0; i <= p.n; i++) {
      var s = p.len * i / p.n;
      pose(s, p.k, p.dir, p.bz, ps);
      r.dist.push(s);
      bodyPoint(ps, p.zra, -p.hw, p.bz, t, 0); r.left.push([t[0], t[1]]);
      bodyPoint(ps, p.zra, p.hw, p.bz, t, 0); r.right.push([t[0], t[1]]);
      if (r.inner) { bodyPoint(ps, p.zra, p.side * p.hw, 0, t, 0); r.inner.push([t[0], t[1]]); }
      if (r.swing && s <= p.swingM + 1e-9) { bodyPoint(ps, p.zra, -p.side * p.hw, p.fz, t, 0); r.swing.push([t[0], t[1]]); }
    }
    var mk = pick(o, 'markers');
    for (i = 0; i < mk.length; i++) {
      var m = mk[i], l;
      if (m < 0 || m > p.len + 1e-9) continue;
      pose(m, p.k, p.dir, p.bz, ps);
      bodyPoint(ps, p.zra, -p.hw, p.bz, t, 0); l = [t[0], t[1]];
      bodyPoint(ps, p.zra, p.hw, p.bz, t, 0);
      r.markers.push({ d: m, l: l, r: [t[0], t[1]] });
    }
    return r;
  }

  // ---------- 顯示邏輯（純函式，node 測試用） ----------
  /** 輔助線模式：'rev' / 'fwd' / null。要有正面證據才畫前進線。 */
  function guideMode(v, o) {
    if (!v) return null;
    var g = v.gear, sp = num(v.speed), max = pick(o, 'fwdMaxKmh'), slow = sp === null || sp < max;
    if (g === 'R') return 'rev';
    if (g === 'D' || g === 'N' || (g === 'P' && pick(o, 'fwdInPark'))) return slow ? 'fwd' : null;
    if (g == null) return sp !== null && sp < max && num(v.steer) !== null ? 'fwd' : null;
    return null;
  }
  /** 讀數顯示哪一側：f / r = 前後最近距離（null = 沒有）。R 檔看後方、D/N 看前方，另一側 < 0.3 m 且更近時改看那側。 */
  function readoutSide(gear, f, r) {
    if (f === null && r === null) return null;
    if (f === null) return 'r';
    if (r === null) return 'f';
    if (gear === 'R') return f < 0.3 && f < r ? 'f' : 'r';
    if (gear === 'D' || gear === 'N') return r < 0.3 && r < f ? 'r' : 'f';
    return f <= r ? 'f' : 'r';
  }

  // ---------- 雷達分段 ----------
  function pdcSeg(d) {            // -1 = 不顯示；0 = 連續紅；1.. = PDC_SEG 區段
    if (num(d) === null || d >= PDC_SEG[PDC_SEG.length - 1]) return -1;
    if (d < PDC_SEG[0]) return 0;
    for (var k = 1; k < PDC_SEG.length; k++) if (d < PDC_SEG[k]) return k;
    return -1;
  }
  function pdcZone(d) { return d < 0.3 ? 'c' : d < 0.6 ? 'r' : d < 1.2 ? 'y' : 'g'; }
  function isFault(v) { return v === null || (typeof v === 'number' && isNaN(v)); }

  // ---------- 3D / HUD ----------
  var T = null, R3 = null, inited = false;
  var HEX = { green: 0x5ef08a, amber: 0xffc24b, red: 0xff5a4d, cont: 0xff2a1f, path: 0x9fe8ff, inner: 0xc8f4ff,
    grid: 0x8fb4d0, fault: 0x8a96a3, lampOff: 0x4a3612 };
  var Y_PATH = 0.045, Y_LINE = 0.05, Y_PDC = 0.056;
  var st = { lastVeh: null, lastVehT: -1e12, lastNow: 0, live: false, steer: 0, mode: null, built: '',
    turn: null, turnT0: 0, blinkOn: false, readout: '', pdcMin: null, pdcF: null, pdcR: null, faults: 0, pdcActive: false };
  var pdc = { bg: [], bgMat: null, faultMat: null, lit: [[], []], xr: [[], []], mat: [[], []], geo: [[], []], fault: [[], []], cur: [[], []] };
  var gd = {};
  var LC = {};   // 雷達分區顏色（線性）
  var lamp = { list: [], on: null, off: null };
  var hud = { l: null, r: null, pdc: null, s: null, v: null, u: null, o: null, cls: '', lOn: null, rOn: null, txt: '' };

  function lin(hex) { var c = new T.Color(hex); return c.convertSRGBToLinear ? c.convertSRGBToLinear() : c; }

  function geom(pos, idx, extra) {
    var g = new T.BufferGeometry();
    g.setAttribute('position', new T.BufferAttribute(pos, 3));
    if (extra) for (var k in extra) g.setAttribute(k, extra[k]);
    g.setIndex(idx);
    return g;
  }
  // x-ray：只畫在被別的東西（自車）擋住的地方
  function xrayMaterial(opts) {
    var m = new T.MeshBasicMaterial(opts);
    m.transparent = true; m.depthWrite = false; m.depthFunc = T.GreaterDepth;
    m.side = T.DoubleSide; m.toneMapped = false;
    return m;
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
  function band(ct, t0, t1, d0, d1) {
    var one = { pos: [], d: [], idx: [] };
    addBand(one, ct, t0, t1, d0, d1);
    return geom(new Float32Array(one.pos), one.idx);
  }
  function buildPdc() {
    var GAP_T = 0.03, GAP_D = 0.03;
    pdc.bgMat = new T.ShaderMaterial({
      uniforms: { uPhase: { value: 0 }, uColor: { value: new T.Color(HEX.grid) } },
      vertexShader: 'attribute float aD; varying float vD;' +
        'void main(){ vD = aD; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
      fragmentShader: 'uniform float uPhase; uniform vec3 uColor; varying float vD;' +
        'void main(){ float w = vD - uPhase; float a = 0.07 + 0.3 * exp(-w * w * 30.0) * (1.0 - vD / 2.2);' +
        ' gl_FragColor = vec4(uColor, a); }',
      transparent: true, depthWrite: false, side: T.DoubleSide
    });
    // 故障：灰色斜線（世界座標條紋，週期 0.5 m）
    pdc.faultMat = new T.ShaderMaterial({
      uniforms: { uColor: { value: new T.Color(HEX.fault) } },
      vertexShader: 'varying vec2 vW;' +
        'void main(){ vec4 w = modelMatrix * vec4(position, 1.0); vW = w.xz; gl_Position = projectionMatrix * viewMatrix * w; }',
      fragmentShader: 'uniform vec3 uColor; varying vec2 vW;' +
        'void main(){ float s = fract((vW.x + vW.y) * 2.0); gl_FragColor = vec4(uColor, s < 0.5 ? 0.6 : 0.12); }',
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
          var d0 = (k === 0 ? 0.02 : PDC_SEG[k - 1] + GAP_D), d1 = PDC_SEG[k] - GAP_D;
          pdc.geo[si][j][k] = band(ct, t0, t1, d0, d1);
          addBand(bg, ct, t0, t1, d0, d1);
        }
        pdc.fault[si][j] = band(ct, t0, t1, 0.02, PDC_SEG[1] - GAP_D);
        var mat = new T.MeshBasicMaterial({ color: lin(HEX.green), transparent: true, opacity: 0.8,
          depthWrite: false, side: T.DoubleSide, toneMapped: false });
        var m = new T.Mesh(pdc.geo[si][j][1], mat);
        m.renderOrder = 24; m.frustumCulled = false; m.visible = false;
        R3.add(m);
        var xm = xrayMaterial({});
        xm.color = mat.color;                           // 顏色跟本體共用同一個 Color 物件（建構子的 color 會複製）
        var x = new T.Mesh(m.geometry, xm);
        x.renderOrder = 34; x.frustumCulled = false; x.visible = false;
        R3.add(x);
        pdc.lit[si][j] = m; pdc.mat[si][j] = mat; pdc.xr[si][j] = x;
        pdc.cur[si][j] = -2;
      }
      var bgm = new T.Mesh(geom(new Float32Array(bg.pos), bg.idx, { aD: new T.BufferAttribute(new Float32Array(bg.d), 1) }), pdc.bgMat);
      bgm.renderOrder = 23; bgm.frustumCulled = false; bgm.visible = false;
      R3.add(bgm);
      pdc.bg[si] = bgm;
    }
  }

  function zoneHex(s) { return s < 0.5 ? HEX.red : s < 1.0 ? HEX.amber : HEX.green; }
  // 帶狀線 4 條：0 左、1 右（保險桿角）、2 內側車身軌跡、3 倒車甩出弧
  var RIB_L = 0, RIB_R = 1, RIB_I = 2, RIB_S = 3, RIBS = 4;
  function buildGuides() {
    var len = Math.max(CFG.guideLen, 0), n = Math.max(1, Math.ceil(len / CFG.guideStep - 1e-9)), N = n + 1, i, e, c, a;
    gd.n = n; gd.ps = [0, 0, 0, 0];
    gd.P = [new Float64Array(N * 2), new Float64Array(N * 2), new Float64Array(N * 2), new Float64Array(N * 2)];
    gd.nSw = 0;
    for (i = 0; i < N; i++) if (len * i / n <= CFG.swingM + 1e-9) gd.nSw = i + 1;
    if (!(CFG.swingM > 0)) gd.nSw = 0;
    var mat = function () {
      return new T.MeshBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false,
        side: T.DoubleSide, toneMapped: false });
    };
    var dyn = function (mesh) {
      var at = mesh.geometry.getAttribute('position');
      if (at.setUsage) at.setUsage(T.DynamicDrawUsage);
      return at;
    };
    var setC = function (arr, v, col, alpha) { arr[v * 4] = col.r; arr[v * 4 + 1] = col.g; arr[v * 4 + 2] = col.b; arr[v * 4 + 3] = alpha; };
    // 帶狀線：邊線顏色依距離分區、3 m 之後淡出；內側軌跡淡青色；甩出弧琥珀色、越遠越淡
    var rPos = new Float32Array(RIBS * N * 2 * 3), rCol = new Float32Array(RIBS * N * 2 * 4), rIdx = [];
    var cInner = lin(HEX.inner), cSwing = lin(HEX.amber);
    for (var ln = 0; ln < RIBS; ln++) {
      for (i = 0; i < N; i++) {
        var s = len * i / n, fade = s <= 3 || len <= 3 ? 1 : 1 - 0.85 * (s - 3) / (len - 3), v = (ln * N + i) * 2;
        if (ln === RIB_S) { c = cSwing; a = i < gd.nSw ? 0.9 - 0.65 * i / Math.max(gd.nSw - 1, 1) : 0; }
        else if (ln === RIB_I) { c = cInner; a = 0.85 * fade; }
        else { c = lin(zoneHex(s)); a = 0.95 * fade; }
        for (e = 0; e < 2; e++) setC(rCol, v + e, c, a);
        if (i < n) rIdx.push(v, v + 1, v + 2, v + 1, v + 3, v + 2);
      }
    }
    gd.lines = new T.Mesh(geom(rPos, rIdx, { color: new T.BufferAttribute(rCol, 4) }), mat());
    gd.rPos = dyn(gd.lines);
    // 行進範圍
    var cPos = new Float32Array(N * 2 * 3), cCol = new Float32Array(N * 2 * 4), cIdx = [];
    c = lin(HEX.path);
    for (i = 0; i < N; i++) {
      for (e = 0; e < 2; e++) setC(cCol, i * 2 + e, c, 0.2 * (1 - i / n) + 0.02);
      if (i < n) cIdx.push(i * 2, i * 2 + 1, i * 2 + 2, i * 2 + 1, i * 2 + 3, i * 2 + 2);
    }
    gd.path = new T.Mesh(geom(cPos, cIdx, { color: new T.BufferAttribute(cCol, 4) }), mat());
    gd.cPos = dyn(gd.path);
    // 距離標記：每個標記左右各一段往內的短橫
    var M = CFG.markers.length, mPos = new Float32Array(M * 8 * 3), mCol = new Float32Array(M * 8 * 4), mIdx = [];
    for (i = 0; i < M; i++) {
      c = lin(CFG.markers[i] <= 0.5 ? HEX.red : CFG.markers[i] <= 1 ? HEX.amber : HEX.green);
      for (e = 0; e < 8; e++) setC(mCol, i * 8 + e, c, 0.95);
      for (e = 0; e < 2; e++) { var q = i * 8 + e * 4; mIdx.push(q, q + 1, q + 2, q + 1, q + 3, q + 2); }
    }
    gd.marks = new T.Mesh(geom(mPos, mIdx, { color: new T.BufferAttribute(mCol, 4) }), mat());
    gd.mPos = dyn(gd.marks);
    [gd.path, gd.lines, gd.marks].forEach(function (m, k) {
      m.renderOrder = 20 + k; m.frustumCulled = false; m.visible = false; R3.add(m);
    });
    // x-ray：邊線與標記（行進範圍很淡，不需要）
    var xm = xrayMaterial({ vertexColors: true, opacity: CFG.xrayOpacity });
    gd.xLines = new T.Mesh(gd.lines.geometry, xm);
    gd.xMarks = new T.Mesh(gd.marks.geometry, xm);
    [gd.xLines, gd.xMarks].forEach(function (m, k) {
      m.renderOrder = 31 + k; m.frustumCulled = false; m.visible = false; R3.add(m);
    });
  }
  function ribbon(P, ln, w) {
    var N = gd.n + 1, pos = gd.rPos.array, hw = w / 2;
    for (var i = 0; i < N; i++) {
      var i0 = i > 0 ? i - 1 : i, i1 = i < N - 1 ? i + 1 : i;
      var tx = P[i1 * 2] - P[i0 * 2], tz = -(P[i1 * 2 + 1] - P[i0 * 2 + 1]), l = Math.sqrt(tx * tx + tz * tz);
      var nx = l > 1e-9 ? -tz / l * hw : 0, nz = l > 1e-9 ? tx / l * hw : 0, x = P[i * 2], z = -P[i * 2 + 1], v = (ln * N + i) * 6;
      pos[v] = x + nx; pos[v + 1] = Y_LINE; pos[v + 2] = z + nz;
      pos[v + 3] = x - nx; pos[v + 4] = Y_LINE; pos[v + 5] = z - nz;
    }
  }
  function collapse(P, from) {        // from 之後的點都疊在 from - 1（零面積，不會畫出東西）
    var x = from > 0 ? P[(from - 1) * 2] : 0, z = from > 0 ? P[(from - 1) * 2 + 1] : 0;
    for (var i = from; i < P.length / 2; i++) { P[i * 2] = x; P[i * 2 + 1] = z; }
  }
  function layoutGuides(steer, reverse) {
    var p = guideParams(steer, reverse, null), ps = gd.ps, i, P = gd.P;
    var curved = p.side !== 0 && Math.abs(1 / p.k) < CFG.innerMaxR, swing = curved && reverse && gd.nSw > 0;
    for (i = 0; i <= p.n; i++) {
      pose(p.len * i / p.n, p.k, p.dir, p.bz, ps);
      bodyPoint(ps, p.zra, -p.hw, p.bz, P[RIB_L], i * 2);
      bodyPoint(ps, p.zra, p.hw, p.bz, P[RIB_R], i * 2);
      if (curved) bodyPoint(ps, p.zra, p.side * p.hw, 0, P[RIB_I], i * 2);
      if (swing && i < gd.nSw) bodyPoint(ps, p.zra, -p.side * p.hw, p.fz, P[RIB_S], i * 2);
    }
    if (!curved) collapse(P[RIB_I], 0);
    collapse(P[RIB_S], swing ? gd.nSw : 0);
    ribbon(P[RIB_L], RIB_L, CFG.lineW); ribbon(P[RIB_R], RIB_R, CFG.lineW);
    ribbon(P[RIB_I], RIB_I, CFG.subLineW); ribbon(P[RIB_S], RIB_S, CFG.subLineW);
    // 行進範圍：轉彎時在內側軌跡與外側保險桿角線之間（車身真正掃過的範圍），直行時在左右線之間
    var A = curved ? P[RIB_I] : P[RIB_L], B = curved ? (p.side > 0 ? P[RIB_L] : P[RIB_R]) : P[RIB_R], cp = gd.cPos.array;
    for (i = 0; i <= p.n; i++) {
      cp[i * 6] = A[i * 2]; cp[i * 6 + 1] = Y_PATH; cp[i * 6 + 2] = -A[i * 2 + 1];
      cp[i * 6 + 3] = B[i * 2]; cp[i * 6 + 4] = Y_PATH; cp[i * 6 + 5] = -B[i * 2 + 1];
    }
    var mp = gd.mPos.array, TL = CFG.markL, TH = CFG.markW, o = [0, 0, 0, 0];
    for (i = 0; i < CFG.markers.length; i++) {
      var m = CFG.markers[i], b = i * 8 * 3;
      if (m > p.len + 1e-9) { for (var z = 0; z < 24; z++) mp[b + z] = 0; continue; }
      pose(m, p.k, p.dir, p.bz, ps);
      bodyPoint(ps, p.zra, -p.hw, p.bz, o, 0); bodyPoint(ps, p.zra, p.hw, p.bz, o, 2);
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
    gd.curved = curved; gd.swing = swing;
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
  function buildLamps() {
    var geo = new T.BoxGeometry(0.16, 0.08, 0.12);
    lamp.on = new T.MeshBasicMaterial({ color: lin(HEX.amber), toneMapped: false });
    lamp.off = new T.MeshBasicMaterial({ color: lin(HEX.lampOff), toneMapped: false });
    var glow = new T.SpriteMaterial({ map: glowTexture(), color: lin(HEX.amber), blending: T.AdditiveBlending,
      transparent: true, depthWrite: false, toneMapped: false });
    [[-1, -1], [-1, 1], [1, -1], [1, 1]].forEach(function (cz) {
      var m = new T.Mesh(geo, lamp.off), sp = new T.Sprite(glow);
      sp.scale.set(1.1, 1.1, 1);
      m.visible = sp.visible = false;
      R3.add(m); R3.add(sp);
      lamp.list.push({ sx: cz[0], sz: cz[1], left: cz[0] < 0, mesh: m, glow: sp });
    });
  }
  // 依自車模型外框擺四角的燈；場景裡車頭朝 -Z：前角 z = min.z、後角 z = max.z；左 = -x
  function placeLamps(ego) {
    if (ego) R3.position.set(ego.position.x, 0, ego.position.z);   // 車身座標原點 = 自車模型位置
    R3.updateMatrixWorld(true);
    var b = modelBox(ego), y = b.min.y + 0.39 * (b.max.y - b.min.y);
    lamp.list.forEach(function (l) {
      var x = l.sx < 0 ? b.min.x + 0.06 : b.max.x - 0.06, z = l.sz < 0 ? b.min.z + 0.04 : b.max.z - 0.04;
      l.mesh.position.set(x - R3.position.x, y, z - R3.position.z);
      l.glow.position.set(x + l.sx * 0.12 - R3.position.x, y, z + l.sz * 0.12 - R3.position.z);
    });
  }

  var CSS =
    '.vh-off{display:none!important}' +
    '.vh-turn{position:absolute;top:50%;width:58px;height:58px;margin-top:-29px;z-index:4;pointer-events:none;color:var(--amber,#ffc24b);opacity:.14}' +
    '.vh-turn.vh-l{left:12px}.vh-turn.vh-r{right:12px}' +
    '.vh-turn.vh-on{opacity:1;filter:drop-shadow(0 0 9px rgba(255,194,75,.9))}' +
    '.vh-turn svg{display:block;width:100%;height:100%}' +
    // 讀數膠囊：底色一律不透明深色；連續紅只疊紅色、閃外圈（不閃底色，免得字看不到）
    '.vh-pdc{position:absolute;left:50%;transform:translateX(-50%);z-index:4;pointer-events:none;display:flex;align-items:baseline;' +
    'padding:5px 14px;border-radius:999px;background:rgba(14,20,28,.92);border:1px solid rgba(120,160,200,.3);' +
    'color:var(--fg,#e8f1f8);font-weight:700;white-space:nowrap;font-variant-numeric:tabular-nums}' +
    '.vh-pdc>*+*{margin-left:6px}' +       // 不用 flex gap：舊車機 WebView（Chrome < 84）不支援
    '.vh-pdc .vh-s{font-size:12px;color:var(--muted,#8ba1b4);letter-spacing:.12em}' +
    '.vh-pdc b{font-size:20px;line-height:1}.vh-pdc i{font-style:normal;font-size:12px;color:var(--muted,#8ba1b4)}' +
    '.vh-pdc .vh-o{margin-left:10px;padding-left:10px;border-left:1px solid rgba(120,160,200,.3);font-size:12px;font-weight:600;color:var(--muted,#8ba1b4);letter-spacing:.06em}' +
    '.vh-pdc.z-g b{color:var(--green,#5ef08a)}.vh-pdc.z-y b{color:var(--amber,#ffc24b)}' +
    '.vh-pdc.z-r b{color:#ff8278}.vh-pdc.z-r{border-color:rgba(255,90,77,.65)}' +
    '.vh-pdc.z-f b{color:#aab6c3;font-size:15px}' +
    '.vh-pdc.z-c{border-color:var(--red,#ff5a4d);background:linear-gradient(rgba(255,90,77,.32),rgba(255,90,77,.32)),rgba(14,20,28,.92);' +
    'animation:vhPulse .6s infinite}' +
    '.vh-pdc.z-c b{color:#fff}.vh-pdc.z-c .vh-s,.vh-pdc.z-c i,.vh-pdc.z-c .vh-o{color:#ffd2cd}' +
    '@keyframes vhPulse{0%{box-shadow:0 0 0 0 rgba(255,90,77,.85)}100%{box-shadow:0 0 0 12px rgba(255,90,77,0)}}';
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
    hud.pdc = mk('vh-pdc vh-off', '<span class="vh-s"></span><b></b><i>m</i><span class="vh-o vh-off"></span>');
    hud.pdc.setAttribute('role', 'status'); hud.pdc.setAttribute('aria-label', '雷達最近距離');
    if (CFG.readoutTop != null) hud.pdc.style.top = CFG.readoutTop + 'px';
    else hud.pdc.style.bottom = (CFG.readoutBottom != null ? CFG.readoutBottom : 64) + 'px';
    hud.s = hud.pdc.children[0]; hud.v = hud.pdc.children[1]; hud.u = hud.pdc.children[2]; hud.o = hud.pdc.children[3];
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
    st.pdcF = st.pdcR = null; st.faults = 0; st.pdcActive = false;
    if (!inited) return;
    for (var si = 0; si < 2; si++) {
      pdc.bg[si].visible = false;
      for (var j = 0; j < 4; j++) { pdc.lit[si][j].visible = pdc.xr[si][j].visible = false; pdc.cur[si][j] = -2; }
    }
    gd.path.visible = gd.lines.visible = gd.marks.visible = gd.xLines.visible = gd.xMarks.visible = false;
    lamp.list.forEach(function (l) { l.mesh.visible = l.glow.visible = false; });
    showEl(hud.l, false); showEl(hud.r, false); showEl(hud.pdc, false);
    hud.lOn = hud.rOn = null; hud.txt = '';
  }

  function updateTurn(turn, lit, now) {
    if (turn !== 'L' && turn !== 'R' && turn !== 'H') turn = null;
    if (turn !== st.turn) { st.turn = turn; st.turnT0 = now; }
    var L = turn === 'L' || turn === 'H', R = turn === 'R' || turn === 'H';
    var per = 1000 / CFG.blinkHz;
    // 有實車燈的亮暗就跟著它（相位一致、撥桿回正後只剩暗的箭頭）；沒有才自己閃
    var on = !turn ? false : (lit === true || lit === false) ? lit : ((now - st.turnT0) % per) < per / 2;
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

  function fmtD(d) { return d.toFixed(2); }
  function updatePdc(p, gear, now) {
    var mins = [null, null], faults = [0, 0], active = '', sec = now / 1000, xo = CFG.xray;
    for (var si = 0; si < 2; si++) {
      var arr = p ? (si === 0 ? p.f : p.r) : null, on = !!(arr && arr.length);
      pdc.bg[si].visible = on;
      if (on) active += si === 0 ? '前' : '後';
      for (var j = 0; j < 4; j++) {
        var m = pdc.lit[si][j], x = pdc.xr[si][j], raw = on ? arr[j] : undefined;
        if (on && isFault(raw)) {           // 感測器故障：灰色斜線
          faults[si]++;
          if (pdc.cur[si][j] !== -3) { m.geometry = pdc.fault[si][j]; m.material = pdc.faultMat; pdc.cur[si][j] = -3; }
          m.visible = true; x.visible = false;
          continue;
        }
        var d = on ? num(raw) : null, k = d === null ? -1 : pdcSeg(d);
        if (k < 0) { m.visible = x.visible = false; pdc.cur[si][j] = -1; continue; }
        if (d < 0) d = 0;
        if (mins[si] === null || d < mins[si]) mins[si] = d;
        if (pdc.cur[si][j] !== k) { m.geometry = x.geometry = pdc.geo[si][j][k]; m.material = pdc.mat[si][j]; pdc.cur[si][j] = k; }
        var z = pdcZone(d), mt = m.material;
        mt.color.copy(LC[z]);
        mt.opacity = z === 'c' ? 0.7 + 0.3 * Math.abs(Math.sin(sec * Math.PI * 4)) :
          z === 'r' ? 0.6 + 0.35 * Math.abs(Math.sin(sec * Math.PI * 2.5)) : z === 'y' ? 0.85 : 0.75;
        m.visible = true;
        x.material.opacity = mt.opacity * CFG.xrayOpacity;
        x.visible = xo;
      }
    }
    pdc.bgMat.uniforms.uPhase.value = (now % 1300) / 1300 * 2.3;
    st.pdcF = mins[0]; st.pdcR = mins[1]; st.faults = faults[0] + faults[1]; st.pdcActive = !!active;
    var side = readoutSide(gear, mins[0], mins[1]);
    st.pdcMin = side ? { side: side, d: side === 'f' ? mins[0] : mins[1] } : null;
    // HUD 讀數
    if (!active) { showEl(hud.pdc, false); st.readout = ''; hud.txt = ''; return; }
    var s, v, cls, sub = '';
    if (!side) {
      if (st.faults) { s = (faults[0] ? '前' : '') + (faults[1] ? '後' : ''); v = '雷達故障'; cls = 'z-f'; }
      else { s = active; v = '無障礙'; cls = 'z-g'; }
    } else {
      s = side === 'f' ? '前' : '後'; v = fmtD(st.pdcMin.d); cls = 'z-' + pdcZone(st.pdcMin.d);
      var other = side === 'f' ? mins[1] : mins[0];
      if (other !== null) sub = (side === 'f' ? '後 ' : '前 ') + fmtD(other);
    }
    var txt = s + ' ' + v + (side ? ' m' : '') + (sub ? ' · ' + sub : '');
    if (hud.pdc) {
      showEl(hud.pdc, true);
      if (txt !== hud.txt) {
        hud.s.textContent = s; hud.v.textContent = v; hud.o.textContent = sub;
        showEl(hud.u, !!side); showEl(hud.o, !!sub);
        hud.txt = txt;
      }
      if (cls !== hud.cls) { hud.pdc.className = 'vh-pdc ' + cls; hud.cls = cls; }
    }
    st.readout = txt;
  }

  function updateGuides(v, dt) {
    var mode = guideMode(v, null);
    var target = num(v.steer) === null ? 0 : v.steer;
    if (mode !== st.mode) st.steer = target;           // 剛出現時不要從舊角度滑過來
    else st.steer += (target - st.steer) * (1 - Math.exp(-dt / 0.12));
    st.mode = mode;
    gd.path.visible = gd.lines.visible = gd.marks.visible = !!mode;
    gd.xLines.visible = gd.xMarks.visible = !!mode && CFG.xray;
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
    scene.add(R3);
    buildPdc();
    buildGuides();
    buildLamps();
    placeLamps(egoGroup);
    buildHud(hudParent);
    inited = true;
    API.root = R3;
    hideAll();
    return API;
  }

  /** 自車模型縮放 / 移動 / 換模型後呼叫：重新對齊原點與四角方向燈 */
  function relayout(egoGroup) {
    if (inited) placeLamps(egoGroup);
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
    updateTurn(veh.turn, veh.lit, now);
    updatePdc(veh.pdc, veh.gear, now);
    updateGuides(veh, dt);
  }

  /** 目前顯示狀態（除錯 / 測試 / 整合端用；pdcActive = 雷達作用中，可據此隱藏其他距離框） */
  function state() {
    return { live: st.live, turn: st.turn, blinkOn: st.blinkOn, guide: st.mode, steer: st.steer,
      pdcMin: st.pdcMin, pdcF: st.pdcF, pdcR: st.pdcR, faults: st.faults, pdcActive: st.pdcActive,
      readout: st.readout, inner: !!(st.mode && gd.curved), swing: !!(st.mode && gd.swing) };
  }
  /** 建議視角：倒車 → 'rear'（朝後看）；前方雷達 < 1.2 m → 'top'（俯視）；其他 null（維持使用者的選擇） */
  function suggestView() {
    if (!st.live) return null;
    if (st.mode === 'rev') return 'rear';
    if (st.pdcF !== null && st.pdcF < 1.2) return 'top';
    return null;
  }

  var API = {
    CFG: CFG, PDC_SEG: PDC_SEG,
    wheelAngleDeg: wheelAngleDeg, curvature: curvature, turningRadius: turningRadius, guides: guides,
    guideMode: guideMode, readoutSide: readoutSide, pdcSeg: pdcSeg, pdcZone: pdcZone,
    init: init, update: update, relayout: relayout, state: state, suggestView: suggestView, root: null
  };
  if (root) root.AVM_VEH = API;
  if (typeof module === 'object' && module && module.exports) module.exports = API;
})(typeof window !== 'undefined' ? window : null);
