#!/usr/bin/env node
/*
 * 輔助線幾何一致性：assets/bev3d/vehicle3d.js 的 guides() / turningRadius()
 * 對照 src/test/resources/vehicle_guides_fixture.json（VehicleSignalsTest 用 Kotlin BicycleModel 產生），
 * 另外檢查 vehicle3d.js 的顯示邏輯（輔助線模式、讀數側、雷達分段）。
 * 用法：node app/src/test/js/vehicle_guides_check.js（VehicleSignalsTest.jsGuidesMatchKotlin 也會自動跑）
 */
'use strict';
var fs = require('fs');
var path = require('path');

var V = require(path.join(__dirname, '..', '..', 'main', 'assets', 'bev3d', 'vehicle3d.js'));
var fx = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'resources', 'vehicle_guides_fixture.json'), 'utf8'));

var TOL = 1e-9, worst = 0, fails = 0, checked = 0;
function bad(msg) { if (fails++ < 12) console.error('不一致：' + msg); }
function near(a, b, what) {
  checked++;
  var d = Math.abs(a - b);
  if (d > worst) worst = d;
  if (!(d <= TOL)) bad(what + ' JS=' + a + ' Kotlin=' + b);
}
function pts(js, kt, what) {
  if (kt === null || js === null) { if (kt !== js) bad(what + ' JS=' + (js && js.length) + ' Kotlin=' + (kt && kt.length)); return; }
  if (js.length !== kt.length) { bad(what + ' 點數 JS=' + js.length + ' Kotlin=' + kt.length); return; }
  for (var i = 0; i < kt.length; i++) {
    near(js[i][0], kt[i][0], what + '[' + i + '].x');
    near(js[i][1], kt[i][1], what + '[' + i + '].z');
  }
}

fx.cases.forEach(function (c, ci) {
  var tag = '#' + ci + ' steer=' + c.steer + (c.reverse ? ' 倒車' : ' 前進');
  var g = V.guides(c.steer, c.reverse, c.opts);
  var r = V.turningRadius(c.steer, c.opts);
  if (c.radius === null) { if (r !== Infinity) bad(tag + ' radius 應為直行，JS=' + r); }
  else near(r, c.radius, tag + ' radius');
  if (g.dist.length !== c.dist.length) { bad(tag + ' 取樣數 JS=' + g.dist.length + ' Kotlin=' + c.dist.length); return; }
  for (var i = 0; i < c.dist.length; i++) near(g.dist[i], c.dist[i], tag + ' dist[' + i + ']');
  pts(g.left, c.left, tag + ' left');
  pts(g.right, c.right, tag + ' right');
  if (g.innerSide !== c.innerSide) bad(tag + ' innerSide JS=' + g.innerSide + ' Kotlin=' + c.innerSide);
  pts(g.inner, c.inner, tag + ' inner');
  pts(g.swing, c.swing, tag + ' swing');
  if (g.markers.length !== c.markers.length) { bad(tag + ' 標記數 JS=' + g.markers.length + ' Kotlin=' + c.markers.length); return; }
  c.markers.forEach(function (m, k) {
    var j = g.markers[k];
    near(j.d, m[0], tag + ' marker' + k + '.d');
    near(j.l[0], m[1], tag + ' marker' + k + '.lx');
    near(j.l[1], m[2], tag + ' marker' + k + '.lz');
    near(j.r[0], m[3], tag + ' marker' + k + '.rx');
    near(j.r[1], m[4], tag + ' marker' + k + '.rz');
  });
});
var geoChecked = checked;

// ---------- 顯示邏輯 ----------
function eq(act, exp, what) { checked++; if (act !== exp) bad(what + '：得到 ' + act + '，應為 ' + exp); }
var M = V.guideMode;
eq(M(null), null, 'guideMode(null)');
eq(M({ gear: 'R' }), 'rev', 'R 檔');
eq(M({ gear: 'R', speed: 30 }), 'rev', 'R 檔不看車速');
eq(M({ gear: 'D' }), 'fwd', 'D 檔車速不明');
eq(M({ gear: 'D', speed: 8 }), 'fwd', 'D 檔低速');
eq(M({ gear: 'D', speed: 15 }), null, 'D 檔 15 km/h');
eq(M({ gear: 'N', speed: 0 }), 'fwd', 'N 檔');
eq(M({ gear: 'P', speed: 0 }), null, 'P 檔（fwdInPark 預設 false）');
eq(M({ gear: 'P', speed: 0 }, { fwdInPark: true }), 'fwd', 'P 檔 + fwdInPark');
eq(M({ turn: 'L' }), null, '只有方向燈');
eq(M({ turn: 'L', pdc: { f: [99, 99, 99, 0.5], r: null } }), null, '只有方向燈 + 雷達');
eq(M({ speed: 8 }), null, '檔位不明、方向盤不明');
eq(M({ speed: 8, steer: 120 }), 'fwd', '檔位不明、低速、方向盤已知');
eq(M({ speed: 40, steer: 120 }), null, '檔位不明、高速');
eq(M({ steer: 120 }), null, '檔位不明、車速不明');
var S = V.readoutSide;
eq(S('R', 0.5, 0.9), 'r', 'R 檔優先後方');
eq(S('R', 0.25, 0.9), 'f', 'R 檔前方連續紅');
eq(S('R', 0.25, 0.2), 'r', 'R 檔前後都很近取後');
eq(S('R', 0.4, null), 'f', 'R 檔後方無障礙');
eq(S('D', 0.9, 0.5), 'f', 'D 檔優先前方');
eq(S('D', 0.9, 0.2), 'r', 'D 檔後方連續紅');
eq(S(null, 0.9, 0.5), 'r', '檔位不明取最近');
eq(S('P', null, null), null, '都沒有');
var PS = [[0.1, 0], [0.29, 0], [0.3, 1], [0.59, 1], [0.6, 2], [0.95, 3], [1.19, 3], [1.2, 4], [1.7, 5], [1.99, 5], [2.0, -1], [99, -1], [null, -1], [NaN, -1]];
PS.forEach(function (p) { eq(V.pdcSeg(p[0]), p[1], 'pdcSeg(' + p[0] + ')'); });
// 每段亮帶至少 0.24 m，分界對齊顏色分界
for (var k = 1; k < V.PDC_SEG.length; k++) { checked++; if (V.PDC_SEG[k] - V.PDC_SEG[k - 1] - 0.06 < 0.24 - 1e-9) bad('PDC_SEG 第 ' + k + ' 段太窄'); }
[0.3, 0.6, 1.2].forEach(function (b) { checked++; if (V.PDC_SEG.indexOf(b) < 0) bad('PDC_SEG 缺顏色分界 ' + b); });

console.log('vehicle3d.js 輔助線 vs Kotlin：' + fx.cases.length + ' 組、' + geoChecked + ' 個數值，最大誤差 ' +
  worst.toExponential(2) + '；顯示邏輯 ' + (checked - geoChecked) + ' 項' + (fails ? '，' + fails + ' 個不一致' : '，一致'));
process.exit(fails ? 1 : 0);
