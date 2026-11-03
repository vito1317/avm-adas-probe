/*
 * 換道輔助 3D 圖層（three.js r128，ES5）：盲點區、HUD 左右警示、換道路徑、後方來車 TTC 標籤。
 *
 *   AVM_LCA.init(scene, THREE, hudParent)   // 建一次
 *   AVM_LCA.update(lca, objs, now)          // 建議每個繪製幀呼叫（閃爍、淡入淡出、路徑流動要靠它）
 *
 * lca = payload.lca：{ L, R, intent, path: [[x,z],...]|null, pathOk, threat: {id, ttc}|null, zones: {L:[xMin,zMin,xMax,zMax], R:[...]} }
 * objs = payload.objs（帶 id、x、z 公尺，z = 前方）。座標：場景 x = x、場景 z = −z。
 * lca 不存在或 1.2 s 沒換新物件 → 全部收起來。
 * 有換道意圖但 pathOk = false、該側又沒有盲點/來車等級時（目標車道前方有並行車）：該側顯示「目標車道有車」。
 */
(function () {
  'use strict';

  var STALE_MS = 1200;
  var Y_ZONE = 0.03, Y_PATH = 0.05, Y_ARROW = 0.051;
  var DEF_ZONE = { L: [-5.25, -12.42, -1.75, 1], R: [1.75, -12.42, 5.25, 1] };
  var HW = 0.3;            // 路徑半寬（m）
  var PERIOD = 2.2;        // 箭紋間距（m）
  var FLOW = 2.0;          // 箭紋流動速度（m/s）
  var MAXV = 512;          // 路徑最多取樣點
  var TAG_H = { truck: 3.9, bus: 3.9, moto: 2.4, bigbike: 2.4, bike: 2.4 };

  var T = null, group = null, hud = null, inited = false;
  var C = {};
  var zone = {}, badge = {};
  var rib = null, ribGeo = null, ribMat = null, chevTex = null;
  var arrow = null, arrowGeo = null, arrowMat = null;
  var pathA = 0, pathSrc = null, pathOkCol = true, pathLen = 0;
  var tag = null, tagEl = null, tagSprite = null, tagCanvas = null, tagTxt = '', tagCls = '', tagOn = false;
  var tagPos = { x: 0, y: 0, z: 0 };
  var cur = null, curAt = -1e9, lastNow = 0;

  function lin(hex) { return new T.Color(hex).convertSRGBToLinear(); }   // 配 toneMapped:false，色碼原樣顯示
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function lvl(v) { return v === 2 ? 2 : v === 1 ? 1 : 0; }
  function ease(dt, rate) { return 1 - Math.exp(-dt * rate); }
  function smooth(a, b, x) { var t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

  // ---------- 樣式 ----------
  function injectCss() {
    if (document.getElementById('lca3d-css')) return;
    var s = document.createElement('style');
    s.id = 'lca3d-css';
    // 左右下角、dock 那一排上方（跟車視角時就在自車兩側後方，像後視鏡）；左上統計面板、右下號誌都避開
    s.textContent =
      '.lca-side{position:absolute;z-index:5;bottom:72px;display:flex;flex-direction:column;' +
      'align-items:center;gap:3px;min-width:66px;padding:8px 8px 6px;border-radius:14px;pointer-events:none;' +
      'background:rgba(14,20,28,.84);border:1.5px solid rgba(255,194,75,.75);color:#ffc24b;' +
      'font-family:system-ui,-apple-system,"Noto Sans TC","PingFang TC","Microsoft JhengHei",sans-serif}' +
      '.lca-side.lca-l{left:10px}.lca-side.lca-r{right:10px}' +
      '.lca-side svg{display:block;width:44px;height:44px}.lca-side.lca-r svg{transform:scaleX(-1)}' +
      '.lca-side .lca-tx{font-size:12px;font-weight:700;letter-spacing:.08em;white-space:nowrap}' +
      '.lca-side.lv2{color:#ff6e62;border-color:#ff5a4d;background:rgba(74,12,9,.88);animation:lcaBlink .5s steps(1) infinite}' +
      '@keyframes lcaBlink{50%{opacity:.3}}' +
      '@media (prefers-reduced-motion:reduce){.lca-side.lv2{animation:none}}' +
      '.lca-ttc{font-size:13px;font-weight:700;padding:2px 8px;border-radius:7px;white-space:nowrap;pointer-events:none;' +
      'font-variant-numeric:tabular-nums;background:rgba(9,13,19,.86);border:1px solid rgba(255,194,75,.8);color:#ffc24b;' +
      'font-family:system-ui,-apple-system,"Noto Sans TC","PingFang TC",sans-serif}' +
      '.lca-ttc.lv2{border-color:#ff5a4d;color:#ff8278}' +
      '[hidden].lca-side{display:none!important}';
    document.head.appendChild(s);
  }

  // 俯視車輛 + 側邊波紋（左側版本；右側用 CSS 鏡射）
  var ICON = '<svg viewBox="0 0 48 48" aria-hidden="true" fill="none" stroke="currentColor" stroke-linecap="round">' +
    '<rect x="21" y="9" width="14" height="30" rx="5" stroke-width="3"/>' +
    '<path d="M24 16h8M24 32h8" stroke-width="2.4"/>' +
    '<path d="M15 15q-4 9 0 18" stroke-width="3"/>' +
    '<path d="M9 11q-7 13 0 26" stroke-width="3" opacity=".6"/></svg>';

  function makeBadge(side) {
    var el = document.createElement('div');
    el.className = 'lca-side lca-' + side.toLowerCase();
    el.setAttribute('role', 'status');
    el.setAttribute('aria-live', 'polite');
    el.hidden = true;
    el.innerHTML = ICON + '<span class="lca-tx"></span>';
    hud.appendChild(el);
    return { el: el, tx: el.querySelector('.lca-tx'), lv: 0, text: '' };
  }

  // ---------- 盲點區：漸層貼圖（靠自車側較濃、往後淡出），內緣一道亮邊 ----------
  function zoneTex(innerRight) {
    var c = document.createElement('canvas');
    c.width = 64; c.height = 128;
    var g = c.getContext('2d');
    for (var x = 0; x < 64; x++) {
      var d = innerRight ? (63 - x) / 63 : x / 63;          // 0 = 內緣（車道線）、1 = 外緣
      var edge = d < 0.06 ? 1 : 0;
      var vg = g.createLinearGradient(0, 0, 0, 128);       // 畫布上方 = 前端（v=1）
      var a0 = edge ? 0.95 : 0.5 * (1 - 0.45 * d), a1 = edge ? 0.25 : 0.0;
      vg.addColorStop(0, 'rgba(255,255,255,' + (a0 * 0.75).toFixed(3) + ')');
      vg.addColorStop(0.18, 'rgba(255,255,255,' + a0.toFixed(3) + ')');
      vg.addColorStop(1, 'rgba(255,255,255,' + a1.toFixed(3) + ')');
      g.fillStyle = vg;
      g.fillRect(x, 0, 1, 128);
    }
    var t = new T.CanvasTexture(c);
    t.minFilter = T.LinearFilter;
    t.generateMipmaps = false;
    return t;
  }

  function makeZone(side) {
    var mat = new T.MeshBasicMaterial({ map: zoneTex(side === 'L'), color: C.amber.clone(), transparent: true,
      opacity: 0, depthWrite: false, toneMapped: false, side: T.DoubleSide,
      polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -6 });
    var m = new T.Mesh(new T.PlaneGeometry(1, 1), mat);
    m.rotation.x = -Math.PI / 2;
    m.position.y = Y_ZONE;
    m.renderOrder = 2;
    m.visible = false;
    group.add(m);
    var r = DEF_ZONE[side];
    return { mesh: m, mat: mat, cur: r.slice(), tgt: r.slice(), a: 0, lv: 0, shownLv: 1 };
  }

  function placeZone(z) {
    var r = z.cur;
    z.mesh.scale.set(Math.max(0.1, r[2] - r[0]), Math.max(0.1, r[3] - r[1]), 1);
    z.mesh.position.set((r[0] + r[2]) / 2, Y_ZONE, -(r[1] + r[3]) / 2);
  }

  // ---------- 換道路徑：帶狀 + 流動箭紋 + 箭頭 ----------
  function chevronTex() {
    var c = document.createElement('canvas');
    c.width = 64; c.height = 32;
    var g = c.getContext('2d');
    g.fillStyle = 'rgba(255,255,255,.34)';
    g.fillRect(0, 0, 64, 32);
    g.strokeStyle = 'rgba(255,255,255,.95)';
    g.lineWidth = 6;
    g.lineJoin = 'round';
    g.beginPath();
    g.moveTo(22, 5); g.lineTo(40, 16); g.lineTo(22, 27);
    g.stroke();
    var t = new T.CanvasTexture(c);
    t.wrapS = T.RepeatWrapping;
    t.minFilter = T.LinearFilter;
    t.generateMipmaps = false;
    return t;
  }

  function makePath() {
    chevTex = chevronTex();
    ribGeo = new T.BufferGeometry();
    var pos = new T.BufferAttribute(new Float32Array(MAXV * 2 * 3), 3);
    var col = new T.BufferAttribute(new Float32Array(MAXV * 2 * 4), 4);
    var uv = new T.BufferAttribute(new Float32Array(MAXV * 2 * 2), 2);
    pos.setUsage(T.DynamicDrawUsage); col.setUsage(T.DynamicDrawUsage); uv.setUsage(T.DynamicDrawUsage);
    ribGeo.setAttribute('position', pos);
    ribGeo.setAttribute('color', col);
    ribGeo.setAttribute('uv', uv);
    var idx = [];
    for (var i = 0; i < MAXV - 1; i++) { var a = i * 2; idx.push(a, a + 1, a + 2, a + 2, a + 1, a + 3); }
    ribGeo.setIndex(idx);
    ribGeo.setDrawRange(0, 0);
    ribMat = new T.MeshBasicMaterial({ map: chevTex, color: C.green.clone(), vertexColors: true, transparent: true,
      opacity: 0, depthWrite: false, toneMapped: false, side: T.DoubleSide,
      polygonOffset: true, polygonOffsetFactor: -3, polygonOffsetUnits: -8 });
    rib = new T.Mesh(ribGeo, ribMat);
    rib.frustumCulled = false;   // 幾何會變，包圍球不準
    rib.renderOrder = 3;
    rib.visible = false;
    group.add(rib);

    arrowGeo = new T.BufferGeometry();
    var ap = new T.BufferAttribute(new Float32Array(4 * 3), 3);
    ap.setUsage(T.DynamicDrawUsage);
    arrowGeo.setAttribute('position', ap);
    arrowGeo.setIndex([0, 1, 2, 0, 2, 3]);   // 尖端、左翼、凹口、右翼
    arrowMat = new T.MeshBasicMaterial({ color: C.green.clone(), transparent: true, opacity: 0, depthWrite: false,
      toneMapped: false, side: T.DoubleSide, polygonOffset: true, polygonOffsetFactor: -3, polygonOffsetUnits: -8 });
    arrow = new T.Mesh(arrowGeo, arrowMat);
    arrow.frustumCulled = false;
    arrow.renderOrder = 3;
    arrow.visible = false;
    group.add(arrow);
  }

  /** path（車身座標 [[x,z],...]）→ 帶狀幾何。回傳是否有效。 */
  function buildPath(path) {
    var px = [], pz = [], ps = [], i, n = 0, s = 0;
    for (i = 0; i < path.length && n < MAXV - 2; i++) {
      var p = path[i];
      if (!p || !num(p[0]) || !num(p[1])) continue;
      if (n > 0) {
        var x0 = px[n - 1], z0 = pz[n - 1], d = Math.hypot(p[0] - x0, p[1] - z0);
        if (d < 0.05) continue;
        // 太疏就線性補點（正常是每 1 m 一點）
        var k = Math.max(1, Math.min(Math.ceil(d / 1.2), MAXV - 2 - n));
        for (var j = 1; j < k; j++) {
          var f = j / k;
          px.push(x0 + (p[0] - x0) * f); pz.push(z0 + (p[1] - z0) * f); ps.push(s + d * f); n++;
        }
        s += d;
      }
      px.push(p[0]); pz.push(p[1]); ps.push(s); n++;
    }
    if (n < 2 || s < 1.5) return false;
    var L = s, AL = Math.min(1.6, L * 0.2), se = L - AL * 0.55;
    var pa = ribGeo.attributes.position.array, ca = ribGeo.attributes.color.array, ua = ribGeo.attributes.uv.array;
    var m = 0;
    function put(x, z, tx, tz, si) {
      var nx = tz, nz = -tx;   // 右手法向
      var a = smooth(1.5, 4.0, si) * (1 - 0.3 * smooth(se - 5, se, si));
      var o = m * 6;
      pa[o] = x - nx * HW; pa[o + 1] = Y_PATH; pa[o + 2] = -(z - nz * HW);
      pa[o + 3] = x + nx * HW; pa[o + 4] = Y_PATH; pa[o + 5] = -(z + nz * HW);
      var c = m * 8;
      ca[c] = ca[c + 1] = ca[c + 2] = 1; ca[c + 3] = a;
      ca[c + 4] = ca[c + 5] = ca[c + 6] = 1; ca[c + 7] = a;
      var u = m * 4;
      ua[u] = si / PERIOD; ua[u + 1] = 0;
      ua[u + 2] = si / PERIOD; ua[u + 3] = 1;
      m++;
    }
    function tan(k) {
      var a = Math.max(0, k - 1), b = Math.min(n - 1, k + 1);
      var tx = px[b] - px[a], tz = pz[b] - pz[a], l = Math.hypot(tx, tz) || 1;
      return [tx / l, tz / l];
    }
    for (i = 0; i < n && ps[i] < se; i++) { var t = tan(i); put(px[i], pz[i], t[0], t[1], ps[i]); }
    // 帶狀尾端剛好接到箭頭底部
    var e = Math.max(1, i);
    var f0 = (se - ps[e - 1]) / Math.max(1e-6, ps[Math.min(e, n - 1)] - ps[e - 1]);
    var ex = px[e - 1] + (px[Math.min(e, n - 1)] - px[e - 1]) * f0, ez = pz[e - 1] + (pz[Math.min(e, n - 1)] - pz[e - 1]) * f0;
    var te = tan(Math.min(e, n - 1));
    put(ex, ez, te[0], te[1], se);
    ribGeo.setDrawRange(0, Math.max(0, m - 1) * 6);
    ribGeo.attributes.position.needsUpdate = true;
    ribGeo.attributes.color.needsUpdate = true;
    ribGeo.attributes.uv.needsUpdate = true;

    // 箭頭：尖端在路徑終點，沿終點切線
    var tl = tan(n - 1), tx = tl[0], tz = tl[1], nx = tz, nz = -tx, bx = px[n - 1] - tx * AL, bz = pz[n - 1] - tz * AL;
    var aw = HW * 2.3, ar = arrowGeo.attributes.position.array;
    ar[0] = px[n - 1]; ar[1] = Y_ARROW; ar[2] = -pz[n - 1];
    ar[3] = bx - nx * aw; ar[4] = Y_ARROW; ar[5] = -(bz - nz * aw);
    ar[6] = bx + tx * AL * 0.3; ar[7] = Y_ARROW; ar[8] = -(bz + tz * AL * 0.3);
    ar[9] = bx + nx * aw; ar[10] = Y_ARROW; ar[11] = -(bz + nz * aw);
    arrowGeo.attributes.position.needsUpdate = true;
    pathLen = L;
    return true;
  }

  // ---------- TTC 標籤 ----------
  function makeTag() {
    if (T.CSS2DObject) {
      tagEl = document.createElement('div');
      tagEl.className = 'lca-ttc';
      tag = new T.CSS2DObject(tagEl);
      tag.visible = false;
      group.add(tag);
      return;
    }
    // 沒載 CSS2DRenderer：退回畫在貼圖上的 Sprite
    tagCanvas = document.createElement('canvas');
    tagCanvas.width = 192; tagCanvas.height = 56;
    var tex = new T.CanvasTexture(tagCanvas);
    tex.minFilter = T.LinearFilter;
    tex.generateMipmaps = false;
    tagSprite = new T.Sprite(new T.SpriteMaterial({ map: tex, transparent: true, depthTest: false, toneMapped: false }));
    tagSprite.scale.set(1.9, 0.55, 1);
    tagSprite.renderOrder = 10;
    tagSprite.visible = false;
    tag = tagSprite;
    group.add(tag);
  }

  function setTag(txt, red) {
    var cls = 'lca-ttc' + (red ? ' lv2' : '');
    if (txt === tagTxt && cls === tagCls) return;
    tagTxt = txt; tagCls = cls;
    if (tagEl) { tagEl.textContent = txt; tagEl.className = cls; return; }
    var g = tagCanvas.getContext('2d'), w = tagCanvas.width, h = tagCanvas.height;
    g.clearRect(0, 0, w, h);
    g.fillStyle = 'rgba(9,13,19,.86)';
    g.strokeStyle = red ? '#ff5a4d' : '#ffc24b';
    g.lineWidth = 3;
    g.beginPath();
    if (g.roundRect) g.roundRect(2, 2, w - 4, h - 4, 12); else g.rect(2, 2, w - 4, h - 4);
    g.fill(); g.stroke();
    g.fillStyle = red ? '#ff8278' : '#ffc24b';
    g.font = 'bold 30px system-ui, sans-serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(txt, w / 2, h / 2 + 1);
    tagSprite.material.map.needsUpdate = true;
  }

  // ---------- 對外 ----------
  function init(scene, THREE, hudParent) {
    if (inited || !scene || !THREE) return;
    T = THREE;
    hud = hudParent || document.body;
    C.amber = lin(0xffc24b); C.red = lin(0xff5a4d); C.green = lin(0x5ef08a);
    injectCss();
    group = new T.Group();
    group.name = 'lca';
    scene.add(group);
    zone.L = makeZone('L'); zone.R = makeZone('R');
    placeZone(zone.L); placeZone(zone.R);
    badge.L = makeBadge('L'); badge.R = makeBadge('R');
    makePath();
    makeTag();
    inited = true;
  }

  function threatSide(lca, objs) {
    var th = lca && lca.threat;
    if (!th || !objs) return null;
    for (var i = 0; i < objs.length; i++) {
      var o = objs[i];
      if (o && o.id === th.id && num(o.x) && num(o.z)) return o;
    }
    return null;
  }

  // blocked：要往這側換道，但預測路徑會碰到目標車道上的車（例如前車門以前、盲點區外的並行車）
  function updBadge(b, side, lv, closing, blocked) {
    var text = lv === 2 ? '危險' : blocked ? '目標車道有車' : lv === 1 ? (closing ? '後方來車' : '盲點有車') : '';
    var show = lv > 0 || blocked;
    if (lv === b.lv && text === b.text) return;
    b.lv = lv; b.text = text;
    b.el.hidden = !show;
    b.el.className = 'lca-side lca-' + side.toLowerCase() + (lv === 2 ? ' lv2' : '');
    b.tx.textContent = text;
    b.el.setAttribute('aria-label', !show ? '' : (side === 'L' ? '左側' : '右側') +
      (lv === 2 ? '危險，請勿變換車道' : blocked ? '目標車道有車，請勿切入' : text));
  }

  function updZone(z, rect, lv, dt, now) {
    if (rect && rect.length === 4 && num(rect[0]) && num(rect[1]) && num(rect[2]) && num(rect[3]) &&
      rect[2] > rect[0] && rect[3] > rect[1]) {
      for (var i = 0; i < 4; i++) z.tgt[i] = rect[i];
    }
    var k = z.mesh.visible ? ease(dt, 10) : 1;   // 隱藏時直接到位，出現時不會從舊位置滑過來
    for (var j = 0; j < 4; j++) z.cur[j] += (z.tgt[j] - z.cur[j]) * k;
    if (lv > 0) z.shownLv = lv;
    z.a += ((lv > 0 ? 1 : 0) - z.a) * ease(dt, lv > 0 ? 14 : 6);
    if (z.a < 0.01) { z.a = 0; z.mesh.visible = false; return; }
    z.mesh.visible = true;
    placeZone(z);
    var red = z.shownLv === 2;
    z.mat.color.copy(red ? C.red : C.amber);
    z.mat.opacity = z.a * (red ? 0.72 + 0.28 * Math.sin(now / 95) : 0.8);
  }

  function update(lca, objs, now) {
    if (!inited) return;
    if (!num(now)) now = (window.performance && performance.now()) || Date.now();
    var dt = lastNow ? Math.min(0.1, Math.max(0, (now - lastNow) / 1000)) : 0.016;
    lastNow = now;
    if (lca && typeof lca === 'object' && lca !== cur) { cur = lca; curAt = now; }
    var live = !!lca && typeof lca === 'object' && now - curAt < STALE_MS;
    var L = live ? lvl(lca.L) : 0, R = live ? lvl(lca.R) : 0;
    var zs = live && lca.zones ? lca.zones : null;

    updZone(zone.L, zs && zs.L, L, dt, now);
    updZone(zone.R, zs && zs.R, R, dt, now);

    var to = live ? threatSide(lca, objs) : null;
    var ttc = live && lca.threat && num(lca.threat.ttc) ? lca.threat.ttc : null;
    var blocked = live && lca.path && lca.pathOk === false ? lca.intent : null;
    updBadge(badge.L, 'L', L, !!to && to.x < 0, blocked === 'L');
    updBadge(badge.R, 'R', R, !!to && to.x >= 0, blocked === 'R');

    // 路徑
    var path = live && lca.path && lca.path.length >= 2 ? lca.path : null;
    if (path && path !== pathSrc) { if (!buildPath(path)) path = null; pathSrc = path; }
    if (path) pathOkCol = lca.pathOk !== false;
    pathA += ((path ? 1 : 0) - pathA) * ease(dt, path ? 10 : 6);
    if (pathA < 0.01) {
      pathA = 0;
      rib.visible = arrow.visible = false;
      if (!path) pathSrc = null;
    } else {
      var pc = pathOkCol ? C.green : C.red;
      if (!rib.visible) ribMat.color.copy(pc);   // 剛出現：直接用目前顏色，不從上一次的顏色漸變（綠紅混成褐色）
      else ribMat.color.lerp(pc, ease(dt, 12));
      rib.visible = arrow.visible = true;
      arrowMat.color.copy(ribMat.color);
      ribMat.opacity = pathA * 0.95;
      arrowMat.opacity = pathA * 0.9;
      chevTex.offset.x = -((now / 1000) * FLOW / PERIOD % 1);
    }

    // TTC 標籤：跟著威脅車輛
    if (to && ttc !== null) {
      var h = TAG_H[to.t] || 3.0, x = to.x, z = -to.z;
      if (!tagOn) { tagPos.x = x; tagPos.y = h; tagPos.z = z; }
      var kp = ease(dt, 12);
      tagPos.x += (x - tagPos.x) * kp; tagPos.y += (h - tagPos.y) * kp; tagPos.z += (z - tagPos.z) * kp;
      tag.position.set(tagPos.x, tagPos.y, tagPos.z);
      var red = ttc < 2 || (to.x < 0 ? L : R) === 2;
      setTag('TTC ' + Math.max(0, ttc).toFixed(1) + ' s', red);
      tag.visible = tagOn = true;
    } else if (tagOn) {
      tag.visible = tagOn = false;
    }
  }

  window.AVM_LCA = { init: init, update: update };
})();
