# AVM ADAS Probe v0.3 — shared design spec

Repo: /Users/vito/development/avm-adas-probe (Android app, Kotlin, package `com.vito.avmprobe`,
AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 26, JDK 17 at /opt/homebrew/opt/openjdk@17,
Android SDK at /Users/vito/Library/Android/sdk). The app runs on an aftermarket Android head unit
(Unisoc UIS7862, Mali-G52, 1920x720 screen) in a BMW E60 (length 4.84 m, width 1.85 m).
User-facing strings are Traditional Chinese (Taiwan). Code comments: match the existing files
(short zh-TW comments, sparse).

## 0. What v0.3 adds

1. **Camera2 direct mode.** Camera2 id "2" delivers 1920x1080 @ 25 fps: a 2x2 mosaic of the four raw
   fisheye cameras. Quad index q: 0 = top-left = FRONT, 1 = top-right = REAR, 2 = bottom-left = LEFT,
   3 = bottom-right = RIGHT. Our own screen (no 全景360 needed): YOLO per quad, fisheye→ground
   geometry, own stitched BEV (bird's-eye view) image, 3D panel.
2. **Ground perception on a metric BEV image** (works on 全景360's BEV in screen-capture mode now, and
   on our own BEV in Camera2 mode): lane markings (white / yellow / red), road edges, traffic cones.
   Rendered in 3D as a clean "virtual road" (Tesla style).
3. **Traffic lights:** COCO class 9 from the raw front camera, plus lamp-state classification
   (red / yellow / green) → HUD + 3D.

## 1. Coordinate conventions (everyone)

- Vehicle frame: origin = ground point at the centre of the car footprint.
  x = right (m), y = up (m), z = forward (m).
- Ego footprint: |x| ≤ 0.925, |z| ≤ 2.42 (car 1.85 x 4.84 m).
- BEV images: row 0 = front, column 0 = left. Square pixels, `mpp` metres per pixel.
  Pixel (i = column, j = row) centre ↔ vehicle frame:
  `x = (i + 0.5 - cx) * mpp`, `z = (cy - (j + 0.5)) * mpp`, where (cx, cy) is the pixel position of
  the vehicle origin.
- 3D scene (index.html, three.js r128): scene x = x, scene z = −z (forward is −Z). Unchanged.
- Angles in JSON are degrees; payload `yaw` is radians.

## 2. Camera model + calibration file

### 2.1 `app/src/main/assets/avm_calib.json`

```json
{
  "version": 1,
  "note": "預設值（未校正）。fx/fy 以象限寬度為單位；角度為度。",
  "car": { "length": 4.84, "width": 1.85 },
  "cams": {
    "front": { "quad": 0, "mirror": false, "cx": 0.5, "cy": 0.5, "fx": 0.265, "fy": 0.265,
               "k1": 0.0, "k2": 0.0, "maxDeg": 95,
               "pos": [0.0, 0.55, 2.35], "yaw": 0, "pitch": 25, "roll": 0 },
    "rear":  { "quad": 1, "mirror": false, "cx": 0.5, "cy": 0.5, "fx": 0.265, "fy": 0.265,
               "k1": 0.0, "k2": 0.0, "maxDeg": 95,
               "pos": [0.0, 0.95, -2.40], "yaw": 180, "pitch": 35, "roll": 0 },
    "left":  { "quad": 2, "mirror": false, "cx": 0.5, "cy": 0.5, "fx": 0.265, "fy": 0.265,
               "k1": 0.0, "k2": 0.0, "maxDeg": 95,
               "pos": [-1.0, 1.0, 0.85], "yaw": -90, "pitch": 55, "roll": 0 },
    "right": { "quad": 3, "mirror": false, "cx": 0.5, "cy": 0.5, "fx": 0.265, "fy": 0.265,
               "k1": 0.0, "k2": 0.0, "maxDeg": 95,
               "pos": [1.0, 1.0, 0.85], "yaw": 90, "pitch": 55, "roll": 0 }
  },
  "bev": { "xMin": -6.0, "xMax": 6.0, "zMin": -7.0, "zMax": 7.0, "mpp": 0.04 }
}
```

### 2.2 Projection (exact; Kotlin and Python must agree to 1e-6)

Frame W×H, quads QW = W/2, QH = H/2; quad q origin = ((q % 2)·QW, (q / 2)·QH).
Quad-local pixel (u, v), continuous coordinates (pixel centre of integer pixel k is k + 0.5).

**Pixel → ray**
1. if mirror: u ← QW − u
2. dx = (u − cx·QW) / (fx·QW); dy = (v − cy·QH) / (fy·QW)      ← both focal lengths scale with QW
3. rd = √(dx² + dy²). θ solves rd = θ(1 + k1θ² + k2θ⁴) (Newton from θ = rd, 10 iterations, θ ≥ 0;
   if k1 = k2 = 0 then θ = rd).
4. a = atan2(dy, dx); camera ray (x right, y down, z optical axis): rc = (sinθ cos a, sinθ sin a, cosθ)
5. vehicle ray rv = Ryaw(yaw) · Rpitch(pitch) · Rroll(roll) · B · rc, with
   - B(x, y, z) = (x, −y, z)
   - Rroll(ρ): (x, y, z) → (x cosρ − y sinρ, x sinρ + y cosρ, z)
   - Rpitch(φ): (x, y, z) → (x, y cosφ − z sinφ, y sinφ + z cosφ)    (φ > 0 tilts the axis down)
   - Ryaw(ψ): (x, y, z) → (x cosψ + z sinψ, y, −x sinψ + z cosψ)     (ψ = 90 → faces +x / right)
6. Ground hit: if rv.y < −1e-6: t = −pos.y / rv.y, G = pos + t·rv → (G.x, G.z); else none.

**Ground → pixel** (gx, gz): d = (gx − pos.x, −pos.y, gz − pos.z); rc = B·Rrollᵀ·Rpitchᵀ·Ryawᵀ·d
(transposes = inverse rotations; B is its own inverse), normalise; θ = acos(clamp(rc.z, −1, 1));
invalid if θ > maxDeg; a = atan2(rc.y, rc.x); rd = θ(1 + k1θ² + k2θ⁴);
u = cx·QW + fx·QW·rd·cos a; v = cy·QH + fy·QW·rd·sin a; if mirror: u ← QW − u;
valid iff 0 ≤ u < QW and 0 ≤ v < QH.

**BEV LUT.** bevW = round((xMax − xMin)/mpp), bevH = round((zMax − zMin)/mpp). For BEV pixel (i, j):
gx = xMin + (i + 0.5)·mpp, gz = zMax − (j + 0.5)·mpp. If inside the ego footprint grown by 0.05 m →
−1. Else among cameras with a valid projection pick the smallest θ; LUT value = frame pixel index
`(qy0 + floor(v)) * W + (qx0 + floor(u))`; no valid camera → −1.
Vehicle origin in the BEV image: cx = −xMin/mpp, cy = zMax/mpp.

### 2.3 `AvmGeometry.kt` (pure Kotlin — NO android.* imports; org.json is allowed)

```kotlin
data class CamParams(
    val name: String, val quad: Int, val mirror: Boolean,
    val cx: Double, val cy: Double, val fx: Double, val fy: Double,
    val k1: Double, val k2: Double, val maxDeg: Double,
    val px: Double, val py: Double, val pz: Double,
    val yaw: Double, val pitch: Double, val roll: Double   // degrees
)
data class BevSpec(val xMin: Double, val xMax: Double, val zMin: Double, val zMax: Double, val mpp: Double) {
    val width: Int; val height: Int        // round((max-min)/mpp)
    val originX: Double; val originY: Double  // vehicle origin in BEV pixels (cx, cy above)
}
class AvmGeometry(val cams: List<CamParams>, val bev: BevSpec, val carLength: Double, val carWidth: Double) {
    companion object { fun fromJson(text: String): AvmGeometry }
    fun cam(name: String): CamParams?
    fun camForQuad(q: Int): CamParams?
    fun pixelToRay(c: CamParams, u: Double, v: Double, qw: Int, qh: Int): DoubleArray      // unit (x,y,z)
    fun pixelToGround(c: CamParams, u: Double, v: Double, qw: Int, qh: Int): DoubleArray?  // (x,z) or null
    fun groundToPixel(c: CamParams, gx: Double, gz: Double, qw: Int, qh: Int): DoubleArray? // (u,v) or null
    fun buildBevLut(frameW: Int, frameH: Int): IntArray   // size bev.width*bev.height
}
```
Precompute rotation matrices once per camera (not per call).

## 3. Ground perception (pure Kotlin, NO android.* imports)

Input everywhere: `argb: IntArray` (0xAARRGGBB, row-major, row 0 = front), `w`, `h`, `mpp`,
`cx`, `cy` (vehicle origin in pixels), `exclude: List<FloatArray>` — rectangles in vehicle metres
`[xMin, zMin, xMax, zMax]` to ignore (ego footprint, detected vehicles; in the legacy screen mode the
caller also passes 全景360's guide-line strips). Pixels with R+G+B < 24 are "no coverage" (invalid).

**Primary input is our own stitched BEV from the raw Camera2 quads** (Camera2 mode is the main path;
the user confirmed the raw feed has no overlays). Its characteristics: the ego footprint is black,
corners/far areas may be black (no coverage), per-camera brightness/white-balance differs so there
are seams roughly along the car's diagonals, resolution degrades (blur, stretching) with distance
from the car, and tall objects smear radially away from the camera that saw them. Secondary input:
全景360's own BEV in screen-capture mode (also metric; has a rendered car icon in the centre and,
in reverse, yellow/red guide-line overlays — handled by the caller's exclude rects, not by you).

### 3.1 `RoadExtractor.kt`

```kotlin
data class RoadLine(val kind: Char /* 'w' white, 'y' yellow, 'r' red curb paint */, val dashed: Boolean,
                    val x1: Float, val z1: Float, val x2: Float, val z2: Float, val conf: Float)
data class RoadResult(val lines: List<RoadLine>, val edgeL: Float?, val edgeR: Float?, val ms: Long)
class RoadExtractor {
    fun extract(argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
                exclude: List<FloatArray>): RoadResult
}
class RoadTracker {   // temporal smoothing; call once per extract
    fun update(r: RoadResult, nowMs: Long): RoadResult
}
```
Reference algorithm (tune thresholds in the Python prototype, then port exactly):
1. Luma Y = (77R + 150G + 29B) >> 8. Colour tests: yellow (R ≥ 90, G ≥ 70, R−B ≥ 45, G−B ≥ 30,
   |R−G| ≤ 80); red (R ≥ 90, R−G ≥ 55, R−B ≥ 45); neutral (max−min ≤ 50).
2. Local background: mean Y over a K×K window of valid pixels only (integral images of Y and of the
   valid count), K = odd(round(0.5 m / mpp)). Top-hat T = Y − mean.
3. Candidates: white = neutral & T ≥ 28 & Y ≥ 110; yellow = yellow & T ≥ 10; red = red.
4. Connected components (8-conn); keep thin elongated ones: area ≥ 0.03 m², PCA width ≤ 0.35 m,
   length ≥ 3 × width. Drops car bodies, the 全景360 car icon, blobs.
5. Hough over kept pixels (1° θ bins, 0.05 m ρ bins); peaks ≥ 0.8 m of support; NMS ±6°, ±0.25 m;
   at most 8 lines. Per line: inliers within 0.08 m; project onto the line axis; split at gaps
   > 0.6 m; covered length ≥ 0.8 m; dashed = (≥ 2 segments) or (longest < 2.5 m);
   kind = majority class; endpoints = min/max projection; conf = min(1, covered / 4 m).
6. Road edges: road colour model from a seed band beside the car (|x| ∈ [1.2, 1.95], |z| ≤ 2.4,
   valid, not a candidate): median and MAD (floor 6) of Y, U = B−Y, V = R−Y. For rows every
   0.25 m in |z| ≤ 4, scan outward from |x| = 1.0: road if |ΔY| ≤ 3·MAD_Y+12 and |ΔU|, |ΔV| ≤
   3·MAD+10, or a marking candidate; edge = first x where ≥ 0.3 m of consecutive valid non-road
   starts; a row that reaches invalid/out-of-image first has no edge. edgeL/edgeR = median over rows
   with an edge, only if ≥ 30 % of scanned rows found one; else null. edgeL < 0 < edgeR.
7. RoadTracker: line state (kind, dashed, angle, signed perpendicular offset from origin, sMin, sMax).
   Match by kind & |Δangle| < 8° & |Δoffset| < 0.35 m; EMA α = 0.35; output a track when it was
   matched in ≥ 2 of its last 5 updates; drop after 800 ms unmatched. Edges: EMA α = 0.3, null after
   1500 ms without a value.
Budget: ≤ 15 ms for a 300×350 image on a phone-class CPU (avoid per-pixel allocation; reuse buffers
across calls).

### 3.2 `ConeDetector.kt` (traffic cones in BEV)

```kotlin
data class Cone(val x: Float, val z: Float, val conf: Float)
class ConeDetector {
    fun detect(argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
               exclude: List<FloatArray>): List<Cone>
}
```
Orange (traffic-cone) pixels, distinct from red curb paint (hue ≈ 0–8°) and yellow paint
(hue ≈ 40–60°): hue ≈ 8–32°, high saturation, R ≥ 140 — tune on synthetic + real colours.
Light morphological cleanup, connected components; accept compact blobs (area 0.03–1.5 m²,
reject long thin strips: length ≥ 1.2 m with width ≤ 0.2 m). Tall objects smear radially away from
the cameras in an IPM image, so the cone **base** = the component pixel closest to the ego footprint
rectangle; report that point. Merge cones closer than 0.4 m. conf from area & colour purity.

### 3.3 `TrafficLightState.kt`

```kotlin
object TrafficLightState {
    /** crop of one detected traffic-light box. Returns state "red"|"yellow"|"green"|"off" and confidence 0..1. */
    fun classify(argb: IntArray, w: Int, h: Int): Pair<String, Float>
}
```
Count bright (V high), saturated pixels per hue band (red: hue < 15° or > 340°; yellow 25–65°;
green 90–200° — LED greens are often cyan-ish); also use position along the housing's long axis
(red top/left, green bottom/right) as a tie-breaker. Must not call "red" on a dark housing.

## 4. 3D payload (Android → `window.setFrame(p)`), backwards compatible

```
{
  objs: [{ t: 'car'|'truck'|'moto'|'ped'|'cone', x, z, w, h, c, yaw? }],  // yaw: radians, optional
  wm, hm, flip, overlap,
  stat: { fps, inf, e2e, eng, res },
  ground?: "data:image/jpeg;base64,...",          // BEV texture, centred on ego, wm × hm metres
  road?: { lines: [{ k: 'w'|'y'|'r', d: 0|1, x1, z1, x2, z2, c }], el: number|null, er: number|null },
  tl?: { s: 'red'|'yellow'|'green'|'off', c, ax? },  // traffic light; ax = azimuth deg (+ = right)
  src?: 'screen'|'cam'
}
```
- `cone` objects behave like other objects (tracked, clearance warnings; radius 0.2 m).
- `yaw` (if present) overrides the w>h "parked sideways" heuristic.
- Without `road` for > 1.2 s → virtual road fades to the default (no lines, default edges).

## 5. Android bridge (JS → Android, `window.AndroidBridge`, may be absent in browser)

Existing: `getPrefs()`, `setRoi(l,t,r,b)`, `setMeters(m)`, `setFlip(b)`, `setRealGround(b)`, `setRoiOnly(b)`.
New:
- `getPrefs()` JSON gains `groundMode: 'real'|'virtual'|'off'`, `src: 'screen'|'cam'`.
- `setGroundMode(mode)`, `saveCapture()` (screen mode: save the current full screen capture PNG for
  calibration). Android calls back `window.onSaved && window.onSaved(text)` with a zh-TW status string.

## 6. Files and owners

| Owner | Files |
|---|---|
| geometry | `AvmGeometry.kt`, `assets/avm_calib.json`, `tools/avm_geometry.py`, `app/src/test/java/com/vito/avmprobe/AvmGeometryTest.kt`, `app/src/test/resources/geometry_fixtures.json` |
| calibfit (after geometry) | `tools/fit_calib.py`, `tools/test_fit_calib.py` |
| perception | `RoadExtractor.kt`, `ConeDetector.kt`, `TrafficLightState.kt`, `tools/perception_proto.py`, `app/src/test/java/com/vito/avmprobe/{RoadExtractorTest,ConeDetectorTest,TrafficLightStateTest}.kt`, `app/src/test/resources/perception/**` |
| web | `app/src/main/assets/bev3d/index.html` only |
| main session (integration) | everything else: Camera2 source, YUV sampling, QuadActivity, Yolo, ScreenCaptureService, Bev3D*, AppPrefs, MainActivity, manifest, build, README |

Rules for everyone: touch only your files. Unit tests run with
`./gradlew :app:testDebugUnitTest --tests 'com.vito.avmprobe.<Class>*'` (JDK 17:
`export JAVA_HOME=/opt/homebrew/opt/openjdk@17`). In a fresh worktree first create the gitignored
`local.properties` containing `sdk.dir=/Users/vito/Library/Android/sdk`. Network is flaky: retry
Gradle on download timeouts. Python: `/opt/homebrew/bin/python3` has numpy 2.4, scipy 1.18,
Pillow 12 (no OpenCV). Commit messages must not mention Claude / AI and carry no Co-Authored-By line.
