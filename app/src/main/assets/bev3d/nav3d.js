/*
 * 車道級導航（3D 道路畫面）：中控台用 OpenStreetMap 搜尋目的地、OSRM 規劃路線後，用 postMessage 把路線送進來（{__route}）。
 * 這裡用 360 送來的定位（payload.map.lat / lon / hdg）算「走到路線的哪裡、下一個要轉的路口」，畫：
 *   - 上方導航卡：下一個動作的箭頭、距離、路名；
 *   - 車道導引列：那個路口每個車道可以往哪走（OSRM 依 OSM turn:lanes 算的 lanes），這條路線該走的車道亮藍色；
 *   - 地面上的路線帶（前方 300 m 內，藍色半透明）。
 * 偏離路線 50 m 以上超過 10 秒 → 請中控台重新規劃（{__reroute}）。只用來顯示提示，不拿去控制車子。
 * 座標：經緯度 → 以目前位置為原點的公尺（東 x、北 y）→ 車身座標（x 右、z 前，依航向 hdg：北 = 0 順時針）→ three.js（z 反向）。
 * 路線資料 © OpenStreetMap 貢獻者；路線規劃 OSRM。
 */
(function () {
  var route = null, map = null, mapAt = -1e9, idx = 0, offAt = 0, askedAt = -1e9;
  // 鏡頭認到的各車道用途（路面箭頭，payload.lp：{"0": "直行", "-1": "左轉"}，0 = 目前車道、−1 = 左邊那條）
  var lp = null, lpAt = -1e9;
  var mini = null;      // 小地圖用：路線點（車身座標），前方 600 m 內
  var guide = null, guideAt = -1e9;   // 給 360 畫面用的導引（中控台轉給 360 引擎）
  var LP_IND = { '直行': ['straight'], '左轉': ['left'], '右轉': ['right'], '直行／左轉': ['left', 'straight'],
    '直行／右轉': ['straight', 'right'], '迴轉': ['uturn'], '左轉／右轉': ['left', 'right'] };
  function dirOf(mod) { return /left/.test(mod) ? 'left' : /right/.test(mod) ? 'right' : mod === 'uturn' ? 'uturn' : 'straight'; }
  var card = null, laneBar = null, band = null, bandGeo = null, THREE_ = null;
  var NB = 60;

  function el(tag, css, parent) { var e = document.createElement(tag); e.style.cssText = css; (parent || document.body).appendChild(e); return e; }

  function init(THREE, scene, hudParent) {
    THREE_ = THREE;
    var root = hudParent || document.body;
    card = el('div', 'position:absolute;top:96px;left:50%;transform:translateX(-50%);z-index:9;display:none;' +
      'background:rgba(10,16,24,.86);border:1px solid rgba(90,170,255,.35);border-radius:14px;padding:8px 16px;color:#e8f1f8;' +
      'font-family:system-ui,"Noto Sans TC",sans-serif;text-align:center;min-width:220px;pointer-events:none', root);
    laneBar = el('div', 'display:flex;gap:4px;justify-content:center;margin-top:6px', card);
    bandGeo = new THREE.BufferGeometry();
    bandGeo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(NB * 2 * 3), 3));
    var ix = []; for (var i = 0; i < NB - 1; i++) { var a = i * 2; ix.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    bandGeo.setIndex(ix);
    band = new THREE.Mesh(bandGeo, new THREE.MeshBasicMaterial({ color: 0x2f8cff, transparent: true, opacity: 0.28, depthWrite: false, side: THREE.DoubleSide, toneMapped: false }));
    band.renderOrder = 4; band.frustumCulled = false; band.visible = false;
    scene.add(band);
  }

  // 經緯度 → 以 (lat0, lon0) 為原點的公尺
  function enu(lat, lon, lat0, lon0) { return [(lon - lon0) * 111320 * Math.cos(lat0 * Math.PI / 180), (lat - lat0) * 110540]; }
  function toCar(e, hdg) {       // (東, 北) → (x 右, z 前)
    var h = hdg * Math.PI / 180, c = Math.cos(h), s = Math.sin(h);
    return [e[0] * c - e[1] * s, e[0] * s + e[1] * c];
  }

  function setRoute(r) {
    route = r || null; idx = 0; offAt = 0;
    if (route) {   // 每個動作在路線上的位置（最近的頂點）
      route.steps.forEach(function (st) {
        var best = 0, bd = 1e18;
        for (var i = 0; i < route.coords.length; i++) {
          var d = Math.pow(route.coords[i][0] - st.loc[0], 2) + Math.pow(route.coords[i][1] - st.loc[1], 2);
          if (d < bd) { bd = d; best = i; }
        }
        st.ri = best;
      });
    }
    if (!route) { card.style.display = 'none'; band.visible = false; }
  }

  function onLanes(l, now) { if (l) { lp = l; lpAt = now; } }
  function onMap(m, now) { if (m && typeof m.lat === 'number' && typeof m.lon === 'number') { map = m; mapAt = now; } }

  var ARROW = { 'left': '↰', 'sharp left': '↰', 'slight left': '↖', 'straight': '↑', 'none': '↑', 'right': '↱', 'sharp right': '↱', 'slight right': '↗', 'uturn': '↶' };
  function glyph(ind) { return (ind && ind.length ? ind : ['none']).map(function (k) { return ARROW[k] || '↑'; }).join(''); }
  function actText(st) {
    var m = st.modifier || '', t = st.type;
    if (t === 'arrive') return '抵達目的地';
    if (t === 'roundabout' || t === 'rotary') return '進圓環' + (st.exit ? '，第 ' + st.exit + ' 個出口' : '');
    var dir = { 'left': '左轉', 'sharp left': '左轉', 'slight left': '靠左', 'right': '右轉', 'sharp right': '右轉', 'slight right': '靠右', 'uturn': '迴轉', 'straight': '直行' }[m] || '直行';
    if (t === 'merge') return '匯入';
    if (t === 'on ramp') return dir + '上匝道';
    if (t === 'off ramp') return dir + '下交流道';
    if (t === 'fork') return dir;
    return dir;
  }
  function fmtDist(d) { return d >= 1000 ? (d / 1000).toFixed(1) + ' km' : (d >= 100 ? Math.round(d / 10) * 10 : Math.round(d)) + ' m'; }

  function update(now) {
    if (!route || !map || now - mapAt > 5000) { if (card) card.style.display = 'none'; if (band) band.visible = false; mini = null; guide = null; return; }
    var lat0 = map.lat, lon0 = map.lon, hdg = map.hdg || 0, C = route.coords;
    // 目前在路線的哪裡：從上次的位置往前找（也容許往回一點）
    var best = idx, bd = 1e18;
    for (var i = Math.max(0, idx - 20); i < Math.min(C.length, idx + 200); i++) {
      var e = enu(C[i][1], C[i][0], lat0, lon0), d = e[0] * e[0] + e[1] * e[1];
      if (d < bd) { bd = d; best = i; }
    }
    idx = best; var off = Math.sqrt(bd);
    if (off > 50) { if (!offAt) offAt = now; if (now - offAt > 10000 && now - askedAt > 30000) { askedAt = now; try { window.parent.postMessage({ __reroute: 1 }, '*'); } catch (e) {} } }
    else offAt = 0;
    // 下一個動作（在目前位置之後的第一個 step，跳過 depart）
    var st = null;
    for (var k = 0; k < route.steps.length; k++) { var s = route.steps[k]; if (s.type !== 'depart' && s.ri > idx) { st = s; break; } }
    if (!st) st = route.steps[route.steps.length - 1];
    // 沿路線到那個動作的距離
    var dist = 0, prev = enu(C[idx][1], C[idx][0], lat0, lon0);
    for (i = idx + 1; i <= Math.min(st.ri, C.length - 1); i++) { var q = enu(C[i][1], C[i][0], lat0, lon0); dist += Math.hypot(q[0] - prev[0], q[1] - prev[1]); prev = q; }
    dist += Math.sqrt(bd);
    if (st.type === 'arrive' && dist < 30) { card.innerHTML = '<div style="font-size:20px;font-weight:700">已抵達目的地</div>'; card.style.display = ''; band.visible = false; guide = { arrive: 1 }; guideAt = now; return; }
    card.innerHTML = '<div style="display:flex;align-items:center;gap:12px;justify-content:center">' +
      '<span style="font-size:34px;line-height:1;color:#5fb4ff">' + glyph([st.modifier || 'straight']) + '</span>' +
      '<span style="font-size:24px;font-weight:700">' + fmtDist(dist) + '</span>' +
      '<span style="font-size:16px">' + actText(st) + (st.name ? '<br><span style="font-size:13px;color:#8ba1b4">' + st.name + '</span>' : '') + '</span></div>';
    card.appendChild(laneBar);
    // 車道導引：這個動作的路口（或之前最近一個有車道資料的路口），2 km 內才顯示
    laneBar.innerHTML = '';
    // 車道資料：地圖有（OSRM 依 OSM turn:lanes）就用；沒有 → 用鏡頭認到的路面箭頭（前方 500 m 內才有意義）
    var lanes = st.lanes && st.lanes.length ? st.lanes : null, cur = -1, want = dirOf(st.modifier || 'straight');
    if (!lanes && lp && now - lpAt < 8000 && dist < 500) {
      var keys = Object.keys(lp).map(Number).sort(function (a, b) { return a - b; });
      lanes = keys.map(function (k) { var ind = LP_IND[lp[String(k)]] || ['straight']; return { ind: ind, valid: ind.indexOf(want) >= 0 }; });
      cur = keys.indexOf(0);
    }
    if (lanes && dist < 2000) {
      lanes.forEach(function (ln, i) {
        var b = document.createElement('div');
        b.style.cssText = 'width:34px;height:38px;border-radius:6px;display:flex;align-items:center;justify-content:center;font-size:20px;' +
          (ln.valid ? 'background:rgba(47,140,255,.85);color:#fff' : 'background:rgba(255,255,255,.08);color:#6d7c8a') +
          (i === cur ? ';outline:2px solid #fff;outline-offset:1px' : '');
        b.textContent = glyph(ln.ind);
        laneBar.appendChild(b);
      });
    } else if (dist < 500 && st.type !== 'arrive') {
      // 沒有車道資料：照下一個動作提示靠哪邊
      var tip = want === 'left' || want === 'uturn' ? '請靠左側車道' : want === 'right' ? '請靠右側車道' : '保持直行';
      var t = document.createElement('div'); t.style.cssText = 'font-size:14px;color:#9fd0ff'; t.textContent = tip; laneBar.appendChild(t);
    }
    card.style.display = '';
    // 給 360 畫面的導引：下一個動作、距離、路名、車道（同上面的車道列）、前方 200 m 的路線（車身座標，每 3 m 一點）
    var gp = [];
    for (i = idx; i < C.length && gp.length < 70; i++) {
      var gc = toCar(enu(C[i][1], C[i][0], lat0, lon0), hdg);
      if (Math.hypot(gc[0], gc[1]) > 200) { if (i > idx + 2) break; else continue; }
      if (!gp.length || Math.hypot(gc[0] - gp[gp.length - 1][0], gc[1] - gp[gp.length - 1][1]) > 3) gp.push([Math.round(gc[0] * 10) / 10, Math.round(gc[1] * 10) / 10]);
    }
    guide = { act: actText(st), mod: st.modifier || 'straight', dist: Math.round(dist), name: st.name || '',
      lanes: lanes && dist < 2000 ? lanes.map(function (l) { return { ind: l.ind, valid: !!l.valid }; }) : null, cur: cur,
      tip: (!lanes && dist < 500 && st.type !== 'arrive') ? (want === 'left' || want === 'uturn' ? '請靠左側車道' : want === 'right' ? '請靠右側車道' : '保持直行') : '',
      pts: gp };
    guideAt = now;
    // 小地圖的路線（600 m 內，每 5 m 一點）
    mini = [];
    for (i = Math.max(0, idx - 5); i < C.length; i++) {
      var cm = toCar(enu(C[i][1], C[i][0], lat0, lon0), hdg);
      if (Math.hypot(cm[0], cm[1]) > 600) { if (i > idx) break; else continue; }
      if (!mini.length || Math.hypot(cm[0] - mini[mini.length - 1][0], cm[1] - mini[mini.length - 1][1]) > 5) mini.push(cm);
    }
    // 地面路線帶：前方 300 m 內的路線點 → 車身座標
    var pos = bandGeo.attributes.position.array, n = 0, pts = [];
    for (i = idx; i < C.length && pts.length < NB; i++) {
      var c = toCar(enu(C[i][1], C[i][0], lat0, lon0), hdg);
      if (Math.hypot(c[0], c[1]) > 300) break;
      if (!pts.length || Math.hypot(c[0] - pts[pts.length - 1][0], c[1] - pts[pts.length - 1][1]) > 2) pts.push(c);
    }
    if (pts.length >= 2) {
      for (i = 0; i < NB; i++) {
        var p = pts[Math.min(i, pts.length - 1)], a = pts[Math.max(0, Math.min(i, pts.length - 1) - 1)], b2 = pts[Math.min(pts.length - 1, i + 1)];
        var dx = b2[0] - a[0], dz = b2[1] - a[1], L = Math.hypot(dx, dz) || 1, nx = dz / L * 1.3, nz = -dx / L * 1.3;
        pos.set([p[0] - nx, 0.02, -(p[1] - nz), p[0] + nx, 0.02, -(p[1] + nz)], i * 6);
      }
      bandGeo.attributes.position.needsUpdate = true;
      band.visible = true;
    } else band.visible = false;
  }

  window.AVM_NAV = { init: init, setRoute: setRoute, onMap: onMap, onLanes: onLanes, update: update, miniPath: function () { return mini; },
    guidance: function (now) { return guide && now - guideAt < 3000 ? guide : null; } };
})();
