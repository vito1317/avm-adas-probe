/*
 * 地圖輔助（OpenStreetMap + GPS）：3D 前方道路帶、車頭朝上小地圖、速限 / 路名 / 紅綠燈提示。
 * ES5，three.js r128。地圖資料 © OpenStreetMap 貢獻者（ODbL）。
 *
 *   AVM_MAP.init(scene, THREE, hudParent)  hudParent：放小地圖與提示的 DOM 容器（位置由呼叫端 CSS 決定）
 *   AVM_MAP.update(map, now)               map = payload.map（或 null：只推進動畫）；now = performance.now()
 *                                          setFrame 收到 map 時呼叫一次，render loop 每幀再呼叫 update(null, now)
 *   AVM_MAP.setNear(surfaceM, linesM)      近處淡入距離（預設 8 / 12 m）
 *
 * 座標：payload 是車輛座標（x 右、z 前，公尺），場景 z = −z。GPS 1 Hz，兩筆之間沿 path 依車速推算位置，
 * 新資料到時平滑過去。超過 5 s 沒有新資料、或沒配到道路 → 淡出。
 */
(function () {
  'use strict';

  var STALE_MS = 5000;        // 多久沒新資料就隱藏
  var DR_MAX_MS = 2500;       // 推算最多往前推多久
  var NEAR = 8;               // 8 m 內交給鏡頭偵測的車道線
  var FAR = 200;
  var LANE_W = 3.5;
  var DASH = 4, PERIOD = 10;  // 車道虛線：實 4 m、空 6 m
  var MAXP = 64;              // path 最多點數
  var MAXQ = 160;             // 虛線 quad 上限
  var MINI = 170;             // 小地圖邊長（CSS px）
  var MINI_SCALE = 0.55;      // px / m
  var CAR_Y = 0.70;           // 車子在小地圖的高度比例
  // 高度：路面在 index.html 虛擬路面（0.006）之上、車道線（0.014）之下；8 m 外由地圖路面接手
  var Y_ROAD = 0.008, Y_LINE = 0.016;
  var COL = { road: 0x2b3542, edge: 0xc9d3dc, white: 0xeef2f5, yellow: 0xffc533 };

  var T = null, root = null, hud = null, ui = {}, inited = false;
  var cur = null;             // 最新一筆（已前處理）
  var lastT = null, lastAt = -1e9, lastNow = 0;
  var disp = [], dispN = 0;   // 顯示中的中心線（目前車輛座標，平滑後）
  var phase = 0, phaseInit = false;
  var fade = 0;
  var meshes = {};
  var miniCtx = null, miniDpr = 1, miniLast = 0;

  // ---------------- 小工具 ----------------
  function hyp(x, y) { return Math.sqrt(x * x + y * y); }
  function mod(a, m) { return ((a % m) + m) % m; }
  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }
  function hwWidth(hw) {
    if (hw === 'service' || hw === 'living_street') return 4.5;
    if (hw === 'residential') return 6;
    return 7;
  }

  // ---------------- 前處理一筆 map ----------------
  function prep(m, now) {
    var src = m.path || [], pts = [], i;
    for (i = 0; i < src.length && pts.length < MAXP; i++) {
      var p = src[i];
      if (p && isFinite(p[0]) && isFinite(p[1])) pts.push([+p[0], +p[1]]);
    }
    var S = [0];
    for (i = 1; i < pts.length; i++) S.push(S[i - 1] + hyp(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    // 車子（原點）投影在 path 上的弧長
    var s0 = 0, best = 1e9;
    for (i = 1; i < pts.length; i++) {
      var ax = pts[i - 1][0], az = pts[i - 1][1], dx = pts[i][0] - ax, dz = pts[i][1] - az;
      var l2 = dx * dx + dz * dz, t = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + az * dz) / l2)) : 0;
      var d = hyp(ax + dx * t, az + dz * t);
      if (d < best) { best = d; s0 = S[i - 1] + t * Math.sqrt(l2); }
    }
    var lanes = num(m.lanes, 0) | 0, known = lanes > 0;
    var w = known ? lanes * LANE_W : num(m.width, 0) > 1 ? m.width : (m.hw ? hwWidth(m.hw) : 7);
    if (!known) lanes = Math.max(1, Math.round(w / LANE_W));
    var minor = m.hw === 'residential' || m.hw === 'living_street' || m.hw === 'service';
    var sig = [];
    if (m.signals && m.signals.length) for (i = 0; i < m.signals.length; i++) if (isFinite(m.signals[i])) sig.push(+m.signals[i]);
    return {
      at: now, pts: pts, S: S, s0: s0, spd: Math.max(0, num(m.spd, 0) / 3.6), off: num(m.off, 0),
      od: num(m.od, 0), lanes: lanes, known: known, w: Math.min(w, 40), oneway: !!m.oneway,
      center: !m.oneway && (known ? lanes >= 2 : !minor),   // 巷弄多半沒有中線
      maxspeed: num(m.maxspeed, 0) > 0 ? Math.round(m.maxspeed) : 0, name: typeof m.name === 'string' ? m.name : '',
      sig: sig, roads: m.roads || [], rc: m.rc || [], matched: m.matched !== false && pts.length >= 2,
      acc: num(m.acc, 0)
    };
  }

  // path 上弧長 s 的點與切線方向（F0 = 該筆資料的車輛座標）
  function at(c, s, out) {
    var P = c.pts, S = c.S, n = P.length, i = 1;
    if (n === 1) { out[0] = P[0][0]; out[1] = P[0][1]; return out; }
    if (s >= S[n - 1]) i = n - 1;
    else if (s > 0) while (i < n - 1 && S[i] < s) i++;
    var ds = S[i] - S[i - 1], k = ds > 1e-6 ? (s - S[i - 1]) / ds : 0;
    out[0] = P[i - 1][0] + (P[i][0] - P[i - 1][0]) * k;
    out[1] = P[i - 1][1] + (P[i][1] - P[i - 1][1]) * k;
    return out;
  }

  // 目前（推算後）車子在 F0 的位姿：位置 + 切線角 φ（從 +z 往 +x 量）
  var tmpA = [0, 0], tmpB = [0, 0];
  function pose(c, now) {
    var ds = c.spd * Math.min(Math.max(0, now - c.at), DR_MAX_MS) / 1000;
    if (c.pts.length < 2) return { s: 0, ds: ds, x: 0, z: ds, cs: 1, sn: 0 };   // 沒配到路：沿原航向直推
    var s = c.s0 + ds;
    at(c, s - 4, tmpA); at(c, s + 4, tmpB);
    var phi = Math.atan2(tmpB[0] - tmpA[0], tmpB[1] - tmpA[1]);
    var p = at(c, s, [0, 0]);
    var cs = Math.cos(phi), sn = Math.sin(phi);
    // 右法向 = (cos φ, −sin φ)
    return { s: s, ds: ds, x: p[0] + c.off * cs, z: p[1] - c.off * sn, cs: cs, sn: sn };
  }
  function toCar(q, x, z, out) {   // F0 → 目前車輛座標
    var dx = x - q.x, dz = z - q.z;
    out[0] = dx * q.cs - dz * q.sn;
    out[1] = dx * q.sn + dz * q.cs;
    return out;
  }

  // ---------------- 材質（距離淡入淡出在 shader 做，跟著目前車位） ----------------
  var VS = [
    'attribute float aA;', 'varying float vA;', 'varying vec2 vXZ;',
    'void main(){', '  vA = aA;', '  vec4 wp = modelMatrix * vec4(position, 1.0);', '  vXZ = wp.xz;',
    '  gl_Position = projectionMatrix * viewMatrix * wp;', '}'
  ].join('\n');
  var FS = [
    'uniform vec3 uColor;', 'uniform float uOpacity;', 'uniform float uNear;', 'uniform float uFar;',
    'varying float vA;', 'varying vec2 vXZ;',
    'void main(){',
    '  float d = length(vXZ);',
    '  float a = smoothstep(uNear, uNear + 6.0, d) * (1.0 - smoothstep(uFar * 0.45, uFar, d));',
    '  gl_FragColor = vec4(uColor, uOpacity * vA * a);',
    '}'
  ].join('\n');
  function mat(hex, opacity, near, bias) {
    var c = new T.Color(hex);   // 不經 tone mapping / encoding：色碼原樣輸出
    var m = new T.ShaderMaterial({
      uniforms: { uColor: { value: new T.Vector3(c.r, c.g, c.b) }, uOpacity: { value: opacity },
        uNear: { value: near }, uFar: { value: FAR * 0.6 } },
      vertexShader: VS, fragmentShader: FS, transparent: true, depthWrite: false, side: T.DoubleSide,
      polygonOffset: true, polygonOffsetFactor: -bias, polygonOffsetUnits: -2 * bias   // 遠處也不跟虛擬路面打架
    });
    m.userData.base = opacity;
    return m;
  }
  function dynMesh(maxV, maxI, material, order) {
    var g = new T.BufferGeometry();
    var pa = new T.BufferAttribute(new Float32Array(maxV * 3), 3), aa = new T.BufferAttribute(new Float32Array(maxV), 1);
    pa.setUsage(T.DynamicDrawUsage); aa.setUsage(T.DynamicDrawUsage);
    g.setAttribute('position', pa); g.setAttribute('aA', aa);
    var idx = new (maxV > 65535 ? Uint32Array : Uint16Array)(maxI);
    g.setIndex(new T.BufferAttribute(idx, 1));
    g.index.setUsage(T.DynamicDrawUsage);
    g.setDrawRange(0, 0);
    var m = new T.Mesh(g, material);
    m.frustumCulled = false;
    m.renderOrder = order;
    root.add(m);
    return m;
  }

  // 沿中心線的帶狀（橫向 lo..hi），寫入 mesh
  function strip(mesh, P, n, nx, nz, lo, hi, alphaEdge) {
    var g = mesh.geometry, pos = g.attributes.position.array, aa = g.attributes.aA.array, idx = g.index.array;
    var v = 0, k = 0, y = mesh.userData.y;
    for (var i = 0; i < n; i++) {
      pos[v * 3] = P[i][0] + nx[i] * lo; pos[v * 3 + 1] = y; pos[v * 3 + 2] = -(P[i][1] + nz[i] * lo); aa[v] = alphaEdge; v++;
      pos[v * 3] = P[i][0] + nx[i] * hi; pos[v * 3 + 1] = y; pos[v * 3 + 2] = -(P[i][1] + nz[i] * hi); aa[v] = alphaEdge; v++;
      if (i > 0) {
        var a = v - 4;
        idx[k++] = a; idx[k++] = a + 1; idx[k++] = a + 2; idx[k++] = a + 2; idx[k++] = a + 1; idx[k++] = a + 3;
      }
    }
    g.attributes.position.needsUpdate = true; g.attributes.aA.needsUpdate = true; g.index.needsUpdate = true;
    g.setDrawRange(0, k);
  }

  // 路面：中間不透明、兩緣 0.6 m 柔邊（三條帶拼起來：中 + 左右漸層）
  function surface(mesh, P, n, nx, nz, w) {
    var g = mesh.geometry, pos = g.attributes.position.array, aa = g.attributes.aA.array, idx = g.index.array;
    var cols = [-w / 2 - 0.4, -w / 2 + 0.3, w / 2 - 0.3, w / 2 + 0.4], al = [0, 1, 1, 0];
    var v = 0, k = 0, y = mesh.userData.y;
    for (var i = 0; i < n; i++) {
      for (var c = 0; c < 4; c++) {
        pos[v * 3] = P[i][0] + nx[i] * cols[c]; pos[v * 3 + 1] = y; pos[v * 3 + 2] = -(P[i][1] + nz[i] * cols[c]);
        aa[v] = al[c]; v++;
      }
      if (i > 0) {
        var b = v - 8;
        for (c = 0; c < 3; c++) {
          idx[k++] = b + c; idx[k++] = b + c + 1; idx[k++] = b + c + 4;
          idx[k++] = b + c + 4; idx[k++] = b + c + 1; idx[k++] = b + c + 5;
        }
      }
    }
    g.attributes.position.needsUpdate = true; g.attributes.aA.needsUpdate = true; g.index.needsUpdate = true;
    g.setDrawRange(0, k);
  }

  // 虛線：offsets 每條分隔線的橫向位置；以弧長（含里程相位）切 4 m / 10 m
  function dashes(mesh, P, Sd, n, nx, nz, offsets, hw, ph) {
    var g = mesh.geometry, pos = g.attributes.position.array, aa = g.attributes.aA.array, idx = g.index.array;
    var v = 0, k = 0, q = 0, y = mesh.userData.y;
    var s0 = Sd[0], s1 = Sd[n - 1];
    var pa = [0, 0], pb = [0, 0], na = [0, 0], nb = [0, 0];
    for (var o = 0; o < offsets.length; o++) {
      var off = offsets[o];
      // 第一段虛線起點：(s + ph) mod PERIOD == 0
      for (var st = s0 - mod(s0 + ph, PERIOD); st < s1 && q < MAXQ; st += PERIOD) {
        var a = Math.max(st, s0), e = Math.min(st + DASH, s1);
        if (e - a < 0.3 || e < NEAR - 2) continue;
        sample(P, Sd, n, nx, nz, a, pa, na); sample(P, Sd, n, nx, nz, e, pb, nb);
        quad(pos, aa, v, pa, na, pb, nb, off, hw, y);
        idx[k++] = v; idx[k++] = v + 1; idx[k++] = v + 2; idx[k++] = v + 2; idx[k++] = v + 1; idx[k++] = v + 3;
        v += 4; q++;
      }
    }
    g.attributes.position.needsUpdate = true; g.attributes.aA.needsUpdate = true; g.index.needsUpdate = true;
    g.setDrawRange(0, k);
  }
  function sample(P, Sd, n, nx, nz, s, out, nout) {
    var i = 1;
    while (i < n - 1 && Sd[i] < s) i++;
    var ds = Sd[i] - Sd[i - 1], k = ds > 1e-6 ? (s - Sd[i - 1]) / ds : 0;
    out[0] = P[i - 1][0] + (P[i][0] - P[i - 1][0]) * k;
    out[1] = P[i - 1][1] + (P[i][1] - P[i - 1][1]) * k;
    nout[0] = nx[i - 1] + (nx[i] - nx[i - 1]) * k;
    nout[1] = nz[i - 1] + (nz[i] - nz[i - 1]) * k;
  }
  function quad(pos, aa, v, pa, na, pb, nb, off, hw, y) {
    var pts = [[pa, na, off - hw], [pa, na, off + hw], [pb, nb, off - hw], [pb, nb, off + hw]];
    for (var j = 0; j < 4; j++) {
      var p = pts[j][0], nn = pts[j][1], o = pts[j][2], b = (v + j) * 3;
      pos[b] = p[0] + nn[0] * o; pos[b + 1] = y; pos[b + 2] = -(p[1] + nn[1] * o);
      aa[v + j] = 1;
    }
  }

  // ---------------- HUD ----------------
  var CSS = [
    '.amap{display:flex;flex-direction:column;align-items:flex-start;gap:6px;pointer-events:none;transition:opacity .4s;',
    '  font-family:system-ui,-apple-system,"Noto Sans TC","PingFang TC","Microsoft JhengHei",sans-serif;color:var(--fg,#e8f1f8)}',
    '.amap[data-off]{opacity:0}',
    '.amap-mini{position:relative;width:' + MINI + 'px;height:' + MINI + 'px;border-radius:16px;overflow:hidden;',
    '  background:rgba(9,13,19,.86);border:1px solid var(--line,rgba(120,160,200,.18));box-shadow:0 4px 18px rgba(0,0,0,.35);transition:opacity .4s}',
    '.amap-mini[data-off]{opacity:0}',
    '.amap-mini canvas{display:block;width:100%;height:100%}',
    '.amap-attr{position:absolute;right:6px;bottom:4px;font-size:9px;line-height:1;color:rgba(200,214,226,.72);',
    '  background:rgba(9,13,19,.6);padding:2px 4px;border-radius:4px;letter-spacing:.02em}',
    '.amap-sign{position:absolute;left:8px;top:8px;width:42px;height:42px;border-radius:50%;background:#fff;',
    '  box-sizing:border-box;border:5px solid #e0362b;display:flex;align-items:center;justify-content:center;',
    '  color:#111;font-weight:800;font-size:17px;letter-spacing:-.04em;font-variant-numeric:tabular-nums;',
    '  box-shadow:0 2px 8px rgba(0,0,0,.45);transition:opacity .3s}',
    '.amap-sign[hidden]{display:none}',
    '.amap-sign.over{animation:amapPulse 1s infinite}',
    '@keyframes amapPulse{50%{box-shadow:0 0 0 5px rgba(255,90,77,.55),0 2px 8px rgba(0,0,0,.45)}}',
    '.amap-acc{position:absolute;right:7px;top:6px;font-size:10px;color:rgba(200,214,226,.6);font-variant-numeric:tabular-nums}',
    '.amap-chip{display:flex;align-items:center;gap:7px;max-width:' + (MINI + 90) + 'px;box-sizing:border-box;',
    '  background:var(--panel,rgba(14,20,28,.82));border:1px solid var(--line,rgba(120,160,200,.18));border-radius:10px;',
    '  padding:5px 9px;font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.amap-chip[hidden]{display:none}',
    '.amap-chip .t{overflow:hidden;text-overflow:ellipsis}',
    '.amap-chip small{color:var(--muted,#8ba1b4);font-weight:500;font-size:11px}',
    '.amap-ow{flex:none;font-size:10px;font-weight:700;color:#0b1118;background:var(--cyan,#38e1ff);border-radius:5px;padding:1px 5px}',
    '.amap-sig{border-color:rgba(255,194,75,.45)}',
    '.amap-sig b{color:var(--amber,#ffc24b);font-variant-numeric:tabular-nums}',
    '.amap-tl{flex:none;display:flex;flex-direction:column;gap:2px;background:#1a2028;border-radius:4px;padding:2px 3px}',
    '.amap-tl i{display:block;width:5px;height:5px;border-radius:50%;background:#3a434e}',
    '.amap-tl i:first-child{background:#ff5a4d}.amap-tl i:last-child{background:#5ef08a}'
  ].join('\n');

  function el(tag, cls, parent) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (parent) parent.appendChild(e);
    return e;
  }

  function buildHud(parent) {
    if (!document.getElementById('amap-css')) {
      var st = document.createElement('style');
      st.id = 'amap-css';
      st.appendChild(document.createTextNode(CSS));
      document.head.appendChild(st);
    }
    if (!parent) {
      parent = el('div', '', document.body);
      parent.style.cssText = 'position:fixed;left:10px;bottom:10px;z-index:5';
    }
    hud = el('div', 'amap', parent);
    hud.setAttribute('role', 'status');
    hud.setAttribute('aria-label', '地圖輔助');
    hud.setAttribute('data-off', '');
    ui.sig = el('div', 'amap-chip amap-sig', hud);
    var tl = el('span', 'amap-tl', ui.sig);
    el('i', '', tl); el('i', '', tl); el('i', '', tl);
    ui.sigTxt = el('span', 't', ui.sig);
    ui.sig.hidden = true;
    ui.mini = el('div', 'amap-mini', hud);
    ui.canvas = el('canvas', '', ui.mini);
    ui.canvas.setAttribute('aria-hidden', 'true');
    ui.sign = el('div', 'amap-sign', ui.mini);
    ui.sign.setAttribute('aria-label', '速限');
    ui.sign.hidden = true;
    ui.acc = el('div', 'amap-acc', ui.mini);
    var attr = el('div', 'amap-attr', ui.mini);
    attr.textContent = '© OpenStreetMap';
    ui.name = el('div', 'amap-chip', hud);
    ui.nameTxt = el('span', 't', ui.name);
    ui.ow = el('span', 'amap-ow', ui.name);
    ui.ow.textContent = '單行';
    ui.name.hidden = true;
    miniDpr = Math.min(2, window.devicePixelRatio || 1);
    ui.canvas.width = Math.round(MINI * miniDpr);
    ui.canvas.height = Math.round(MINI * miniDpr);
    miniCtx = ui.canvas.getContext('2d');
  }

  var RC_STYLE = [   // 主幹道、次要、巷道、服務道路
    { c: '#6f8297', w: 4.2 }, { c: '#56677a', w: 3.2 }, { c: '#3f4d5d', w: 2.3 }, { c: '#2f3a47', w: 1.6 }
  ];
  function drawMini(c, q, now) {
    var g = miniCtx, W = MINI, cx = W / 2, cy = W * CAR_Y, s = MINI_SCALE, i, j, r, p = [0, 0];
    g.setTransform(miniDpr, 0, 0, miniDpr, 0, 0);
    g.clearRect(0, 0, W, W);
    g.lineCap = 'round'; g.lineJoin = 'round';
    // 距離圈
    g.strokeStyle = 'rgba(120,160,200,.10)'; g.lineWidth = 1;
    g.beginPath(); g.arc(cx, cy, 100 * s, 0, Math.PI * 2); g.stroke();
    if (c) {
      var order = [3, 2, 1, 0];   // 小路先畫
      for (var o = 0; o < order.length; o++) {
        var cls = order[o], sty = RC_STYLE[cls];
        g.strokeStyle = sty.c; g.lineWidth = sty.w;
        g.beginPath();
        for (i = 0; i < c.roads.length; i++) {
          var rc = c.rc && c.rc.length === c.roads.length ? c.rc[i] : 1;
          if (rc !== cls) continue;
          r = c.roads[i];
          if (!r || !r.length) continue;
          for (j = 0; j < r.length; j++) {
            if (!r[j]) continue;
            toCar(q, r[j][0], r[j][1], p);
            var X = cx + p[0] * s, Y = cy - p[1] * s;
            if (j === 0) g.moveTo(X, Y); else g.lineTo(X, Y);
          }
        }
        g.stroke();
      }
    }
    // 配對的道路：前方亮、後方暗
    if (c && c.matched && dispN >= 2) {
      g.lineWidth = 5;
      g.strokeStyle = 'rgba(56,225,255,.35)';
      g.beginPath();
      for (i = 0; i < dispN; i++) { var X2 = cx + disp[i][0] * s, Y2 = cy - disp[i][1] * s; if (i === 0) g.moveTo(X2, Y2); else g.lineTo(X2, Y2); }
      g.stroke();
      g.lineWidth = 3;
      var grd = g.createLinearGradient(0, cy, 0, cy - FAR * s);
      grd.addColorStop(0, 'rgba(56,225,255,1)'); grd.addColorStop(1, 'rgba(56,225,255,.25)');
      g.strokeStyle = grd;
      g.beginPath();
      var started = false;
      for (i = 0; i < dispN; i++) {
        if (disp[i][1] < -1) continue;
        var X3 = cx + disp[i][0] * s, Y3 = cy - disp[i][1] * s;
        if (!started) { g.moveTo(X3, Y3); started = true; } else g.lineTo(X3, Y3);
      }
      g.stroke();
    }
    // 紅綠燈位置
    if (c && c.matched && c.sig.length) {
      for (i = 0; i < c.sig.length; i++) {
        var d = c.sig[i] - q.ds;
        if (d < 0 || d > FAR) continue;
        var pt = pointAtDisp(d);
        if (!pt) continue;
        g.fillStyle = '#ffc24b'; g.strokeStyle = 'rgba(9,13,19,.9)'; g.lineWidth = 2;
        g.beginPath(); g.arc(cx + pt[0] * s, cy - pt[1] * s, 3.5, 0, Math.PI * 2); g.fill(); g.stroke();
      }
    }
    // 自車箭頭
    g.save();
    g.translate(cx, cy);
    g.fillStyle = 'rgba(56,225,255,.18)';
    g.beginPath(); g.arc(0, 0, Math.max(6, Math.min(40, (c ? c.acc : 0) * s)), 0, Math.PI * 2); g.fill();
    g.fillStyle = '#eaf7ff'; g.strokeStyle = '#0b1118'; g.lineWidth = 1.5;
    g.beginPath(); g.moveTo(0, -9); g.lineTo(6.5, 7); g.lineTo(0, 3.5); g.lineTo(-6.5, 7); g.closePath();
    g.fill(); g.stroke();
    g.restore();
  }
  // 顯示中心線上，從車子起算弧長 d 的點
  function pointAtDisp(d) {
    var acc = 0, i0 = -1;
    for (var i = 1; i < dispN; i++) if (disp[i - 1][1] <= 0 && disp[i][1] > 0) { i0 = i; break; }
    if (i0 < 0) return null;
    var px = 0, pz = 0;
    for (i = i0; i < dispN; i++) {
      var l = hyp(disp[i][0] - px, disp[i][1] - pz);
      if (acc + l >= d) { var k = l > 0 ? (d - acc) / l : 0; return [px + (disp[i][0] - px) * k, pz + (disp[i][1] - pz) * k]; }
      acc += l; px = disp[i][0]; pz = disp[i][1];
    }
    return null;
  }

  // ---------------- 主流程 ----------------
  function init(scene, THREE, hudParent) {
    if (inited) return;
    T = THREE || window.THREE;
    if (!T || !scene) return;
    inited = true;
    root = new T.Group();
    root.name = 'avm-map';
    root.visible = false;
    scene.add(root);
    // 線從 12 m 才淡入：近處讓鏡頭偵測的車道線
    var mRoad = mat(COL.road, 0.92, NEAR, 2), mEdge = mat(COL.edge, 0.55, NEAR + 4, 3),
      mWhite = mat(COL.white, 0.8, NEAR + 4, 3), mYellow = mat(COL.yellow, 0.85, NEAR + 4, 3);
    meshes.road = dynMesh(MAXP * 4, MAXP * 18, mRoad, -3); meshes.road.userData.y = Y_ROAD;
    meshes.edgeL = dynMesh(MAXP * 2, MAXP * 6, mEdge, -2); meshes.edgeL.userData.y = Y_LINE;
    meshes.edgeR = dynMesh(MAXP * 2, MAXP * 6, mEdge, -2); meshes.edgeR.userData.y = Y_LINE;
    meshes.yel1 = dynMesh(MAXP * 2, MAXP * 6, mYellow, -2); meshes.yel1.userData.y = Y_LINE;
    meshes.yel2 = dynMesh(MAXP * 2, MAXP * 6, mYellow, -2); meshes.yel2.userData.y = Y_LINE;
    meshes.dash = dynMesh(MAXQ * 4, MAXQ * 6, mWhite, -2); meshes.dash.userData.y = Y_LINE;
    buildHud(hudParent);
  }

  function update(map, now) {
    if (!inited) return;
    now = typeof now === 'number' ? now : performance.now();
    var dt = Math.min(0.2, Math.max(0, (now - (lastNow || now)) / 1000));
    lastNow = now;
    if (map && typeof map === 'object') {
      var isNew = map.t === undefined || map.t !== lastT;
      if (isNew) {
        lastT = map.t;
        lastAt = now;
        cur = prep(map, now);
        if (cur.matched) {
          var ph = mod(cur.od, PERIOD);
          if (!phaseInit) { phase = ph; phaseInit = true; }
          cur.phT = ph;
        }
      }
    }
    var fresh = cur && now - lastAt < STALE_MS;
    var showRoad = !!(fresh && cur.matched);
    var showMini = !!(fresh && (cur.matched || cur.roads.length));
    var k = 1 - Math.exp(-dt / 0.35);
    fade += ((showRoad ? 1 : 0) - fade) * (1 - Math.exp(-dt / 0.4));

    var q = cur ? pose(cur, now) : null;
    if (cur && cur.matched && q) buildDisplay(cur, q, k);
    else if (!showRoad) dispN = fade < 0.02 ? 0 : dispN;

    // 3D
    root.visible = fade > 0.02 && dispN >= 2;
    if (root.visible) {
      for (var key in meshes) if (meshes.hasOwnProperty(key)) {
        var mm = meshes[key].material;
        mm.uniforms.uOpacity.value = mm.userData.base * fade;
      }
      draw3d(cur, q);
    }

    // HUD
    if (hud) {
      offAttr(hud, !(showMini || showRoad));
      offAttr(ui.mini, !showMini);
      if (showMini && now - miniLast > 60) { miniLast = now; drawMini(cur, q, now); }
      chips(cur, q, showRoad);
    }
  }
  function offAttr(e, off) {
    if (e.hasAttribute('data-off') === off) return;
    if (off) e.setAttribute('data-off', ''); else e.removeAttribute('data-off');
  }

  // 目前車輛座標下的中心線（每 5 m），對上一幀平滑
  function buildDisplay(c, q, k) {
    var n = 0, tgt = [], p = [0, 0];
    var sMin = c.S[0], sMax = c.S[c.S.length - 1];
    for (var rel = -30; rel <= FAR + 1e-6; rel += 5) {
      var s = q.s + rel;
      if (s < sMin - 1e-6 || s > sMax + 1e-6) continue;
      at(c, s, p);
      var v = toCar(q, p[0], p[1], [0, 0]);
      tgt.push([v[0], v[1], rel]);
      n++;
    }
    var out = [], i, j = 0;
    for (i = 0; i < n; i++) {
      // 用相對弧長對齊上一幀的點
      var old = null;
      while (j < dispN && disp[j][2] < tgt[i][2] - 0.01) j++;
      if (j < dispN && Math.abs(disp[j][2] - tgt[i][2]) < 0.01) old = disp[j];
      if (old) out.push([old[0] + (tgt[i][0] - old[0]) * k, old[1] + (tgt[i][1] - old[1]) * k, tgt[i][2]]);
      else out.push(tgt[i]);
    }
    disp = out; dispN = n;
    if (c.phT !== undefined) {
      var tp = mod(c.phT + q.ds, PERIOD);
      phase = mod(phase + (mod(tp - phase + PERIOD / 2, PERIOD) - PERIOD / 2) * k, PERIOD);
    }
  }

  var NX = new Float32Array(MAXP), NZ = new Float32Array(MAXP), SD = new Float32Array(MAXP);
  function draw3d(c, q) {
    var n = Math.min(dispN, MAXP), P = disp, i;
    if (n < 2) return;
    var nx = NX, nz = NZ;
    for (i = 0; i < n; i++) {
      var a = P[Math.max(0, i - 1)], b = P[Math.min(n - 1, i + 1)];
      var tx = b[0] - a[0], tz = b[1] - a[1], l = hyp(tx, tz) || 1;
      nx[i] = tz / l; nz[i] = -tx / l;     // 右法向
      SD[i] = i === 0 ? 0 : SD[i - 1] + hyp(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1]);
    }
    // 弧長原點：車子（rel = 0）那一點
    var base = 0;
    for (i = 0; i < n; i++) if (Math.abs(P[i][2]) < 0.01) { base = SD[i]; break; }
    var Sd = [];
    for (i = 0; i < n; i++) Sd.push(SD[i] - base);
    var w = c.w, hw = w / 2;
    surface(meshes.road, P, n, nx, nz, w);
    strip(meshes.edgeL, P, n, nx, nz, -hw + 0.18, -hw + 0.32, 1);
    strip(meshes.edgeR, P, n, nx, nz, hw - 0.32, hw - 0.18, 1);
    var offs = [], L = c.lanes, j;
    if (c.oneway) {
      for (j = 1; j < L; j++) offs.push(-hw + j * w / L);
    } else {
      var per = Math.max(1, Math.round(L / 2));
      for (j = 1; j < per; j++) { offs.push(j * hw / per); offs.push(-j * hw / per); }
    }
    if (c.center) {
      strip(meshes.yel1, P, n, nx, nz, -0.2, -0.07, 1);   // 雙黃線
      strip(meshes.yel2, P, n, nx, nz, 0.07, 0.2, 1);
    }
    meshes.yel1.visible = meshes.yel2.visible = c.center;
    dashes(meshes.dash, P, Sd, n, nx, nz, offs, 0.075, phase);
  }

  function chips(c, q, show) {
    var sp = show && c.maxspeed > 0;
    ui.sign.hidden = !sp;
    if (sp) {
      var txt = String(c.maxspeed);
      if (ui.sign.textContent !== txt) ui.sign.textContent = txt;
      var over = c.spd * 3.6 > c.maxspeed + 5;
      if (ui.sign.className.indexOf('over') >= 0 !== over) ui.sign.className = 'amap-sign' + (over ? ' over' : '');
    }
    var nm = show && c.name;
    ui.name.hidden = !nm;
    if (nm) {
      var lt = c.name;
      var key = lt + '|' + (c.known ? c.lanes : '');
      if (ui.nameTxt.getAttribute('data-v') !== key) {
        ui.nameTxt.setAttribute('data-v', key);
        ui.nameTxt.innerHTML = '';
        ui.nameTxt.appendChild(document.createTextNode(lt + ' '));
        if (c.known) {   // 車道數只在 OSM 有標時顯示
          var sm = document.createElement('small');
          sm.textContent = c.lanes + ' 車道';
          ui.nameTxt.appendChild(sm);
        }
      }
      ui.ow.hidden = !c.oneway;
    }
    var d = -1;
    if (show && c.sig.length) for (var i = 0; i < c.sig.length; i++) { var di = c.sig[i] - q.ds; if (di >= -2) { d = Math.max(0, di); break; } }
    var sg = d >= 0 && d <= 300;
    ui.sig.hidden = !sg;
    if (sg) {
      var dr = d < 50 ? Math.round(d) : Math.round(d / 5) * 5;
      var html = '前方 <b>' + dr + '</b> m 紅綠燈';
      if (ui.sigTxt.getAttribute('data-v') !== html) { ui.sigTxt.setAttribute('data-v', html); ui.sigTxt.innerHTML = html; }
    }
    var at2 = c && show && c.acc > 0 ? 'GPS ±' + Math.round(c.acc) + ' m' : '';
    if (ui.acc.textContent !== at2) ui.acc.textContent = at2;
  }

  /** 調整近處淡入距離（m）：路面 / 車道線。預設 8 / 12。 */
  function setNear(surfaceM, linesM) {
    if (!inited) return;
    for (var key in meshes) if (meshes.hasOwnProperty(key)) {
      var v = key === 'road' ? surfaceM : linesM;
      if (typeof v === 'number' && isFinite(v) && v >= 0) meshes[key].material.uniforms.uNear.value = v;
    }
  }

  window.AVM_MAP = { init: init, update: update, setNear: setNear };
})();
