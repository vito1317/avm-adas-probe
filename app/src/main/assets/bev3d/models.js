/*
 * models.js — procedural models for the bev3d surround view (three.js r128, ES5 only).
 *
 * window.AVM_MODELS.makeE60 / makeSedan / makeVan / makeTruck / makeBus / makeScooter /
 * makeMotorcycle / makeBicycle / makePerson / makeCone / makeTrafficLight, plus prewarm() and triangles().
 *
 * Contract (every factory): returns a THREE.Group whose origin is the ground centre of the footprint,
 * y up, FRONT = +Z, metres. userData.paint = per-instance MeshStandardMaterial (index.html flashes its
 * emissive; paint.userData.baseEm / baseEmI hold the resting values), userData.labelY, userData.dims {w,l,h},
 * optional userData.animate(t, speed), userData.setAlert(level 0..1) (optional helper: red emissive + tint,
 * stronger on light paints). Large secondary surfaces (truck box, riders' clothes) share the paint's emissive
 * Color object and intensity, so flashing the paint flashes them too.
 *
 * Draw calls: everything that is not paint is merged per material class (trim / metal / glass / lamp) with
 * vertex colours, so a car is ~5 draw calls and a pedestrian 1. Geometry and class materials are cached at
 * module level and shared; the paint (and, for people, a small colour attribute) is per instance.
 * Lamps are unlit (MeshBasic, toneMapped:false) so ACES keeps reds red. No textures except one small
 * CanvasTexture atlas (number plates, bus destination sign). No shadows, no transparency.
 */
