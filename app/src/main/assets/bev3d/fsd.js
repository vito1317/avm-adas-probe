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

  var mesh = null, line = null, lastLane = null, laneAt = -1e9, sm = null;

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
  function update(veh, lines, now, enabled) {
    if (!mesh) return;
    if (lines) { var ln = laneOf(lines); if (ln) { lastLane = ln; laneAt = now; } }
    var lane = now - laneAt < 1500 ? lastLane : null;
    var pts = enabled ? predict(veh, lane) : null;
    if (!pts) { mesh.visible = line.visible = false; sm = null; return; }
    // 平滑（每點指數平均），路徑才不會一幀一跳
    if (!sm || sm.length !== pts.length) sm = pts.map(function (p) { return p.slice(); });
    else for (var i = 0; i < pts.length; i++) { sm[i][0] += 0.3 * (pts[i][0] - sm[i][0]); sm[i][1] += 0.3 * (pts[i][1] - sm[i][1]); }
    var pos = mesh.geometry.attributes.position.array, col = mesh.geometry.attributes.color.array, lp = line.geometry.attributes.position.array;
    for (var j = 0; j <= N; j++) {
      var a = sm[Math.max(0, j - 1)], b = sm[Math.min(N, j + 1)];
      var dx = b[0] - a[0], dz = b[1] - a[1], L = Math.hypot(dx, dz) || 1, nx = dz / L * HW, nz = -dx / L * HW;
      var p = sm[j], Y = 0.035, f = j / N, al = 0.62 * (1 - f) + 0.06;
      pos.set([p[0] - nx, Y, -(p[1] - nz), p[0] + nx, Y, -(p[1] + nz)], j * 6);
      col.set([0.16, 0.55, 1.0, al, 0.16, 0.55, 1.0, al], j * 8);
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

  window.AVM_FSD = { init: init, update: update, predict: predict, applyStyle: applyStyle, dressObject: dressObject,
    undressObject: undressObject, style: function () { return style; }, savedStyle: savedStyle };
})();
