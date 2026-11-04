/*
 * road users: GLB 模型 + 程式建模備援（three.js r128，ES5）。說明見 docs/road-user-models.md。
 *
 *   AVM_RU.init(base, onManifest, skinned)  讀 base + 'manifest.json'（base 預設 'models/'）；
 *                                  skinned=false 時行人一律用程式建模（GPU 不能用骨骼貼圖時）
 *   AVM_RU.wrap(t, fallback)       建外層 Group；fallback() 回傳程式建模的 Group（models.js 介面）
 *   AVM_RU.dress(g, id)            依 track id 決定車款與車色；模型還沒載完先顯示 fallback，載完自動換上
 *   AVM_RU.preload(['car','moto']) 選用：先把這些類別的車款都載進來
 *   AVM_RU.cloneSkinned(obj)       等同 r128 examples/js/utils/SkeletonUtils.clone
 */
(function () {
  'use strict';
  var T = window.THREE;
  if (!T) return;

  // 台灣街景常見車色（sRGB hex；同一色重複出現＝權重）。只用在 manifest tint=true 的車款與程式建模備援。
  var PALETTE = {
    car: [0xf2f3f4, 0xf2f3f4, 0xf2f3f4, 0xeceae4, 0xc4c8cc, 0xc4c8cc, 0xa9adb2, 0x8a8f95, 0x55595e,
      0x1d1f22, 0x1d1f22, 0x1d1f22, 0x1f3352, 0x2a4a73, 0x8e1b22, 0xc9b48f, 0x5b4636],
    van: [0xf2f3f4, 0xf2f3f4, 0xf2f3f4, 0xc4c8cc, 0x8a8f95, 0x1d1f22, 0x1f3352],
    truck: [0xf2f3f4, 0xe8eaec, 0x2f6ea8, 0x8d939a],
    bus: [0x2f8f5b, 0x1f6fb4, 0xd8452f, 0xe0b23a, 0xf2f3f4, 0x3aa0c8],
    moto: [0xf2f3f4, 0xf2f3f4, 0x1d1f22, 0x1d1f22, 0xc4c8cc, 0x8a8f95, 0x8e1b22, 0x2a4a73, 0x9fc9d6, 0xd9c7a3],
    bigbike: [0x1d1f22, 0x1f4fb4, 0x1f7f3f, 0xd8452f],
    bike: [0x1d1f22, 0xf2f3f4, 0xc4c8cc, 0x2f5f3f, 0x8e1b22, 0xf2a51a, 0x2a4a73]
  };
  var WEIGHT = { 'car_taxi.glb': 0.6 };          // 車款相對出現比例（預設 1）
  var ENV = { paint: 0.6, glass: 0.9 };           // envMapIntensity，其餘 0.5（跟自車 E60 一致）
  var LINK = { rider: 1, helmet: 1, hair: 1 };    // 跟著 paint 一起閃紅的次要材質（騎士、安全帽、頭髮）

  var base = 'models/', man = null, tpl = {}, spare = {}, waiting = {}, all = [], skinOK = true;
  var loader = T.GLTFLoader ? new T.GLTFLoader() : null;

  function hash01(n) { n = Math.abs(Math.floor(n)) % 1048576; return ((n * 2654435761) % 4294967296) / 4294967296; }

  function pick(t, id) {   // 依 track id 固定挑一個車款（載入失敗的跳過）
    var list = man && man[t], ok = [], tot = 0, i, r;
    if (!list || (t === 'ped' && !skinOK)) return null;
    for (i = 0; i < list.length; i++) {
      if (!(tpl[list[i].file] && tpl[list[i].file].failed)) { ok.push(list[i]); tot += WEIGHT[list[i].file] || 1; }
    }
    if (!ok.length) return null;
    r = hash01(id) * tot;
    for (i = 0; i < ok.length; i++) { r -= WEIGHT[ok[i].file] || 1; if (r < 0) return ok[i]; }
    return ok[ok.length - 1];
  }
  function colourFor(t, id) {
    var p = PALETTE[t];
    return p ? p[Math.floor(hash01(id * 7 + 13) * p.length)] : null;
  }

  // ---- SkeletonUtils.clone（r128）的 ES5 版：骨架要跟著複製，不然所有行人共用同一副骨頭
  function cloneSkinned(src) {
    var dst = src.clone(true), a = [], b = [], i;
    (function walk(s, d) {
      a.push(s); b.push(d);
      for (var k = 0; k < s.children.length; k++) walk(s.children[k], d.children[k]);
    })(src, dst);
    for (i = 0; i < a.length; i++) {
      if (!a[i].isSkinnedMesh) continue;
      var sk = a[i].skeleton, bones = [];
      for (var k = 0; k < sk.bones.length; k++) bones.push(b[a.indexOf(sk.bones[k])]);
      b[i].bind(new T.Skeleton(bones, sk.boneInverses), a[i].bindMatrix);
    }
    return dst;
  }

  // paint 的 emissive（Color 物件與強度）分享給次要材質，閃 paint 就一起閃（同 models.js 的 linkTo）
  function linkTo(m, paint) {
    m.emissive = paint.emissive;
    try {
      Object.defineProperty(m, 'emissiveIntensity', {
        get: function () { return paint.emissiveIntensity; }, set: function () {}, configurable: true, enumerable: true
      });
    } catch (e) { m.emissiveIntensity = 1; }
  }

  // ---- 載入（每個檔只載一次；clone 共用幾何與貼圖）
  function request(e) {
    var f = e.file, s = tpl[f];
    if (s) return s;
    s = tpl[f] = { e: e, ready: false, failed: false, scene: null, clips: {}, skinned: false };
    loader.load(base + f, function (gltf) {
      prepare(s, gltf);
      s.ready = true;
      var list = waiting[f] || [];
      waiting[f] = [];
      for (var i = 0; i < list.length; i++) {
        if (list[i].userData.ru.want === f) swap(list[i], f);
      }
    }, undefined, function () {
      s.failed = true;            // 留著程式建模版；之後 pick() 不再挑這個檔
      waiting[f] = [];
    });
    return s;
  }
  function prepare(s, gltf) {
    var i;
    s.scene = gltf.scene;
    for (i = 0; i < gltf.animations.length; i++) s.clips[gltf.animations[i].name] = gltf.animations[i];
    gltf.scene.traverse(function (o) {
      if (o.isSkinnedMesh) s.skinned = true;
      if (!o.isMesh) return;
      o.material.envMapIntensity = ENV[o.material.name] || 0.5;
      if (o.material.transparent) o.material.depthWrite = false;
    });
    if (!s.skinned) {             // 剛體模型不會動：關掉每幀的矩陣重算（clone 會沿用）
      gltf.scene.traverse(function (o) { o.updateMatrix(); o.matrixAutoUpdate = false; });
    }
  }
  function walker(body, clips, vNat) {
    var mixer = new T.AnimationMixer(body);
    var walk = mixer.clipAction(clips.walk), idle = mixer.clipAction(clips.idle);
    var w = 0, last = -1;
    walk.play(); idle.play();
    walk.time = Math.random() * clips.walk.duration;     // 錯開步伐，一群人才不會同步
    idle.time = Math.random() * clips.idle.duration;
    walk.setEffectiveWeight(0);
    idle.setEffectiveWeight(1);
    return function (t, speed) {  // index.html 每幀呼叫 animate(秒, 對地速度 m/s)
      var dt = last < 0 ? 0 : Math.min(Math.max(t - last, 0), 0.1);
      last = t;
      w += ((speed > 0.3 ? 1 : 0) - w) * Math.min(1, dt * 6);   // 約 0.3 s 淡入淡出
      walk.setEffectiveWeight(w);
      idle.setEffectiveWeight(1 - w);
      walk.timeScale = Math.min(2, Math.max(0.5, speed / vNat));  // 步頻跟速度走，腳才不會滑
      mixer.update(dt);
    };
  }
  function build(f) {
    var s = tpl[f], e = s.e, body = s.skinned ? cloneSkinned(s.scene) : s.scene.clone(true);
    var main = e.paint ? 'paint' : 'person', paint = null, linked = [], i;
    body.traverse(function (o) {
      if (!o.isMesh) return;
      if (o.material.name === main) {
        if (!paint) {
          paint = o.material.clone();           // 每個實例一份：換色、閃紅互不影響
          paint.userData.baseEm = paint.emissive.getHex();
          paint.userData.baseEmI = paint.emissiveIntensity;
        }
        o.material = paint;
      } else if (LINK[o.material.name]) {
        o.material = o.material.clone();
        linked.push(o.material);
      }
    });
    if (!paint) {                               // 沒有主材質也給一個，index.html 的閃紅才不會碰到 null
      paint = new T.MeshStandardMaterial();
      paint.userData.baseEm = 0;
      paint.userData.baseEmI = 1;
    }
    for (i = 0; i < linked.length; i++) linkTo(linked[i], paint);
    body.userData = {
      file: f, paint: paint, dims: { w: e.width, l: e.length, h: e.height },
      labelY: e.height + (e.height > 2.5 ? 0.6 : 0.45)
    };
    if (s.clips.walk && s.clips.idle) body.userData.animate = walker(body, s.clips, e.walkSpeed || 1.1);
    return body;
  }

  // ---- 外層 Group：index.html 對它設位置、轉向、掛標籤；裡面的「身體」可以換（程式建模 ⇄ GLB、換車款）
  function wrap(t, fallback) {
    var g = new T.Group();
    g.userData.ru = { t: t, fallback: fallback, body: null, file: null, want: null, id: 0 };
    all.push(g);
    return g;
  }
  function swap(g, f) {
    var ru = g.userData.ru, old = ru.body, key, body, u;
    if (old && ru.file === f) return;
    key = f || ('~' + ru.t);
    body = (spare[key] && spare[key].pop()) || (f ? build(f) : ru.fallback());
    if (old) {
      g.remove(old);
      key = ru.file || ('~' + ru.t);
      (spare[key] = spare[key] || []).push(old);
    }
    g.add(body);
    ru.body = body;
    ru.file = f;
    u = body.userData;
    g.userData.paint = u.paint;
    g.userData.animate = u.animate || null;
    g.userData.dims = u.dims;
    if (typeof u.labelY === 'number') {
      g.userData.labelY = u.labelY;
      if (g.userData.lab) g.userData.lab.position.y = u.labelY;
    }
    tint(g);
  }
  function tint(g) {
    var ru = g.userData.ru, p = g.userData.paint, c;
    if (!p || (ru.file && !tpl[ru.file].e.tint)) return;   // tint=false：車色固定（計程車、黑 SUV、公車塗裝…）
    c = colourFor(ru.t, ru.id);
    if (c !== null) p.color.setHex(c).convertSRGBToLinear();
  }
  function dress(g, id) {
    var ru = g.userData.ru, e = loader ? pick(ru.t, id) : null, s;
    ru.id = id;
    ru.want = e ? e.file : null;
    if (!e) { swap(g, null); tint(g); return; }
    s = request(e);
    if (s.ready) { swap(g, e.file); tint(g); return; }
    if (!s.failed) (waiting[e.file] = waiting[e.file] || []).push(g);
    if (!ru.body || s.failed) swap(g, null);
    tint(g);
  }
  function preload(types) {   // 選用：manifest 到了之後先載常見類別的所有車款，第一次出現時就不會先閃程式建模版
    for (var i = 0; i < types.length; i++) {
      var list = (man && man[types[i]]) || [];
      for (var k = 0; k < list.length; k++) if (loader) request(list[k]);
    }
  }
  function init(b, onManifest, skinned) {
    if (b) base = b;
    if (skinned === false) skinOK = false;
    if (!loader) return;
    new T.FileLoader().setResponseType('json').load(base + 'manifest.json', function (j) {
      man = j;
      for (var i = 0; i < all.length; i++) {           // 已經在畫面上的程式建模物件也換成 GLB
        if (all[i].userData.ru.body) dress(all[i], all[i].userData.ru.id);
      }
      if (onManifest) onManifest(man);
    });
  }

  window.AVM_RU = {
    init: init, wrap: wrap, dress: dress, preload: preload, pick: pick, colourFor: colourFor, cloneSkinned: cloneSkinned,
    manifest: function () { return man; }, templates: tpl,
    RADIUS: { car: 0.9, van: 1.0, truck: 1.2, bus: 1.3, moto: 0.4, bigbike: 0.4, bike: 0.4, ped: 0.3, cone: 0.2 }
  };
})();