(function () {
  'use strict';
  var T = window.THREE;
  if (!T) { return; }
  var PI = Math.PI, HALF = Math.PI / 2;

  // ---------------------------------------------------------------- small utils
  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function lerp(a, b, k) { return a + (b - a) * k; }
  function smooth(a, b, v) { var k = clamp((v - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); }
  // deterministic PRNG (LCG, exact in doubles) so a seed always gives the same pedestrian
  function rng(seed) {
    var s = (Math.abs(Math.floor(seed)) % 4294967296) || 1;
    return function () {
      s = (s * 1664525 + 1013904223) % 4294967296;
      return s / 4294967296;
    };
  }
  function pick(r, arr) { return arr[Math.floor(r() * arr.length) % arr.length]; }

  // ---------------------------------------------------------------- materials
  var MAT = {};
  // hex colours in this file are sRGB design values; the renderer works in linear space (sRGB output)
  function lin(m) {
    if (m.color) { m.color.convertSRGBToLinear(); }
    if (m.emissive) { m.emissive.convertSRGBToLinear(); }
    return m;
  }
  function linCol(hex) { return new T.Color(hex).convertSRGBToLinear(); }
  // shared, cached material (never the paint)
  function mat(key, p, basic) {
    var m = MAT[key];
    if (m) { return m; }
    m = basic ? new T.MeshBasicMaterial(p) : new T.MeshStandardMaterial(p);
    if (!basic && !('envMapIntensity' in p)) { m.envMapIntensity = 0.5; }
    lin(m);
    MAT[key] = m;
    return m;
  }
  // per-instance body material that index.html flashes. o: { skinned, vc (vertex colours), env }
  function paintMat(color, metal, rough, o) {
    o = o || {};
    var m = new T.MeshStandardMaterial({ color: color, metalness: metal, roughness: rough });
    lin(m);
    m.envMapIntensity = o.env !== undefined ? o.env : 0.4;
    if (o.skinned) { m.skinning = true; }
    if (o.vc) { m.vertexColors = true; }
    m.emissive.setHex(0x000000);
    m.emissiveIntensity = 1;
    m.userData.baseEm = 0x000000;
    m.userData.baseEmI = 1;
    return m;
  }
  // make a per-instance secondary material follow the paint's flash: same emissive Color object,
  // emissiveIntensity read through from the paint (index.html only touches the paint)
  function linkTo(m, paint) {
    m.emissive = paint.emissive;
    try {
      Object.defineProperty(m, 'emissiveIntensity', {
        get: function () { return paint.emissiveIntensity; }, set: function () {}, configurable: true, enumerable: true
      });
    } catch (e) { m.emissiveIntensity = 1; }
    return m;
  }
  // optional alert helper: level 0 = rest, 1 = critical. Light colours also get tinted so the flash reads.
  var ALERT = new T.Color(0xff2a1a).convertSRGBToLinear();
  function alertFn(paint) {
    var base = paint.color.clone(), lum = 0.2126 * base.r + 0.7152 * base.g + 0.0722 * base.b;
    var tint = clamp((lum - 0.12) * 0.9, 0, 0.55);
    return function (level) {
      level = clamp(level || 0, 0, 1);
      if (level <= 0) {
        paint.emissive.setHex(paint.userData.baseEm);
        paint.emissiveIntensity = paint.userData.baseEmI;
        paint.color.copy(base);
        return;
      }
      paint.emissive.copy(ALERT);
      paint.emissiveIntensity = 0.35 + 0.65 * level;
      paint.color.copy(base).lerp(ALERT, tint * (0.5 + 0.5 * level));
    };
  }

  // ---------------------------------------------------------------- texture atlas (plates, bus sign)
  // 512 x 256 canvas, background white: any vertex without a uv samples (0,0) = white, so the atlas can sit
  // on the shared trim / lamp materials without affecting untextured parts.
  var ATLAS = null, AW = 512, AH = 256;
  var REG = { plate: [16, 4, 256, 104], plateG: [264, 4, 504, 104], plateR: [16, 108, 256, 208], sign: [256, 112, 512, 160] };
  function regUV(name) { var r = REG[name]; return [r[0] / AW, 1 - r[3] / AH, r[2] / AW, 1 - r[1] / AH]; }
  function atlasTex() {
    if (ATLAS) { return ATLAS; }
    var c = document.createElement('canvas'); c.width = AW; c.height = AH;
    var x = c.getContext('2d');
    if (x && x.fillRect) {
      x.fillStyle = '#ffffff'; x.fillRect(0, 0, AW, AH);
      var plate = function (r, ink, text) {
        var w = r[2] - r[0], h = r[3] - r[1];
        x.fillStyle = '#f4f5f2'; x.fillRect(r[0], r[1], w, h);
        x.strokeStyle = ink; x.lineWidth = 4; x.strokeRect(r[0] + 4, r[1] + 4, w - 8, h - 8);
        x.fillStyle = ink;
        x.font = 'bold 66px "Arial Narrow","Roboto Condensed",Arial,sans-serif';
        x.textBaseline = 'middle';
        var mt = x.measureText ? x.measureText(text) : null, tw = mt && mt.width ? mt.width : w * 0.8;
        if (x.save) { x.save(); }
        x.translate(r[0] + w / 2, r[1] + h / 2 + 3);
        x.scale(Math.min(1, (w - 30) / tw), 1);
        x.fillText(text, -tw / 2, 0);
        if (x.restore) { x.restore(); }
      };
      plate(REG.plate, '#111111', 'BMW-5250');
      plate(REG.plateG, '#0f6b34', 'KLB-3021');
      plate(REG.plateR, '#b4161b', 'KKA-6688');
      // bus destination sign: amber LED text on near-black, dot mask
      var s = REG.sign, sw = s[2] - s[0], sh = s[3] - s[1];
      x.fillStyle = '#120a02'; x.fillRect(s[0], s[1], sw, sh);
      x.fillStyle = '#ffb02e'; x.textBaseline = 'middle';
      x.font = 'bold 34px "Noto Sans TC","PingFang TC","Microsoft JhengHei",sans-serif';
      x.fillText('307', s[0] + 10, s[1] + 25);
      x.font = 'bold 26px "Noto Sans TC","PingFang TC","Microsoft JhengHei",sans-serif';
      x.fillText('臺北車站', s[0] + 92, s[1] + 25);
      x.fillStyle = 'rgba(18,10,2,0.55)';
      var i;
      for (i = 0; i < sw; i += 3) { x.fillRect(s[0] + i, s[1], 1, sh); }
      for (i = 0; i < sh; i += 3) { x.fillRect(s[0], s[1] + i, sw, 1); }
    }
    ATLAS = new T.CanvasTexture(c);
    ATLAS.encoding = T.sRGBEncoding;
    ATLAS.anisotropy = 1;
    return ATLAS;
  }

  // ---------------------------------------------------------------- shared material classes (vertex coloured)
  var CLS = {
    trim: function () { return mat('c:trim', { color: 0xffffff, vertexColors: true, metalness: 0.1, roughness: 0.66, map: atlasTex(), envMapIntensity: 0.4 }); },
    metal: function () { return mat('c:metal', { color: 0xffffff, vertexColors: true, metalness: 0.85, roughness: 0.3, envMapIntensity: 0.9 }); },
    glass: function () { return mat('c:glass', { color: 0xffffff, vertexColors: true, metalness: 0.2, roughness: 0.32, envMapIntensity: 0.3 }); },
    lamp: function () { return mat('c:lamp', { color: 0xffffff, vertexColors: true, toneMapped: false, map: atlasTex() }, true); }
  };
  // part key -> [class, sRGB colour]
  var PART = {
    black: ['trim', 0x0c0f13], plastic: ['trim', 0x1c2128], tyre: ['trim', 0x17191b], plate: ['trim', 0xffffff, 1],
    redPlate: ['trim', 0xc8242a], signalYellow: ['trim', 0xf2c200], white: ['trim', 0xe6e9ec], rubber: ['trim', 0x222529],
    seat: ['trim', 0x16181b], cargoDark: ['trim', 0x3a4048],
    chrome: ['metal', 0xdfe4ea], rim: ['metal', 0xbcc3cb], steel: ['metal', 0x8a939d], darkMetal: ['metal', 0x3c4249], gold: ['metal', 0xc9a23e],
    glass: ['glass', 0x05090d], headLens: ['glass', 0x4a5561], mirrorGlass: ['glass', 0x2b3640],
    headLamp: ['lamp', 0xfff3dc], angel: ['lamp', 0xdcebff], tailLens: ['lamp', 0x560808], tailBar: ['lamp', 0xff1a0f],
    amber: ['lamp', 0xff8c12], amberDim: ['lamp', 0xa05a12], reflector: ['lamp', 0x8a1508], reflectWhite: ['lamp', 0xd2d5d8], sign: ['lamp', 0xffffff, 1],
    lampOff: ['lamp', 0x1a1d21]
  };
  var CLS_ORDER = ['trim', 'metal', 'glass', 'lamp'];

  // ---------------------------------------------------------------- geometry helpers
  var GEO = {};
  function cached(key, build) { return GEO[key] || (GEO[key] = build()); }

  var _m4 = new T.Matrix4(), _q = new T.Quaternion(), _eu = new T.Euler(), _v3 = new T.Vector3(), _s3 = new T.Vector3();
  function flat(g) { return g.index ? g.toNonIndexed() : g; }
  // transformed copy (positive scales only; use mirrorX for reflections)
  function xf(geo, x, y, z, rx, ry, rz, sx, sy, sz) {
    var g = geo.clone();
    _eu.set(rx || 0, ry || 0, rz || 0);
    _q.setFromEuler(_eu);
    _v3.set(x || 0, y || 0, z || 0);
    _s3.set(sx === undefined ? 1 : sx, sy === undefined ? 1 : sy, sz === undefined ? 1 : sz);
    _m4.compose(_v3, _q, _s3);
    g.applyMatrix4(_m4);
    return g;
  }
  // reflect across x = 0 and repair the triangle winding
  function mirrorX(geo) {
    var g = flat(geo.clone()), names = Object.keys(g.attributes), i, k, c, a, arr, sz, tmp;
    var p = g.attributes.position, n = g.attributes.normal;
    for (i = 0; i < p.count; i++) {
      p.setX(i, -p.getX(i));
      if (n) { n.setX(i, -n.getX(i)); }
    }
    for (a = 0; a < names.length; a++) {
      arr = g.attributes[names[a]].array; sz = g.attributes[names[a]].itemSize;
      for (i = 0; i < p.count; i += 3) {
        for (c = 0; c < sz; c++) {
          k = (i + 1) * sz + c; tmp = arr[k];
          arr[k] = arr[(i + 2) * sz + c]; arr[(i + 2) * sz + c] = tmp;
        }
      }
    }
    return g;
  }
  function flatten(list, out) {
    for (var i = 0; i < list.length; i++) {
      if (!list[i]) { continue; }
      if (list[i].length !== undefined) { flatten(list[i], out); } else { out.push(list[i]); }
    }
    return out;
  }
  // concatenate geometries into one non-indexed BufferGeometry (position + normal [+ uv])
  function merge(list, keepUv) {
    var src = flatten(list, []), gs = [], total = 0, i, g;
    for (i = 0; i < src.length; i++) {
      g = flat(src[i]);
      if (!g.attributes.normal) { g.computeVertexNormals(); }
      gs.push(g); total += g.attributes.position.count;
    }
    var pos = new Float32Array(total * 3), nor = new Float32Array(total * 3), uv = keepUv ? new Float32Array(total * 2) : null, o = 0;
    for (i = 0; i < gs.length; i++) {
      g = gs[i];
      pos.set(g.attributes.position.array.subarray(0, g.attributes.position.count * 3), o * 3);
      nor.set(g.attributes.normal.array.subarray(0, g.attributes.position.count * 3), o * 3);
      if (uv && g.attributes.uv) { uv.set(g.attributes.uv.array.subarray(0, g.attributes.position.count * 2), o * 2); }
      o += g.attributes.position.count;
    }
    var out = new T.BufferGeometry();
    out.setAttribute('position', new T.BufferAttribute(pos, 3));
    out.setAttribute('normal', new T.BufferAttribute(nor, 3));
    if (uv) { out.setAttribute('uv', new T.BufferAttribute(uv, 2)); }
    out.computeBoundingSphere();
    out.computeBoundingBox();
    return out;
  }
  // merge [{list:[geos], col:Color, uv:bool}] into one geometry with a constant colour per item; uvs are kept only
  // for atlas-mapped items (plates, signs) -- every other vertex gets uv (0,0) = the atlas' white corner
  function mergeC(items, withUv) {
    var gs = [], cs = [], us = [], total = 0, i, j, g;
    for (i = 0; i < items.length; i++) {
      var l = flatten(items[i].list, []);
      for (j = 0; j < l.length; j++) {
        g = flat(l[j]);
        if (!g.attributes.normal) { g.computeVertexNormals(); }
        gs.push(g); cs.push(items[i].col); us.push(!!items[i].uv); total += g.attributes.position.count;
      }
    }
    var pos = new Float32Array(total * 3), nor = new Float32Array(total * 3), col = new Float32Array(total * 3);
    var uv = withUv ? new Float32Array(total * 2) : null, o = 0, k;
    for (i = 0; i < gs.length; i++) {
      g = gs[i];
      var n = g.attributes.position.count, c = cs[i];
      pos.set(g.attributes.position.array.subarray(0, n * 3), o * 3);
      nor.set(g.attributes.normal.array.subarray(0, n * 3), o * 3);
      if (g.attributes.color) { col.set(g.attributes.color.array.subarray(0, n * 3), o * 3); }
      else { for (k = 0; k < n; k++) { col[(o + k) * 3] = c.r; col[(o + k) * 3 + 1] = c.g; col[(o + k) * 3 + 2] = c.b; } }
      if (uv && us[i] && g.attributes.uv) { uv.set(g.attributes.uv.array.subarray(0, n * 2), o * 2); }
      o += n;
    }
    var out = new T.BufferGeometry();
    out.setAttribute('position', new T.BufferAttribute(pos, 3));
    out.setAttribute('normal', new T.BufferAttribute(nor, 3));
    out.setAttribute('color', new T.BufferAttribute(col, 3));
    if (uv) { out.setAttribute('uv', new T.BufferAttribute(uv, 2)); }
    out.computeBoundingSphere();
    out.computeBoundingBox();
    return out;
  }
  // P = { partKey: [geos] } -> [[geometry, class], ...] one merged geometry per material class
  function classify(P) {
    var buckets = {}, out = [], key, i;
    for (key in P) {
      if (!P.hasOwnProperty(key) || key === 'paint' || !P[key].length) { continue; }
      var def = PART[key];
      if (!def) { throw new Error('models.js: unknown part ' + key); }
      (buckets[def[0]] = buckets[def[0]] || []).push({ list: P[key], col: linCol(def[1]), uv: def[2] === 1 });
    }
    for (i = 0; i < CLS_ORDER.length; i++) {
      var c = CLS_ORDER[i];
      if (buckets[c]) { out.push([mergeC(buckets[c], c === 'trim' || c === 'lamp'), c]); }
    }
    return out;
  }
  function partsToGroup(g, parts) {
    for (var i = 0; i < parts.length; i++) {
      var m = new T.Mesh(parts[i][0], CLS[parts[i][1]]());
      m.name = parts[i][1];
      m.matrixAutoUpdate = false;
      g.add(m);
    }
  }
  // cheap baked ambient occlusion for a paint geometry: fn(x, y, z, ny) -> 0..1
  function bakeAO(geo, fn) {
    var p = geo.attributes.position, n = geo.attributes.normal, c = new Float32Array(p.count * 3), i, a;
    for (i = 0; i < p.count; i++) {
      a = fn(p.getX(i), p.getY(i), p.getZ(i), n ? n.getY(i) : 0);
      c[i * 3] = a; c[i * 3 + 1] = a; c[i * 3 + 2] = a;
    }
    geo.setAttribute('color', new T.BufferAttribute(c, 3));
    return geo;
  }
  // generic car-body AO: dark sills and underside, darker inside the wheel arches
  function carAO(axles, wr, archR, yTop) {
    return function (x, y, z, ny) {
      var a = lerp(0.62, 1, smooth(0.10, yTop, y)), i;
      for (i = 0; i < axles.length; i++) {
        var dz = z - axles[i], dy = y - wr, d = Math.sqrt(dz * dz + dy * dy);
        a *= lerp(0.55, 1, smooth(archR - 0.02, archR + 0.12, d));
      }
      if (ny < -0.35) { a *= 0.6; }
      return a;
    };
  }
  // smooth normals across edges flatter than 'deg', keep sharper edges crisp
  function crease(geo, deg) {
    var g = flat(geo), p = g.attributes.position.array, nt = p.length / 9, cosA = Math.cos((deg || 40) * PI / 180);
    var fa = new Float32Array(nt * 3), fu = new Float32Array(nt * 3), map = {}, t, c, i, key, list;
    for (t = 0; t < nt; t++) {
      i = t * 9;
      var ux = p[i + 3] - p[i], uy = p[i + 4] - p[i + 1], uz = p[i + 5] - p[i + 2];
      var vx = p[i + 6] - p[i], vy = p[i + 7] - p[i + 1], vz = p[i + 8] - p[i + 2];
      var nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, l = Math.sqrt(nx * nx + ny * ny + nz * nz) || 1e-12;
      fa[t * 3] = nx; fa[t * 3 + 1] = ny; fa[t * 3 + 2] = nz;
      fu[t * 3] = nx / l; fu[t * 3 + 1] = ny / l; fu[t * 3 + 2] = nz / l;
      for (c = 0; c < 3; c++) {
        key = Math.round(p[i + c * 3] * 2000) + ',' + Math.round(p[i + c * 3 + 1] * 2000) + ',' + Math.round(p[i + c * 3 + 2] * 2000);
        list = map[key];
        if (!list) { list = map[key] = []; }
        list.push(t);
      }
    }
    var out = new Float32Array(p.length);
    for (t = 0; t < nt; t++) {
      i = t * 9;
      for (c = 0; c < 3; c++) {
        key = Math.round(p[i + c * 3] * 2000) + ',' + Math.round(p[i + c * 3 + 1] * 2000) + ',' + Math.round(p[i + c * 3 + 2] * 2000);
        list = map[key];
        var sx = 0, sy = 0, sz = 0, k, o;
        for (k = 0; k < list.length; k++) {
          o = list[k];
          if (fu[o * 3] * fu[t * 3] + fu[o * 3 + 1] * fu[t * 3 + 1] + fu[o * 3 + 2] * fu[t * 3 + 2] >= cosA) {
            sx += fa[o * 3]; sy += fa[o * 3 + 1]; sz += fa[o * 3 + 2];
          }
        }
        var ln = Math.sqrt(sx * sx + sy * sy + sz * sz) || 1;
        out[i + c * 3] = sx / ln; out[i + c * 3 + 1] = sy / ln; out[i + c * 3 + 2] = sz / ln;
      }
    }
    g.setAttribute('normal', new T.BufferAttribute(out, 3));
    return g;
  }
  function deform(geo, fn) {
    var p = geo.attributes.position, v = new T.Vector3(), i;
    for (i = 0; i < p.count; i++) {
      v.set(p.getX(i), p.getY(i), p.getZ(i));
      fn(v);
      p.setXYZ(i, v.x, v.y, v.z);
    }
    return geo;
  }
  // Shape from [[a,b],...] (side view: a = z forward, b = y up). Entries [ 'arc', cx, cy, r, a0, a1, cw ] add arcs.
  function shapeOf(pts) {
    var s = new T.Shape(), i, q, first = true;
    for (i = 0; i < pts.length; i++) {
      q = pts[i];
      if (q[0] === 'arc') {
        if (first) { s.moveTo(q[1] + q[3] * Math.cos(q[4]), q[2] + q[3] * Math.sin(q[4])); first = false; }
        s.absarc(q[1], q[2], q[3], q[4], q[5], !!q[6]);
      } else if (first) { s.moveTo(q[0], q[1]); first = false; } else { s.lineTo(q[0], q[1]); }
    }
    return s;
  }
  // extrude a side-profile Shape across the car: overall width w (bevels included), centred on x = 0
  function extrudeSide(shape, w, bt, bs, segs, curveSegs, steps) {
    var depth = Math.max(0.001, w - 2 * bt);
    var g = new T.ExtrudeGeometry(shape, {
      depth: depth, steps: steps || 1, curveSegments: curveSegs || 8,
      bevelEnabled: bt > 0, bevelThickness: bt, bevelSize: bs, bevelOffset: -bs, bevelSegments: segs || 3
    });
    g.rotateY(-HALF);            // shape x -> +z (forward), extrusion -> -x
    g.translate(depth / 2, 0, 0);
    g.deleteAttribute('uv');
    return flat(g);
  }
  // extrude a plan/front Shape along an axis: shape in (x,y), extruded depth d along +z then centred
  function extrudeFlat(shape, d, bt, bs, segs, curveSegs) {
    var depth = Math.max(0.001, d - 2 * bt);
    var g = new T.ExtrudeGeometry(shape, {
      depth: depth, steps: 1, curveSegments: curveSegs || 6,
      bevelEnabled: bt > 0, bevelThickness: bt, bevelSize: bs, bevelOffset: -bs, bevelSegments: segs || 2
    });
    g.translate(0, 0, -depth / 2);
    g.deleteAttribute('uv');
    return flat(g);
  }
  function roundRectShape(w, h, r) {
    var s = new T.Shape(), x = -w / 2, y = -h / 2;
    r = Math.min(r, w / 2 - 1e-4, h / 2 - 1e-4);
    s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r);
    s.lineTo(x + w, y + h - r); s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
    s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y);
    return s;
  }
  function rrPath(w, h, r) { // same rounded rect as a hole Path
    var s = roundRectShape(w, h, r), p = new T.Path();
    p.curves = s.curves; p.currentPoint = s.currentPoint;
    return p;
  }
  // rounded box (bevelled), centred
  function rbox(w, h, d, r, segs) {
    r = Math.min(r, w / 2 - 1e-3, h / 2 - 1e-3, d / 2 - 1e-3);
    return extrudeFlat(roundRectShape(w, h, r), d, r, r, segs || 1, 2);
  }

  // hand-built triangles (flat normals, auto-oriented to a hint direction)
  function Builder() { this.p = []; this.uv = null; }
  Builder.prototype.tri = function (a, b, c, hint, ua, ub, uc) {
    var ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
    var nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    if (hint && nx * hint[0] + ny * hint[1] + nz * hint[2] < 0) { var t = b; b = c; c = t; t = ub; ub = uc; uc = t; }
    this.p.push(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
    if (ua) { if (!this.uv) { this.uv = []; } this.uv.push(ua[0], ua[1], ub[0], ub[1], uc[0], uc[1]); }
  };
  Builder.prototype.quad = function (a, b, c, d, hint, ua, ub, uc, ud) { this.tri(a, b, c, hint, ua, ub, uc); this.tri(a, c, d, hint, ua, uc, ud); };
  Builder.prototype.geo = function () {
    var g = new T.BufferGeometry();
    g.setAttribute('position', new T.BufferAttribute(new Float32Array(this.p), 3));
    if (this.uv) { g.setAttribute('uv', new T.BufferAttribute(new Float32Array(this.uv), 2)); }
    g.computeVertexNormals();
    return g;
  };
  // flat polygon given in (u,v) coords mapped to 3D by fn(u,v) -> [x,y,z]
  function polyGeo(pts2, fn, hint) {
    var contour = [], i, b = new Builder();
    for (i = 0; i < pts2.length; i++) { contour.push(new T.Vector2(pts2[i][0], pts2[i][1])); }
    var tris = T.ShapeUtils.triangulateShape(contour, []);
    for (i = 0; i < tris.length; i++) {
      b.tri(fn(contour[tris[i][0]].x, contour[tris[i][0]].y), fn(contour[tris[i][1]].x, contour[tris[i][1]].y),
        fn(contour[tris[i][2]].x, contour[tris[i][2]].y), hint);
    }
    return b.geo();
  }
  // ribbon (strip of width w) following a 2D path (u,v), mapped by fn; closed loops supported
  function ribbon(path, w, fn, hint, closed) {
    var b = new Builder(), n = path.length, i, L = [], R = [];
    for (i = 0; i < n; i++) {
      var a = path[closed ? (i - 1 + n) % n : Math.max(0, i - 1)], c = path[closed ? (i + 1) % n : Math.min(n - 1, i + 1)];
      var tu = c[0] - a[0], tv = c[1] - a[1], tl = Math.sqrt(tu * tu + tv * tv) || 1;
      var nu = -tv / tl * w / 2, nv = tu / tl * w / 2;
      L.push(fn(path[i][0] + nu, path[i][1] + nv)); R.push(fn(path[i][0] - nu, path[i][1] - nv));
    }
    var last = closed ? n : n - 1;
    for (i = 0; i < last; i++) { b.quad(L[i], R[i], R[(i + 1) % n], L[(i + 1) % n], hint); }
    return b.geo();
  }
  // surface probe on a finished mesh (used once per model type to sit lamps/trim on the body)
  var _ray = new T.Raycaster();
  function probe(mesh, o, d) {
    _ray.set(new T.Vector3(o[0], o[1], o[2]), new T.Vector3(d[0], d[1], d[2]).normalize());
    var h = _ray.intersectObject(mesh, false)[0];
    return h ? { p: h.point.clone(), n: h.face.normal.clone() } : null;
  }
  // place a geometry on a surface hit: local +Z -> surface normal, local +X -> horizontal tangent
  // (cross(worldUp, n)), local +Y -> cross(n, x). No arbitrary roll, so text and handles stay level.
  function onSurface(geo, hit, push, spin) {
    var g = geo.clone(), n = hit.n.clone().normalize(), x = new T.Vector3(0, 1, 0).cross(n);
    if (x.lengthSq() < 1e-6) { x.set(1, 0, 0); }
    x.normalize();
    var y = new T.Vector3().crossVectors(n, x);
    if (spin) { g.rotateZ(spin); }
    _m4.makeBasis(x, y, n);
    _m4.setPosition(hit.p.clone().addScaledVector(n, push || 0));
    g.applyMatrix4(_m4);
    return g;
  }
  function tris(obj) {
    var n = 0;
    obj.traverse(function (o) {
      if (o.isMesh && o.geometry) {
        var g = o.geometry;
        n += g.index ? g.index.count / 3 : g.attributes.position.count / 3;
      }
    });
    return Math.round(n);
  }
  function finish(g, paint, dims, labelY) {
    g.userData.paint = paint;
    g.userData.dims = dims;
    g.userData.labelY = labelY;
    if (!g.userData.setAlert) { g.userData.setAlert = alertFn(paint); }
    return g;
  }

  // ---------------------------------------------------------------- wheels
  // returns { tyre, rim } geometries with the axle on X and the outer face toward +X
  function wheelGeos(key, o) {
    return cached('wheel:' + key, function () {
      var r = o.r, w = o.w, rr = o.rimR, seg = o.seg || 16, i;
      var prof = o.lo ? [
        [rr * 0.98, -w / 2 + 0.01], [r - 0.03, -w / 2], [r, -w / 2 + 0.04],
        [r, w / 2 - 0.04], [r - 0.03, w / 2], [rr * 0.98, w / 2 - 0.01]
      ] : [
        [rr * 0.98, -w / 2 + 0.01], [r - 0.035, -w / 2], [r - 0.01, -w / 2 + 0.016], [r, -w / 2 + 0.05],
        [r, w / 2 - 0.05], [r - 0.01, w / 2 - 0.016], [r - 0.035, w / 2], [rr * 0.98, w / 2 - 0.01]
      ];
      var pts = [];
      for (i = 0; i < prof.length; i++) { pts.push(new T.Vector2(prof[i][0], prof[i][1])); }
      // seg is a multiple of 4 and the lathe starts at phi = 0, so a vertex ring touches the ground exactly
      var tyre = new T.LatheGeometry(pts, seg);
      tyre.rotateZ(-HALF);                         // lathe axis Y -> X
      var back = new T.CircleGeometry(rr * 0.98, seg);
      back.rotateY(HALF); back.translate(w / 2 - 0.09, 0, 0);
      var tyreG = merge([tyre, back]);

      var parts = [], lip = [], spokes = o.spokes || 5;
      if (o.lo) { lip.push(new T.Vector2(rr, w / 2 - 0.006), new T.Vector2(rr * 0.9, w / 2 - 0.012), new T.Vector2(rr * 0.8, w / 2 - 0.045)); }
      else { lip.push(new T.Vector2(rr, w / 2 - 0.03), new T.Vector2(rr, w / 2 - 0.006), new T.Vector2(rr * 0.9, w / 2 - 0.012), new T.Vector2(rr * 0.8, w / 2 - 0.045)); }
      var lipG = new T.LatheGeometry(lip, seg); lipG.rotateZ(-HALF);
      parts.push(lipG);
      var hubR = rr * 0.22;
      var hub = new T.CylinderGeometry(hubR, hubR * 1.15, 0.05, o.lo ? 8 : 10, 1);
      hub.rotateZ(-HALF); hub.translate(w / 2 - 0.035, 0, 0);
      parts.push(hub);
      var sl = rr * 0.92 - hubR * 0.8, sw = o.spokeW || rr * 0.14;
      var sp = new T.BoxGeometry(0.022, sl, sw);
      sp.translate(0, hubR * 0.8 + sl / 2, 0);
      for (i = 0; i < spokes; i++) {
        var a0 = i / spokes * PI * 2;
        if (o.twin) {
          parts.push(xf(sp, w / 2 - 0.04, 0, 0, a0 + 0.13, 0, 0, 1, 1, 0.55));
          parts.push(xf(sp, w / 2 - 0.04, 0, 0, a0 - 0.13, 0, 0, 1, 1, 0.55));
        } else {
          parts.push(xf(sp, w / 2 - 0.04, 0, 0, a0, 0, 0));
        }
      }
      return { tyre: tyreG, rim: merge(parts) };
    });
  }
  // static wheels merged into the kit (non-ego vehicles: the spin is invisible at AVM range)
  // positions: [[x, y, z, side], ...] side = +1 right (+x face) / -1 left
  function wheelsInto(P, geos, positions, rimKey) {
    for (var i = 0; i < positions.length; i++) {
      var q = positions[i], ry = q[3] < 0 ? PI : 0;
      P.tyre.push(xf(geos.tyre, q[0], q[1], q[2], 0, ry, 0));
      P[rimKey || 'rim'].push(xf(geos.rim, q[0], q[1], q[2], 0, ry, 0));
    }
  }
  // animated wheels (ego car): one pivot per wheel, tyre (trim) + rim (metal) with baked colours
  function wheelMeshes(key, geos) {
    return cached('wheelC:' + key, function () {
      return { tyre: mergeC([{ list: [geos.tyre], col: linCol(PART.tyre[1]) }], true), rim: mergeC([{ list: [geos.rim], col: linCol(PART.rim[1]) }]) };
    });
  }
  function addWheels(g, key, geos, positions) {
    var wm = wheelMeshes(key, geos), list = [], i;
    for (i = 0; i < positions.length; i++) {
      var q = positions[i], piv = new T.Group();
      piv.position.set(q[0], q[1], q[2]);
      piv.rotation.y = q[3] < 0 ? PI : 0;
      piv.add(new T.Mesh(wm.tyre, CLS.trim()));
      piv.add(new T.Mesh(wm.rim, CLS.metal()));
      g.add(piv);
      list.push(piv);
    }
    return list;
  }
  function wheelAnimator(list, r) {
    var lastT = null, ang = 0;
    return function (t, speed) {
      var dt = lastT === null ? 0 : clamp(t - lastT, 0, 0.2);
      lastT = t;
      ang = (ang + dt * (speed || 0) / r) % (PI * 2);
      for (var i = 0; i < list.length; i++) { list[i].rotation.x = ang; }
    };
  }

  // ---------------------------------------------------------------- car body kit (shared by E60 / sedan / van)
  // wheel-arch arc for shapeOf(): centre z = cx, wheel centre height wy, radius r, arch ends at height yA
  function arch(cx, wy, r, yA) {
    var a = Math.asin(clamp((yA - wy) / r, -0.99, 0.99));
    return ['arc', cx, wy, r, PI - a, a, true];
  }
  function hitPoint(mesh, o, d, push, prev) {
    var h = probe(mesh, o, d);
    if (!h) {
      if (window.AVM_DEBUG) { window.AVM_DEBUG.push(o.slice()); }
      return prev || [o[0] + d[0] * 4, o[1] + d[1] * 4, o[2] + d[2] * 4];
    }
    if (h.n.x * d[0] + h.n.y * d[1] + h.n.z * d[2] > 0) { h.n.negate(); }
    h.p.addScaledVector(h.n, push);
    return [h.p.x, h.p.y, h.p.z];
  }
  // (z, y) on the right (+x) flank
  function sideMap(mesh, push) {
    var prev = null;
    return function (z, y) { prev = hitPoint(mesh, [5, y, z], [-1, 0, 0], push, prev); return prev; };
  }
  // (x, y) on the nose / tail
  function frontMap(mesh, push) {
    var prev = null;
    return function (x, y) { prev = hitPoint(mesh, [x, y, 9], [0, 0, -1], push, prev); return prev; };
  }
  function rearMap(mesh, push) {
    var prev = null;
    return function (x, y) { prev = hitPoint(mesh, [x, y, -9], [0, 0, 1], push, prev); return prev; };
  }
  // (x, z) from above
  function topMap(mesh, push) {
    var prev = null;
    return function (x, z) { prev = hitPoint(mesh, [x, 6, z], [0, -1, 0], push, prev); return prev; };
  }
  // unrolled coordinate s around a plan-view corner of the right flank (sgn +1 nose, -1 tail):
  // s <= cx: axial ray at x = s; cx < s <= cx + R*PI/2: ray aimed at the corner centre (cx, cz), angle (s-cx)/R;
  // beyond: side ray at z = cz -+ (s - cx - R*PI/2). Lets lamps wrap continuously from the face onto the side.
  function arcMap(mesh, push, cx, cz, R, sgn) {
    var prev = null, q = R * HALF;
    return function (s, y) {
      var o, d;
      if (s <= cx) { o = [s, y, sgn * 9]; d = [0, 0, -sgn]; }
      else if (s <= cx + q) {
        var a = (s - cx) / R, dx = Math.sin(a), dz = sgn * Math.cos(a);
        o = [cx + dx * 6, y, cz + dz * 6]; d = [-dx, 0, -dz];
      } else { o = [6, y, cz - sgn * (s - cx - q)]; d = [-1, 0, 0]; }
      prev = hitPoint(mesh, o, d, push, prev);
      return prev;
    };
  }
  // grid patch: mapFn(a, b) with a, b in [0,1] -> [x,y,z]
  // uvr (optional) = [u0, v0, u1, v1] atlas rectangle stretched over the patch (a -> u, b -> v)
  function gridGeo(nu, nv, mapFn, hint, deg, uvr) {
    var b = new Builder(), P = [], U = [], i, j, w = nu + 1;
    for (j = 0; j <= nv; j++) {
      for (i = 0; i <= nu; i++) {
        P.push(mapFn(i / nu, j / nv));
        if (uvr) { U.push([lerp(uvr[0], uvr[2], i / nu), lerp(uvr[1], uvr[3], j / nv)]); }
      }
    }
    for (j = 0; j < nv; j++) {
      for (i = 0; i < nu; i++) {
        var a = j * w + i, c = j * w + i + 1, d = (j + 1) * w + i + 1, e = (j + 1) * w + i;
        if (uvr) { b.quad(P[a], P[c], P[d], P[e], hint, U[a], U[c], U[d], U[e]); }
        else { b.quad(P[a], P[c], P[d], P[e], hint); }
      }
    }
    return crease(b.geo(), deg || 50);
  }
  // solid conformed slab: outer skin from mapOut, walls down to mapIn (same parameters, pushed below the
  // body surface), so lamp lenses have no floating edge from any angle. f(a, b) -> [u, v] for the maps.
  function slabGeo(nu, nv, f, mapOut, mapIn, hint) {
    var top = gridGeo(nu, nv, function (a, b) { var q = f(a, b); return mapOut(q[0], q[1]); }, hint, 50);
    var b = new Builder(), ring = [], i;
    for (i = 0; i < nu; i++) { ring.push([i / nu, 0]); }
    for (i = 0; i < nv; i++) { ring.push([1, i / nv]); }
    for (i = nu; i > 0; i--) { ring.push([i / nu, 1]); }
    for (i = nv; i > 0; i--) { ring.push([0, i / nv]); }
    var O = [], I = [];
    for (i = 0; i < ring.length; i++) {
      var q = f(ring[i][0], ring[i][1]);
      O.push(mapOut(q[0], q[1])); I.push(mapIn(q[0], q[1]));
    }
    // wall normals point away from the slab centre
    var cq = f(0.5, 0.5), cc = mapOut(cq[0], cq[1]);
    for (i = 0; i < ring.length; i++) {
      var k = (i + 1) % ring.length, mx = (O[i][0] + O[k][0]) / 2 - cc[0], my = (O[i][1] + O[k][1]) / 2 - cc[1], mz = (O[i][2] + O[k][2]) / 2 - cc[2];
      b.quad(O[i], O[k], I[k], I[i], [mx, my, mz]);
    }
    return [top, b.geo()];
  }
  // number plate patch conformed to the body: map(x, y), centred at x = 0, plate w x h, bottom at y0
  function plateOn(map, w, h, y0, rear, region) {
    var uv = regUV(region || 'plate');
    if (rear) { uv = [uv[2], uv[1], uv[0], uv[3]]; }   // seen from behind: +x is the viewer's left
    return gridGeo(2, 1, function (a, b) { return map(lerp(-w / 2, w / 2, a), y0 + b * h); }, [0, 0, rear ? -1 : 1], 50, uv);
  }
  // flat plate (trucks / two-wheelers): w x h, facing +z (dir = 1) or -z (dir = -1), optional tilt about x
  function plateBox(w, h, x, y, z, dir, region, tilt) {
    var uv = regUV(region || 'plate'), g = new T.PlaneGeometry(w, h), a = g.attributes.uv, i;
    for (i = 0; i < a.count; i++) { a.setXY(i, lerp(uv[0], uv[2], a.getX(i)), lerp(uv[1], uv[3], a.getY(i))); }
    var g2 = merge([g], true);
    if (dir < 0) { g2.rotateY(PI); }
    if (tilt) { g2.rotateX(tilt); }
    g2.translate(x, y, z);
    return g2;
  }
  // patch on a sloped glass face of the glasshouse (windscreen / backlight): p0 -> p1 along the profile,
  // across x within +-hw(y) * span. up = +1 when the face looks up/outward.
  function slopePatch(mesh, p0, p1, hw, nu, nv, push, span0, span1) {
    var dz = p1[0] - p0[0], dy = p1[1] - p0[1], l = Math.sqrt(dz * dz + dy * dy);
    var nz = -dy / l, ny = dz / l;
    if (ny < 0) { nz = -nz; ny = -ny; }
    var prev = null;
    return gridGeo(nu, nv, function (a, s) {
      var z = p0[0] + dz * s, y = p0[1] + dy * s, sp = lerp(span0, span1 === undefined ? span0 : span1, s);
      var x = (a * 2 - 1) * hw(y) * sp;
      prev = hitPoint(mesh, [x, y + ny * 2, z + nz * 2], [0, -ny, -nz], push, prev);
      return prev;
    }, [0, ny, nz]);
  }
  // turn a list of [u,v] into a densified list (so conformed polygons follow curvature)
  function densify(pts, step, closed) {
    var out = [], i, n = pts.length, last = closed ? n : n - 1;
    for (i = 0; i < last; i++) {
      var a = pts[i], b = pts[(i + 1) % n], d = Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]));
      var k, m = Math.max(1, Math.ceil(d / step));
      for (k = 0; k < m; k++) { out.push([lerp(a[0], b[0], k / m), lerp(a[1], b[1], k / m)]); }
    }
    if (!closed) { out.push(pts[n - 1]); }
    return out;
  }
  // shared rounded-tube segment (for frames, pipes, handlebars); r at a, r2 (default r) at b
  function tubeBetween(a, b, r, seg, r2) {
    var dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], l = Math.sqrt(dx * dx + dy * dy + dz * dz);
    var g = new T.CylinderGeometry(r2 === undefined ? r : r2, r, l, seg || 6, 1, true);
    var q = new T.Quaternion().setFromUnitVectors(new T.Vector3(0, 1, 0), new T.Vector3(dx / l, dy / l, dz / l));
    _m4.compose(new T.Vector3((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2), q, new T.Vector3(1, 1, 1));
    g.applyMatrix4(_m4);
    return g;
  }
  // black wheel-arch liner (half tube across the car) so the arch tunnel never shows paint
  function archLiner(z, wy, r, w) {
    var g = new T.CylinderGeometry(r, r, w, 12, 1, true, -0.4, PI + 0.8);
    g.rotateZ(HALF);                 // axis -> X, theta 0..PI = upper half
    var f = mirrorX(g);              // symmetric in x: only flips winding so it faces inward
    f.translate(0, wy, z);
    return f;
  }

  // ---------------------------------------------------------------- BMW 525i E60 (ego car)
  var E60 = { L: 4.841, W: 1.846, H: 1.468, wb: 2.888, ovF: 0.83, track: 1.56, tyreR: 0.3284, rimR: 0.2159 };
  // piecewise-linear lookup in [[z, y], ...] (z descending)
  function profAt(pts, z) {
    if (z >= pts[0][0]) { return pts[0][1]; }
    for (var i = 1; i < pts.length; i++) {
      if (z >= pts[i][0]) { return lerp(pts[i][1], pts[i - 1][1], (z - pts[i][0]) / (pts[i - 1][0] - pts[i][0])); }
    }
    return pts[pts.length - 1][1];
  }
  function e60Kit() {
    return cached('e60', function () {
      var zF = E60.L / 2, axF = zF - E60.ovF, axR = axF - E60.wb, wr = E60.tyreR, AR = 0.372, HW = E60.W / 2;
      // plan-view rounding of nose and tail (same law for every body layer so they stay concentric)
      function planDef(v) {
        var ax = v.x / HW, k;
        if (v.z > 1.95) { k = smooth(1.95, 2.42, v.z); v.z -= 0.17 * ax * ax * k; v.x *= 1 - 0.035 * k; }
        if (v.z < -1.75) { k = smooth(-1.75, -2.42, v.z); v.z += 0.13 * ax * ax * k; v.x *= 1 - 0.03 * k; }
      }
      function extr(pts, w, bt, bs, segs, steps) {
        var g = new T.ExtrudeGeometry(shapeOf(pts), { depth: w - 2 * bt, steps: steps || 1, curveSegments: 4,
          bevelEnabled: bt > 0, bevelThickness: bt, bevelSize: bs, bevelOffset: -bs, bevelSegments: segs || 3 });
        g.rotateY(-HALF); g.translate((w - 2 * bt) / 2, 0, 0); g.deleteAttribute('uv');
        return flat(g);
      }
      // shoulder crease: starts at the lower outer corner of the headlamp and climbs gently to the tail lamp
      var cLine = [[2.40, 0.50], [2.15, 0.545], [1.65, 0.655], [0.9, 0.755], [0.0, 0.80], [-1.0, 0.835], [-1.9, 0.862], [-2.405, 0.875]];
      // MID layer: sill -> just above the crease (its top edge hides inside the upper layer's lip)
      var midTop = [];
      for (var ci = cLine.length - 1; ci >= 0; ci--) { midTop.push([cLine[ci][0], cLine[ci][1] + 0.04]); }
      midTop[0] = [-2.39, 0.915]; midTop[midTop.length - 1] = [2.395, 0.535];
      var midPts = [[2.29, 0.185], [2.37, 0.24], [2.41, 0.36], [2.418, 0.47], [2.41, 0.515]].concat(midTop.slice().reverse()).concat([
        [-2.405, 0.86], [-2.415, 0.80], [-2.42, 0.72], [-2.415, 0.50], [-2.39, 0.36], [-2.34, 0.27], [-2.27, 0.215], [-2.05, 0.20],
        arch(axR, wr, AR, 0.205), [-0.90, 0.168], [1.20, 0.168], arch(axF, wr, AR, 0.205), [2.05, 0.195]]);
      var mid = extr(midPts, 1.82, 0.03, 0.03, 2, 4);
      deform(mid, function (v) { planDef(v); v.x *= 1 + 0.008 * smooth(1.9, 2.35, v.z); });
      // BULGE: the convex band under E60's lower character line (front door bottom -> over the rear arch)
      var bulgePts = [[1.18, 0.168], [1.03, 0.36], [0.85, 0.43], [0.30, 0.515], [-0.30, 0.595], [-0.72, 0.65], [-1.0, 0.672],
        ['arc', axR, wr, AR, 1.2, -0.338, true], [-0.90, 0.168]];
      var bulge = extr(bulgePts, 1.836, 0.022, 0.02, 2, 2);
      deform(bulge, function (v) { planDef(v); v.x *= 1 - 0.016 * smooth(0.55, 0.98, v.z); });
      // UPPER layer: crease -> hood / beltline / deck, rounded generously so the shoulder rolls into the glass
      var upperPts = [[2.4205, 0.50], [2.418, 0.58], [2.405, 0.625], [2.39, 0.66], [2.36, 0.70], [2.315, 0.728],
        [2.15, 0.752], [1.80, 0.795], [1.40, 0.84], [1.05, 0.875], [0.80, 0.905],
        [0.62, 0.935], [0.20, 0.958], [-0.40, 0.975], [-1.10, 0.998], [-1.60, 1.018],
        [-1.80, 1.030], [-2.10, 1.040], [-2.29, 1.036], [-2.35, 1.012], [-2.385, 0.965], [-2.40, 0.92], [-2.405, 0.875]].concat(cLine.slice(1, cLine.length - 1).reverse());
      var upper = extr(upperPts, E60.W, 0.06, 0.05, 3, 4);
      deform(upper, function (v) {
        planDef(v);
        if (v.y > 0.70 && v.z > -1.7) { v.y += 0.012 * (1 - Math.pow(v.x / HW, 2)) * smooth(0.70, 0.76, v.y); }   // crown
      });
      // hood power dome: a raised centre panel whose hard side edges converge from the windscreen (+-0.30)
      // to the kidneys (+-0.17), standing ~1.5 cm proud of the hood
      var hoodTop = [[2.27, 0.737], [2.15, 0.752], [1.80, 0.795], [1.40, 0.84], [1.05, 0.875], [0.80, 0.905]];
      var domePts = [];
      for (var hi = 0; hi < hoodTop.length; hi++) { domePts.push([hoodTop[hi][0], hoodTop[hi][1] + 0.012 + 0.015]); }
      for (hi = hoodTop.length - 1; hi >= 0; hi--) { domePts.push([hoodTop[hi][0] - (hi === 0 ? 0.01 : 0), hoodTop[hi][1] - 0.03]); }
      var dome = extr(domePts, 0.60, 0.004, 0.004, 1, 2);
      deform(dome, function (v) {
        v.x *= lerp(1, 0.17 / 0.30, smooth(0.85, 2.25, v.z));
        v.y += 0.004 * (1 - Math.pow(v.x / 0.3, 2)) - 0.024 * smooth(1.80, 2.27, v.z);   // creases fade out at the kidneys
      });
      // glasshouse: narrower, with tumblehome, wrap-around windscreen
      var ghPts = [[0.90, 0.86], [0.74, 0.958], [0.30, 1.22], [-0.02, 1.415], [-0.20, 1.452], [-0.50, 1.468],
        [-0.80, 1.458], [-1.02, 1.425], [-1.30, 1.27], [-1.62, 1.05], [-1.70, 0.99], [-1.70, 0.86]];
      var GHW = 1.78, TUMB = 0.16;
      var gh = new T.ExtrudeGeometry(shapeOf(ghPts), { depth: GHW - 0.1, steps: 4, curveSegments: 4,
        bevelEnabled: true, bevelThickness: 0.05, bevelSize: 0.035, bevelOffset: -0.035, bevelSegments: 2 });
      gh.rotateY(-HALF); gh.translate((GHW - 0.1) / 2, 0, 0); gh.deleteAttribute('uv');
      gh = flat(gh);
      deform(gh, function (v) {
        var k = clamp((v.y - 0.93) / 0.54, 0, 1);
        v.x *= 1 - TUMB * k;
        var ax = v.x / (GHW / 2);
        if (v.z > -0.2) { v.z -= 0.10 * ax * ax * smooth(-0.2, 0.5, v.z); }
        if (v.z < -0.95) { v.z += 0.07 * ax * ax * smooth(-0.95, -1.5, v.z); }
        if (v.y > 1.40) { v.y += 0.012 * (1 - ax * ax); }
      });
      // "Bangle butt": trunk lid sits proud of the deck with a sharp shut line; its face leans forward ~3 cm
      var lidPts = [[-1.64, 1.022], [-1.70, 1.068], [-1.90, 1.083], [-2.18, 1.088], [-2.31, 1.076], [-2.375, 1.035],
        [-2.395, 0.96], [-2.412, 0.86], [-2.4205, 0.80], [-2.37, 0.79], [-2.36, 1.0], [-1.72, 0.99]];
      var lid = extr(lidPts, 1.32, 0.025, 0.012, 1, 6);
      deform(lid, planDef);

      mid = crease(mid, 40); bulge = crease(bulge, 40); upper = crease(upper, 40); gh = crease(gh, 42); lid = crease(lid, 35); dome = crease(dome, 35);
      var probeMesh = new T.Mesh(merge([mid, bulge, upper, gh, lid, dome]), new T.MeshBasicMaterial());
      probeMesh.updateMatrixWorld(true);

      var P = { paint: [mid, bulge, upper, gh, lid, dome], glass: [], chrome: [], black: [], plastic: [], headLens: [], mirrorGlass: [],
        angel: [], headLamp: [], amberDim: [], tailLens: [], tailBar: [], plate: [], reflector: [], rubber: [] };
      var side = sideMap(probeMesh, 0.004), sideHi = sideMap(probeMesh, 0.0075), sideTop = sideMap(probeMesh, 0.009);
      var sideLo = sideMap(probeMesh, 0.0025);
      function both(geo, list) {
        if (geo.length !== undefined) { for (var bi = 0; bi < geo.length; bi++) { both(geo[bi], list); } return; }
        list.push(geo, mirrorX(geo));
      }

      // -- side glass: DLO with the Hofmeister kink, chrome surround, B-pillar
      var dlo = [[0.56, 0.985], [0.26, 1.185], [-0.02, 1.372], [-0.40, 1.395], [-0.80, 1.385], [-1.00, 1.35],
        [-1.25, 1.17], [-1.37, 1.075], [-1.28, 1.035], [-1.15, 1.022], [-0.60, 1.012], [0.0, 0.997]];
      var dloD = densify(dlo, 0.09, true);
      both(crease(polyGeo(dloD, side, [1, 0, 0]), 35), P.glass);
      both(ribbon(dloD, 0.022, sideHi, [1, 0, 0], true), P.chrome);
      both(gridGeo(1, 3, function (a, b) { return sideTop(lerp(-0.15, -0.245, a) + b * 0.03, lerp(1.0, 1.39, b)); }, [1, 0, 0]), P.black);
      both(ribbon(densify([[-1.04, 1.015], [-1.0, 1.34]], 0.1), 0.014, sideTop, [1, 0, 0]), P.chrome);
      // -- door shut lines and body-colour handles (level: onSurface keeps them horizontal)
      var shut = [
        [[0.93, 0.79], [0.95, 0.60], [0.94, 0.30], [0.92, 0.19]],
        [[-0.20, 0.99], [-0.21, 0.60], [-0.22, 0.30], [-0.22, 0.18]],
        [[-1.20, 1.0], [-1.19, 0.80], [-1.161, 0.704], [-1.015, 0.611], [-0.921, 0.465], [-0.8975, 0.3284], [-0.90, 0.19]]
      ];
      for (var si = 0; si < shut.length; si++) { both(ribbon(densify(shut[si], 0.08), 0.006, sideLo, [1, 0, 0]), P.black); }
      var handle = rbox(0.16, 0.03, 0.026, 0.012, 1), hz = [0.28, -0.78];
      for (hi = 0; hi < hz.length; hi++) {
        var hh = probe(probeMesh, [5, profAt(cLine, hz[hi]) - 0.035, hz[hi]], [-1, 0, 0]);
        if (hh) { both(onSurface(handle, hh, 0.006), P.paint); }
      }
      // -- door mirrors: body-colour housing on a short black arm, glass facing back (~2.09 m over mirrors)
      var mHouse = rbox(0.18, 0.115, 0.10, 0.04, 2);
      mHouse.translate(HW + 0.032, 1.035, 0.585);
      both(mHouse, P.paint);
      both(xf(new T.BoxGeometry(0.07, 0.04, 0.08), HW - 0.035, 1.0, 0.60), P.black);
      both(xf(new T.BoxGeometry(0.15, 0.09, 0.01), HW + 0.037, 1.035, 0.532), P.mirrorGlass);
      // -- windscreen + backlight, sunroof (reads well in the top view)
      var ghHW = function (y) { return (GHW / 2 - 0.035) * (1 - TUMB * clamp((y - 0.93) / 0.54, 0, 1)); };
      P.glass.push(slopePatch(probeMesh, [0.71, 0.975], [0.0, 1.40], ghHW, 8, 3, 0.004, 0.88, 0.90));
      P.glass.push(slopePatch(probeMesh, [-1.58, 1.075], [-1.05, 1.41], ghHW, 8, 3, 0.004, 0.82, 0.86));
      var top = topMap(probeMesh, 0.006);
      P.glass.push(gridGeo(6, 4, function (a, b) { return top(lerp(-0.37, 0.37, a), lerp(-0.72, -0.08, b)); }, [0, 1, 0]));
      P.black.push(ribbon(densify([[-0.39, -0.74], [0.39, -0.74], [0.39, -0.06], [-0.39, -0.06]], 0.1, true), 0.012, topMap(probeMesh, 0.005), [0, 1, 0], true));

      // -- nose: kidneys, angel-eye headlights with the eyebrow indicator, intake, plate
      var kChrome = roundRectShape(0.205, 0.155, 0.055);
      kChrome.holes.push(rrPath(0.165, 0.118, 0.04));
      var kRing = extrudeFlat(kChrome, 0.03, 0, 0, 1, 3);
      var kBack = extrudeFlat(roundRectShape(0.18, 0.13, 0.045), 0.012, 0, 0, 1, 3);
      var slats = [], sl;
      for (sl = -3; sl <= 3; sl++) { slats.push(xf(new T.BoxGeometry(0.008, 0.115, 0.012), sl * 0.021, 0, 0.004)); }
      var kSlats = merge(slats);
      for (var ks = -1; ks <= 1; ks += 2) {
        var kh = probe(probeMesh, [ks * 0.123, 0.62, 9], [0, 0, -1]);
        if (kh) {
          P.chrome.push(onSurface(kRing, kh, 0.006, ks * 0.06));
          P.black.push(onSurface(kBack, kh, -0.002, ks * 0.06));
          P.plastic.push(onSurface(kSlats, kh, 0.002, ks * 0.06));
        }
      }
      // headlamp in unrolled corner coordinates (s, y): inner end tall (18 cm), outer end narrow, wraps the corner
      var FC = [0.74, 2.17, 0.12], S1 = FC[0] + FC[2] * HALF + 0.10;
      var lensOut = arcMap(probeMesh, 0.009, FC[0], FC[1], FC[2], 1), lensIn = arcMap(probeMesh, -0.02, FC[0], FC[1], FC[2], 1);
      var browMap = arcMap(probeMesh, 0.013, FC[0], FC[1], FC[2], 1);
      function lensBot(s) { return lerp(0.535, 0.61, smooth(0.30, FC[0], s)) + 0.012 * smooth(FC[0], S1, s); }
      function lensTop(s) { return lerp(0.716, 0.700, (s - 0.30) / (S1 - 0.30)); }
      var lens = slabGeo(16, 5, function (a, b) { var s = lerp(0.30, S1, a); return [s, lerp(lensBot(s), lensTop(s), b)]; }, lensOut, lensIn, [0, 0, 1]);
      both(lens, P.headLens);
      both(gridGeo(12, 1, function (a, b) { var s = lerp(0.31, S1 - 0.01, a), t = lensTop(s); return browMap(s, lerp(t - 0.022, t - 0.003, b)); }, [0, 0, 1]), P.amberDim);
      var ring = new T.TorusGeometry(0.05, 0.0065, 3, 14), cup = new T.CylinderGeometry(0.026, 0.032, 0.02, 10, 1);
      cup.rotateX(HALF);
      var rings = [[0.43, 0.632], [0.625, 0.648]];
      for (var ri = 0; ri < rings.length; ri++) {
        var rh = probe(probeMesh, [rings[ri][0], rings[ri][1], 9], [0, 0, -1]);
        if (rh) { both(onSurface(ring, rh, 0.014), P.angel); both(onSurface(cup, rh, 0.012), P.chrome); }
      }
      for (var hs = -1; hs <= 1; hs += 2) {
        P.headLens.push(gridGeo(2, 1, function (a, b) { return frontMap(probeMesh, 0.004)(hs * lerp(0.58, 0.72, a), lerp(0.30, 0.36, b)); }, [0, 0, 1]));
      }
      var front = frontMap(probeMesh, 0.004), frontHi = frontMap(probeMesh, 0.006);
      P.black.push(gridGeo(8, 1, function (a, b) { return front(lerp(-0.52, 0.52, a), lerp(0.25, 0.36, b)); }, [0, 0, 1]));
      P.black.push(gridGeo(10, 1, function (a, b) { return front(lerp(-0.86, 0.86, a), lerp(0.19, 0.235, b)); }, [0, 0, 1]));
      P.plate.push(plateOn(frontHi, 0.38, 0.16, 0.365, false, 'plate'));

      // -- tail: two solid lenses per side. Inner on the trunk lid, outer an L on the rear quarter that wraps
      //    8-10 cm onto the flank; L-shaped light bars inset 7 mm inside each lens.
      var RC = [0.78, -2.20, 0.11], RS = RC[0] + RC[2] * HALF + 0.09;
      var tOut = arcMap(probeMesh, 0.006, RC[0], RC[1], RC[2], -1), tIn = arcMap(probeMesh, -0.02, RC[0], RC[1], RC[2], -1);
      var tBar = arcMap(probeMesh, 0.0095, RC[0], RC[1], RC[2], -1);
      var lidOut = rearMap(probeMesh, 0.006), lidIn = rearMap(probeMesh, -0.02), lidBar = rearMap(probeMesh, 0.0095);
      function rect(x0, x1, y0, y1) { return function (a, b) { return [lerp(x0, x1, a), lerp(y0, y1, b)]; }; }
      var tail = [], rearHi = rearMap(probeMesh, 0.006);
      tail.push(slabGeo(5, 2, rect(0.42, 0.645, 0.862, 0.998), lidOut, lidIn, [0, 0, -1]));
      // horizontal part wraps the corner onto the flank; the vertical leg stays on the rear-facing corner and
      // tucks under the horizontal part (pushed 1 mm less) so the crease between body layers never shows
      tail.push(slabGeo(10, 2, rect(0.675, RS, 0.882, 1.005), arcMap(probeMesh, 0.007, RC[0], RC[1], RC[2], -1), tIn, [0, 0, -1]));
      tail.push(slabGeo(4, 3, rect(0.76, 0.885, 0.745, 0.90), tOut, tIn, [0, 0, -1]));
      both(tail, P.tailLens);
      var bars = [];
      bars.push(ribbon(densify([[0.43, 0.975], [0.637, 0.975]], 0.05), 0.016, lidBar, [0, 0, -1]));
      bars.push(ribbon(densify([[0.45, 0.935], [0.637, 0.935]], 0.05), 0.012, lidBar, [0, 0, -1]));
      // two parallel L-shaped light bars: horizontal along the top, vertical legs down the rear-facing corner
      bars.push(ribbon(densify([[0.683, 0.983], [0.86, 0.983], [0.86, 0.762]], 0.04), 0.016, tBar, [0, 0, -1]));
      bars.push(ribbon(densify([[0.683, 0.94], [0.81, 0.94], [0.81, 0.762]], 0.04), 0.012, tBar, [0, 0, -1]));
      both(bars, P.tailBar);
      // trunk-lid shut lines (rear face and along the deck), bumper split line, reflectors, plate, diffuser
      for (var ts = -1; ts <= 1; ts += 2) {
        P.black.push(ribbon(densify([[ts * 0.66, 0.80], [ts * 0.66, 1.0]], 0.05), 0.008, rearMap(probeMesh, 0.003), [0, 0, -1]));
        P.black.push(ribbon(densify([[ts * 0.668, -1.66], [ts * 0.668, -2.30]], 0.08), 0.01, topMap(probeMesh, 0.003), [0, 1, 0]));
        P.reflector.push(gridGeo(2, 1, function (a, b) { return rearHi(ts * lerp(0.62, 0.82, a), lerp(0.40, 0.43, b)); }, [0, 0, -1]));
      }
      P.black.push(ribbon(densify([[-0.86, 0.755], [0.86, 0.755]], 0.08), 0.007, rearMap(probeMesh, 0.003), [0, 0, -1]));
      P.black.push(ribbon(densify([[-0.64, 0.795], [0.64, 0.795]], 0.1), 0.008, rearMap(probeMesh, 0.003), [0, 0, -1]));
      P.plate.push(plateOn(rearMap(probeMesh, 0.006), 0.38, 0.16, 0.46, true, 'plate'));
      P.black.push(gridGeo(8, 1, function (a, b) { return rearMap(probeMesh, 0.004)(lerp(-0.84, 0.84, a), lerp(0.21, 0.31, b)); }, [0, 0, -1]));
      var pipe = new T.CylinderGeometry(0.036, 0.036, 0.14, 10, 1, true); pipe.rotateX(HALF);
      var pipeIn = new T.CircleGeometry(0.033, 10); pipeIn.rotateY(PI);
      P.chrome.push(xf(pipe, 0.46, 0.235, -2.33), xf(pipe, 0.56, 0.235, -2.33));
      P.black.push(xf(pipeIn, 0.46, 0.235, -2.37), xf(pipeIn, 0.56, 0.235, -2.37));
      // -- arch liners (black tunnels so the paint never shows inside the arch)
      P.black.push(archLiner(axF, wr, 0.362, 1.72), archLiner(axR, wr, 0.362, 1.72));

      var paint = merge(P.paint);
      bakeAO(paint, carAO([axF, axR], wr, AR, 0.56));
      var out = { paint: paint, parts: classify(P), wheelPos: [],
        wheel: wheelGeos('e60', { r: wr, w: 0.225, rimR: E60.rimR, seg: 16, spokes: 5, twin: true, spokeW: 0.024 }) };
      var tx = E60.track / 2;
      out.wheelPos = [[tx, wr, axF, 1], [-tx, wr, axF, -1], [tx, wr, axR, 1], [-tx, wr, axR, -1]];
      return out;
    });
  }
  function makeE60(color) {
    var kit = e60Kit(), g = new T.Group();
    var paint = paintMat(color === undefined || color === null ? 0x8a939d : color, 0.45, 0.40, { vc: true, env: 0.28 });
    var body = new T.Mesh(kit.paint, paint); body.name = 'paint';
    g.add(body);
    partsToGroup(g, kit.parts);
    var wh = addWheels(g, 'e60', kit.wheel, kit.wheelPos);
    g.userData.animate = wheelAnimator(wh, E60.tyreR);
    g.name = 'E60';
    return finish(g, paint, { w: E60.W, l: E60.L, h: E60.H }, E60.H + 0.5);
  }

  // ---------------------------------------------------------------- generic profile-built vehicle (sedan / van)
  function rectOn(map, sx, x0, x1, ylo0, ylo1, yhi0, yhi1, nu, nv, hint) {
    return gridGeo(nu, nv, function (a, b) {
      var x = lerp(x0, x1, a);
      return map(sx * x, lerp(lerp(ylo0, ylo1, a), lerp(yhi0, yhi1, a), b));
    }, hint);
  }
  function carKit(s) {
    return cached('car:' + s.key, function () {
      var HW = s.W / 2, i, j, sx;
      function planDef(v) {
        var ax = v.x / HW, k;
        if (v.z > s.pf[0]) { k = smooth(s.pf[0], s.L / 2 + 0.03, v.z); v.z -= s.pf[1] * ax * ax * k; v.x *= 1 - s.pf[2] * k; }
        if (v.z < s.pr[0]) { k = smooth(s.pr[0], -s.L / 2 - 0.03, v.z); v.z += s.pr[1] * ax * ax * k; v.x *= 1 - s.pr[2] * k; }
      }
      var body = extrudeSide(shapeOf(s.body), s.W, s.bodyB[0], s.bodyB[1], s.bodyB[2], 6, s.bodySteps || 1);
      deform(body, planDef);
      var gh = extrudeSide(shapeOf(s.gh), s.ghW, s.ghB[0], s.ghB[1], s.ghB[2], 4, s.ghSteps || 2);
      var gHW = s.ghW / 2;
      deform(gh, function (v) {
        var k = clamp((v.y - s.ghY0) / (s.H - s.ghY0), 0, 1);
        v.x *= 1 - s.tumble * k;
        var ax = v.x / gHW;
        if (s.wrapF && v.z > s.wrapF[0]) { v.z -= s.wrapF[1] * ax * ax * smooth(s.wrapF[0], s.wrapF[2], v.z); }
        if (s.wrapR && v.z < s.wrapR[0]) { v.z += s.wrapR[1] * ax * ax * smooth(s.wrapR[0], s.wrapR[2], v.z); }
        if (s.crown && v.y > s.H - 0.08) { v.y += s.crown * (1 - ax * ax); }
      });
      body = crease(body, 42); gh = crease(gh, 42);
      var probeMesh = new T.Mesh(merge([body, gh]), new T.MeshBasicMaterial());
      probeMesh.updateMatrixWorld(true);
      var P = { paint: [body, gh], glass: [], chrome: [], black: [], plastic: [], headLens: [], headLamp: [], amber: [],
        tailLens: [], tailBar: [], plate: [], reflector: [], mirrorGlass: [], tyre: [], rim: [] };
      function both(geo, list) {
        if (geo.length !== undefined) { for (var bi = 0; bi < geo.length; bi++) { both(geo[bi], list); } return; }
        list.push(geo, mirrorX(geo));
      }
      var side = sideMap(probeMesh, 0.004), sideHi = sideMap(probeMesh, 0.0075), sideLo = sideMap(probeMesh, 0.0025);
      var ghHW = function (y) { return gHW * (1 - s.tumble * clamp((y - s.ghY0) / (s.H - s.ghY0), 0, 1)); };
      // side glass + frames
      for (i = 0; i < s.windows.length; i++) {
        var wd = densify(s.windows[i], s.winStep || 0.12, true);
        both(crease(polyGeo(wd, side, [1, 0, 0]), 35), P.glass);
        if (s.frame) { both(ribbon(wd, s.frameW || 0.018, sideHi, [1, 0, 0], true), P[s.frame]); }
      }
      for (i = 0; i < (s.pillars || []).length; i++) {
        var pl = s.pillars[i];
        both(gridGeo(1, 2, function (a, b) { return sideHi(lerp(pl[0], pl[1], a) + b * (pl[4] || 0), lerp(pl[2], pl[3], b)); }, [1, 0, 0]), P.black);
      }
      for (i = 0; i < (s.shut || []).length; i++) { both(ribbon(densify(s.shut[i], 0.1), 0.007, sideLo, [1, 0, 0]), P.black); }
      for (i = 0; i < (s.shutR || []).length; i++) { P.black.push(mirrorX(ribbon(densify(s.shutR[i], 0.1), s.shutRW || 0.01, sideLo, [1, 0, 0]))); }
      var handle = new T.BoxGeometry(0.14, 0.026, 0.02);
      for (i = 0; i < (s.handles || []).length; i++) {
        var hh = probe(probeMesh, [5, s.handles[i][1], s.handles[i][0]], [-1, 0, 0]);
        if (hh) { both(onSurface(handle, hh, 0.008), P[s.handleMat || 'paint']); }
      }
      // windscreen / backlight
      if (s.ws) { P.glass.push(slopePatch(probeMesh, s.ws[0], s.ws[1], ghHW, 6, 2, 0.004, s.ws[2], s.ws[3])); }
      if (s.bl) { P.glass.push(slopePatch(probeMesh, s.bl[0], s.bl[1], ghHW, 6, 2, 0.004, s.bl[2], s.bl[3])); }
      // lamps / grille / plates: {m, x:[x0,x1], y:[ylo0,ylo1,yhi0,yhi1], n:[nu,nv], mirror:bool, push}
      function deco(list, rear) {
        for (var d = 0; d < (list || []).length; d++) {
          var q = list[d], y = q.y.length === 2 ? [q.y[0], q.y[0], q.y[1], q.y[1]] : q.y;
          var map = rear ? rearMap(probeMesh, q.push || 0.004) : frontMap(probeMesh, q.push || 0.004);
          var n = q.n || [2, 1], hint = rear ? [0, 0, -1] : [0, 0, 1];
          if (q.m === 'plate') { P.plate.push(plateOn(map, 0.38, 0.16, y[0], rear, q.region)); continue; }
          P[q.m].push(rectOn(map, 1, q.x[0], q.x[1], y[0], y[1], y[2], y[3], n[0], n[1], hint));
          if (q.mirror) { P[q.m].push(rectOn(map, -1, q.x[0], q.x[1], y[0], y[1], y[2], y[3], n[0], n[1], hint)); }
        }
      }
      deco(s.front, false); deco(s.rear, true);
      // mirrors
      if (s.mirror) {
        var mr = s.mirror, mh = rbox(mr[3], mr[4], mr[5], Math.min(mr[4], mr[5]) * 0.4, 1);
        mh.translate(mr[0], mr[1], mr[2]);
        both(mh, P.paint);
        both(xf(new T.BoxGeometry(mr[3] * 0.85, mr[4] * 0.8, 0.01), mr[0], mr[1], mr[2] - mr[5] / 2 - 0.004), P.mirrorGlass);
        both(xf(new T.BoxGeometry(0.09, 0.03, 0.05), mr[0] - mr[3] / 2 + 0.01, mr[1] - mr[4] * 0.3, mr[2] + 0.01), P.black);
      }
      for (i = 0; i < s.axles.length; i++) { P.black.push(archLiner(s.axles[i], s.wheel.r, s.archR - 0.01, s.W - 0.12)); }
      if (s.extra) { s.extra(P, probeMesh, both); }
      var wp = [];
      for (i = 0; i < s.axles.length; i++) {
        for (sx = -1; sx <= 1; sx += 2) { wp.push([sx * s.track / 2, s.wheel.r, s.axles[i], sx]); }
      }
      wheelsInto(P, wheelGeos(s.key, s.wheel), wp);
      var paint = merge(P.paint);
      bakeAO(paint, carAO(s.axles, s.wheel.r, s.archR, s.aoTop || 0.55));
      return { paint: paint, parts: classify(P) };
    });
  }
  // non-ego vehicles: paint + one mesh per material class, static wheels (no animate needed)
  function makeFromKit(kit, color, metal, rough, name, dims) {
    var g = new T.Group(), paint = paintMat(color, metal, rough, { vc: !!kit.paint.attributes.color });
    var body = new T.Mesh(kit.paint, paint); body.name = 'paint';
    g.add(body);
    partsToGroup(g, kit.parts);
    g.name = name;
    return finish(g, paint, dims, dims.h + 0.5);
  }

  // generic 4-door sedan (Corolla-Altis-like proportions: shorter hood, rounder roof, no Bangle deck)
  var SEDAN = {
    key: 'sedan', L: 4.63, W: 1.78, H: 1.45, ghW: 1.62, ghY0: 0.9, tumble: 0.17, crown: 0.012,
    axles: [1.395, -1.305], track: 1.53, archR: 0.36,
    wheel: { r: 0.316, w: 0.205, rimR: 0.2032, seg: 12, spokes: 5, lo: true, spokeW: 0.04 },
    pf: [1.85, 0.15, 0.04], pr: [-1.85, 0.10, 0.03],
    body: [[2.22, 0.20], [2.30, 0.28], [2.33, 0.45], [2.31, 0.62], [2.25, 0.70], [1.9, 0.76], [1.3, 0.835], [0.95, 0.875],
      [0.4, 0.925], [-0.6, 0.955], [-1.5, 0.985], [-2.0, 1.025], [-2.22, 1.015], [-2.30, 0.95], [-2.33, 0.75], [-2.32, 0.45],
      [-2.28, 0.28], [-2.20, 0.21], [-1.75, 0.205], arch(-1.305, 0.316, 0.36, 0.215), [-0.9, 0.175], [0.95, 0.175],
      arch(1.395, 0.316, 0.36, 0.215), [1.85, 0.205]],
    bodyB: [0.05, 0.05, 2], bodySteps: 3,
    gh: [[0.98, 0.84], [0.90, 0.915], [0.20, 1.37], [-0.05, 1.435], [-0.45, 1.45], [-0.85, 1.425], [-1.15, 1.33],
      [-1.62, 1.05], [-1.72, 0.98], [-1.72, 0.84]],
    ghB: [0.05, 0.035, 2], ghSteps: 3,
    wrapF: [0.0, 0.08, 0.7], wrapR: [-1.0, 0.06, -1.5],
    ws: [[0.84, 0.955], [0.19, 1.36], 0.86, 0.83], bl: [[-1.56, 1.085], [-1.15, 1.31], 0.82, 0.80],
    windows: [[[0.72, 0.965], [0.10, 1.36], [-0.30, 1.385], [-0.27, 0.975]],
      [[-0.37, 0.977], [-0.40, 1.385], [-0.85, 1.36], [-1.15, 1.25], [-1.40, 1.06], [-1.38, 1.0]]],
    frame: 'black', frameW: 0.02,
    pillars: [],
    shut: [[[0.98, 0.85], [0.99, 0.5], [0.97, 0.2]], [[-0.32, 0.94], [-0.33, 0.5], [-0.34, 0.19]],
      [[-1.30, 0.97], [-1.27, 0.76], [-1.236, 0.71], [-1.105, 0.662], [-0.999, 0.573], [-0.929, 0.453], [-0.905, 0.316], [-0.90, 0.19]]],
    handles: [[0.15, 0.84], [-0.88, 0.86]],
    mirror: [0.95, 0.98, 0.70, 0.17, 0.10, 0.09],
    front: [
      { m: 'headLens', x: [0.40, 0.84], y: [0.615, 0.655, 0.69, 0.715], n: [4, 1], mirror: true },
      { m: 'headLamp', x: [0.46, 0.60], y: [0.635, 0.645, 0.675, 0.685], n: [1, 1], mirror: true, push: 0.008 },
      { m: 'black', x: [-0.36, 0.36], y: [0.50, 0.50, 0.60, 0.60], n: [4, 1] },
      { m: 'black', x: [-0.62, 0.62], y: [0.22, 0.22, 0.32, 0.32], n: [6, 1] },
      { m: 'plate', x: [-0.19, 0.19], y: [0.335, 0.495], push: 0.006 }
    ],
    rear: [
      { m: 'tailLens', x: [0.48, 0.86], y: [0.84, 0.82, 0.97, 0.96], n: [4, 1], mirror: true },
      { m: 'tailBar', x: [0.52, 0.82], y: [0.90, 0.89, 0.925, 0.915], n: [3, 1], mirror: true, push: 0.008 },
      { m: 'plate', x: [-0.19, 0.19], y: [0.48, 0.64], push: 0.006 },
      { m: 'black', x: [-0.80, 0.80], y: [0.22, 0.22, 0.29, 0.29], n: [6, 1] },
      { m: 'reflector', x: [0.62, 0.80], y: [0.40, 0.40, 0.43, 0.43], n: [2, 1], mirror: true, push: 0.006 }
    ]
  };
  function makeSedan(color) {
    return makeFromKit(carKit(SEDAN), color === undefined || color === null ? 0x2a5b8c : color, 0.3, 0.38, 'sedan',
      { w: SEDAN.W, l: SEDAN.L, h: SEDAN.H });
  }

  // 箱型車: Hiace-like van (short sloped nose, glass all round, sliding door on the right = -x side)
  var VAN = {
    key: 'van', L: 4.9, W: 1.90, H: 2.0, ghW: 1.88, ghY0: 1.0, tumble: 0.05, crown: 0.02,
    axles: [1.50, -1.20], track: 1.65, archR: 0.39,
    wheel: { r: 0.3465, w: 0.195, rimR: 0.19, seg: 12, spokes: 6, lo: true, spokeW: 0.05 }, winStep: 0.3,
    pf: [2.0, 0.14, 0.03], pr: [-2.3, 0.04, 0.01],
    body: [[2.35, 0.24], [2.43, 0.32], [2.45, 0.55], [2.44, 0.80], [2.40, 0.97], [2.30, 1.04], [2.0, 1.10],
      [0.0, 1.08], [-2.40, 1.08], [-2.45, 1.0], [-2.45, 0.40], [-2.40, 0.28], [-2.30, 0.24], [-1.62, 0.24],
      arch(-1.20, 0.3465, 0.39, 0.27), [-0.75, 0.22], [1.05, 0.22], arch(1.50, 0.3465, 0.39, 0.27), [2.0, 0.24]],
    bodyB: [0.06, 0.06, 2], bodySteps: 2,
    gh: [[2.06, 0.98], [1.98, 1.08], [1.25, 1.88], [1.05, 1.96], [0.6, 1.985], [-2.25, 1.985], [-2.42, 1.95],
      [-2.45, 1.85], [-2.45, 0.98]],
    ghB: [0.07, 0.06, 2], ghSteps: 2,
    wrapF: [1.0, 0.06, 1.8],
    ws: [[1.93, 1.13], [1.30, 1.83], 0.9, 0.9], bl: [[-2.49, 1.16], [-2.49, 1.84], 0.86, 0.86],
    windows: [[[1.78, 1.14], [1.26, 1.78], [0.80, 1.82], [0.80, 1.14]],
      [[0.66, 1.14], [0.66, 1.82], [-0.30, 1.82], [-0.30, 1.14]],
      [[-0.40, 1.14], [-0.40, 1.82], [-1.30, 1.82], [-1.30, 1.14]],
      [[-1.40, 1.14], [-1.40, 1.82], [-2.25, 1.82], [-2.28, 1.14]]],
    frame: 'black', frameW: 0.03,
    shut: [[[0.74, 1.10], [0.74, 0.30]], [[2.0, 1.06], [1.95, 0.80], [1.98, 0.30]]],
    // sliding door (right side only): front/rear cuts + the rail groove at the waist
    shutR: [[[0.70, 1.86], [0.70, 0.26]], [[-0.36, 1.86], [-0.36, 0.26]], [[-0.36, 1.06], [-2.30, 1.06]]], shutRW: 0.012,
    handles: [[0.55, 0.98], [1.0, 0.98]], handleMat: 'black',
    mirror: [1.02, 1.30, 1.72, 0.12, 0.22, 0.08],
    front: [
      { m: 'headLens', x: [0.52, 0.90], y: [0.88, 0.90, 1.0, 1.02], n: [3, 1], mirror: true },
      { m: 'headLamp', x: [0.58, 0.74], y: [0.91, 0.92, 0.975, 0.985], n: [1, 1], mirror: true, push: 0.008 },
      { m: 'amber', x: [0.78, 0.88], y: [0.91, 0.915, 0.97, 0.975], n: [1, 1], mirror: true, push: 0.008 },
      { m: 'black', x: [-0.48, 0.48], y: [0.86, 0.86, 1.0, 1.0], n: [4, 1] },
      { m: 'chrome', x: [-0.46, 0.46], y: [0.925, 0.925, 0.945, 0.945], n: [4, 1], push: 0.008 },
      { m: 'plastic', x: [-0.92, 0.92], y: [0.26, 0.26, 0.52, 0.52], n: [8, 1], push: 0.006 },
      { m: 'plate', x: [-0.19, 0.19], y: [0.34, 0.50], push: 0.01 }
    ],
    rear: [
      { m: 'tailLens', x: [0.80, 0.93], y: [0.55, 0.55, 1.05, 1.05], n: [1, 3], mirror: true },
      { m: 'tailBar', x: [0.82, 0.91], y: [0.85, 0.85, 1.02, 1.02], n: [1, 2], mirror: true, push: 0.008 },
      { m: 'amber', x: [0.82, 0.91], y: [0.70, 0.70, 0.80, 0.80], n: [1, 1], mirror: true, push: 0.008 },
      { m: 'plate', x: [-0.19, 0.19], y: [0.64, 0.80], push: 0.006 },
      { m: 'plastic', x: [-0.88, 0.88], y: [0.31, 0.31, 0.50, 0.50], n: [8, 1], push: 0.006 },
      { m: 'black', x: [-0.30, 0.30], y: [1.00, 1.00, 1.04, 1.04], n: [2, 1] }
    ]
  };
  function makeVan(color) {
    return makeFromKit(carKit(VAN), color === undefined || color === null ? 0xe8eaec : color, 0.12, 0.45, 'van',
      { w: VAN.W, l: VAN.L, h: VAN.H });
  }

  // ---------------------------------------------------------------- Taiwan 3.5 t light truck (cab-over + aluminium box)
  var TRUCK = { L: 6.2, W: 2.0, H: 2.9, axF: 2.0, axR: -1.35, wr: 0.377 };
  function truckKit() {
    return cached('truck', function () {
      var wr = TRUCK.wr, CW = 1.86, i, sx;
      var cabPts = [[3.08, 0.62], [3.13, 0.95], [3.12, 1.30], [2.99, 2.10], [2.86, 2.23], [1.60, 2.26], [1.46, 2.20], [1.45, 0.62],
        arch(TRUCK.axF, wr, 0.43, 0.62)];
      var cab = extrudeSide(shapeOf(cabPts), CW, 0.07, 0.06, 2, 6, 2);
      deform(cab, function (v) {
        var ax = v.x / (CW / 2);
        if (v.z > 2.7) { v.z -= 0.09 * ax * ax * smooth(2.7, 3.13, v.z); }
        v.x *= 1 - 0.04 * clamp((v.y - 1.3) / 1.0, 0, 1);
      });
      cab = crease(cab, 40);
      var probeMesh = new T.Mesh(cab, new T.MeshBasicMaterial());
      probeMesh.updateMatrixWorld(true);
      var P = { paint: [cab], glass: [], black: [], plastic: [], chrome: [], steel: [], headLens: [], headLamp: [],
        amber: [], tailLens: [], tailBar: [], plate: [], reflector: [], white: [], mirrorGlass: [], tyre: [], rim: [] };
      function both(geo, list) { list.push(geo, mirrorX(geo)); }
      var side = sideMap(probeMesh, 0.004), sideHi = sideMap(probeMesh, 0.007);
      var front = frontMap(probeMesh, 0.004), frontHi = frontMap(probeMesh, 0.008);
      // glazing
      P.glass.push(slopePatch(probeMesh, [3.11, 1.37], [2.995, 2.06], function () { return CW / 2; }, 4, 2, 0.004, 0.9, 0.88));
      var win = densify([[2.86, 1.36], [2.88, 2.02], [2.10, 2.07], [2.02, 1.36]], 0.15, true);
      both(polyGeo(win, side, [1, 0, 0]), P.glass);
      both(ribbon(win, 0.03, sideHi, [1, 0, 0], true), P.black);
      both(ribbon(densify([[2.95, 1.30], [2.98, 0.88]], 0.3), 0.008, side, [1, 0, 0]), P.black);
      both(ribbon(densify([[1.98, 2.08], [1.97, 0.88]], 0.3), 0.008, side, [1, 0, 0]), P.black);
      // face: grille, headlamps, indicators, plate
      P.black.push(rectOn(front, 1, -0.46, 0.46, 0.80, 0.80, 1.14, 1.14, 4, 1, [0, 0, 1]));
      P.chrome.push(rectOn(frontHi, 1, -0.40, 0.40, 1.18, 1.18, 1.24, 1.24, 4, 1, [0, 0, 1]));
      for (sx = -1; sx <= 1; sx += 2) {
        P.headLens.push(rectOn(front, sx, 0.52, 0.86, 0.72, 0.72, 0.93, 0.92, 3, 1, [0, 0, 1]));
        P.headLamp.push(rectOn(frontHi, sx, 0.56, 0.70, 0.76, 0.76, 0.89, 0.89, 1, 1, [0, 0, 1]));
        P.amber.push(rectOn(frontHi, sx, 0.74, 0.84, 0.76, 0.76, 0.89, 0.885, 1, 1, [0, 0, 1]));
      }
      P.plastic.push(xf(rbox(1.94, 0.22, 0.16, 0.04, 1), 0, 0.56, 3.10));
      P.plate.push(plateBox(0.38, 0.16, 0, 0.56, 3.184, 1, 'plateG'));
      P.black.push(archLiner(TRUCK.axF, wr, 0.42, CW - 0.14));
      // big truck mirrors on tubular arms + cab steps
      for (sx = -1; sx <= 1; sx += 2) {
        P.black.push(tubeBetween([sx * 0.90, 2.0, 2.92], [sx * 1.12, 2.0, 2.98], 0.015, 5));
        P.black.push(tubeBetween([sx * 0.90, 1.45, 2.92], [sx * 1.12, 1.50, 2.98], 0.015, 5));
        P.black.push(xf(new T.BoxGeometry(0.06, 0.40, 0.20), sx * 1.14, 1.75, 2.98));
        P.mirrorGlass.push(xf(new T.BoxGeometry(0.01, 0.34, 0.16), sx * 1.14, 1.75, 2.87, 0, sx * 0.3, 0));
        P.black.push(xf(new T.BoxGeometry(0.30, 0.04, 0.42), sx * 0.86, 0.42, 2.45));
      }
      // chassis, fuel tank, mudguards, underride bar
      P.black.push(xf(new T.BoxGeometry(0.08, 0.20, 5.9), 0.42, 0.72, -0.05), xf(new T.BoxGeometry(0.08, 0.20, 5.9), -0.42, 0.72, -0.05));
      P.black.push(xf(new T.BoxGeometry(1.0, 0.08, 4.4), 0, 0.88, -0.88));
      var tank = new T.CylinderGeometry(0.2, 0.2, 0.8, 10, 1); tank.rotateX(HALF);
      P.steel.push(xf(tank, -0.72, 0.58, 0.40));
      var guard = new T.CylinderGeometry(0.44, 0.44, 0.52, 10, 1, true, -0.2, PI + 0.4);
      guard.rotateZ(HALF);
      for (sx = -1; sx <= 1; sx += 2) { P.black.push(xf(guard, sx * 0.755, wr, TRUCK.axR)); }
      P.black.push(xf(new T.BoxGeometry(1.90, 0.10, 0.08), 0, 0.50, -3.06));
      // aluminium box (own per-instance material, see makeTruck) with ribs, rails, rear doors and
      // Taiwan red/white reflective tape
      var bz0 = 1.36, bz1 = -3.10, bl = bz0 - bz1, bc = (bz0 + bz1) / 2, by0 = 0.95, by1 = 2.90;
      var box = xf(rbox(2.0, by1 - by0, bl, 0.035, 1), 0, (by0 + by1) / 2, bc);
      for (i = 0; i <= 6; i++) {
        var rz = bz1 + 0.05 + i * (bl - 0.1) / 6;
        both(xf(new T.BoxGeometry(0.025, by1 - by0 - 0.06, 0.05), 1.006, (by0 + by1) / 2, rz), P.steel);
      }
      both(xf(new T.BoxGeometry(0.03, 0.08, bl), 1.008, by1 - 0.04, bc), P.steel);
      both(xf(new T.BoxGeometry(0.03, 0.10, bl), 1.008, by0 + 0.05, bc), P.steel);
      for (i = 0; i < 7; i++) {
        var tz = bz1 + 0.3 + i * 0.62;
        both(xf(new T.BoxGeometry(0.01, 0.05, 0.25), 1.022, by0 + 0.05, tz), i % 2 ? P.white : P.reflector);
      }
      P.black.push(xf(new T.BoxGeometry(0.012, by1 - by0 - 0.1, 0.01), 0, (by0 + by1) / 2, bz1 - 0.003));
      for (i = 0; i < 4; i++) {
        var lx = [-0.72, -0.25, 0.25, 0.72][i], bar = new T.CylinderGeometry(0.014, 0.014, by1 - by0 - 0.12, 5, 1, true);
        P.steel.push(xf(bar, lx, (by0 + by1) / 2, bz1 - 0.03));
        P.steel.push(xf(new T.BoxGeometry(0.06, 0.08, 0.04), lx, by0 + 0.75, bz1 - 0.025));
      }
      for (i = 0; i < 6; i++) {
        P[i % 2 ? 'white' : 'reflector'].push(xf(new T.BoxGeometry(0.3, 0.05, 0.01), -0.75 + i * 0.3, by0 + 0.04, bz1 - 0.008));
      }
      for (sx = -1; sx <= 1; sx += 2) {
        P.tailLens.push(xf(new T.BoxGeometry(0.28, 0.13, 0.05), sx * 0.78, 0.61, -3.08));
        P.tailBar.push(xf(new T.BoxGeometry(0.10, 0.09, 0.01), sx * 0.70, 0.61, -3.108));
        P.amber.push(xf(new T.BoxGeometry(0.08, 0.09, 0.01), sx * 0.86, 0.61, -3.108));
      }
      P.plate.push(plateBox(0.38, 0.16, 0, 0.64, -3.107, -1, 'plateG'));
      var wg = wheelGeos('truck', { r: wr, w: 0.2, rimR: 0.2032, seg: 12, spokes: 4, lo: true, spokeW: 0.07 }), wp = [];
      for (sx = -1; sx <= 1; sx += 2) {
        wp.push([sx * 0.83, wr, TRUCK.axF, sx], [sx * 0.865, wr, TRUCK.axR, sx]);
        P.tyre.push(xf(wg.tyre, sx * 0.645, wr, TRUCK.axR, 0, sx < 0 ? PI : 0, 0));   // inner dual: rim hidden, tyre only
      }
      wheelsInto(P, wg, wp);
      var paint = merge(P.paint);
      bakeAO(paint, carAO([TRUCK.axF], wr, 0.43, 1.0));
      var boxG = merge([box]);
      bakeAO(boxG, function (x, y, z, ny) { return lerp(0.8, 1, smooth(0.95, 1.6, y)) * (ny < -0.5 ? 0.6 : 1); });
      return { paint: paint, box: boxG, parts: classify(P) };
    });
  }
  function makeTruck(color) {
    var kit = truckKit();
    var g = makeFromKit(kit, color === undefined || color === null ? 0xd9dde2 : color, 0.15, 0.45, 'truck',
      { w: TRUCK.W, l: TRUCK.L, h: TRUCK.H });
    // the aluminium box is the biggest surface from the chase camera: give it its own material that
    // follows the paint's flash (shared emissive Color, emissiveIntensity read through)
    var boxM = new T.MeshStandardMaterial({ color: 0xc4cad0, metalness: 0.5, roughness: 0.45, vertexColors: true });
    lin(boxM); boxM.envMapIntensity = 0.4;
    linkTo(boxM, g.userData.paint);
    var box = new T.Mesh(kit.box, boxM); box.name = 'cargo';
    g.add(box);
    return g;
  }

  // ---------------------------------------------------------------- Taiwan city bus (low floor, 2 doors on the right)
  var BUS = { L: 11.5, W: 2.5, H: 3.2, axF: 3.15, axR: -2.75, wr: 0.478 };
  function busKit() {
    return cached('bus', function () {
      var wr = BUS.wr, HW = BUS.W / 2, i, sx, z;
      var pts = [[5.70, 0.30], [5.76, 0.55], [5.78, 1.0], [5.76, 2.62], [5.66, 2.90], [5.45, 2.98], [-5.50, 2.98],
        [-5.70, 2.86], [-5.75, 2.55], [-5.75, 0.38], [-5.68, 0.30], [-3.40, 0.30],
        arch(BUS.axR, wr, 0.54, 0.32), [-2.0, 0.30], [2.40, 0.30], arch(BUS.axF, wr, 0.54, 0.32)];
      var body = extrudeSide(shapeOf(pts), BUS.W, 0.12, 0.10, 3, 6, 2);
      deform(body, function (v) {
        var ax = v.x / HW;
        if (v.z > 5.2) { v.z -= 0.08 * ax * ax * smooth(5.2, 5.78, v.z); }
        if (v.z < -5.3) { v.z += 0.05 * ax * ax * smooth(-5.3, -5.75, v.z); }
      });
      body = crease(body, 40);
      var probeMesh = new T.Mesh(body, new T.MeshBasicMaterial());
      probeMesh.updateMatrixWorld(true);
      var P = { paint: [body], glass: [], black: [], plastic: [], chrome: [], steel: [], headLens: [], headLamp: [], amber: [],
        tailLens: [], tailBar: [], plate: [], reflector: [], sign: [], tyre: [], rim: [] };
      function sidePoly(pts2, s, push) { // flat side (x = +-HW): pts2 = [[z,y]...]
        return polyGeo(pts2, function (zz, yy) { return [s * (HW + push), yy, zz]; }, [s, 0, 0]);
      }
      function sideRect(z0, z1, y0, y1, s, push) { return sidePoly([[z0, y0], [z1, y0], [z1, y1], [z0, y1]], s, push); }
      // left side (+x): one long window band with pillars
      P.glass.push(sideRect(-5.35, 4.30, 1.18, 2.62, 1, 0.004));
      P.glass.push(sidePoly([[4.42, 1.18], [5.50, 1.0], [5.55, 2.62], [4.42, 2.62]], 1, 0.004));
      for (z = -4.1; z < 4.3; z += 1.25) { P.black.push(sideRect(z - 0.05, z + 0.05, 1.18, 2.62, 1, 0.006)); }
      // right side (-x): windows, front door (ahead of the front axle) and middle door
      P.glass.push(sideRect(-5.35, -0.35, 1.18, 2.62, -1, 0.004), sideRect(1.15, 2.52, 1.18, 2.62, -1, 0.004));
      for (z = -4.1; z < -0.4; z += 1.25) { P.black.push(sideRect(z - 0.05, z + 0.05, 1.18, 2.62, -1, 0.006)); }
      var doors = [[-0.30, 1.10], [3.80, 4.92]];
      for (i = 0; i < doors.length; i++) {
        var d0 = doors[i][0], d1 = doors[i][1], dm = (d0 + d1) / 2;
        P.black.push(sideRect(d0, d1, 0.32, 2.72, -1, 0.003));
        P.glass.push(sideRect(d0 + 0.07, dm - 0.03, 0.55, 2.62, -1, 0.006), sideRect(dm + 0.03, d1 - 0.07, 0.55, 2.62, -1, 0.006));
        P.glass.push(sideRect(d0 + 0.07, dm - 0.03, 0.40, 0.50, -1, 0.006), sideRect(dm + 0.03, d1 - 0.07, 0.40, 0.50, -1, 0.006));
      }
      P.glass.push(sideRect(4.98, 5.52, 1.0, 2.62, -1, 0.004));
      // window sill line both sides
      P.steel.push(sideRect(-5.4, 5.5, 1.14, 1.17, 1, 0.006), sideRect(-5.4, 5.5, 1.14, 1.17, -1, 0.006));
      // front: windscreen, destination sign, lamps, bumper
      var front = frontMap(probeMesh, 0.004), frontHi = frontMap(probeMesh, 0.008);
      P.glass.push(slopePatch(probeMesh, [5.775, 1.02], [5.74, 2.60], function () { return HW; }, 4, 2, 0.004, 0.92, 0.92));
      P.black.push(rectOn(front, 1, -1.0, 1.0, 2.66, 2.66, 2.90, 2.90, 2, 1, [0, 0, 1]));
      for (sx = -1; sx <= 1; sx += 2) {
        P.headLens.push(rectOn(front, sx, 0.70, 1.12, 0.52, 0.52, 0.72, 0.72, 2, 1, [0, 0, 1]));
        P.headLamp.push(rectOn(frontHi, sx, 0.76, 0.92, 0.56, 0.56, 0.68, 0.68, 1, 1, [0, 0, 1]));
        P.amber.push(rectOn(frontHi, sx, 0.96, 1.08, 0.56, 0.56, 0.68, 0.68, 1, 1, [0, 0, 1]));
        // "rabbit ear" mirrors hanging in front of the windscreen
        P.black.push(tubeBetween([sx * 1.10, 2.86, 5.62], [sx * 1.30, 2.78, 6.02], 0.022, 5));
        P.black.push(tubeBetween([sx * 1.30, 2.78, 6.02], [sx * 1.32, 2.30, 6.06], 0.022, 5));
        P.black.push(xf(new T.BoxGeometry(0.10, 0.42, 0.24), sx * 1.34, 2.08, 6.06));
      }
      P.plastic.push(rectOn(frontMap(probeMesh, 0.006), 1, -1.18, 1.18, 0.36, 0.36, 0.50, 0.50, 6, 1, [0, 0, 1]));
      P.plate.push(plateOn(frontMap(probeMesh, 0.012), 0.38, 0.16, 0.36, false, 'plateR'));
      // rear: window (finely subdivided so it follows the bulged tail), engine louvres, tail lamps
      var rear = rearMap(probeMesh, 0.006), rearHi = rearMap(probeMesh, 0.01);
      P.glass.push(rectOn(rear, 1, -1.0, 1.0, 1.9, 1.9, 2.7, 2.7, 8, 4, [0, 0, -1]));
      P.black.push(rectOn(rear, 1, -0.9, 0.9, 0.6, 0.6, 1.3, 1.3, 8, 4, [0, 0, -1]));
      for (i = 0; i < 6; i++) { P.steel.push(rectOn(rearHi, 1, -0.85, 0.85, 0.66 + i * 0.11, 0.66 + i * 0.11, 0.69 + i * 0.11, 0.69 + i * 0.11, 8, 1, [0, 0, -1])); }
      for (sx = -1; sx <= 1; sx += 2) {
        P.tailLens.push(rectOn(rear, sx, 1.02, 1.20, 0.55, 0.55, 1.45, 1.45, 2, 4, [0, 0, -1]));
        P.tailBar.push(rectOn(rearHi, sx, 1.05, 1.17, 1.10, 1.10, 1.40, 1.40, 2, 2, [0, 0, -1]));
        P.amber.push(rectOn(rearHi, sx, 1.05, 1.17, 0.85, 0.85, 1.02, 1.02, 2, 1, [0, 0, -1]));
      }
      P.plastic.push(rectOn(rearMap(probeMesh, 0.008), 1, -1.18, 1.18, 0.40, 0.40, 0.52, 0.52, 8, 1, [0, 0, -1]));
      P.plate.push(plateOn(rearMap(probeMesh, 0.014), 0.38, 0.16, 0.36, true, 'plateR'));
      // roof A/C pod + liners
      P.plastic.push(xf(rbox(1.9, 0.24, 3.1, 0.08, 2), 0, 2.98 + 0.11, 1.8));
      P.steel.push(xf(new T.BoxGeometry(1.5, 0.02, 2.4), 0, 2.98 + 0.235, 1.8));
      P.black.push(archLiner(BUS.axF, wr, 0.53, BUS.W - 0.26), archLiner(BUS.axR, wr, 0.53, BUS.W - 0.26));
      // destination signs (front / right side / rear) on the atlas; onSurface keeps them level and unmirrored
      var uv = regUV('sign');
      function signPlane(w, h) {
        var g = new T.PlaneGeometry(w, h), a = g.attributes.uv;
        for (var k = 0; k < a.count; k++) { a.setXY(k, lerp(uv[0], uv[2], a.getX(k)), lerp(uv[1], uv[3], a.getY(k))); }
        return g;
      }
      var hF = probe(probeMesh, [0, 2.78, 9], [0, 0, -1]);
      if (hF) { P.sign.push(onSurface(signPlane(1.9, 0.22), hF, 0.012)); }
      var sS = signPlane(0.9, 0.18); sS.rotateY(-HALF); sS.translate(-HW - 0.012, 2.30, 2.0);
      P.sign.push(sS);
      var hR = probe(probeMesh, [0, 2.82, -9], [0, 0, 1]);
      if (hR) { P.sign.push(onSurface(signPlane(0.75, 0.18), hR, 0.012)); }
      var wp = [];
      for (sx = -1; sx <= 1; sx += 2) {
        wp.push([sx * 1.04, wr, BUS.axF, sx]);
        wp.push([sx * 1.02, wr, BUS.axR, sx], [sx * 0.74, wr, BUS.axR, sx]);
      }
      wheelsInto(P, wheelGeos('bus', { r: wr, w: 0.27, rimR: 0.2858, seg: 12, spokes: 4, lo: true, spokeW: 0.08 }), wp);
      var paint = merge(P.paint);
      bakeAO(paint, carAO([BUS.axF, BUS.axR], wr, 0.54, 1.1));
      return { paint: paint, parts: classify(P) };
    });
  }
  function makeBus(color) {
    return makeFromKit(busKit(), color === undefined || color === null ? 0x2f8f5b : color, 0.15, 0.45, 'bus',
      { w: BUS.W, l: BUS.L, h: BUS.H });
  }

  // ---------------------------------------------------------------- articulated human (one SkinnedMesh, blended weights)
  // Built at a nominal 1.70 m (~7.9 heads) and scaled per instance. Bones (index):
  // 0 root 1 hips 2 spine 3 neck 4 head 5 shL 6 elL 7 wrL 8 shR 9 elR 10 wrR 11 thL 12 knL 13 anL 14 thR 15 knR 16 anR
  // Person's left = +x (they face +z). The geometry (positions, normals, skin attributes) is cached per look and
  // shared; every instance only owns a small Uint8 colour attribute, so one person = one draw call
  // (riders: two, the helmet uses a glossy material).
  var HB = { hips: 0.95, spine: 1.02, neck: 1.44, head: 1.51, sh: 1.40, el: 1.08, wr: 0.83, th: 0.87, kn: 0.48, an: 0.07 };
  var BONE = { root: 0, hips: 1, spine: 2, neck: 3, head: 4, shL: 5, elL: 6, wrL: 7, shR: 8, elR: 9, wrR: 10, thL: 11, knL: 12, anL: 13, thR: 14, knR: 15, anR: 16 };
  // colour slots
  var S_SKIN = 0, S_HAIR = 1, S_TOP = 2, S_BOT = 3, S_SHOE = 4, S_ACC = 5, S_EYE = 6, S_VISOR = 7, S_UMB = 8, S_BAG = 9, S_SOLE = 10, S_BROW = 11, S_MASK = 12;
  // East-Asian-weighted skin tones (sRGB), none so pale that ACES + the blue sky fill turns them chalky
  var SKIN = [0xe3b28c, 0xd9a47e, 0xcf9872, 0xc48a65, 0xb67b57, 0x9c6646, 0x83533a, 0x6b432d];
  var SKINW = [3, 4, 4, 3, 2, 1, 1, 1];
  var HAIR = [0x0d0b0a, 0x15110e, 0x1d1712, 0x2a1e16, 0x3a2a1d, 0x5a3c25, 0x74522f];
  var HAIRW = [5, 5, 4, 3, 2, 2, 1];
  var TOPS = [0xeeeeec, 0xe2ded4, 0x23272e, 0x33445f, 0x3e6aa0, 0x9c3434, 0x5d7a4c, 0xd5a33f, 0x6f5aa6, 0xd98aa0,
    0x4d9bbd, 0xc9c1af, 0x8a8f96, 0xb85c2c, 0x2f6e6a, 0x1c2a44, 0x7d8a6a];
  var BOTTOMS = [0x1d2840, 0x2f4a72, 0x3d5f8c, 0x1c1c1e, 0x4c4c50, 0x8a7d66, 0xc2b394, 0x5a4636, 0x6d7a5e, 0x2a2c31];
  var SHOES = [0xf0f0ee, 0xe6e6e6, 0x1a1a1b, 0x2b2b2e, 0x5d4128, 0x7c8087, 0x27355c, 0xb03a32];
  var ACCS = [0x26292e, 0x3b4a63, 0x7a3030, 0x5b6b3c, 0xc9b98f, 0x111111, 0xe0e0e0];
  var UMBS = [0x1d2c4a, 0x7a1f2b, 0x2b2b2e, 0x2f6b5a, 0xc9a43a, 0x6a4c8c, 0xb7bcc2];
  var BAGS = [0xc9b98f, 0x8a5a3c, 0x26292e, 0xe7e1d6, 0x9c3434, 0x3b4a63];
  function wpick(r, arr, w) {
    var s = 0, i, x;
    for (i = 0; i < w.length; i++) { s += w[i]; }
    x = r() * s;
    for (i = 0; i < w.length; i++) { x -= w[i]; if (x <= 0) { return arr[i]; } }
    return arr[arr.length - 1];
  }
  function gauss(r) { return (r() + r() + r() + r() - 2) / 0.577; }
  function lathe(prof, seg, sz, sx) {
    var pts = [], i;
    for (i = 0; i < prof.length; i++) { pts.push(new T.Vector2(prof[i][0], prof[i][1])); }
    var g = new T.LatheGeometry(pts, seg || 10);
    if (sz !== undefined || sx !== undefined) { g.scale(sx || 1, 1, sz === undefined ? 1 : sz); }
    return g;
  }
  var LIMBSEG = 7;
  function limb(r0, r1, y0, y1, x, seg, z) { // tapered tube from y0 (r0) down to y1 (r1)
    var g = new T.CylinderGeometry(r0, r1, y0 - y1, seg || LIMBSEG, 1, true);
    g.translate(x, (y0 + y1) / 2, z || 0);
    return g;
  }
  var LODBALL = false;
  function ball(r, x, y, z, ws, hs, sx, sy, sz) {
    ws = ws || 7; hs = hs || 5;
    if (LODBALL) { ws = Math.max(4, ws - 2); hs = Math.max(3, hs - 1); }
    var g = new T.SphereGeometry(r, ws, hs);
    g.scale(sx || 1, sy || 1, sz || 1);
    g.translate(x, y, z || 0);
    return g;
  }
  function sgnPow(v, e) { return v < 0 ? -Math.pow(-v, e) : Math.pow(v, e); }
  // body cross-section table: rows [y, half-width, front depth, back depth, z centre]; at(y) interpolates
  function rowsAt(rows, y) {
    if (y <= rows[0][0]) { return rows[0]; }
    for (var i = 1; i < rows.length; i++) {
      if (y <= rows[i][0]) {
        var a = rows[i - 1], b = rows[i], k = (y - a[0]) / (b[0] - a[0]);
        return [y, lerp(a[1], b[1], k), lerp(a[2], b[2], k), lerp(a[3], b[3], k), lerp(a[4], b[4], k)];
      }
    }
    return rows[rows.length - 1];
  }
  // lofted tube through super-elliptic rings (pw > 2 = boxier); caps: 'bottom' closes the first ring
  function loft(rows, seg, pw, cap) {
    var b = new Builder(), R = [], i, k, e = 2 / (pw || 2);
    for (i = 0; i < rows.length; i++) {
      var r = rows[i], ring = [];
      for (k = 0; k < seg; k++) {
        var th = k / seg * PI * 2, c = Math.cos(th), s = Math.sin(th);
        ring.push([r[1] * sgnPow(c, e), r[0], r[4] + (s >= 0 ? r[2] : r[3]) * sgnPow(s, e)]);
      }
      R.push(ring);
    }
    for (i = 0; i < rows.length - 1; i++) {
      for (k = 0; k < seg; k++) {
        var a = R[i][k], c2 = R[i][(k + 1) % seg], d = R[i + 1][(k + 1) % seg], f = R[i + 1][k];
        b.quad(a, c2, d, f, [(a[0] + d[0]) / 2, 0, (a[2] + d[2]) / 2 - rows[i][4]]);
      }
    }
    if (cap === 'bottom') {
      var cy = rows[0][0] - 0.004, cz = rows[0][4];
      for (k = 0; k < seg; k++) { b.tri([0, cy, cz], R[0][(k + 1) % seg], R[0][k], [0, -1, 0]); }
    }
    return crease(b.geo(), 62);
  }
  // offset a row table outward by d (all directions)
  function grow(rows, d) {
    var out = [];
    for (var i = 0; i < rows.length; i++) { out.push([rows[i][0], rows[i][1] + d, rows[i][2] + d, rows[i][3] + d, rows[i][4]]); }
    return out;
  }
  // base torso (skin surface) for each sex: crotch -> neck base
  var BODY_M = [
    [0.800, 0.060, 0.045, 0.050, 0.000], [0.825, 0.112, 0.070, 0.078, -0.004], [0.860, 0.150, 0.088, 0.098, -0.008],
    [0.905, 0.165, 0.096, 0.108, -0.010], [0.960, 0.161, 0.096, 0.102, -0.008], [1.020, 0.149, 0.098, 0.090, 0.000],
    [1.100, 0.150, 0.104, 0.090, 0.004], [1.200, 0.160, 0.114, 0.098, 0.006], [1.280, 0.168, 0.118, 0.103, 0.004],
    [1.340, 0.177, 0.112, 0.104, 0.000], [1.385, 0.181, 0.097, 0.096, -0.006], [1.415, 0.163, 0.080, 0.082, -0.010],
    [1.447, 0.114, 0.061, 0.063, -0.010], [1.472, 0.064, 0.050, 0.052, -0.006]];
  var BODY_F = [
    [0.800, 0.065, 0.045, 0.052, 0.000], [0.825, 0.122, 0.072, 0.082, -0.005], [0.865, 0.163, 0.088, 0.106, -0.010],
    [0.915, 0.175, 0.094, 0.114, -0.012], [0.970, 0.163, 0.090, 0.102, -0.008], [1.040, 0.133, 0.086, 0.084, 0.000],
    [1.110, 0.135, 0.090, 0.086, 0.004], [1.190, 0.145, 0.108, 0.090, 0.006], [1.245, 0.149, 0.122, 0.092, 0.008],
    [1.300, 0.153, 0.110, 0.094, 0.004], [1.355, 0.162, 0.097, 0.091, -0.002], [1.395, 0.161, 0.083, 0.084, -0.008],
    [1.425, 0.139, 0.070, 0.072, -0.010], [1.452, 0.100, 0.056, 0.058, -0.010], [1.472, 0.057, 0.045, 0.048, -0.006]];
  function slice(rows, y0, y1, d, extra) {
    var out = [], i;
    out.push(rowsAt(rows, y0));
    for (i = 0; i < rows.length; i++) { if (rows[i][0] > y0 + 0.004 && rows[i][0] < y1 - 0.004) { out.push(rows[i]); } }
    out.push(rowsAt(rows, y1));
    out = grow(out, d);
    if (extra) { for (i = 0; i < out.length; i++) { extra(out[i]); } }
    return out;
  }

  // body description from a seed: proportions, clothes, hair, colours, idle habit and what they carry
  function personSpec(seed, opts) {
    var r = rng(seed * 9973 + 17), o = opts || {};
    for (var w = 0; w < 3; w++) { r(); }
    var fem = o.female !== undefined ? o.female : r() < 0.5;
    var h = fem ? clamp(1.60 + gauss(r) * 0.055, 1.48, 1.74) : clamp(1.72 + gauss(r) * 0.06, 1.58, 1.86);
    var old = r() < 0.12;
    var s = {
      seed: seed, female: fem, height: o.height || h,
      skin: wpick(r, SKIN, SKINW),
      hair: old ? pick(r, [0x8d8a86, 0xb9b6b1, 0x5f5a55]) : wpick(r, HAIR, HAIRW),
      hairStyle: fem ? pick(r, ['long', 'long', 'bob', 'pony', 'bun']) : (r() < 0.85 ? 'short' : 'mid'),
      top: o.top !== undefined && o.top !== null ? o.top : pick(r, TOPS),
      bottom: pick(r, BOTTOMS),
      shoes: pick(r, SHOES),
      acc: pick(r, ACCS),
      sleeve: r() < 0.38 ? 'long' : 'short',
      legs: fem ? pick(r, ['pants', 'pants', 'skirt', 'shorts']) : (r() < 0.75 ? 'pants' : 'shorts'),
      bag: r() < 0.24,
      hat: r() < 0.12,
      helmet: false, stance: r(),
      carry: 'none', idle: 'hang',
      umb: pick(r, UMBS), bagCol: pick(r, BAGS)
    };
    var c = r();
    s.carry = c < 0.27 ? 'phone' : c < (fem ? 0.45 : 0.33) ? 'tote' : c < (fem ? 0.52 : 0.39) ? 'umbrella' : 'none';
    var id = r();
    s.idle = id < 0.42 ? 'hang' : id < 0.72 ? 'pockets' : 'cross';
    if (s.carry === 'umbrella') { s.hat = false; }
    if (s.legs === 'skirt' && r() < 0.5) { s.bottom = pick(r, [0x1d1d22, 0x8a4f5e, 0x3b4a63, 0xc9b98f, 0x6a6f78]); }
    // face masks are common on Taiwanese streets (drawn last so the other choices of a seed stay the same)
    s.mask = !o.helmet && r() < 0.16;
    s.maskCol = pick(r, [0xe9eef2, 0xe9eef2, 0xa9c8e4, 0x2a2d33, 0xf0c8d2]);
    if (o.helmet) {
      s.helmet = o.helmet === 'full' ? 'full' : 'open'; s.hat = false; s.bag = false; s.hairStyle = 'none';
      s.acc = pick(r, [0xf2f2f2, 0x15171a, 0xb3261e, 0x1f4e9a, 0xe8c547, 0x9aa1a8, 0x2f6b3a]);
    }
    if (o.rider) { s.carry = 'none'; s.idle = 'hang'; s.hat = false; }
    if (o.sleeve) { s.sleeve = o.sleeve; }
    if (o.legs) { s.legs = o.legs; }
    if (o.bag !== undefined) { s.bag = o.bag; }
    if (o.bottom !== undefined) { s.bottom = o.bottom; }
    if (o.shoes !== undefined) { s.shoes = o.shoes; }
    if (o.acc !== undefined) { s.acc = o.acc; }
    if (o.carry !== undefined) { s.carry = o.carry; }
    if (o.idle !== undefined) { s.idle = o.idle; }
    return s;
  }

  // per-vertex weight functions (factories, so each limb keeps its own bone indices)
  function upperArmW(sb, eb) {
    return function (v) {
      return v.y > HB.sh - 0.15 ? [sb, 2, 0.3 * smooth(HB.sh - 0.08, HB.sh - 0.005, v.y)] : [sb, eb, 0.4 * smooth(HB.el + 0.07, HB.el - 0.005, v.y)];
    };
  }
  function thighWeights(tb, kb) {
    return function (v) {
      if (v.y > HB.kn + 0.1) { return [tb, 1, 0.5 * smooth(HB.th - 0.06, HB.th + 0.05, v.y)]; }
      return [tb, kb, 0.35 * smooth(HB.kn + 0.07, HB.kn - 0.005, v.y)];
    };
  }
  // geometry for one look (cached by the parts that change the mesh, not by colour or height)
  function humanGeo(s) {
    var key = 'human:' + (s.lod ? 'L' : '') + (s.female ? 'f' : 'm') + s.hairStyle + s.sleeve + s.legs + (s.bag ? 'b' : '') +
      (s.hat ? 'h' : '') + (s.helmet ? 'H' + s.helmet : '') + (s.mask ? 'M' : '') + ':' + s.carry;
    return cached(key, function () {
      LIMBSEG = s.lod ? 5 : 7; LODBALL = !!s.lod;
      var S8 = s.lod ? 6 : 8;
      var P = [], f = s.female, shX = f ? 0.168 : 0.186, hipX = f ? 0.092 : 0.088, X, i;
      var S12 = s.lod ? 8 : 12, S10 = s.lod ? 8 : 10, BODY = f ? BODY_F : BODY_M;
      // w: bone index, or [bone, bone2, yA, wA, yB, wB] (bone2 weight goes wA -> wB from yA to yB), or a function(v) -> [b1, b2, w2]
      function add(g, w, slot, gloss) { P.push({ g: g, w: w, m: slot, gloss: !!gloss }); }
      // ---- head: lathe egg sheared so the chin sits forward and the skull bulges back
      var head = lathe([[0, -0.107], [0.040, -0.098], [0.060, -0.07], [0.072, -0.035], [0.078, 0.0], [0.080, 0.045],
        [0.070, 0.085], [0.045, 0.110], [0, 0.118]], S12, 1.16, f ? 0.95 : 0.98);
      deform(head, function (v) { v.z -= 0.14 * v.y; });
      head.translate(0, 1.585, 0.008);
      if (s.helmet !== 'full') { add(head, 4, S_SKIN); }   // a full-face helmet hides the whole head
      var headProbe = new T.Mesh(head, new T.MeshBasicMaterial());
      headProbe.updateMatrixWorld(true);
      function onFace(geo, x, y, push) {
        var hit = probe(headProbe, [x, y, 1], [0, 0, -1]);
        return hit ? onSurface(geo, hit, push) : null;
      }
      if (!s.helmet || s.helmet === 'open') {
        // eyes and brows: small dark patches; nose: a wedge (no more band-aid box)
        for (X = -1; X <= 1; X += 2) {
          var eye = onFace(new T.PlaneGeometry(0.022, 0.009), X * 0.031, 1.592, 0.0015);
          var brow = onFace(new T.PlaneGeometry(0.03, 0.006), X * 0.032, 1.613, 0.0018);
          if (eye) { add(eye, 4, S_EYE); }
          if (brow) { add(brow, 4, S_BROW); }
        }
        var nb = probe(headProbe, [0, 1.603, 1], [0, 0, -1]), nt = probe(headProbe, [0, 1.562, 1], [0, 0, -1]);
        if (nb && nt && !s.mask) {
          var nz = nt.p.z, nB = new Builder();
          var top = [0, 1.603, nb.p.z - 0.002], tip = [0, 1.566, nz + 0.021], nl = [-0.015, 1.556, nz + 0.001], nr = [0.015, 1.556, nz + 0.001];
          nB.tri(top, tip, nl, [-1, 0.2, 0.6]); nB.tri(top, nr, tip, [1, 0.2, 0.6]); nB.tri(nl, tip, nr, [0, -1, 0.3]);
          add(nB.geo(), 4, S_SKIN);
        }
        add(ball(1, 0.079, 1.578, -0.004, 4, 3, 0.012, 0.03, 0.019), 4, S_SKIN);
        add(ball(1, -0.079, 1.578, -0.004, 4, 3, 0.012, 0.03, 0.019), 4, S_SKIN);
        if (s.mask) {
          // surgical mask: a slightly larger copy of the lower face, nose bridge to under the chin, with ear loops
          var mk = new T.LatheGeometry([new T.Vector2(0.047, -0.094), new T.Vector2(0.066, -0.068), new T.Vector2(0.077, -0.036), new T.Vector2(0.082, -0.004),
            new T.Vector2(0.081, 0.006)], 7, -1.2, 2.4);
          mk.scale((f ? 0.95 : 0.98) * 1.06, 1, 1.16 * 1.06);
          deform(mk, function (v) { v.z -= 0.14 * v.y; v.z += 0.012 * Math.max(0, 1 - Math.abs(v.x) / 0.03) * smooth(-0.06, -0.01, v.y); });
          mk.translate(0, 1.585, 0.008);
          add(mk, 4, S_MASK);
          for (var mx = -1; mx <= 1; mx += 2) { add(tubeBetween([mx * 0.072, 1.575, 0.045], [mx * 0.081, 1.58, -0.005], 0.0025, 3), 4, S_MASK); }
        }
      }
      // ---- hair / hat / helmet
      var capProf = [[0.083, -0.012], [0.0865, 0.045], [0.0765, 0.088], [0.049, 0.117], [0, 0.127]];
      // egg-shaped shell around the head: lift = how far the front edge rises (hairline over the forehead),
      // nape = how far the back edge drops; phi0 > 0 leaves the front open (sector phi0 .. 2PI - phi0)
      function shellOf(prof, nape, sc, lift, phi0) {
        var pts = [], i;
        for (i = 0; i < prof.length; i++) { pts.push(new T.Vector2(prof[i][0], prof[i][1])); }
        var g = new T.LatheGeometry(pts, S12, phi0 || 0, PI * 2 - 2 * (phi0 || 0));
        g.scale((f ? 0.98 : 0.99) * (sc || 1), 1, 1.16);
        var top = lift === undefined ? 0.08 : lift;
        deform(g, function (v) {
          var tf = clamp(v.z / 0.09, 0, 1);
          v.y += tf * Math.max(0, top - v.y) * 0.85;
          if (v.y < 0) { v.y -= nape * clamp(-v.z / 0.09, 0, 1); }
          v.z -= 0.14 * v.y;
        });
        g.translate(0, 1.585, 0.008);
        return g;
      }
      if (s.helmet) {
        var full = s.helmet === 'full';
        var shell = new T.SphereGeometry(0.128, 9, full ? 6 : 5, 0, PI * 2, 0, full ? 2.5 : 1.95);
        shell.scale(1.0, 1.0, 1.12); shell.rotateX(-0.25); shell.translate(0, 1.592, -0.005);
        add(shell, 4, S_ACC, true);
        // visor: full-face covers the whole eye port (y 1.53-1.62), open-face is a short peak-visor
        var visor = full ? new T.SphereGeometry(0.1315, 10, 4, HALF - 0.95, 1.9, 1.33, 0.78) : new T.SphereGeometry(0.131, 10, 3, HALF - 0.95, 1.9, 1.15, 0.5);
        visor.scale(1.0, 1.0, 1.12); visor.rotateX(-0.25); visor.translate(0, 1.592, -0.005);
        add(visor, 4, S_VISOR, true);
      } else {
        var nape = s.hairStyle === 'short' ? 0.035 : 0.07;
        if (s.hat) {
          // cap: hair only shows below the crown (sides / nape); crown 5+ mm outside the hair everywhere
          // baseball cap: hair shows only at the sides and nape (front left open, hidden by the cap), crown
          // 5+ mm outside the hair everywhere, brim at forehead height
          add(shellOf([[0.0835, -0.012], [0.0868, 0.03], [0.083, 0.05]], nape, 1, 0, 1.0), 4, S_HAIR);
          add(shellOf([[0.0935, 0.012], [0.0925, 0.05], [0.083, 0.093], [0.0535, 0.123], [0, 0.134]], 0.01, 1, 0.045), 4, S_ACC);
          var brim = new T.CylinderGeometry(0.078, 0.078, 0.008, 10, 1, false, -1.3, 2.6);
          brim.scale(1.1, 1, 1.0); brim.rotateX(0.14); brim.translate(0, 1.632, 0.072);
          add(brim, 4, S_ACC);
        } else {
          add(shellOf(capProf, nape, s.hairStyle === 'mid' ? 1.03 : 1), 4, S_HAIR);
        }
        if (s.hairStyle === 'long') {
          // straight hair falling behind the shoulders: open half-shell from the cap down to the shoulder blades
          // straight hair falling behind the shoulders: a curtain (back 140 degrees) from the cap to the shoulder blades
          var lh = new T.CylinderGeometry(0.084, 0.098, 0.27, 8, 2, true, 1.75, 2.78);
          lh.translate(0, 1.47, -0.028);
          add(lh, [4, 2, 1.45, 0, 1.36, 0.5], S_HAIR);
        } else if (s.hairStyle === 'bob') {
          var bob = new T.CylinderGeometry(0.094, 0.106, 0.13, 10, 1, true, 0.85, PI * 2 - 1.7);   // open at the face
          bob.scale(1, 1, 1.12); bob.translate(0, 1.54, -0.02);
          add(bob, 4, S_HAIR);
        } else if (s.hairStyle === 'pony') {
          add(ball(0.034, 0, 1.635, -0.103, 6, 4), 4, S_HAIR);
          add(ball(1, 0, 1.53, -0.128, 6, 5, 0.03, 0.10, 0.028), 4, S_HAIR);
        } else if (s.hairStyle === 'bun') {
          add(ball(0.05, 0, 1.66, -0.085, 7, 5), 4, S_HAIR);
        }
      }
      // ---- neck (blends into the head at the top and the chest at the bottom)
      add(limb(0.047, 0.054, 1.535, 1.425, 0, 8, -0.006), [3, 4, 1.47, 0, 1.53, 0.45], S_SKIN);
      // ---- torso layers, all derived from the same section table so they nest without crossing
      var pantsTop = s.legs === 'skirt' ? 1.0 : 1.07;
      var pelvis = loft(slice(BODY, 0.80, pantsTop, 0.004), S12, 2.2, 'bottom');
      add(pelvis, [1, 2, 0.95, 0, 1.07, 0.45], S_BOT);
      var hem = s.legs === 'skirt' ? 1.0 : (f ? 0.915 : 0.895);
      var shirtRows = slice(BODY, hem, 1.472, 0.006, function (row) {
        // loose over the hips (>= 1 cm outside the trousers in x and z), close-fitting over the chest
        var d = lerp(0.013, 0.0, smooth(1.06, 1.16, row[0]));
        row[1] += d; row[2] += d; row[3] += d;
      });
      var shirt = loft(shirtRows, s.lod ? 8 : 12, 2.4);
      add(shirt, function (v) { return [2, 1, lerp(0.55, 0, smooth(hem, hem + 0.17, v.y))]; }, S_TOP);
      if (f && s.legs !== 'skirt') {
        // nothing extra: the female table already carries the bust
      }
      if (s.legs === 'skirt') {
        // A-line skirt worn over the tucked top; each half sways with its thigh
        var sk = [], top0 = rowsAt(BODY, 1.06);
        sk.push([0.55, 0.245, 0.185, 0.185, -0.005], [0.68, 0.228, 0.162, 0.168, -0.008], [0.80, 0.208, 0.136, 0.150, -0.012]);
        // waistband sits >= 1 cm outside the tucked top (top = body + 1.9 cm below y 1.06)
        var r1 = rowsAt(BODY, 0.90), r2 = rowsAt(BODY, 0.98);
        sk.push([0.90, r1[1] + 0.036, r1[2] + 0.034, r1[3] + 0.034, r1[4]], [0.98, r2[1] + 0.032, r2[2] + 0.031, r2[3] + 0.031, r2[4]]);
        sk.push([1.06, top0[1] + 0.029, top0[2] + 0.028, top0[3] + 0.028, top0[4]]);
        add(loft(sk, 12, 2.2), function (v) {
          var wt = 0.5 * smooth(0.86, 0.58, v.y) * clamp(Math.abs(v.x) / 0.1, 0, 1);
          return [1, v.x > 0 ? 11 : 14, wt];
        }, S_BOT);
      }
      // ---- backpack
      if (s.bag) {
        add(xf(rbox(0.27, 0.36, 0.13, 0.04, 1), 0, 1.20, -0.165), 2, S_ACC);
        for (X = -1; X <= 1; X += 2) { add(xf(new T.BoxGeometry(0.035, 0.30, 0.02), X * 0.085, 1.30, -0.07, 0.25, 0, 0), 2, S_ACC); }
      }
      // ---- arms: deltoid tucked under the trapezius slope, sleeves end below the shoulder line
      for (X = -1; X <= 1; X += 2) {
        var sb = X > 0 ? 5 : 8, eb = sb + 1, wb = sb + 2, x = X * shX;
        var upTop = [sb, 2, HB.sh - 0.08, 0, HB.sh - 0.005, 0.3], upLow = [sb, eb, HB.el + 0.07, 0, HB.el - 0.005, 0.4];
        var foreTop = [eb, sb, HB.el - 0.07, 0, HB.el + 0.005, 0.4];
        // deltoid: centred on the arm axis just below the acromion, top level with the shoulder line; the arm
        // tube starts inside it so no rim shows
        add(ball(f ? 0.045 : 0.05, x - X * 0.008, HB.sh - 0.035, 0, 7, 5), [sb, 2, HB.sh - 0.07, 0, HB.sh, 0.25], S_TOP);
        if (s.sleeve === 'long') {
          add(limb(f ? 0.043 : 0.047, f ? 0.04 : 0.044, HB.sh - 0.035, HB.el + 0.005, x), upperArmW(sb, eb), S_TOP);
          add(ball(f ? 0.039 : 0.043, x, HB.el, 0, 6, 4), eb, S_TOP);
          add(limb(f ? 0.039 : 0.043, f ? 0.031 : 0.034, HB.el - 0.005, HB.wr + 0.015, x), foreTop, S_TOP);
        } else {
          add(limb(f ? 0.045 : 0.049, f ? 0.048 : 0.052, HB.sh - 0.035, HB.sh - 0.16, x), upTop, S_TOP);
          add(limb(f ? 0.039 : 0.044, f ? 0.034 : 0.038, HB.sh - 0.07, HB.el + 0.005, x), upLow, S_SKIN);
          add(ball(f ? 0.034 : 0.038, x, HB.el, 0, 6, 4), eb, S_SKIN);
          add(limb(f ? 0.034 : 0.038, f ? 0.026 : 0.03, HB.el - 0.005, HB.wr + 0.01, x), foreTop, S_SKIN);
        }
        // hand: palm + thumb (fingertips ~0.38 H)
        add(ball(1, x, HB.wr - 0.08, 0.008, 6, 4, f ? 0.023 : 0.026, f ? 0.08 : 0.087, f ? 0.043 : 0.048), wb, S_SKIN);
        add(ball(1, x - X * 0.014, HB.wr - 0.055, 0.032, 4, 3, 0.012, 0.032, 0.012), wb, S_SKIN);
      }
      // ---- carried items (right hand = -x)
      if (s.carry === 'phone') {
        add(xf(new T.BoxGeometry(0.009, 0.15, 0.072), -shX + 0.03, HB.wr - 0.10, 0.012), 10, S_EYE);
      } else if (s.carry === 'tote') {
        var tb = xf(rbox(0.09, 0.25, 0.27, 0.03, 1), -shX - 0.01, HB.wr - 0.30, 0.0);
        add(tb, 10, S_BAG);
        add(tubeBetween([-shX, HB.wr - 0.07, -0.02], [-shX - 0.01, HB.wr - 0.18, -0.08], 0.007, 4), 10, S_BAG);
        add(tubeBetween([-shX, HB.wr - 0.07, 0.02], [-shX - 0.01, HB.wr - 0.18, 0.08], 0.007, 4), 10, S_BAG);
      } else if (s.carry === 'umbrella') {
        // carried on the spine bone (stays upright); the right hand is posed onto the shaft (see HOLD)
        var ux = -0.075, uz = 0.235;
        add(tubeBetween([ux, 1.10, uz], [ux, 2.02, uz], 0.008, 4), 2, S_EYE);
        var can = new T.CylinderGeometry(0.03, 0.50, 0.17, 10, 1, false);   // closed: the flat cap is the underside
        can.translate(ux, 1.98, uz);
        add(can, 2, S_UMB);
      }
      // ---- legs
      for (X = -1; X <= 1; X += 2) {
        var tb2 = X > 0 ? 11 : 14, kb = tb2 + 1, ab = tb2 + 2, lx = X * hipX;
        var legSlot = s.legs === 'pants' ? S_BOT : S_SKIN;
        var thighW = thighWeights(tb2, kb);
        var shinTop = [kb, tb2, HB.kn - 0.08, 0, HB.kn + 0.005, 0.35];
        // thighs: slightly flattened sideways (fuller front-to-back, like real upper thighs) and tapered at the
        // top so they fill the crotch inside the pelvis without reaching the shirt hem
        var thigh = function (prof, slot) { var g2 = lathe(prof, S8, 1, f ? 0.84 : 0.86); g2.translate(lx, 0, 0); add(g2, thighW, slot); };
        if (s.legs === 'shorts') {
          thigh([[0.080, HB.th - 0.22], [0.086, HB.th - 0.10], [0.088, HB.th], [0.064, HB.th + 0.085]], S_BOT);
          thigh([[0.058, HB.kn - 0.005], [0.066, HB.th - 0.25], [0.075, HB.th - 0.17]], S_SKIN);
        } else if (s.legs === 'skirt') {
          thigh([[0.054, HB.kn - 0.005], [0.064, HB.th - 0.25], [0.078, HB.th - 0.10], [0.080, HB.th], [0.058, HB.th + 0.07]], S_SKIN);
        } else {
          thigh([[0.062, HB.kn - 0.005], [0.070, HB.th - 0.25], [0.083, HB.th - 0.10], [0.086, HB.th], [0.062, HB.th + 0.085]], S_BOT);
        }
        add(ball(legSlot === S_BOT ? 0.06 : 0.052, lx, HB.kn, 0.004, 7, 5), kb, legSlot);
        var shin = legSlot === S_BOT ? lathe([[0.054, HB.an - 0.005], [0.056, 0.20], [0.060, 0.33], [0.061, HB.kn + 0.005]], S8)
          : lathe([[0.034, HB.an + 0.01], [0.038, 0.16], [0.050, 0.29], [0.054, 0.38], [0.051, HB.kn + 0.005]], S8);
        shin.translate(lx, 0, 0);
        add(shin, shinTop, legSlot);
        var shoe = extrudeSide(shapeOf([[-0.065, -0.058], [0.172, -0.058], [0.198, -0.05], [0.204, -0.032], [0.186, -0.015],
          [0.12, 0.005], [0.05, 0.03], [-0.03, 0.036], [-0.066, 0.01]]), f ? 0.088 : 0.098, s.lod ? 0 : 0.018, s.lod ? 0 : 0.015, 1, 3);
        shoe.rotateY(X * 0.08);
        shoe.translate(lx, HB.an, 0);
        add(shoe, ab, S_SHOE);
        var sole = extrudeSide(shapeOf([[-0.066, -0.07], [0.18, -0.07], [0.205, -0.055], [0.198, -0.05], [0.172, -0.056], [-0.065, -0.056]]), f ? 0.09 : 0.1, 0, 0, 1, 2);
        sole.rotateY(X * 0.08);
        sole.translate(lx, HB.an, 0);
        add(sole, ab, S_SOLE);
      }
      LIMBSEG = 7; LODBALL = false;
      return skinnedGeo(P);
    });
  }
  // assemble parts into shared attributes; vertex order = main group, then gloss group (helmet)
  function skinnedGeo(P) {
    var total = 0, i, k, g, gs = [], order = [];
    for (i = 0; i < P.length; i++) { if (!P[i].gloss) { order.push(i); } }
    var nMainParts = order.length;
    for (i = 0; i < P.length; i++) { if (P[i].gloss) { order.push(i); } }
    for (i = 0; i < order.length; i++) {
      g = flat(P[order[i]].g);
      if (!g.attributes.normal) { g.computeVertexNormals(); }
      gs.push(g); total += g.attributes.position.count;
    }
    var pos = new Float32Array(total * 3), nor = new Float32Array(total * 3), si = new Uint16Array(total * 4), sw = new Float32Array(total * 4);
    var slots = new Uint8Array(total), o = 0, nMain = 0, v = new T.Vector3();
    for (i = 0; i < gs.length; i++) {
      g = gs[i];
      var p = P[order[i]], n = g.attributes.position.count, pa = g.attributes.position;
      pos.set(pa.array.subarray(0, n * 3), o * 3);
      nor.set(g.attributes.normal.array.subarray(0, n * 3), o * 3);
      for (k = 0; k < n; k++) {
        var j = o + k, b1, b2 = 0, w2 = 0;
        slots[j] = p.m;
        if (typeof p.w === 'number') { b1 = p.w; }
        else {
          v.set(pa.getX(k), pa.getY(k), pa.getZ(k));
          if (typeof p.w === 'function') { var r = p.w(v); b1 = r[0]; b2 = r[1]; w2 = r[2]; }
          else { b1 = p.w[0]; b2 = p.w[1]; w2 = lerp(p.w[3], p.w[5], smooth(p.w[2], p.w[4], v.y)); }
        }
        si[j * 4] = b1; si[j * 4 + 1] = b2; sw[j * 4] = 1 - w2; sw[j * 4 + 1] = w2;
      }
      o += n;
      if (i === nMainParts - 1) { nMain = o; }
    }
    if (nMainParts === gs.length) { nMain = total; }
    var A = {
      position: new T.BufferAttribute(pos, 3), normal: new T.BufferAttribute(nor, 3),
      skinIndex: new T.Uint16BufferAttribute(si, 4), skinWeight: new T.Float32BufferAttribute(sw, 4)
    };
    return { attrs: A, slots: slots, count: total, nMain: nMain };
  }
  var PERSON_SPHERE = new T.Sphere(new T.Vector3(0, 0.95, 0), 1.2);
  function personColors(base, s) {
    var map = [], cols = [s.skin, s.hair, s.top, s.bottom, s.shoes, s.acc, 0x15110f, 0x0a0d11, s.umb, s.bagCol, 0, 0, s.maskCol || 0xe9eef2], i;
    // soles: light for white/grey shoes and trainers, dark otherwise; brows a touch darker than the hair
    var shoeL = new T.Color(s.shoes);
    cols[S_SOLE] = (shoeL.r + shoeL.g + shoeL.b) / 3 > 0.6 || s.seed % 3 === 0 ? 0xe9e7e2 : 0x1b1b1c;
    var hc = new T.Color(s.hair); hc.multiplyScalar(0.7); cols[S_BROW] = hc.getHex();
    for (i = 0; i < cols.length; i++) {
      var c = linCol(cols[i]);
      map.push([Math.round(c.r * 255), Math.round(c.g * 255), Math.round(c.b * 255)]);
    }
    var arr = new Uint8Array(base.count * 3);
    for (i = 0; i < base.count; i++) { var m = map[base.slots[i]]; arr[i * 3] = m[0]; arr[i * 3 + 1] = m[1]; arr[i * 3 + 2] = m[2]; }
    return new T.BufferAttribute(arr, 3, true);
  }
  function makeBones(s) {
    var f = s.female, shX = f ? 0.168 : 0.186, hipX = f ? 0.092 : 0.088;
    var defs = [
      [-1, 0, 0, 0], [0, 0, HB.hips, 0], [1, 0, HB.spine - HB.hips, 0], [2, 0, HB.neck - HB.spine, 0], [3, 0, HB.head - HB.neck, 0],
      [2, shX, HB.sh - HB.spine, 0], [5, 0, HB.el - HB.sh, 0], [6, 0, HB.wr - HB.el, 0],
      [2, -shX, HB.sh - HB.spine, 0], [8, 0, HB.el - HB.sh, 0], [9, 0, HB.wr - HB.el, 0],
      [1, hipX, HB.th - HB.hips, 0], [11, 0, HB.kn - HB.th, 0], [12, 0, HB.an - HB.kn, 0],
      [1, -hipX, HB.th - HB.hips, 0], [14, 0, HB.kn - HB.th, 0], [15, 0, HB.an - HB.kn, 0]
    ];
    var bones = [], i;
    for (i = 0; i < defs.length; i++) {
      var b = new T.Bone();
      b.position.set(defs[i][1], defs[i][2], defs[i][3]);
      if (defs[i][0] >= 0) { bones[defs[i][0]].add(b); }
      bones.push(b);
    }
    return bones;
  }
  // skinned vertex-coloured materials: the main one is per instance (pedestrian paint / rider's linked
  // material); the glossy one (helmets) is shared
  function skinVC(rough) { var m = new T.MeshStandardMaterial({ color: 0xffffff, roughness: rough || 0.72, metalness: 0, vertexColors: true }); m.skinning = true; m.envMapIntensity = 0.4; return m; }
  function skinGloss() { var m = mat('sk:gloss', { color: 0xffffff, roughness: 0.22, metalness: 0.25, vertexColors: true, envMapIntensity: 0.8 }); m.skinning = true; return m; }
  // build one human: returns { group (scaled to height), mesh, bones, spec }
  function buildHuman(s, mainMat) {
    var base = humanGeo(s), bones = makeBones(s), geo = new T.BufferGeometry(), k;
    for (k in base.attrs) { if (base.attrs.hasOwnProperty(k)) { geo.setAttribute(k, base.attrs[k]); } }
    geo.setAttribute('color', personColors(base, s));
    geo.boundingSphere = PERSON_SPHERE;
    var mats = mainMat;
    if (base.nMain < base.count) {
      geo.addGroup(0, base.nMain, 0); geo.addGroup(base.nMain, base.count - base.nMain, 1);
      mats = [mainMat, skinGloss()];
    }
    var mesh = new T.SkinnedMesh(geo, mats);
    mesh.add(bones[0]);
    mesh.updateMatrixWorld(true);
    mesh.bind(new T.Skeleton(bones));
    var body = new T.Group();
    body.scale.setScalar(s.height / 1.70);
    body.add(mesh);
    var B = {}, n;
    for (n in BONE) { if (BONE.hasOwnProperty(n)) { B[n] = bones[BONE[n]]; } }
    return { group: body, mesh: mesh, b: B, spec: s, scale: s.height / 1.70 };
  }

  // ---------------------------------------------------------------- gait + idle + IK
  // Foot-placement gait (phase 0 = heel strike, 0..0.62 stance, 0.62..1 swing). Each planted foot is pinned to
  // the ground and moves back at exactly the walking speed (heel rocker -> flat -> toe rocker), the swing foot
  // follows a minimum-jerk path with toe and heel clearance, and 2-bone IK turns both into hip / knee angles.
  // The pelvis height is the highest the legs allow (natural 3-4 cm bob), so no foot can slide or skim.
  var LEG1 = HB.th - HB.kn, LEG2 = HB.kn - HB.an, HEEL_Z = 0.066, TOE_Z = 0.205, SOLE = 0.07;
  var F0 = 0.22, FT = -0.65, PT = 0.62;
  function reach(k) { return Math.sqrt(LEG1 * LEG1 + LEG2 * LEG2 + 2 * LEG1 * LEG2 * Math.cos(k)); }
  // ankle (z relative to the hip joint, y above ground) + sole pitch f (toes up +) for phase p, stride S
  function footAt(p, S, o) {
    p -= Math.floor(p);
    var a0 = 0.40 * PT * S, f, az, ay;
    if (p < PT) {
      var a = a0 - S * p;                                  // ground anchor: moves back at exactly the walking speed
      if (p < 0.1) {                                       // heel rocker
        f = F0 * (1 - smooth(0, 0.1, p));
        az = a - HEEL_Z + HEEL_Z * Math.cos(f) - SOLE * Math.sin(f); ay = HEEL_Z * Math.sin(f) + SOLE * Math.cos(f);
      } else if (p < 0.42) { f = 0; az = a; ay = SOLE; }  // foot flat
      else {                                               // toe rocker (heel rises)
        f = FT * smooth(0.42, PT, p);
        az = a + TOE_Z - TOE_Z * Math.cos(f) - SOLE * Math.sin(f); ay = -TOE_Z * Math.sin(f) + SOLE * Math.cos(f);
      }
    } else {
      var u = (p - PT) / (1 - PT);
      var zs = a0 - S * PT + TOE_Z - TOE_Z * Math.cos(FT) - SOLE * Math.sin(FT), ys = -TOE_Z * Math.sin(FT) + SOLE * Math.cos(FT);
      var ze = a0 - HEEL_Z + HEEL_Z * Math.cos(F0) - SOLE * Math.sin(F0), ye = HEEL_Z * Math.sin(F0) + SOLE * Math.cos(F0);
      f = lerp(FT, F0, smooth(0.12, 0.96, u));
      // cubic Hermite with end tangents = ground speed: the foot leaves and lands at rest relative to the ground
      var m = -S * (1 - PT), u2 = u * u, u3 = u2 * u;
      az = zs * (2 * u3 - 3 * u2 + 1) + m * (u3 - 2 * u2 + u) + ze * (3 * u2 - 2 * u3) + m * (u3 - u2);
      var c = Math.cos(f), sn = Math.sin(f);
      ay = Math.max(lerp(ys, ye, smooth(0.3, 1.0, u)),
        0.018 * smooth(0, 0.2, u) + SOLE * c - TOE_Z * sn,             // toe clearance
        0.03 * (1 - smooth(0.78, 1.0, u)) + SOLE * c + HEEL_Z * sn);   // heel clearance until touch-down
    }
    o.p = p; o.az = az; o.ay = ay; o.f = f;
    return o;
  }
  // planar 2-bone IK: ankle at (az, ay) relative to the hip joint -> [hip, knee]
  function legIK(az, ay) {
    var a = LEG1, b = LEG2, d = clamp(Math.sqrt(az * az + ay * ay), Math.abs(a - b) + 1e-4, a + b - 1e-4);
    var knee = PI - Math.acos(clamp((a * a + b * b - d * d) / (2 * a * b), -1, 1));
    var alpha = Math.acos(clamp((a * a + d * d - b * b) / (2 * a * d), -1, 1));
    return [Math.atan2(az, -ay) + alpha, knee];
  }
  // held arm poses, solved once per person with 2-bone IK in the rest pose: w = wrist target (body frame,
  // nominal metres), hand = world Euler of the hand, pole = elbow hinge axis. 'mirror' copies L to the right arm.
  var HOLD = {
    phone: { R: { w: [-0.07, 1.14, 0.25], hand: [-1.9, -HALF, 0], pole: [1, 0, 0] }, head: 0.42, headWalk: 0.24 },
    umbrella: { R: { w: [-0.075, 1.15, 0.15], hand: [-HALF, 0, 0], pole: [1, 0, 0] } },
    pockets: { L: { w: [0.118, 0.955, 0.082], hand: [0.1, 0, 0.12], pole: [0.89, 0, 0.45] }, mirror: true },
    cross: { L: { w: [-0.085, 1.205, 0.165], hand: [1.25, 0, -0.5], pole: [0.34, 0.27, 0.88] },
      R: { w: [0.10, 1.175, 0.185], hand: [1.25, 0, 0.5], pole: [0.34, -0.27, -0.88] } }
  };
  function holdArm(h, def, right) {
    if (!def) { return null; }
    var src = right ? (def.R || (def.mirror ? def.L : null)) : def.L, flip = right && !def.R;
    if (!src) { return null; }
    var B = h.b, U = right ? B.shR : B.shL, L = right ? B.elR : B.elL, E = right ? B.wrR : B.wrL;
    var w = new T.Vector3(flip ? -src.w[0] : src.w[0], src.w[1], src.w[2]);
    // the hinge axis is an axial vector: mirroring across x = 0 maps (a, b, c) -> (a, -b, -c)
    var pole = new T.Vector3(src.pole[0], flip ? -src.pole[1] : src.pole[1], flip ? -src.pole[2] : src.pole[2]).normalize();
    h.mesh.updateWorldMatrix(true, true);
    solveLimb(U, L, E, h.mesh.localToWorld(w), -1, pole);
    orientWorld(E, src.hand[0], flip ? -src.hand[1] : src.hand[1], flip ? -src.hand[2] : src.hand[2]);
    var r = { sh: U.quaternion.clone(), el: L.rotation.x, wr: E.quaternion.clone() };
    U.quaternion.set(0, 0, 0, 1); L.rotation.set(0, 0, 0); E.quaternion.set(0, 0, 0, 1);
    return r;
  }
  function applyArm(U, L, E, sw, hold, k) {
    U.rotation.set(sw[0], sw[1], sw[2]); L.rotation.set(sw[3], 0, 0); E.rotation.set(sw[4], sw[5], sw[6]);
    if (hold && k > 0.001) {
      U.quaternion.slerp(hold.sh, k);
      L.rotation.x = lerp(sw[3], hold.el, k);
      E.quaternion.slerp(hold.wr, k);
    }
  }
  function walker(h) {
    var B = h.b, s = h.spec, sc = h.scale, st = { ph: (s.seed % 13) / 13, w: 0, lastT: null };
    var seedA = (s.seed % 97) * 0.37, lean = s.stance > 0.5 ? 1 : -1, hipX = s.female ? 0.092 : 0.088;
    var carry = HOLD[s.carry] || null, idle = HOLD[s.idle] || null;
    var tote = s.carry === 'tote', busy = !!(carry || tote);
    var cR = holdArm(h, carry, true), iL = holdArm(h, idle, false), iR = busy ? null : holdArm(h, idle, true);
    if (busy && s.idle === 'cross') { iL = null; }          // one hand busy: no crossed arms
    var legs = [{}, {}];
    B.thL.rotation.order = 'YXZ'; B.thR.rotation.order = 'YXZ';
    return function (t, speed) {
      speed = Math.max(0, speed || 0);
      var dt = st.lastT === null ? 0 : clamp(t - st.lastT, 0, 0.25);
      st.lastT = t;
      var wT = smooth(0.06, 0.45, speed);
      st.w += (wT - st.w) * Math.min(1, dt * 4);
      if (dt === 0) { st.w = wT; }
      var w = st.w, iw = 1 - w, v = Math.min(speed, 2.4) / sc, i, g;
      var A = clamp(0.17 + 0.14 * v, 0.2, 0.5);                       // arm swing amplitude
      // stride (nominal metres per cycle): longer steps first, then cadence; skirts take shorter steps
      var S = clamp(0.5 + 0.65 * v, 0.6, 1.55) * (s.legs === 'skirt' ? 0.88 : 1);
      if (v > 0.01) { st.ph += dt * v / S; }
      st.ph -= Math.floor(st.ph);
      footAt(st.ph, S, legs[0]); footAt(st.ph + 0.5, S, legs[1]);
      // idle: feet under the hips, weight on one leg, the other relaxed slightly forward with a bent knee
      var relax = iw * (0.8 + 0.2 * Math.sin(t * 0.21 + seedA));
      for (i = 0; i < 2; i++) {
        g = legs[i];
        var loose = (i === 1) === (lean > 0) ? relax : 0;
        g.az = lerp(0.012 + 0.035 * loose, g.az, w); g.ay = lerp(SOLE + 0.004 * loose, g.ay, w); g.f = lerp(0.03 * loose, g.f, w);
        g.k = 0.07 + 0.12 * loose;
      }
      var ph2 = (st.ph + 0.06) * 2 * PI, c2 = Math.cos(ph2);
      var roll = 0.045 * w * Math.sin(ph2 + 0.3) + iw * 0.035 * lean, dyL = hipX * Math.sin(roll);
      var yaw = -0.10 * w * c2, pitch = 0.03 * w, dzL = -hipX * Math.sin(yaw);
      // pelvis height: the highest point every leg can still reach with at least its minimum knee bend
      var H = 1e9;
      for (i = 0; i < 2; i++) {
        g = legs[i];
        var dy = i ? -dyL : dyL, r2 = reach(g.k);
        H = Math.min(H, g.ay + Math.sqrt(Math.max(0, r2 * r2 - g.az * g.az)) - dy);
      }
      for (i = 0; i < 2; i++) {
        g = legs[i];
        var ik = legIK(g.az - (i ? -dzL : dzL), g.ay - (H + (i ? -dyL : dyL)));
        g.hip = ik[0]; g.knee = ik[1];
      }
      var L = legs[0], R = legs[1];
      // thighs counter the pelvis rotation (order YXZ) so each leg still swings in the walking plane
      B.thL.rotation.set(-(L.hip + pitch), -yaw, 0.035 + 0.02 * iw - roll);
      B.knL.rotation.set(L.knee, 0, 0);
      B.anL.rotation.set(-(L.f - (L.hip - L.knee)), 0, -0.02);
      B.thR.rotation.set(-(R.hip + pitch), -yaw, -0.035 - 0.02 * iw - roll);
      B.knR.rotation.set(R.knee, 0, 0);
      B.anR.rotation.set(-(R.f - (R.hip - R.knee)), 0, 0.02);
      B.hips.position.set(0.018 * w * Math.sin(ph2 + 0.5) + iw * (0.016 * lean + 0.006 * Math.sin(t * 0.37 + seedA)),
        H + (HB.hips - HB.th), 0);
      B.hips.rotation.set(pitch, yaw, roll);
      // torso: forward lean, gentle counter-twist, breathing
      var breath = Math.sin(t * 1.65 + seedA);
      B.spine.rotation.set(0.02 + 0.035 * v * w + 0.012 * breath * iw, 0.10 * w * c2, -0.6 * roll);
      var down = carry && carry.head ? lerp(carry.headWalk, carry.head, iw) : 0;
      B.neck.rotation.set(-0.02 - 0.02 * v * w + 0.3 * down, 0, 0);
      var look = iw * (down ? 0.15 : 0.55) * Math.sin(t * 0.23 + seedA) * Math.sin(t * 0.113 + seedA * 2);
      B.head.rotation.set(0.04 + 0.04 * iw * Math.sin(t * 0.17 + seedA) + 0.7 * down, -0.7 * (B.spine.rotation.y + B.hips.rotation.y) + look, 0);
      // arms swing against the legs (elbows bend more on the forward swing), then held poses blend in
      var sw = 0.9 * A * c2 * w, sway = iw * 0.03 * Math.sin(t * 0.9 + seedA);
      var aL = [sw + 0.04 * iw + sway, 0, 0.10 + 0.02 * iw, -(0.22 + 0.5 * Math.max(0, -sw) + 0.06 * v * w), -0.05, 0.3, 0];
      var swR = tote ? -sw * 0.35 : -sw;
      var aR = [swR + 0.04 * iw - sway, 0, -0.10 - 0.02 * iw - (tote ? 0.12 : 0), -(0.22 + 0.5 * Math.max(0, -swR) + 0.06 * v * w) * (tote ? 0.5 : 1), -0.05, -0.3, 0];
      applyArm(B.shL, B.elL, B.wrL, aL, iL, iw);
      if (cR) { applyArm(B.shR, B.elR, B.wrR, aR, cR, 1); } else { applyArm(B.shR, B.elR, B.wrR, aR, iR, iw); }
    };
  }
  // two-bone IK: place end bone E (child of L, child of U) at world point 'target'; hinge about local x.
  // bendSign +1 = knee (lower segment swings back), -1 = elbow. poleW = world-space hinge axis.
  function solveLimb(U, L, E, target, bendSign, poleW) {
    U.parent.updateWorldMatrix(true, false);
    var tl = U.parent.worldToLocal(target.clone());
    var a = L.position.length(), b = E.position.length();
    var d = tl.sub(U.position), dl = clamp(d.length(), Math.abs(a - b) + 1e-3, a + b - 1e-3);
    var bend = PI - Math.acos(clamp((a * a + b * b - dl * dl) / (2 * a * b), -1, 1));
    L.rotation.set(bendSign * bend, 0, 0);
    var e = E.position.clone().applyEuler(L.rotation).add(L.position);
    var f1 = e.normalize(), f2 = new T.Vector3(1, 0, 0), f3 = new T.Vector3().crossVectors(f1, f2);
    var g1 = d.normalize(), pq = new T.Quaternion();
    U.parent.getWorldQuaternion(pq);
    var pole = poleW.clone().applyQuaternion(pq.invert());
    var g2 = pole.sub(g1.clone().multiplyScalar(pole.dot(g1))).normalize(), g3 = new T.Vector3().crossVectors(g1, g2);
    var mf = new T.Matrix4().makeBasis(f1, f2, f3), mg = new T.Matrix4().makeBasis(g1, g2, g3);
    U.quaternion.setFromRotationMatrix(mg.multiply(mf.transpose()));
  }
  // give bone E a fixed world orientation (Euler, world axes)
  function orientWorld(E, rx, ry, rz) {
    E.parent.updateWorldMatrix(true, false);
    var pq = new T.Quaternion();
    E.parent.getWorldQuaternion(pq);
    E.quaternion.copy(pq.invert().multiply(new T.Quaternion().setFromEuler(new T.Euler(rx, ry, rz))));
  }
  // seat a rider: c = { seat:[x,y,z], lean, hands:[[x,y,z],[x,y,z]], feet:[[x,y,z],[x,y,z]], footPitch, handPitch }
  function poseRider(h, root, c) {
    var B = h.b, sc = h.scale;
    h.group.position.set(c.seat[0], c.seat[1] + 0.14 * sc - HB.hips * sc, c.seat[2]);
    B.hips.position.set(0, HB.hips, 0);
    B.hips.rotation.set(c.lean * 0.45, 0, 0);
    B.spine.rotation.set(c.lean * 0.55, 0, 0);
    B.neck.rotation.set(-c.lean * 0.55, 0, 0);
    B.head.rotation.set(-c.lean * 0.42 + (c.headPitch || 0), 0, 0);
    poseLimbs(h, root, c);
  }
  function poseLimbs(h, root, c) {
    var B = h.b, X = new T.Vector3(1, 0, 0);
    root.updateMatrixWorld(true);
    solveLimb(B.thL, B.knL, B.anL, new T.Vector3(c.feet[0][0], c.feet[0][1], c.feet[0][2]), 1, c.kneePole || X);
    solveLimb(B.thR, B.knR, B.anR, new T.Vector3(c.feet[1][0], c.feet[1][1], c.feet[1][2]), 1, c.kneePole || X);
    solveLimb(B.shL, B.elL, B.wrL, new T.Vector3(c.hands[0][0], c.hands[0][1], c.hands[0][2]), -1, c.elbowPoleL || X);
    solveLimb(B.shR, B.elR, B.wrR, new T.Vector3(c.hands[1][0], c.hands[1][1], c.hands[1][2]), -1, c.elbowPoleR || X);
    orientWorld(B.anL, -(c.footPitch || 0), 0.06, 0);
    orientWorld(B.anR, -(c.footPitch || 0), -0.06, 0);
    orientWorld(B.wrL, c.handPitch === undefined ? -1.35 : c.handPitch, 0, 0.25);
    orientWorld(B.wrR, c.handPitch === undefined ? -1.35 : c.handPitch, 0, -0.25);
    root.updateMatrixWorld(true);
  }

  // ---------------------------------------------------------------- makePerson
  var personCount = 0;
  function makePerson(shirtHex, seed) {
    if (seed === undefined || seed === null) { seed = 101 + (personCount++) * 7919; }
    var s = personSpec(seed, shirtHex !== undefined && shirtHex !== null ? { top: shirtHex } : null);
    // the whole person is one draw call: the paint is the per-instance vertex-coloured material, so a
    // collision flash tints the entire pedestrian
    var paint = paintMat(0xffffff, 0, 0.64, { skinned: true, vc: true });
    var h = buildHuman(s, paint);
    var g = new T.Group();
    g.name = 'person';
    g.add(h.group);
    g.userData.spec = s;
    g.userData.animate = walker(h);
    g.userData.animate(0, 0);
    var sc = h.scale, umb = s.carry === 'umbrella';
    return finish(g, paint, { w: (umb ? 1.0 : 0.5) * sc, l: (umb ? 1.0 : s.bag || s.carry === 'tote' ? 0.42 : 0.30) * sc, h: s.height },
      (umb ? 2.07 * sc : s.height) + 0.3);
  }

  // ---------------------------------------------------------------- two-wheelers with riders
  function riderTop(h) {
    var v = new T.Vector3();
    h.b.head.getWorldPosition(v);
    return v.y + 0.14 * h.scale;
  }
  function riderSpec(seed, o) {
    o = o || {};
    o.rider = true;
    var s = personSpec(seed, o);
    s.lod = 1;
    s.height = clamp(s.height, 1.60, 1.80);
    if (s.legs === 'skirt') { s.legs = 'pants'; }
    return s;
  }
  // rider clothes follow the vehicle's flash: per-instance material sharing the paint's emissive
  function riderMat(paint, rough) { return linkTo(skinVC(rough || 0.72), paint); }
  var riderCount = 0;
  function nextSeed() { riderCount++; return 777 + riderCount * 4099; }

  // Taiwan step-through scooter (Kymco / Gogoro-like), rider with open-face helmet
  var SCOOT = { L: 1.85, W: 0.70, H: 1.15, axF: 0.62, axR: -0.68, wr: 0.258 };
  function scooterKit() {
    return cached('scooter', function () {
      var P = { paint: [], black: [], plastic: [], steel: [], chrome: [], headLamp: [], tailBar: [], amber: [], plate: [], seat: [], mirrorGlass: [], tyre: [], rim: [] };
      // front fairing / leg shield over the front wheel
      var front = extrudeSide(shapeOf([[0.34, 0.30], [0.44, 0.30], [0.47, 0.56], [0.86, 0.585], [0.92, 0.66], [0.86, 0.86],
        [0.76, 1.00], [0.62, 1.05], [0.50, 0.99], [0.40, 0.62]]), 0.46, 0.09, 0.07, 3, 6);
      deform(front, function (v) { v.x *= 1 - 0.25 * smooth(0.6, 0.92, v.z) * smooth(0.5, 0.9, v.y); });
      P.paint.push(crease(front, 45));
      // floorboard + tunnel, rear body with seat base
      P.paint.push(xf(rbox(0.36, 0.08, 0.55, 0.03, 1), 0, 0.32, 0.10));
      var rearB = extrudeSide(shapeOf([[-0.12, 0.29], [0.02, 0.34], [0.06, 0.60], [-0.18, 0.71], [-0.78, 0.745], [-0.95, 0.71],
        [-0.98, 0.58], [-0.84, 0.44], [-0.52, 0.38], [-0.32, 0.29]]), 0.40, 0.08, 0.06, 3, 6);
      deform(rearB, function (v) { v.x *= 1 - 0.3 * smooth(-0.6, -0.98, v.z); });
      P.paint.push(crease(rearB, 45));
      P.seat.push(xf(rbox(0.30, 0.10, 0.92, 0.045, 1), 0, 0.775, -0.44));             // seat
      P.black.push(xf(rbox(0.30, 0.03, 0.42, 0.01, 1), 0, 0.37, 0.12));                // floor mat
      // front fender, fork, engine/swingarm, muffler, rear rack
      var fender = new T.CylinderGeometry(0.29, 0.29, 0.12, 10, 1, true, -0.2, 1.9);
      fender.rotateZ(HALF); fender.translate(0, SCOOT.wr, SCOOT.axF);
      P.paint.push(fender);
      P.steel.push(tubeBetween([0.065, SCOOT.wr, SCOOT.axF], [0.06, 0.62, 0.55], 0.022, 6), tubeBetween([-0.065, SCOOT.wr, SCOOT.axF], [-0.06, 0.62, 0.55], 0.022, 6));
      P.plastic.push(xf(rbox(0.12, 0.17, 0.48, 0.04, 1), 0.12, 0.30, -0.50));
      var muff = new T.CylinderGeometry(0.055, 0.05, 0.42, 8, 1); muff.rotateX(HALF + 0.12);
      P.black.push(xf(muff, -0.16, 0.34, -0.66));
      P.chrome.push(xf(new T.BoxGeometry(0.02, 0.06, 0.18), -0.215, 0.36, -0.66));
      P.black.push(tubeBetween([0.17, 0.80, -0.62], [0.17, 0.84, -0.95], 0.014, 5), tubeBetween([-0.17, 0.80, -0.62], [-0.17, 0.84, -0.95], 0.014, 5),
        tubeBetween([0.17, 0.84, -0.95], [-0.17, 0.84, -0.95], 0.014, 5));
      // handlebar with cover, grips, short mirror stalks (mirrors ~1.18 m, not antennae)
      P.paint.push(xf(rbox(0.44, 0.10, 0.20, 0.045, 1), 0, 1.06, 0.53));
      P.black.push(tubeBetween([-0.34, 1.06, 0.45], [0.34, 1.06, 0.45], 0.016, 6));
      P.black.push(tubeBetween([0.25, 1.06, 0.45], [0.35, 1.06, 0.43], 0.022, 6), tubeBetween([-0.25, 1.06, 0.45], [-0.35, 1.06, 0.43], 0.022, 6));
      for (var sx = -1; sx <= 1; sx += 2) {
        P.black.push(tubeBetween([sx * 0.19, 1.10, 0.54], [sx * 0.245, 1.165, 0.53], 0.008, 4));
        P.black.push(xf(rbox(0.11, 0.065, 0.03, 0.012, 1), sx * 0.27, 1.18, 0.53, 0, 0, sx * -0.15));
        P.mirrorGlass.push(xf(new T.BoxGeometry(0.095, 0.05, 0.004), sx * 0.27, 1.18, 0.514, 0, 0, sx * -0.15));
        P.amber.push(xf(new T.BoxGeometry(0.06, 0.03, 0.03), sx * 0.20, 0.97, 0.80, 0, sx * 0.4, 0));
      }
      P.headLamp.push(xf(new T.BoxGeometry(0.20, 0.07, 0.04), 0, 1.06, 0.625));
      P.headLamp.push(xf(new T.BoxGeometry(0.12, 0.05, 0.04), 0, 0.88, 0.83, -0.5, 0, 0));
      P.tailBar.push(xf(new T.BoxGeometry(0.22, 0.05, 0.04), 0, 0.66, -0.965, 0.4, 0, 0));
      P.plate.push(plateBox(0.22, 0.12, 0, 0.50, -0.962, -1, 'plate', -0.25));
      wheelsInto(P, wheelGeos('scooter', { r: SCOOT.wr, w: 0.10, rimR: 0.178, seg: 12, spokes: 5, lo: true, spokeW: 0.03 }),
        [[0, SCOOT.wr, SCOOT.axF, 1], [0, SCOOT.wr, SCOOT.axR, 1]]);
      return { parts: classify(P), paint: merge(P.paint) };
    });
  }
  function makeScooter(color, seed) {
    var kit = scooterKit(), g = new T.Group();
    var paint = paintMat(color === undefined || color === null ? 0xd8dde3 : color, 0.2, 0.35);
    var body = new T.Mesh(kit.paint, paint); body.name = 'paint'; g.add(body);
    partsToGroup(g, kit.parts);
    var rs = riderSpec(seed === undefined ? nextSeed() : seed, { helmet: 'open', bag: false });
    var h = buildHuman(rs, riderMat(paint));
    g.add(h.group);
    poseRider(h, g, { seat: [0, 0.80, -0.20], lean: 0.20, hands: [[0.29, 1.05, 0.38], [-0.29, 1.05, 0.38]],
      feet: [[0.12, 0.455, 0.16], [-0.12, 0.455, 0.16]], footPitch: 0.0, headPitch: 0.02 });
    var B = h.b, base = B.head.rotation.y;
    g.userData.animate = function (t) { B.head.rotation.y = base + 0.12 * Math.sin(t * 0.4 + (h.spec.seed % 7)); };
    g.name = 'scooter';
    return finish(g, paint, { w: SCOOT.W, l: SCOOT.L, h: riderTop(h) }, riderTop(h) + 0.35);
  }

  // 重機: big naked / sport bike (wide tank, inline-4 with radiator and belly pan), rider tucked in leathers
  var MOTO = { L: 2.1, W: 0.80, H: 1.15, axF: 0.72, axR: -0.71, wrF: 0.30, wrR: 0.315 };
  function motoKit() {
    return cached('moto', function () {
      var P = { paint: [], black: [], plastic: [], steel: [], chrome: [], darkMetal: [], gold: [], headLamp: [], tailBar: [], amber: [], redPlate: [],
        seat: [], mirrorGlass: [], tyre: [], rim: [] };
      // big fuel tank: ~0.48 wide, tall, with knee recesses at the back
      var tank = extrudeSide(shapeOf([[0.47, 0.86], [0.38, 1.05], [0.20, 1.12], [0.02, 1.11], [-0.16, 1.04], [-0.25, 0.90], [0.10, 0.80]]),
        0.48, 0.11, 0.08, 3, 6);
      deform(tank, function (v) { v.x *= 1 - 0.30 * smooth(0.05, -0.25, v.z) * smooth(1.05, 0.88, v.y); });
      P.paint.push(crease(tank, 45));
      // headlight nacelle + fly screen, side shrouds / radiator scoops, tail cowl
      var nose = extrudeSide(shapeOf([[0.52, 0.88], [0.70, 0.90], [0.77, 0.98], [0.68, 1.12], [0.56, 1.10], [0.50, 0.98]]), 0.28, 0.07, 0.05, 2, 5);
      P.paint.push(crease(nose, 45));
      // tank shrouds: short, integrated with the tank flanks (no dirt-bike radiator wings)
      var shroud = extrudeSide(shapeOf([[0.47, 0.80], [0.46, 0.96], [0.30, 1.0], [0.22, 0.86], [0.32, 0.74]]), 0.52, 0.07, 0.06, 2, 4);
      deform(shroud, function (v) { v.x *= 1 - 0.3 * smooth(0.30, 0.47, v.z) - 0.15 * smooth(0.9, 0.74, v.y); });
      P.paint.push(crease(shroud, 45));
      var tail = extrudeSide(shapeOf([[-0.50, 0.84], [-0.98, 1.00], [-1.03, 0.97], [-0.92, 0.86], [-0.55, 0.75]]), 0.26, 0.06, 0.04, 2, 4);
      deform(tail, function (v) { v.x *= 1 - 0.45 * smooth(-0.55, -1.0, v.z); });
      P.paint.push(crease(tail, 45));
      P.seat.push(xf(rbox(0.28, 0.08, 0.42, 0.035, 1), 0, 0.87, -0.40, -0.12, 0, 0));    // rider seat
      // inline-4: wide crankcase + block, cylinder head fins, radiator, belly pan, frame spars, swingarm
      P.darkMetal.push(xf(rbox(0.45, 0.30, 0.46, 0.06, 1), 0, 0.46, 0.08));
      P.darkMetal.push(xf(rbox(0.40, 0.22, 0.30, 0.04, 1), 0, 0.70, 0.20, -0.35, 0, 0));
      for (var fi = 0; fi < 3; fi++) { P.steel.push(xf(new T.BoxGeometry(0.42, 0.012, 0.22), 0, 0.65 + fi * 0.04, 0.24, -0.35, 0, 0)); }
      var cover = new T.CylinderGeometry(0.11, 0.11, 0.05, 9, 1); cover.rotateZ(HALF);
      P.chrome.push(xf(cover, 0.235, 0.44, 0.04), xf(cover, -0.235, 0.44, 0.04));
      P.black.push(xf(rbox(0.42, 0.34, 0.05, 0.02, 1), 0, 0.70, 0.44, -0.25, 0, 0));        // radiator
      P.steel.push(xf(new T.BoxGeometry(0.44, 0.36, 0.02), 0, 0.70, 0.425, -0.25, 0, 0));
      var belly = extrudeSide(shapeOf([[0.40, 0.36], [0.26, 0.24], [-0.22, 0.26], [-0.26, 0.40], [0.10, 0.34]]), 0.40, 0.05, 0.04, 2, 3);
      P.plastic.push(belly);
      for (var sx = -1; sx <= 1; sx += 2) {
        P.darkMetal.push(tubeBetween([sx * 0.12, 1.0, 0.53], [sx * 0.19, 0.66, -0.20], 0.04, 6));
        P.darkMetal.push(tubeBetween([sx * 0.12, 0.52, -0.22], [sx * 0.10, MOTO.wrR, MOTO.axR], 0.032, 6, 0.024));
        // upside-down fork: thick gold stanchions low, black outer tubes up to the clamps
        P.gold.push(tubeBetween([sx * 0.095, MOTO.wrF + 0.02, MOTO.axF - 0.01], [sx * 0.093, 0.72, 0.625], 0.026, 7));
        P.darkMetal.push(tubeBetween([sx * 0.093, 0.70, 0.63], [sx * 0.09, 1.04, 0.53], 0.034, 7));
        P.black.push(tubeBetween([sx * 0.09, 1.04, 0.53], [sx * 0.34, 1.05, 0.44], 0.016, 5));
        P.black.push(tubeBetween([sx * 0.28, 1.05, 0.46], [sx * 0.36, 1.05, 0.43], 0.022, 5));
        P.black.push(tubeBetween([sx * 0.20, 1.06, 0.50], [sx * 0.26, 1.17, 0.50], 0.008, 4));
        P.black.push(xf(rbox(0.12, 0.065, 0.03, 0.012, 1), sx * 0.28, 1.19, 0.50));
        P.mirrorGlass.push(xf(new T.BoxGeometry(0.10, 0.05, 0.004), sx * 0.28, 1.19, 0.484));
        P.steel.push(tubeBetween([sx * 0.10, 0.42, -0.22], [sx * 0.20, 0.42, -0.24], 0.012, 4));      // foot pegs
        P.amber.push(xf(new T.BoxGeometry(0.05, 0.025, 0.03), sx * 0.12, 0.92, -1.0));
      }
      var can = new T.CylinderGeometry(0.06, 0.07, 0.44, 10, 1); can.rotateX(HALF - 0.25);
      P.darkMetal.push(xf(can, -0.19, 0.56, -0.54));
      P.chrome.push(tubeBetween([-0.12, 0.28, 0.30], [-0.17, 0.44, -0.32], 0.028, 6));
      // front fender, hugger, plate holder (Taiwan heavy bikes: red plate)
      var ff = new T.CylinderGeometry(MOTO.wrF + 0.03, MOTO.wrF + 0.03, 0.14, 10, 1, true, -0.2, 1.5);
      ff.rotateZ(HALF); ff.translate(0, MOTO.wrF, MOTO.axF);
      P.paint.push(ff);
      P.black.push(tubeBetween([0, 0.82, -0.85], [0, 0.66, -1.02], 0.012, 4));
      P.redPlate.push(xf(new T.BoxGeometry(0.21, 0.12, 0.01), 0, 0.64, -1.03, 0.35, 0, 0));
      P.headLamp.push(xf(new T.BoxGeometry(0.17, 0.08, 0.04), 0, 0.99, 0.79, -0.2, 0, 0));
      P.tailBar.push(xf(new T.BoxGeometry(0.12, 0.03, 0.03), 0, 0.97, -1.025, 0.3, 0, 0));
      wheelsInto(P, wheelGeos('motoF', { r: MOTO.wrF, w: 0.12, rimR: 0.2159, seg: 12, spokes: 3, twin: true, lo: true, spokeW: 0.032 }),
        [[0, MOTO.wrF, MOTO.axF, 1]]);
      wheelsInto(P, wheelGeos('motoR', { r: MOTO.wrR, w: 0.19, rimR: 0.2159, seg: 12, spokes: 3, twin: true, lo: true, spokeW: 0.032 }),
        [[0, MOTO.wrR, MOTO.axR, 1]]);
      return { parts: classify(P), paint: merge(P.paint) };
    });
  }
  function makeMotorcycle(color, seed) {
    var kit = motoKit(), g = new T.Group();
    var paint = paintMat(color === undefined || color === null ? 0x1f4fb4 : color, 0.35, 0.3);
    var body = new T.Mesh(kit.paint, paint); body.name = 'paint'; g.add(body);
    partsToGroup(g, kit.parts);
    var s = riderSpec(seed === undefined ? nextSeed() : seed, { helmet: 'full', bag: false, sleeve: 'long', legs: 'pants',
      bottom: 0x16171a, shoes: 0x111111 });
    var r = rng(s.seed + 5);
    s.top = pick(r, [0x16171a, 0x16171a, 0x2a2d33, 0x7a1d1d, 0x1d3a6e]);
    var h = buildHuman(s, riderMat(paint, 0.5));
    g.add(h.group);
    poseRider(h, g, { seat: [0, 0.87, -0.32], lean: 0.75, hands: [[0.31, 1.06, 0.42], [-0.31, 1.06, 0.42]],
      feet: [[0.20, 0.47, -0.26], [-0.20, 0.47, -0.26]], footPitch: -0.35, headPitch: 0.1,
      kneePole: new T.Vector3(1, 0.25, 0).normalize() });
    g.name = 'motorcycle';
    return finish(g, paint, { w: MOTO.W, l: MOTO.L, h: riderTop(h) }, riderTop(h) + 0.35);
  }

  // city bicycle (YouBike-like step-through) with a pedalling rider
  var BIKE = { L: 1.75, W: 0.60, axF: 0.54, axR: -0.54, wr: 0.33, bb: [-0.02, 0.29], crank: 0.17 };
  function bikeWheel() {
    return cached('bikeWheel', function () {
      var r = BIKE.wr, i, parts = [];
      // 20 tubular segments: a ring vertex sits exactly at the contact point (no floating)
      var tyre = new T.TorusGeometry(r - 0.018, 0.018, 4, 20); tyre.rotateY(HALF);
      var rim = new T.TorusGeometry(r - 0.04, 0.009, 3, 20); rim.rotateY(HALF);
      parts.push(rim);
      for (i = 0; i < 12; i++) {
        var a = i / 12 * PI * 2, sp = new T.BoxGeometry(0.003, r - 0.06, 0.003);
        sp.translate(0, (r - 0.06) / 2 + 0.015, 0);
        parts.push(xf(sp, (i % 2 ? 1 : -1) * 0.012, 0, 0, a, 0, 0));
      }
      var hub = new T.CylinderGeometry(0.02, 0.02, 0.09, 8, 1); hub.rotateZ(HALF);
      parts.push(hub);
      return { tyre: flat(tyre), rim: merge(parts) };
    });
  }
  function bikeKit() {
    return cached('bike', function () {
      var P = { paint: [], black: [], steel: [], chrome: [], headLamp: [], tailBar: [], plastic: [], seat: [], tyre: [], rim: [] }, sx;
      var bb = [0, BIKE.bb[1], BIKE.bb[0]], ht0 = [0, 0.70, 0.40], ht1 = [0, 0.90, 0.36], st1 = [0, 0.86, -0.20];
      P.paint.push(tubeBetween(ht1, ht0, 0.024, 8));
      P.paint.push(tubeBetween(ht0, [0, 0.42, 0.18], 0.026, 8), tubeBetween([0, 0.42, 0.18], bb, 0.026, 8));  // curved step-through down tube
      P.paint.push(tubeBetween(bb, st1, 0.022, 8));
      for (sx = -1; sx <= 1; sx += 2) {
        P.paint.push(tubeBetween([sx * 0.01, bb[1], bb[2]], [sx * 0.06, BIKE.wr, BIKE.axR], 0.012, 5));
        P.paint.push(tubeBetween([sx * 0.01, 0.80, -0.19], [sx * 0.06, BIKE.wr, BIKE.axR], 0.011, 5));
        P.paint.push(tubeBetween([sx * 0.015, 0.70, 0.40], [sx * 0.05, BIKE.wr, BIKE.axF], 0.013, 5));
      }
      // seat post + saddle, stem + swept bars + grips, basket, fenders, lamps, chainring
      P.steel.push(tubeBetween(st1, [0, 0.89, -0.235], 0.014, 6));
      P.seat.push(xf(rbox(0.17, 0.06, 0.26, 0.03, 1), 0, 0.90, -0.25));
      P.steel.push(tubeBetween(ht1, [0, 1.06, 0.33], 0.014, 6));
      P.steel.push(tubeBetween([0, 1.06, 0.33], [0.24, 1.07, 0.24], 0.012, 5), tubeBetween([0, 1.06, 0.33], [-0.24, 1.07, 0.24], 0.012, 5));
      P.black.push(tubeBetween([0.22, 1.07, 0.25], [0.30, 1.07, 0.21], 0.018, 5), tubeBetween([-0.22, 1.07, 0.25], [-0.30, 1.07, 0.21], 0.018, 5));
      var basket = rbox(0.36, 0.22, 0.28, 0.02, 1);
      P.black.push(xf(basket, 0, 0.90, 0.56));
      P.steel.push(tubeBetween([0, 0.76, 0.42], [0, 0.80, 0.47], 0.012, 4), tubeBetween([0.1, 0.80, 0.62], [0.05, BIKE.wr, BIKE.axF], 0.006, 4),
        tubeBetween([-0.1, 0.80, 0.62], [-0.05, BIKE.wr, BIKE.axF], 0.006, 4));
      var fen = new T.CylinderGeometry(BIKE.wr + 0.02, BIKE.wr + 0.02, 0.05, 12, 1, true, -0.3, 1.9);
      fen.rotateZ(HALF);
      P.paint.push(xf(fen, 0, BIKE.wr, BIKE.axF), xf(fen, 0, BIKE.wr, BIKE.axR, 0, PI, 0));
      P.headLamp.push(xf(new T.BoxGeometry(0.07, 0.05, 0.04), 0, 0.76, 0.71));
      P.tailBar.push(xf(new T.BoxGeometry(0.06, 0.04, 0.03), 0, 0.625, -0.77, -0.7, 0, 0));
      var ring = new T.CylinderGeometry(0.10, 0.10, 0.008, 16, 1); ring.rotateZ(HALF);
      P.steel.push(xf(ring, -0.05, bb[1], bb[2]));
      P.black.push(tubeBetween([-0.055, bb[1] + 0.1, bb[2]], [-0.055, BIKE.wr + 0.03, BIKE.axR], 0.006, 4),
        tubeBetween([-0.055, bb[1] - 0.1, bb[2]], [-0.055, BIKE.wr - 0.03, BIKE.axR], 0.006, 4));
      var wg = bikeWheel();
      P.tyre.push(xf(wg.tyre, 0, BIKE.wr, BIKE.axF), xf(wg.tyre, 0, BIKE.wr, BIKE.axR));
      P.steel.push(xf(wg.rim, 0, BIKE.wr, BIKE.axF), xf(wg.rim, 0, BIKE.wr, BIKE.axR));
      // crank (animated): arms + pedals, built around the bottom bracket axis
      var crank = merge([xf(new T.BoxGeometry(0.02, BIKE.crank, 0.03), 0.08, -BIKE.crank / 2, 0),
        xf(new T.BoxGeometry(0.02, BIKE.crank, 0.03), -0.08, BIKE.crank / 2, 0),
        xf(new T.BoxGeometry(0.09, 0.02, 0.06), 0.13, -BIKE.crank, 0), xf(new T.BoxGeometry(0.09, 0.02, 0.06), -0.13, BIKE.crank, 0),
        xf(new T.CylinderGeometry(0.015, 0.015, 0.18, 6, 1), 0, 0, 0, 0, 0, HALF)]);
      return { parts: classify(P), paint: merge(P.paint), crank: mergeC([{ list: [crank], col: linCol(PART.black[1]) }], true) };
    });
  }
  function makeBicycle(color, seed) {
    var kit = bikeKit(), g = new T.Group(), i;
    var paint = paintMat(color === undefined || color === null ? 0xf2a51a : color, 0.2, 0.4);
    var body = new T.Mesh(kit.paint, paint); body.name = 'paint'; g.add(body);
    partsToGroup(g, kit.parts);
    var crank = new T.Mesh(kit.crank, CLS.trim());
    crank.position.set(0, BIKE.bb[1], BIKE.bb[0]);
    g.add(crank);
    var s = riderSpec(seed === undefined ? nextSeed() : seed, { bag: false });
    var h = buildHuman(s, riderMat(paint));
    g.add(h.group);
    var cfg = { seat: [0, 0.93, -0.27], lean: 0.32, hands: [[0.27, 1.09, 0.17], [-0.27, 1.09, 0.17]], feet: [[0, 0, 0], [0, 0, 0]],
      footPitch: 0.05, headPitch: 0.05 };
    poseRider(h, g, cfg);
    // pre-solve the legs for 16 crank angles; animate() slerps between them
    var B = h.b, N = 16, keys = [], legBones = [B.thL, B.knL, B.anL, B.thR, B.knR, B.anR];
    function feetAt(a) {
      var y = BIKE.bb[1], z = BIKE.bb[0], c = BIKE.crank;
      // crank.rotation.x = a puts the left pedal at (y - c cos a, z - c sin a); ankle sits above/behind the ball of the foot
      cfg.feet[0] = [0.13, y - c * Math.cos(a) + 0.075, z - c * Math.sin(a) - 0.09];
      cfg.feet[1] = [-0.13, y + c * Math.cos(a) + 0.075, z + c * Math.sin(a) - 0.09];
      cfg.footPitch = 0.12 * Math.cos(a);
    }
    for (i = 0; i < N; i++) {
      feetAt(i / N * PI * 2);
      poseLimbs(h, g, cfg);
      var k = [];
      for (var j = 0; j < legBones.length; j++) { k.push(legBones[j].quaternion.clone()); }
      keys.push(k);
    }
    var st = { a: 0, lastT: null };
    function apply(a) {
      a = ((a % (PI * 2)) + PI * 2) % (PI * 2);
      var f = a / (PI * 2) * N, i0 = Math.floor(f) % N, i1 = (i0 + 1) % N, u = f - Math.floor(f);
      for (var j = 0; j < legBones.length; j++) { legBones[j].quaternion.copy(keys[i0][j]).slerp(keys[i1][j], u); }
      crank.rotation.x = a;
    }
    apply(0.6);
    g.userData.animate = function (t, speed) {
      var dt = st.lastT === null ? 0 : clamp(t - st.lastT, 0, 0.2);
      st.lastT = t;
      st.a += dt * (speed || 0) / BIKE.wr / 2.2;
      apply(st.a + 0.6);
    };
    g.name = 'bicycle';
    return finish(g, paint, { w: BIKE.W, l: BIKE.L, h: riderTop(h) }, riderTop(h) + 0.35);
  }

  // ---------------------------------------------------------------- traffic cone (Taiwan, 0.70 m)
  function coneKit() {
    return cached('cone', function () {
      var y0 = 0.045, y1 = 0.70, r0 = 0.150, r1 = 0.026;
      function seg(a, b, top) {
        var pts = [[r0 + (r1 - r0) * (a - y0) / (y1 - y0), a], [r0 + (r1 - r0) * (b - y0) / (y1 - y0), b]];
        if (top) { pts.push([0.012, y1 + 0.006], [0, y1 + 0.008]); }
        return lathe(pts, 16);
      }
      var orange = [seg(y0, 0.24), seg(0.33, 0.43), seg(0.53, y1, true)];
      var white = [seg(0.24, 0.33), seg(0.43, 0.53)];
      var base = rbox(0.38, 0.045, 0.38, 0.03, 1); base.translate(0, 0.0225, 0);
      var lip = lathe([[0.17, 0.045], [0.155, 0.06]], 16);
      return { orange: merge(orange), parts: classify({ reflectWhite: white, black: [base, lip] }) };
    });
  }
  function makeCone() {
    var k = coneKit(), g = new T.Group();
    var paint = paintMat(0xff5a12, 0, 0.55);
    paint.emissive.setHex(0x3a0c00).convertSRGBToLinear(); paint.userData.baseEm = paint.emissive.getHex(); paint.userData.baseEmI = 1;
    g.add(new T.Mesh(k.orange, paint));
    partsToGroup(g, k.parts);
    g.name = 'cone';
    return finish(g, paint, { w: 0.38, l: 0.38, h: 0.70 }, 1.05);
  }

  // ---------------------------------------------------------------- traffic light (Taiwan: pole + mast arm + horizontal head)
  // Lamps face local +Z (index.html turns the group with rotation.y = atan2(-X, -Z), which points +Z at the ego;
  // do NOT add the PI used for vehicles). The pole stands at the origin (roadside) and the arm reaches over the road
  // toward -X: userData.headOffset = head centre, userData.reach = arm length. dims = the pole's footprint only.
  // setState('red' | 'yellow' | 'green' | 'off', secondsLeft?) toggles the lamps, the countdown box and the
  // pedestrian signal on the pole (green man while the vehicle light is red).
  var LAMP = { red: 0xff2a1a, yellow: 0xffb000, green: 0x00d070 };
  var TL = { hx: -3.75, hy: 5.47, arm: 5.85 };
  function lampMat(c, on) {
    if (on) { return mat('lampOn:' + c, { color: LAMP[c], toneMapped: false }, true); }
    var dim = new T.Color(LAMP[c]).multiplyScalar(0.09).getHex();
    return mat('lampOff:' + c, { color: dim, metalness: 0.2, roughness: 0.2, envMapIntensity: 0.9 });
  }
  function pedIcon() {
    if (REG.pedGo) { return; }
    REG.pedGo = [264, 168, 328, 232]; REG.pedStop = [336, 168, 400, 232];
    var c = atlasTex().image, x = c && c.getContext ? c.getContext('2d') : null;
    if (!x || !x.fillRect) { return; }
    function man(r, col, walking) {
      var cx = (r[0] + r[2]) / 2, top = r[1];
      x.fillStyle = '#050607'; x.fillRect(r[0], r[1], 64, 64);
      x.fillStyle = col; x.strokeStyle = col; x.lineCap = 'round'; x.lineWidth = 7;
      x.beginPath(); x.arc(cx, top + 12, 6, 0, PI * 2); x.fill();
      x.beginPath(); x.moveTo(cx, top + 21); x.lineTo(cx + (walking ? 2 : 0), top + 39);
      if (walking) {
        x.moveTo(cx + 2, top + 39); x.lineTo(cx - 9, top + 56); x.moveTo(cx + 2, top + 39); x.lineTo(cx + 12, top + 55);
        x.moveTo(cx, top + 25); x.lineTo(cx - 10, top + 35); x.moveTo(cx, top + 25); x.lineTo(cx + 10, top + 31);
      } else {
        x.moveTo(cx - 4, top + 39); x.lineTo(cx - 5, top + 57); x.moveTo(cx + 4, top + 39); x.lineTo(cx + 5, top + 57);
        x.moveTo(cx - 3, top + 23); x.lineTo(cx - 11, top + 38); x.moveTo(cx + 3, top + 23); x.lineTo(cx + 11, top + 38);
      }
      x.stroke();
    }
    man(REG.pedGo, '#22ff8a', true);
    man(REG.pedStop, '#ff3b2a', false);
    atlasTex().needsUpdate = true;
  }
  function pedMat(on) {
    return mat('ped:' + (on ? 1 : 0), { color: on ? 0xffffff : 0x1c1c1c, map: atlasTex(), toneMapped: false }, true);
  }
  function signalKit() {
    return cached('signal', function () {
      pedIcon();
      var hx = TL.hx, hy = TL.hy, hz = 0.0, i, P = { black: [], signalYellow: [], steel: [] };
      var pole = new T.CylinderGeometry(0.085, 0.11, 6.0, 12, 1); pole.translate(0, 3.0, 0);
      var cap = new T.SphereGeometry(0.09, 8, 4, 0, PI * 2, 0, HALF); cap.translate(0, 6.0, 0);
      var plate = rbox(0.42, 0.04, 0.42, 0.02, 1); plate.translate(0, 0.02, 0);
      var arm = new T.CylinderGeometry(0.05, 0.075, 4.3, 10, 1); arm.rotateZ(HALF); arm.translate(-2.15, TL.arm, 0);
      var brace = tubeBetween([-0.05, 5.15, 0], [-1.6, TL.arm - 0.03, 0], 0.03, 6);
      var hang = tubeBetween([hx, TL.arm, 0], [hx, hy + 0.2, 0], 0.025, 6);
      var poleG = merge([pole, cap, plate, arm, brace, hang]);
      // head: black housing, back plate with yellow edge (its top stays below the arm), visors over each lamp
      P.black.push(xf(rbox(1.08, 0.40, 0.26, 0.05, 1), hx, hy, hz));
      var bp = roundRectShape(1.42, 0.66, 0.08); bp.holes.push(rrPath(1.30, 0.54, 0.05));
      P.signalYellow.push(xf(extrudeFlat(bp, 0.03, 0.006, 0.004, 1, 4), hx, hy, hz - 0.15));
      P.black.push(xf(rbox(1.32, 0.56, 0.02, 0.04, 1), hx, hy, hz - 0.155));
      // visor: open half-tube along +z whose solid part is ABOVE the lamp (theta from HALF-0.2)
      var visor = new T.CylinderGeometry(0.155, 0.155, 0.20, 12, 1, true, HALF - 0.2, PI + 0.4);
      visor.rotateX(HALF);
      var visorIn = mirrorX(visor);
      for (i = -1; i <= 1; i++) {
        P.black.push(xf(visor, hx + i * 0.34, hy, hz + 0.21), xf(visorIn, hx + i * 0.34, hy, hz + 0.21));
      }
      // countdown box beside the head (digits drawn per instance) and the pedestrian head on the pole
      P.black.push(xf(rbox(0.46, 0.34, 0.18, 0.03, 1), hx + 0.86, hy, hz));
      P.black.push(xf(rbox(0.30, 0.62, 0.20, 0.03, 1), -0.24, 2.75, 0), xf(new T.BoxGeometry(0.12, 0.08, 0.06), -0.12, 2.75, 0));
      var lamp = new T.CircleGeometry(0.125, 16), lens = [];
      for (i = -1; i <= 1; i++) { lens.push(xf(lamp, hx + i * 0.34, hy, hz + 0.133)); }
      var digits = new T.PlaneGeometry(0.40, 0.28); digits.translate(hx + 0.86, hy, hz + 0.093);
      function icon(region, y) {
        var g = new T.PlaneGeometry(0.22, 0.22), a = g.attributes.uv, uv = regUV(region);
        for (var k = 0; k < a.count; k++) { a.setXY(k, lerp(uv[0], uv[2], a.getX(k)), lerp(uv[1], uv[3], a.getY(k))); }
        g.rotateY(-HALF); g.translate(-0.391, y, 0);
        return g;
      }
      return { pole: poleG, lens: lens, digits: digits, pedStop: icon('pedStop', 2.89), pedGo: icon('pedGo', 2.61), parts: classify(P) };
    });
  }
  function makeTrafficLight() {
    var k = signalKit(), g = new T.Group(), i;
    var paint = paintMat(0x8d959e, 0.5, 0.45);
    g.add(new T.Mesh(k.pole, paint));
    partsToGroup(g, k.parts);
    var names = ['red', 'yellow', 'green'], lamps = {};
    for (i = 0; i < 3; i++) {
      var m = new T.Mesh(k.lens[i], lampMat(names[i], false));
      m.name = 'lamp-' + names[i];
      lamps[names[i]] = m;
      g.add(m);
    }
    var pedStop = new T.Mesh(k.pedStop, pedMat(false)), pedGo = new T.Mesh(k.pedGo, pedMat(false));
    g.add(pedStop, pedGo);
    // countdown digits: small per-instance canvas, redrawn only when the number or colour changes
    var cv = document.createElement('canvas'); cv.width = 64; cv.height = 32;
    var cx = cv.getContext ? cv.getContext('2d') : null, tex = new T.CanvasTexture(cv), shown = '';
    tex.encoding = T.sRGBEncoding; tex.minFilter = T.LinearFilter; tex.generateMipmaps = false;
    var digM = new T.MeshBasicMaterial({ map: tex, toneMapped: false });
    g.add(new T.Mesh(k.digits, digM));
    function drawCount(st, sec) {
      var txt = sec === undefined || sec === null || st === 'off' ? '' : String(Math.max(0, Math.min(99, Math.ceil(sec))));
      var key = st + ':' + txt;
      if (key === shown || !cx || !cx.fillRect) { return; }
      shown = key;
      cx.fillStyle = '#060708'; cx.fillRect(0, 0, 64, 32);
      if (txt) {
        cx.fillStyle = st === 'green' ? '#2bff8f' : st === 'yellow' ? '#ffc21a' : '#ff3322';
        cx.font = 'bold 28px "Courier New",monospace'; cx.textAlign = 'center'; cx.textBaseline = 'middle';
        cx.fillText(txt, 32, 17);
      }
      tex.needsUpdate = true;
    }
    g.userData.state = 'off';
    g.userData.setState = function (st, secondsLeft) {
      var s2 = LAMP[st] ? st : 'off';
      for (var n in lamps) { if (lamps.hasOwnProperty(n)) { lamps[n].material = lampMat(n, n === s2); } }
      pedGo.material = pedMat(s2 === 'red');
      pedStop.material = pedMat(s2 === 'green' || s2 === 'yellow');
      drawCount(s2, secondsLeft);
      g.userData.state = s2;
    };
    g.userData.setState('red');
    g.userData.headOffset = [TL.hx, TL.hy, 0];
    g.userData.reach = -TL.hx;
    g.name = 'trafficLight';
    return finish(g, paint, { w: 0.42, l: 0.42, h: 6.1 }, 6.5);
  }

  // ---------------------------------------------------------------- prewarm
  // Build every shared kit (ray probing makes the first E60 / van cost tens of ms) and, when a renderer and the
  // live scene are passed, compile the shader programs too, so the first vehicle that appears does not hitch.
  function prewarm(renderer, scene, camera) {
    var t0 = (window.performance || Date).now(), list = [], i;
    e60Kit(); carKit(SEDAN); carKit(VAN); truckKit(); busKit(); scooterKit(); motoKit(); bikeKit(); coneKit(); signalKit();
    for (i = 0; i < 24; i++) { humanGeo(personSpec(101 + i * 7919)); }
    if (renderer && scene && renderer.compile) {
      var tmp = new T.Group();
      list = [makeE60(), makeSedan(), makeTruck(), makeBus(), makeScooter(), makeMotorcycle(), makeBicycle(), makePerson(), makeCone(), makeTrafficLight()];
      for (i = 0; i < list.length; i++) { list[i].position.set(i * 3, -50, 0); tmp.add(list[i]); }
      scene.add(tmp);
      try { renderer.compile(scene, camera || new T.PerspectiveCamera()); } catch (e) { /* ignore */ }
      scene.remove(tmp);
    }
    return Math.round((window.performance || Date).now() - t0);
  }

  // ---------------------------------------------------------------- export
  window.AVM_MODELS = {
    version: '1.1.0',
    makeE60: makeE60,
    makeSedan: makeSedan,
    makeVan: makeVan,
    makeTruck: makeTruck,
    makeBus: makeBus,
    makeScooter: makeScooter,
    makeMotorcycle: makeMotorcycle,
    makeBicycle: makeBicycle,
    makePerson: makePerson,
    makeCone: makeCone,
    makeTrafficLight: makeTrafficLight,
    prewarm: prewarm,
    triangles: tris
  };
})();
