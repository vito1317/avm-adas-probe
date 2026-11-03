#!/usr/bin/env node
/*
 * 輔助線幾何一致性：assets/bev3d/vehicle3d.js 的 guides() / turningRadius()
 * 對照 src/test/resources/vehicle_guides_fixture.json（VehicleSignalsTest 用 Kotlin BicycleModel 產生）。
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

fx.cases.forEach(function (c, ci) {
  var tag = '#' + ci + ' steer=' + c.steer + (c.reverse ? ' 倒車' : ' 前進');
  var g = V.guides(c.steer, c.reverse, c.opts);
  var r = V.turningRadius(c.steer, c.opts);
  if (c.radius === null) { if (r !== Infinity) bad(tag + ' radius 應為直行，JS=' + r); }
  else near(r, c.radius, tag + ' radius');
  if (g.left.length !== c.left.length || g.right.length !== c.right.length) {
    bad(tag + ' 點數 JS=' + g.left.length + ' Kotlin=' + c.left.length);
    return;
  }
  for (var i = 0; i < c.left.length; i++) {
    near(g.dist[i], c.dist[i], tag + ' dist[' + i + ']');
    near(g.left[i][0], c.left[i][0], tag + ' left[' + i + '].x');
    near(g.left[i][1], c.left[i][1], tag + ' left[' + i + '].z');
    near(g.right[i][0], c.right[i][0], tag + ' right[' + i + '].x');
    near(g.right[i][1], c.right[i][1], tag + ' right[' + i + '].z');
  }
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

console.log('vehicle3d.js 輔助線 vs Kotlin：' + fx.cases.length + ' 組、' + checked + ' 個數值，最大誤差 ' +
  worst.toExponential(2) + (fails ? '，' + fails + ' 個不一致' : '，一致'));
process.exit(fails ? 1 : 0);
