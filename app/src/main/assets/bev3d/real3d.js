/*
 * 3D 道路畫面「實景模式」：360 引擎把四顆鏡頭的畫面貼到自車周圍的「碗」上（BowlTexture.kt），
 * 這裡照同一組參數建碗的網格、貼上那張圖：近處是地面、遠處往上彎成牆，旁邊的車、建築都是真的影像。
 * 疊上去的只有自己的 E60、藍色路徑、警示；別台車畫面裡本來就看得到，不再放 3D 模型（不然會重複）。
 * 實景模式時每 3 秒跟中控台要一次貼圖（中控台轉給 360 引擎；沒人要了引擎就不做，不吃效能）。
 * 座標：碗的參數是車身座標（x 右、y 上、z 前），three.js 世界 z 反過來（−z 前）。
 */
(function () {
  var mesh = null, tex = null, img = null, params = null, lastAt = -1e9, loading = false, pending = null, askAt = -1e9;

  function build(THREE, scene, g) {
    var W = 128, H = 48;                  // 網格解析度（貼圖 512x192，網格不用那麼密）
    var pos = new Float32Array((W + 1) * (H + 1) * 3), uv = new Float32Array((W + 1) * (H + 1) * 2), idx = [];
    for (var j = 0; j <= H; j++) for (var i = 0; i <= W; i++) {
      var th = 2 * Math.PI * i / W, rho = j / H, r, y;
      if (rho < g.rf) { r = rho / g.rf * g.flat; y = 0; }
      else { r = g.flat + (rho - g.rf) / (1 - g.rf) * (g.rmax - g.flat); y = g.k * (r - g.flat) * (r - g.flat); }
      var k = j * (W + 1) + i;
      pos[k * 3] = r * Math.sin(th); pos[k * 3 + 1] = y + 0.01; pos[k * 3 + 2] = -r * Math.cos(th);
      uv[k * 2] = i / W; uv[k * 2 + 1] = 1 - rho;          // 貼圖第 0 列 = 中心（three.js 貼圖預設上下翻）
    }
    for (j = 0; j < H; j++) for (i = 0; i < W; i++) {
      var a = j * (W + 1) + i, b = a + 1, c = a + W + 1, d = c + 1;
      idx.push(a, c, b, b, c, d);
    }
    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    geo.setIndex(idx);
    tex = new THREE.Texture();
    if (THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    tex.minFilter = THREE.LinearFilter; tex.generateMipmaps = false;
    mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: tex, side: THREE.DoubleSide, toneMapped: false, fog: false }));
    mesh.visible = false; mesh.renderOrder = -1;
    scene.add(mesh);
    params = JSON.stringify(g);
  }

  function load(url) {
    if (loading) { pending = url; return; }
    loading = true;
    var im = new Image();
    im.onload = function () {
      tex.image = im; tex.needsUpdate = true; loading = false; lastAt = performance.now();
      if (pending) { var p = pending; pending = null; load(p); }
    };
    im.onerror = function () { loading = false; };
    im.src = url;
  }

  /** 每次收到資料：[bowl] 貼圖（data URL）、[g] 碗的參數。 */
  function onFrame(THREE, scene, bowl, g) {
    if (!bowl || !g) return;
    if (!mesh || params !== JSON.stringify(g)) { if (mesh) scene.remove(mesh); build(THREE, scene, g); }
    load(bowl);
  }

  /** 實景模式而且最近 2 秒內有貼圖 → 碗面在用（呼叫端據此藏掉虛擬路面、平面實景、別台車模型）。 */
  function active(wanted, now) { return !!mesh && wanted && now - lastAt < 2000; }

  /** 每幀：要不要顯示；實景模式時每 3 秒跟中控台要一次。 */
  function update(wanted, now) {
    if (wanted && now - askAt > 3000) {
      askAt = now;
      try { if (window.parent && window.parent !== window) window.parent.postMessage({ __realView: 1 }, '*'); } catch (e) {}
    }
    var on = active(wanted, now);
    if (mesh) mesh.visible = on;
    return on;
  }

  window.AVM_REAL = { onFrame: onFrame, update: update, active: active };
})();
