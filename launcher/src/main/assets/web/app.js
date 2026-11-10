/* 車機桌面：3D 車子展示＋中控台。資料來自 Android 的 window.Car（CarData：車機 CAN、車門）。
 * 道路畫面（Tesla 式）在原廠 360 裡（原生繪圖），這裡的卡片負責打開它。
 * 在電腦瀏覽器打開時沒有 window.Car，用假資料預覽。 */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };

  // ───────── 資料來源 ─────────
  var Car = window.Car || mockCar();
  function mockCar() {
    var t0 = Date.now(), prefs = {};
    return {
      state: function () {
        var t = (Date.now() - t0) / 1000, spd = Math.max(0, Math.round(60 + 50 * Math.sin(t / 6)));
        var q = location.search;                                     // 預覽用：?fx 全部特效、?car 停車
        if (q.indexOf('car') >= 0) spd = 0;
        if (q.indexOf('fx') >= 0) return JSON.stringify({ can: true, speed: spd, rpm: 900, gear: spd ? 'D' : 'P', lowBeam: true, highBeam: true, brake: true, turnL: true,
          fogF: true, wiper: 2, doors: { fl: true, fr: false, rl: false, rr: true, trunk: true, hood: false }, range: 412, water: 88, out: 24, volt: 14.1, odo: 238513,
          adas: { objs: [{ t: 'car', x: 3.6, z: 8, c: .9, id: 2, yaw: 0 }], road: { lines: [{ k: 'w', d: 1, x1: -1.8, z1: -6, x2: -1.8, z2: 7, c: .9, kv: 0 }, { k: 'w', d: 1, x1: 1.8, z1: -6, x2: 1.8, z2: 7, c: .9, kv: 0 }], el: -5.6, er: 5.6 }, veh: { turn: 'L', gear: 'D', speed: spd } } });
        return JSON.stringify({ can: true, speed: spd, rpm: 800 + spd * 28, gear: spd > 0 ? 'D' : 'P', lowBeam: true, highBeam: false,
          handbrake: false, belt: true, brake: Math.sin(t / 2) > 0.85, turnL: (t % 12) < 4, turnR: false, hazard: false,
          range: 412, water: 88, out: 23.5, volt: 14.1, odo: 238513, fuel: 18, doors: { fl: (t % 20) > 17, fr: false, rl: false, rr: false, trunk: false, hood: false },
          adas: { objs: [{ t: 'car', x: 0.2, z: 16 - (t % 8), c: .9, id: 1, yaw: 0 }, { t: 'car', x: 3.6, z: 6, c: .9, id: 2, yaw: 0 }, { t: 'truck', x: -3.5, z: 24, c: .9, id: 3, yaw: 0 },
              { t: 'moto', x: 3.2, z: -3, c: .9, id: 4, yaw: 0 }, { t: 'bus', x: -3.6, z: 45, c: .9, id: 5, yaw: Math.PI }],
            road: { lines: [{ k: 'w', d: 1, x1: -1.8, z1: -6, x2: -1.8, z2: 7, c: .9, kv: 0 }, { k: 'w', d: 1, x1: 1.8, z1: -6, x2: 1.8, z2: 7, c: .9, kv: 0 },
              { k: 'y', d: 0, x1: -5.4, z1: -6, x2: -5.4, z2: 7, c: .9, kv: 0 }, { k: 'w', d: 0, x1: 5.4, z1: -6, x2: 5.4, z2: 7, c: .9, kv: 0 }], el: -5.6, er: 5.6 },
            veh: { turn: null, gear: 'D', speed: spd } } });
      },
      apps: function () { return JSON.stringify([{ label: 'Google 地圖', pkg: 'a' }, { label: 'YouTube Music', pkg: 'b' }, { label: '設定', pkg: 'c' }]); },
      icon: function () { return ''; }, launch: function () {}, open: function () {}, media: function () {}, volume: function () {},
      engine: function () {}, roadView: function () {},
      pref: function (k) { return prefs[k] || ''; }, setPref: function (k, v) { prefs[k] = v; }
    };
  }
  function pref(k, d) { var v = ''; try { v = Car.pref(k); } catch (e) {} return v === '' || v == null ? d : v; }
  function setPref(k, v) { try { Car.setPref(k, String(v)); } catch (e) {} }

  // ───────── 時鐘 ─────────
  var WD = ['日', '一', '二', '三', '四', '五', '六'];
  function tick() {
    var d = new Date(), p = function (n) { return (n < 10 ? '0' : '') + n; };
    $('time').textContent = p(d.getHours()) + ':' + p(d.getMinutes());
    $('date').textContent = (d.getMonth() + 1) + ' 月 ' + d.getDate() + ' 日　星期' + WD[d.getDay()];
  }
  tick(); setInterval(tick, 5000);

  // ───────── 強調色、車漆 ─────────
  var ACCENTS = ['#38e1ff', '#b721ff', '#3dff9a', '#ff3d5a', '#ffb020', '#ffffff'];
  var PAINTS = [['銀灰', '#b9c2cc'], ['極地白', '#eef1f4'], ['碳黑', '#15181d'], ['寶石藍', '#1d4f9c'], ['火焰紅', '#a3141c'], ['墨綠', '#1f3d32'], ['香檳金', '#b9a27a']];
  function applyAccent(c) { document.documentElement.style.setProperty('--accent', c); }
  applyAccent(pref('accent', ACCENTS[0]));
  function dotRow(el, items, cur, onPick) {
    el.innerHTML = '';
    items.forEach(function (it) {
      var col = Array.isArray(it) ? it[1] : it, label = Array.isArray(it) ? it[0] : col;
      var b = document.createElement('button');
      b.style.background = col; b.style.setProperty('--c', col); b.setAttribute('aria-label', label);
      b.setAttribute('aria-pressed', String(col === cur));
      b.onclick = function () { [].forEach.call(el.children, function (x) { x.setAttribute('aria-pressed', 'false'); }); b.setAttribute('aria-pressed', 'true'); onPick(col); };
      el.appendChild(b);
    });
  }

  // ───────── 3D 車子 ─────────
  var T = window.THREE, scene, camera, renderer, controls, car = null, mats = {}, fx = null, ring, plates = {};
  var canvas = $('car3d');
  function init3d() {
    if (!T) return;
    renderer = new T.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true, powerPreference: 'high-performance' });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.25));
    if (renderer.outputEncoding !== undefined) renderer.outputEncoding = T.sRGBEncoding;
    renderer.toneMapping = T.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.05;
    scene = new T.Scene();
    scene.fog = new T.Fog(0x04060a, 14, 34);
    camera = new T.PerspectiveCamera(30, 1, 0.1, 100);
    camera.position.set(6.4, 2.4, 6.6);              // 車頭朝自己的斜前方（車頭朝 +Z，+X 是車的左邊）
    if (T.RoomEnvironment) {
      var pm = new T.PMREMGenerator(renderer);
      scene.environment = pm.fromScene(new T.RoomEnvironment(), 0.04).texture;
    }
    scene.add(new T.HemisphereLight(0x9fd8ff, 0x10141c, 0.5));
    var key = new T.DirectionalLight(0xffffff, 1.1); key.position.set(-4, 7, 5); scene.add(key);
    var rim = new T.DirectionalLight(0x6a5cff, 0.8); rim.position.set(5, 3, -6); scene.add(rim);

    // 地板：中間亮、往外變暗的圓盤＋霓虹圈
    var fc = document.createElement('canvas'); fc.width = fc.height = 512;
    var g = fc.getContext('2d'), gr = g.createRadialGradient(256, 256, 0, 256, 256, 256);
    gr.addColorStop(0, 'rgba(70,110,160,0.55)'); gr.addColorStop(0.45, 'rgba(20,32,52,0.5)'); gr.addColorStop(1, 'rgba(4,6,10,0)');
    g.fillStyle = gr; g.fillRect(0, 0, 512, 512);
    var floor = new T.Mesh(new T.CircleGeometry(9, 64), new T.MeshBasicMaterial({ map: new T.CanvasTexture(fc), transparent: true, depthWrite: false }));
    floor.rotation.x = -Math.PI / 2; scene.add(floor);
    ring = new T.Mesh(new T.RingGeometry(3.2, 3.32, 96), new T.MeshBasicMaterial({ color: 0x38e1ff, transparent: true, opacity: 0.6, blending: T.AdditiveBlending, side: T.DoubleSide }));
    ring.rotation.x = -Math.PI / 2; ring.position.y = 0.01; scene.add(ring);
    var ring2 = ring.clone(); ring2.material = ring.material.clone(); ring2.scale.setScalar(1.25); ring2.material.opacity = 0.25; scene.add(ring2);
    ring.userData.r2 = ring2;

    controls = new T.OrbitControls(camera, canvas);
    controls.target.set(0, 0.65, 0);
    controls.enableDamping = true; controls.dampingFactor = 0.08; controls.enablePan = false;
    controls.minDistance = 6; controls.maxDistance = 13; controls.minPolarAngle = 0.9; controls.maxPolarAngle = 1.48;
    controls.autoRotate = true; controls.autoRotateSpeed = 0.55;
    canvas.addEventListener('pointerdown', function () { controls.autoRotate = false; clearTimeout(init3d.t); });
    canvas.addEventListener('pointerup', function () { init3d.t = setTimeout(function () { controls.autoRotate = true; }, 8000); });

    new T.GLTFLoader().load('bev3d/models/e60.glb', function (gltf) {
      car = gltf.scene; scene.add(car);
      car.traverse(function (o) {
        if (!o.isMesh) return;
        var m = o.material; if (!m) return;
        // 原本的空白車牌（後面）跟字：藏起來，換成自己的車牌
        if (['0130_Gainsboro', 'Metal_Steel_Textured_White', 'Navy', '0056_Yellow', '0046_Gold'].indexOf(m.name) >= 0) { o.visible = false; return; }   // 含歐盟藍條
        if (m.name === 'paint' || m.name === 'headlight' || m.name === 'taillight') { o.material = m.clone(); mats[m.name] = o.material; }
      });
      setPaint(pref('paint', PAINTS[0][1]));
      fx = window.CarFx ? CarFx.attach(car, T) : null;      // 開門、車燈、方向燈、煞車（跟道路畫面共用）
      buildPlates();
    });
    window.addEventListener('resize', resize); resize();
    loop();
  }
  function resize() {
    if (!renderer) return;
    var w = window.innerWidth, h = window.innerHeight;
    renderer.setSize(w, h, false); camera.aspect = w / h;
    // 左右兩欄佔掉的寬度：相機往右邊偏一點，讓車子在中間的空位
    camera.setViewOffset(w, h, 0, -h * 0.04, w, h);
    camera.updateProjectionMatrix();
  }
  function setPaint(hex) {
    var m = mats.paint; if (!m) return;
    m.color = new T.Color(hex); if (m.color.convertSRGBToLinear) m.color.convertSRGBToLinear();
    m.metalness = 0.6; m.roughness = 0.28; if ('clearcoat' in m) m.clearcoat = 1;
  }

  // ───────── 車牌 ─────────
  function plateTexture(text) {
    var c = document.createElement('canvas'); c.width = 1024; c.height = 216;
    var g = c.getContext('2d');
    g.fillStyle = '#f7f8f4'; g.fillRect(0, 0, 1024, 216);
    g.strokeStyle = '#111'; g.lineWidth = 14; g.strokeRect(10, 10, 1004, 196);
    g.fillStyle = '#111'; g.textAlign = 'center'; g.textBaseline = 'middle';
    var t = (text || 'ABC-1234').toUpperCase();
    g.font = 'bold 168px "DIN Alternate","Arial Narrow",Arial,sans-serif';
    var w = g.measureText(t).width; if (w > 940) g.font = 'bold ' + Math.floor(168 * 940 / w) + 'px "Arial Narrow",Arial,sans-serif';
    g.fillText(t, 512, 116);
    var tex = new T.CanvasTexture(c);
    if (tex.encoding !== undefined) tex.encoding = T.sRGBEncoding;
    tex.anisotropy = 4;
    return tex;
  }
  function plateMesh(tex) {
    return new T.Mesh(new T.PlaneGeometry(0.52, 0.11), new T.MeshStandardMaterial({ map: tex, roughness: 0.4, metalness: 0.1 }));
  }
  function buildPlates() {
    if (!car) return;
    ['front', 'rear'].forEach(function (k) { if (plates[k]) { car.remove(plates[k]); plates[k] = null; } });
    var tex = plateTexture(pref('plate', 'ABC-1234'));
    // 後車牌：原模型空白車牌的位置（z = -2.33，高 0.72 m），面朝後
    var rear = plateMesh(tex); rear.position.set(-0.03, 0.72, -2.336); rear.rotation.y = Math.PI; car.add(rear); plates.rear = rear;
    // 前車牌：從車頭正前方打一條水平射線找保險桿
    if (pref('plateFront', '1') === '1') {
      var rc = new T.Raycaster(new T.Vector3(0, 0.38, 6), new T.Vector3(0, 0, -1), 0, 6);
      car.updateMatrixWorld(true);
      var hit = rc.intersectObject(car, true).filter(function (h) { return h.object.visible; })[0];
      var z = hit ? hit.point.z + 0.012 : 2.43;
      var front = plateMesh(tex); front.position.set(0, 0.38, z); car.add(front); plates.front = front;
    }
  }

  // ───────── 每幀 ─────────
  var st = {}, lastFrame = 0, roadOn = false;
  function loop(now) {
    requestAnimationFrame(loop);
    now = now || 0;
    if (now - lastFrame < 30) return;               // 約 30 fps，省電
    lastFrame = now;
    if (!renderer) return;
    controls.update();
    var pulse = 0.5 + 0.5 * Math.sin(now / 700);
    if (ring) { ring.material.opacity = 0.35 + 0.35 * pulse; ring.userData.r2.material.opacity = 0.12 + 0.15 * (1 - pulse); }
    if (fx) fx.update(st, now);
    if (!roadOn) renderer.render(scene, camera);            // 道路畫面蓋著時不畫 3D 車，省 GPU
  }
  // ───────── 中控台數值 ─────────
  var ARC_S = 452, ARC_R = 377;      // 環形儀表弧長（3/4 圈）
  function chip(text, cls) { return '<span class="chip ' + (cls || '') + '">' + text + '</span>'; }
  function poll() {
    try { st = JSON.parse(Car.state()); } catch (e) { st = {}; }
    var spd = Math.round(st.speed || 0), rpm = st.rpm;
    $('speed').textContent = spd;
    $('arcSpd').style.strokeDasharray = (Math.min(spd, 240) / 240 * ARC_S) + ' 603';
    $('arcRpm').style.strokeDasharray = (rpm ? Math.min(rpm, 7000) / 7000 * ARC_R : 0) + ' 503';
    $('rpm').textContent = rpm ? rpm + ' rpm' : '— rpm';
    var g = st.gear || '–'; $('gear').textContent = g; $('gear').className = 'gear ' + g;
    var blink = Math.floor(Date.now() / 400) % 2 === 0, hz = !!st.hazard;
    $('tL').classList.toggle('on', (st.turnL || hz) && blink);
    $('tR').classList.toggle('on', (st.turnR || hz) && blink);
    var c = '';
    if (st.highBeam) c += chip('遠光', 'on'); else if (st.lowBeam) c += chip('近光', 'on');
    if (st.fogF || st.fogR) c += chip('霧燈', 'on');
    if (st.handbrake === true) c += chip('手煞車', spd > 3 ? 'bad' : 'warn');
    if (st.belt === false) c += chip('安全帶', spd > 5 ? 'bad' : 'warn');
    var d = st.doors || {}, names = { fl: '左前門', fr: '右前門', rl: '左後門', rr: '右後門', trunk: '後車廂', hood: '引擎蓋' };
    Object.keys(names).forEach(function (k) { if (d[k]) c += chip(names[k] + '開', spd > 3 ? 'bad' : 'warn'); });
    if (st.brake) c += chip('煞車', 'on');
    if (st.adas && st.adas.lot) c += chip('停車場', 'on');
    if (!st.can) c += chip('沒有車機 CAN 資料', 'warn');
    $('chips').innerHTML = c;
    $('range').textContent = st.range != null ? st.range : '—';
    $('fuelBar').style.width = st.range != null ? Math.min(100, st.range / 6) + '%' : '0';
    $('water').textContent = st.water != null ? st.water : '—';
    $('out').textContent = st.out != null ? Number(st.out).toFixed(0) : '—';
    $('volt').textContent = st.volt != null ? Number(st.volt).toFixed(1) : '—';
    $('odo').textContent = st.odo != null ? Number(st.odo).toLocaleString() : '—';
    // 警示（停車也要看的）
    var al = [];
    if (st.handbrake === true && spd > 5) al.push(['手煞車還拉著', 2]);
    if (st.belt === false && spd > 10) al.push(['請繫安全帶', 1]);
    if (spd > 3 && Object.keys(names).some(function (k) { return d[k]; })) al.push(['車門沒關好', 2]);
    if (st.range != null && st.range < 50) al.push(['續航只剩 ' + st.range + ' km', 1]);
    // 下雨動畫：自動跟雨刷段數（CAN 有給才有），或設定成一直開／關
    var rm = pref('rainMode', 'auto');
    if (window.Rain) Rain.set(rm === 'off' ? 0 : rm === 'on' ? 2 : (st.wiper > 0 ? Math.min(3, st.wiper) : 0));
    $('alerts').innerHTML = al.map(function (a) { return '<div class="alert l' + a[1] + '">' + a[0] + '</div>'; }).join('');
  }
  setInterval(poll, 150); poll();

  // ───────── 中間畫面：3D 車 ／ 3D 道路 ─────────
  // auto：開車（≥ 5 km/h）切成道路；快捷列「道路畫面」手動切換，開始／停止行車時恢復自動
  var override = null, wasDriving = false, road = $('road');
  function updateView() {
    var mode = pref('viewMode', 'auto'), spd = st.speed || 0, driving = spd >= 5;
    if (driving !== wasDriving) { override = null; wasDriving = driving; }
    var want = override || (mode === 'road' ? 'road' : mode === 'car' ? 'car' : driving ? 'road' : 'car');
    roadOn = want === 'road';
    road.classList.toggle('on', roadOn);
    $('roadFx').classList.toggle('on', roadOn);
    $('views').classList.toggle('on', roadOn);
    if (roadOn && !road._viewSent && road.contentWindow) { road._viewSent = true; sendView(pref('roadView', 'front')); }
    $('viewBtn').setAttribute('aria-pressed', String(roadOn));
    $('roadWait').hidden = !(roadOn && !st.adas);
    if (roadOn && road.contentWindow) {
      try {
        if (st.adas) road.contentWindow.postMessage(st.adas, '*');
        road.contentWindow.postMessage({ __car: { brake: st.brake, turnL: st.turnL, turnR: st.turnR, hazard: st.hazard, lowBeam: st.lowBeam,
          highBeam: st.highBeam, ill: st.ill, fogF: st.fogF, fogR: st.fogR, doors: st.doors } }, '*');
      } catch (e) {}
    }
  }
  setInterval(updateView, 150);
  // 視角：前方斜視（預設）／追車／俯視／環繞，記住上次選的
  function sendView(v) {
    try { road.contentWindow.postMessage({ __view: v }, '*'); } catch (e) {}
    document.querySelectorAll('#views button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.view === v)); });
  }
  document.querySelectorAll('#views button').forEach(function (b) {
    b.onclick = function () { setPref('roadView', b.dataset.view); sendView(b.dataset.view); };
  });
  road.addEventListener('load', function () { road._viewSent = false; });

  // ───────── 按鈕 ─────────
  document.querySelectorAll('[data-open]').forEach(function (b) { b.onclick = function () { try { Car.open(b.dataset.open); } catch (e) {} }; });
  document.querySelectorAll('[data-media]').forEach(function (b) { b.onclick = function () { try { Car.media(b.dataset.media); } catch (e) {} }; });
  document.querySelectorAll('[data-vol]').forEach(function (b) { b.onclick = function () { try { Car.volume(+b.dataset.vol); } catch (e) {} }; });
  function openRoad() { try { Car.roadView(); } catch (e) {} }
  $('viewBtn').onclick = function () { override = roadOn ? 'car' : 'road'; updateView(); };
  $('roadCard').onclick = openRoad;
  // ───────── 車控（經 360 引擎；引擎會先驗中控台的簽章）─────────
  function control(cmd, arg) { try { Car.control(cmd, String(arg)); } catch (e) {} }
  var AMB = [['紅', '#ff2a2a'], ['橘', '#ff7a1a'], ['黃', '#ffd21a'], ['綠', '#2aff6a'], ['青', '#2ae0ff'], ['藍', '#2a5cff'], ['紫', '#b02aff'], ['白', '#ffffff']];
  function pickAmb(col) { setPref('ambColor', col); setPref('ambMode', ''); control('ambient_color', col.slice(1)); markModes(); }
  // 動態模式（炫彩循環、漸變、呼吸、律動、流水、爆閃、單色）
  function setMode(code) { setPref('ambMode', code === '0800' ? '' : code); control('ambient_mode', code); markModes(); }
  function markModes() {
    var m = pref('ambMode', '');
    document.querySelectorAll('[data-mode]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.mode === (m || '0800'))); });
  }
  document.querySelectorAll('[data-mode]').forEach(function (b) { b.onclick = function () { setMode(b.dataset.mode); }; });
  function ambCard() {
    dotRow($('ambDots'), AMB, pref('ambMode', '') ? '' : pref('ambColor', ''), pickAmb);
    var r = document.createElement('button'); r.className = 'rainbow'; r.setAttribute('aria-label', '炫彩循環');
    r.setAttribute('aria-pressed', String(pref('ambMode', '') === '0804'));
    r.onclick = function () { setMode('0804'); ambCard(); };
    $('ambDots').appendChild(r);
  }
  ambCard(); markModes();
  var askCb = null;
  function ask(text, cb) { $('askText').textContent = text; askCb = cb; show('ask', true); }
  $('askYes').onclick = function () { show('ask', false); if (askCb) askCb(); askCb = null; };
  $('askNo').onclick = function () { show('ask', false); askCb = null; };
  document.querySelectorAll('[data-remote]').forEach(function (b) {
    b.onclick = function () { ask(b.dataset.ask, function () { control('remote', b.dataset.remote); }); };
  });
  document.querySelectorAll('[data-ctl]').forEach(function (b) { b.onclick = function () { control(b.dataset.ctl, b.dataset.arg); }; });
  var bt = null;
  $('ambBright').addEventListener('input', function () { clearTimeout(bt); var v = $('ambBright').value; bt = setTimeout(function () { setPref('ambBright', v); control('ambient_bright', v); }, 250); });
  (function () {
    var box = $('rearSlots');
    for (var n = 1; n <= 12; n++) (function (n) {
      var b = document.createElement('button'); b.textContent = n; b.setAttribute('aria-label', '播第 ' + n + ' 則');
      b.onclick = function () { control('rear_play', n); }; box.appendChild(b);
    })(n);
  })();
  $('ctlBtn').onclick = function () {
    dotRow($('ctlAmbDots'), AMB, pref('ambMode', '') ? '' : pref('ambColor', ''), function (c) { pickAmb(c); ambCard(); });
    markModes();
    $('ambBright').value = pref('ambBright', '80');
    show('control', true);
  };
  function accStatus() {
    var a = st.acc;
    $('stRemote').textContent = !a ? '（等 360 回報）' : a.remoteReady ? a.remote : '還沒在 360 設定遠端模組（金鑰、選模組）';
    $('stAmb').textContent = !a ? '' : a.ambientOn ? a.ambient : '還沒在 360 啟用氛圍燈';
    $('stRear').textContent = !a ? '' : a.rearOn ? a.rear : '還沒在 360 啟用看板';
  }
  setInterval(function () { if (!$('control').hidden) accStatus(); }, 500);

  // 抽屜、設定
  function show(id, on) { $(id).hidden = !on; }
  document.querySelectorAll('[data-close]').forEach(function (b) { b.onclick = function () { b.closest('.sheet').hidden = true; }; });
  document.querySelectorAll('.sheet').forEach(function (s) { s.addEventListener('click', function (e) { if (e.target === s) s.hidden = true; }); });
  window.onBack = function () { document.querySelectorAll('.sheet').forEach(function (s) { s.hidden = true; }); };
  // 常用 App：抽屜裡長按釘到快捷列（最多 4 個），再長按取消
  function favs() { try { return JSON.parse(pref('favs', '[]')); } catch (e) { return []; } }
  function renderFavs() {
    var box = $('favs'); box.innerHTML = '';
    favs().forEach(function (a) {
      var b = document.createElement('button'); b.className = 'fav';
      b.innerHTML = '<b><img alt=""></b><span></span>'; b.querySelector('span').textContent = a.label;
      b.onclick = function () { try { Car.launch(a.pkg); } catch (e) {} };
      try { var u = Car.icon(a.pkg); if (u) b.querySelector('img').src = u; } catch (e) {}
      box.appendChild(b);
    });
  }
  function toggleFav(a, btn) {
    var f = favs(), i = f.findIndex(function (x) { return x.pkg === a.pkg; });
    if (i >= 0) f.splice(i, 1); else { if (f.length >= 4) f.shift(); f.push({ pkg: a.pkg, label: a.label }); }
    setPref('favs', JSON.stringify(f)); renderFavs();
    document.querySelectorAll('#appGrid button').forEach(function (x) { x.classList.toggle('pinned', f.some(function (y) { return y.pkg === x.dataset.pkg; })); });
  }
  renderFavs();
  var appsLoaded = false;
  $('appsBtn').onclick = function () {
    show('drawer', true);
    if (appsLoaded) return; appsLoaded = true;
    var list = []; try { list = JSON.parse(Car.apps()); } catch (e) {}
    var grid = $('appGrid'); grid.innerHTML = '';
    list.forEach(function (a, i) {
      var b = document.createElement('button');
      b.innerHTML = '<img alt=""><span></span>'; b.querySelector('span').textContent = a.label;
      b.dataset.pkg = a.pkg;
      if (favs().some(function (y) { return y.pkg === a.pkg; })) b.classList.add('pinned');
      var held = null, longPressed = false;
      b.addEventListener('pointerdown', function () { longPressed = false; held = setTimeout(function () { longPressed = true; toggleFav(a, b); }, 600); });
      ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) { b.addEventListener(ev, function () { clearTimeout(held); }); });
      b.addEventListener('contextmenu', function (e) { e.preventDefault(); });
      b.onclick = function () { if (longPressed) return; try { Car.launch(a.pkg); } catch (e) {} show('drawer', false); };
      grid.appendChild(b);
      setTimeout(function () { try { var u = Car.icon(a.pkg); if (u) b.querySelector('img').src = u; } catch (e) {} }, 20 * i);
    });
  };
  $('setBtn').onclick = function () {
    $('plate').value = pref('plate', 'ABC-1234');
    $('plateFront').checked = pref('plateFront', '1') === '1';
    dotRow($('paints'), PAINTS, pref('paint', PAINTS[0][1]), function (c) { setPref('paint', c); setPaint(c); });
    dotRow($('accents'), ACCENTS, pref('accent', ACCENTS[0]), function (c) { setPref('accent', c); applyAccent(c); });
    $('viewMode').value = pref('viewMode', 'auto');
    $('rainMode').value = pref('rainMode', 'auto');
    $('rainMode').onchange = function () { setPref('rainMode', $('rainMode').value); };
    $('viewMode').onchange = function () { setPref('viewMode', $('viewMode').value); override = null; };
    show('settings', true);
  };
  $('plate').addEventListener('input', function () {
    var v = $('plate').value.toUpperCase().replace(/[^A-Z0-9\-]/g, '').slice(0, 10);
    $('plate').value = v; setPref('plate', v || 'ABC-1234'); buildPlates();
  });
  $('plateFront').addEventListener('change', function () { setPref('plateFront', $('plateFront').checked ? '1' : '0'); buildPlates(); });

  init3d();
})();
