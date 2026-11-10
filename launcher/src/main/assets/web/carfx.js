/* 自車特效（中控台的 3D 車展示、3D 道路畫面共用）：開門（半透明車門從鉸鍊打開）、方向燈／雙閃、煞車、大燈。
 * E60 模型座標：公尺、車頭朝 +Z、+X 是車的左邊、Y 往上。
 * 用法：var fx = CarFx.attach(carObject3D, THREE); 每幀 fx.update(state, nowMs)。
 * state：{ brake, turnL, turnR, hazard, lowBeam, highBeam, ill, doors: { fl, fr, rl, rr, trunk, hood } } */
(function () {
  'use strict';
  function glowTex(T, rgb) {
    var c = document.createElement('canvas'); c.width = c.height = 128;
    var g = c.getContext('2d'), gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    gr.addColorStop(0, 'rgba(' + rgb + ',1)'); gr.addColorStop(0.25, 'rgba(' + rgb + ',0.75)'); gr.addColorStop(1, 'rgba(' + rgb + ',0)');
    g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
    return new T.CanvasTexture(c);
  }

  function attach(car, T) {
    var amber = glowTex(T, '255,165,30'), red = glowTex(T, '255,25,25'), white = glowTex(T, '235,245,255'), dimRed = glowTex(T, '200,20,20');
    function sprite(tex, s, x, y, z) {
      var sp = new T.Sprite(new T.SpriteMaterial({ map: tex, blending: T.AdditiveBlending, depthWrite: false, depthTest: false, transparent: true }));
      sp.scale.set(s, s, 1); sp.position.set(x, y, z); sp.visible = false; sp.renderOrder = 10; car.add(sp); return sp;
    }
    var g = {
      fl: sprite(amber, 0.75, 0.72, 0.62, 2.25), fr: sprite(amber, 0.75, -0.72, 0.62, 2.25),
      ml: sprite(amber, 0.45, 0.96, 0.78, 1.55), mr: sprite(amber, 0.45, -0.96, 0.78, 1.55),     // 葉子板側燈
      rl: sprite(amber, 0.75, 0.74, 0.82, -2.3), rr: sprite(amber, 0.75, -0.74, 0.82, -2.3),
      bl: sprite(red, 1.0, 0.6, 0.78, -2.38), br: sprite(red, 1.0, -0.6, 0.78, -2.38), bc: sprite(red, 0.6, 0, 1.12, -1.95),  // 第三煞車燈
      hl: sprite(white, 0.9, 0.6, 0.62, 2.28), hr: sprite(white, 0.9, -0.6, 0.62, 2.28),          // 大燈
      tl: sprite(dimRed, 0.55, 0.62, 0.8, -2.37), tr: sprite(dimRed, 0.55, -0.62, 0.8, -2.37),     // 小燈（尾燈）
      ffl: sprite(white, 0.4, 0.62, 0.3, 2.3), ffr: sprite(white, 0.4, -0.62, 0.3, 2.3),           // 前霧燈
      rfg: sprite(red, 0.5, -0.45, 0.42, -2.4)                                                  // 後霧燈
    };

    // 大燈光束：貼地往前照的漸層扇形（遠光更長更亮）
    function beamTex() {
      var c = document.createElement('canvas'); c.width = 64; c.height = 256;
      var x = c.getContext('2d'), gr = x.createLinearGradient(0, 256, 0, 0);
      gr.addColorStop(0, 'rgba(230,242,255,0.55)'); gr.addColorStop(0.5, 'rgba(210,230,255,0.18)'); gr.addColorStop(1, 'rgba(200,220,255,0)');
      x.fillStyle = gr; x.fillRect(0, 0, 64, 256);
      return new T.CanvasTexture(c);
    }
    var beamMat = new T.MeshBasicMaterial({ map: beamTex(), transparent: true, blending: T.AdditiveBlending, depthWrite: false, side: T.DoubleSide });
    var beam = new T.Mesh(new T.PlaneGeometry(1, 1), beamMat);
    // 梯形：近端兩燈寬、遠端展開（用 3 個頂點的扇形比較像，這裡用拉長的平面＋貼圖漸層）
    var bg = new T.BufferGeometry();
    bg.setAttribute('position', new T.Float32BufferAttribute([-0.8, 0, 0, 0.8, 0, 0, 3.2, 0, 1, -3.2, 0, 1], 3));
    bg.setAttribute('uv', new T.Float32BufferAttribute([0, 0, 1, 0, 1, 1, 0, 1], 2));
    bg.setIndex([0, 1, 2, 0, 2, 3]);
    beam.geometry = bg;
    beam.position.set(0, 0.03, 2.35); beam.visible = false; beam.renderOrder = 5; car.add(beam);

    // 車門：半透明、發紅光的板子，鉸鍊在前緣，打開時往外轉；後車廂、引擎蓋往上掀
    var doorMat = new T.MeshStandardMaterial({ color: 0xff3d5a, emissive: 0xff2244, emissiveIntensity: 0.6, transparent: true, opacity: 0.55, roughness: 0.3, metalness: 0.2, side: T.DoubleSide, depthWrite: false });
    var edgeMat = new T.LineBasicMaterial({ color: 0xff8a9c, transparent: true, opacity: 0.95 });
    function panel(w, h, l) {
      var geo = new T.BoxGeometry(w, h, l), m = new T.Mesh(geo, doorMat);
      m.add(new T.LineSegments(new T.EdgesGeometry(geo), edgeMat));
      return m;
    }
    function hinge(x, y, z) { var p = new T.Object3D(); p.position.set(x, y, z); p.visible = false; car.add(p); return p; }
    var doors = {};
    // 前門 z 0.15～1.2、後門 z -0.85～0.15；鉸鍊在前緣
    [['fl', 0.93, 1.2, 1.05, 1], ['fr', -0.93, 1.2, 1.05, -1], ['rl', 0.93, 0.15, 1.0, 1], ['rr', -0.93, 0.15, 1.0, -1]].forEach(function (d) {
      var h = hinge(d[1], 0.72, d[2]), p = panel(0.05, 0.72, d[3]); p.position.set(0, 0, -d[3] / 2); h.add(p);
      doors[d[0]] = { h: h, axis: 'y', open: d[4] > 0 ? -1.05 : 1.05, a: 0 };       // 左門往 +X 轉出去、右門往 -X
    });
    (function () {   // 後車廂：鉸鍊在後擋風玻璃下緣，蓋子往後延伸，往上掀
      var h = hinge(0, 1.02, -1.75), p = panel(1.3, 0.05, 0.62); p.position.set(0, 0, -0.31); h.add(p);
      doors.trunk = { h: h, axis: 'x', open: 1.05, a: 0 };
    })();
    (function () {   // 引擎蓋：鉸鍊在前擋風玻璃下緣，往前延伸，往上掀
      var h = hinge(0, 0.98, 1.05), p = panel(1.45, 0.05, 1.15); p.position.set(0, 0, 0.575); h.add(p);
      doors.hood = { h: h, axis: 'x', open: -0.9, a: 0 };
    })();

    var mats = {};
    car.traverse(function (o) {
      if (!o.isMesh || !o.material) return;
      var n = o.material.name;
      if (n === 'headlight' || n === 'taillight') { if (!mats[n]) { o.material = o.material.clone(); mats[n] = o.material; } else o.material = mats[n]; }
    });

    return {
      update: function (s, now) {
        s = s || {};
        var blink = Math.floor(now / 400) % 2 === 0, hz = !!s.hazard;
        var L = (s.turnL || hz) && blink, R = (s.turnR || hz) && blink;
        g.fl.visible = g.ml.visible = g.rl.visible = L;
        g.fr.visible = g.mr.visible = g.rr.visible = R;
        g.bl.visible = g.br.visible = g.bc.visible = !!s.brake;
        var lit = s.lowBeam || s.highBeam || s.ill;
        g.hl.visible = g.hr.visible = !!lit;
        g.hl.scale.setScalar(s.highBeam ? 0.95 : 0.65); g.hr.scale.setScalar(s.highBeam ? 0.95 : 0.65);
        g.tl.visible = g.tr.visible = !!lit && !s.brake;
        g.ffl.visible = g.ffr.visible = !!s.fogF;
        g.rfg.visible = !!s.fogR;
        beam.visible = !!lit;
        beam.scale.set(1, 1, s.highBeam ? 22 : 11);                // 近光 11 m、遠光 22 m
        beamMat.opacity = s.highBeam ? 0.55 : 0.38;
        if (mats.headlight) { mats.headlight.emissive = new T.Color(0xeaf4ff); mats.headlight.emissiveIntensity = s.highBeam ? 2.2 : lit ? 1.2 : 0.2; }
        if (mats.taillight) { mats.taillight.emissive = new T.Color(0xff2016); mats.taillight.emissiveIntensity = s.brake ? 3.2 : lit ? 1.1 : 0.3; }
        var d = s.doors || {};
        Object.keys(doors).forEach(function (k) {
          var dr = doors[k], target = d[k] ? dr.open : 0;
          dr.a += (target - dr.a) * 0.18;                          // 平滑開關
          if (Math.abs(dr.a - target) < 0.002) dr.a = target;
          dr.h.visible = Math.abs(dr.a) > 0.01;
          dr.h.rotation.set(0, 0, 0); dr.h.rotation[dr.axis] = dr.a;
        });
        doorMat.emissiveIntensity = 0.45 + 0.35 * (0.5 + 0.5 * Math.sin(now / 250));   // 開著的門紅光呼吸
      }
    };
  }

  window.CarFx = { attach: attach };

  // 在 3D 道路畫面（bev3d iframe）裡：等它載好自車，接收中控台送來的車況
  if (window.parent !== window) {
    var fx = null, last = {};
    window.addEventListener('message', function (e) { if (e.data && e.data.__car) last = e.data.__car; });
    (function wait() {
      if (window.__egoCar && window.THREE) { fx = attach(window.__egoCar, window.THREE); loop(); }
      else setTimeout(wait, 300);
    })();
    function loop() { requestAnimationFrame(loop); if (fx) fx.update(last, performance.now()); }
  }
})();
