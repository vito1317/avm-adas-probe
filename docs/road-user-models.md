# Road-user 3D models — integration guide for `bev3d/index.html`

Audience: whoever wires the realistic road-user models into the 3D panel (`app/src/main/assets/bev3d/index.html`).
Everything below was checked on 2026-11-03 against three.js r128 (`lib/three.min.js`, `lib/GLTFLoader.js`) in a
test page that copies the index.html flow (wrapper groups, per-type pools, collision flash, labels). The ego car
(`models/e60.glb`) is not covered here and must not be changed.

Files:

| Path | What |
|---|---|
| `app/src/main/assets/bev3d/models/*.glb` | 19 road-user models (+ `e60.glb`, the ego car) |
| `app/src/main/assets/bev3d/models/manifest.json` | one list of variants per object type (schema in §2) |
| `app/src/main/assets/bev3d/models/CREDITS.md` | attribution; must ship with the app (§11) |
| `tools/prep_models.py` | rebuilds all of the above from `../avm-assets3d/models` (§10) |

## 1. What is in `models/`

Conventions for every GLB: **front faces +Z**, metres, y up, ground at y = 0, footprint centred on x/z
(same as `models.js` and `e60.glb`; index.html already turns objects by `Math.PI + yaw`). Plain glTF 2.0:
no `extensionsUsed` / `extensionsRequired`, no Draco / meshopt / KTX2 / quantisation, embedded JPEG/PNG
textures of at most 512 px. Each loads with the stock r128 `THREE.GLTFLoader`.

| type | file | L × W × H (m) | tris | draws | KB | tex MB | paint | tint | anim | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| car | `car_taxi.glb` | 4.55 × 2.03 × 1.40 | 7122 | 4 | 431 | 4.7 | yes | no | — | generic sedan in Taiwan taxi yellow (fixed colour) |
| car | `car_sedan.glb` | 4.70 × 1.88 × 1.45 | 11307 | 3 | 366 | 0.0 | yes | yes | — | VW Passat B5.5, flat paint |
| car | `car_logan.glb` | 4.25 × 1.95 × 1.75 | 11755 | 5 | 885 | 5.0 | yes | yes | — | Renault Logan, weathered paint; red tail lamps kept |
| car | `car_hatch.glb` | 4.10 × 1.80 × 1.40 | 6808 | 4 | 402 | 4.7 | yes | yes | — | generic hatchback |
| car | `suv_evoque.glb` | 4.36 × 2.04 × 1.65 | 11754 | 5 | 472 | 6.7 | yes | yes | — | Range Rover Evoque |
| car | `suv_generic.glb` | 4.61 × 2.07 × 1.73 | 7951 | 4 | 438 | 4.7 | yes | no | — | generic SUV, black (fixed) |
| van | `van_minivan.glb` | 4.70 × 1.91 × 1.80 | 7450 | 4 | 419 | 4.7 | yes | yes | — | generic minivan |
| van | `van_postvan.glb` | 5.34 × 2.19 × 1.98 | 6031 | 4 | 429 | 5.0 | yes | no | — | panel van, blue/yellow postal livery (fixed) |
| truck | `truck.glb` | 5.50 × 2.00 × 3.30 | 14520 | 1 | 964 | 0.8 | yes | no | — | Japanese box truck (fictional design), white (fixed), one material |
| bus | `bus.glb` | 12.00 × 2.84 × 3.00 | 12735 | 5 | 571 | 1.5 | yes | no | — | town bus, white/red/orange livery (fixed), silver rims |
| bus | `bus_city.glb` | 12.00 × 2.75 × 3.05 | 9058 | 5 | 565 | 5.7 | yes | yes | — | city bus, white body with blue stripe; wheels split out of `paint` |
| moto | `scooter_rider.glb` | 1.77 × 0.75 × 1.75 | 11437 | 7 | 556 | 7.7 | yes | yes | — | Yamaha Vino scooter + rider with open-face helmet |
| bigbike | `bigbike_rider.glb` | 2.05 × 0.72 × 1.66 | 11814 | 7 | 467 | 5.7 | yes | no | — | Kawasaki Z1000 + rider with full-face helmet |
| bike | `bicycle_rider.glb` | 1.75 × 0.70 × 1.78 | 11161 | 4 | 453 | 0.8 | yes | yes | — | city bicycle + rider (no helmet) |
| ped | `ped_man.glb` | 0.43 × 0.58 × 1.80 | 7836 | 2 | 562 | 1.0 | — (`person`) | no | walk, idle (1.02 m/s) | Rocketbox adult male |
| ped | `ped_woman.glb` | 0.44 × 0.51 × 1.74 | 8732 | 2 | 573 | 1.0 | — (`person`) | no | walk, idle (1.22 m/s) | Rocketbox adult female |
| ped | `ped_worker.glb` | 0.43 × 0.58 × 1.83 | 8058 | 2 | 474 | 1.0 | — (`person`) | no | walk, idle (1.02 m/s) | Rocketbox construction worker (vest, hard hat) |
| ped | `ped_child.glb` | 0.32 × 0.45 × 1.43 | 6660 | 1 | 398 | 0.7 | — (`person`) | no | walk, idle (0.80 m/s) | Rocketbox child (1.43 m) |
| cone | `cone.glb` | 0.38 × 0.38 × 0.70 | 1136 | 1 | 89 | 0.7 | yes | no | — | 70 cm traffic cone (fixed orange) |

`L × W × H` is the bounding box (mirrors included; pedestrians in walk frame 0). Draws = draw calls per
instance (one mesh per material). Tex MB = GPU memory of the file's textures, including mipmaps, paid once per
loaded file however many instances you show.

Material names (use them, not mesh names):

- `paint` — body paint on every vehicle and the cone. Clone it per instance (§4). Pedestrians have none; their
  main material is `person` (body + head atlas).
- `trim`, `glass` — small materials merged into one 16 × 16 palette texture (NEAREST filtering). `glass` is
  alpha BLEND.
- `lights` (lamp textures; on `car_logan` the tail lamps cut out of the paint texture), `wheel` (on `bus_city`
  it uses the body texture, split off so rims keep their colour when the paint is tinted), `decal`,
  `chassis`, `parts`, `black`, `metal` — ordinary textured materials, shared by all instances.
- Riders and pedestrians: `rider` (rider body atlas), `helmet` (vertex colours), `hair` (alpha MASK).

Fixes made in this pass (all in `tools/prep_models.py`, files regenerated):

- `bigbike_rider`: the Z1000 source is pitched about 1°, so the front wheel floated 2.4 cm. Two-wheelers are now
  levelled about x so both tyres touch y = 0 (`level_pitch()`).
- `bus`, `bus_city`: uniform scaling to 11 m / 10.8 m left them 2.7 m tall, lower than the 3.3 m box truck.
  Both are now 12.0 m long and 3.0 / 3.05 m tall (Taiwan city buses are 12 m × 2.5 m × 3.1–3.3 m). The y and z
  scale factors differ by less than 2 %, so the wheels stay round. Body width is about 2.5 m.
- Tintable paint (`tint: true` in the manifest): the paint texture of `car_logan`, `car_hatch`, `suv_evoque`,
  `van_minivan` and `scooter_rider` was recoloured to near-white (shading kept). `paint.color` then multiplies to
  the colour you choose. `car_logan`'s tail lamps, which are painted into the body texture, were moved into
  their own `lights` material so they stay red on every colour.
- `manifest.json` now also carries `tint`, `paintTex` and `walkSpeed`. `--only` runs no longer drop entries.

Second review pass (same day, independent check in a separate viewer with the index.html lighting):

- `car_taxi`: the source yellow is dark and olive. Under ACES it read as a gold car, not a taxi. The paint
  texture is now recoloured to Taiwan taxi yellow (`TAXI_YELLOW`, shading kept). The colour is still fixed
  (`tint: false`).
- `bus`: the source wheel texture has bright cyan-blue rims. They are now silver-grey (`tex_recolor`).
- `bus_city`: tyres and rims were part of the tinted body texture, so a red or green bus got red or green rims.
  The wheels are now their own `wheel` material using the same texture (`wheels_in_paint`, +1 draw call: 4 → 5).
- `truck`: the CREDITS/copyright note said "uniformly scaled". It is scaled per axis (source 6.0 × 2.2 × 3.76 m,
  wheels about 4 % flatter), and the note now says so.
- Everything else was checked and left unchanged: orientation (+Z), wheel contact, rider pose and grip, walk cycle
  (feet move −Z during stance, so walking is +Z; in place; toes touch y = 0), paint tint in white, silver, black,
  red and blue, CREDITS against `RESEARCH_RESULT.json`, budgets. A full rebuild is byte-identical for the other
  15 models.

Known and accepted: `bus.glb`'s rear-left tyre sits 1.6 cm above the ground (it is like that in the source
model; it can't be seen at panel scale). `car_logan` is 1.75 m tall because of its roof antenna (the roof is at
1.51 m).

## 2. `manifest.json` schema

```json
{
  "car":     [ { "file": "car_taxi.glb", "length": 4.55, "width": 2.027, "height": 1.401, "tris": 7122,
                 "paint": true, "anim": [], "tint": false, "paintTex": true, "draws": 4, "kb": 427.7 }, ... ],
  "van": [...], "truck": [...], "bus": [...], "moto": [...], "bigbike": [...], "bike": [...],
  "ped":     [ { "file": "ped_man.glb", ..., "paint": false, "anim": ["walk", "idle"], "walkSpeed": 1.02, ... } ],
  "cone": [...]
}
```

All nine keys are always there, and they match the `t` values in the Android payload and `NAMES` in
index.html. Each list holds one or more variants. Don't sort them: the variant picker in §5 depends on the
order.

| Field | Type | Meaning |
|---|---|---|
| `file` | string | GLB file name, relative to `models/` |
| `length`, `width`, `height` | number (m) | bounding box along z, x, y |
| `tris` | int | triangles |
| `paint` | bool | there is a material named `paint` (all vehicles and the cone) |
| `anim` | string[] | animation clip names (`["walk", "idle"]` for pedestrians, otherwise empty) |
| `tint` | bool | `paint.color` may be set per instance. `false` = fixed livery: don't touch the colour, only flash `emissive` |
| `paintTex` | bool | `paint` has a base-colour texture, so the colour you set multiplies the texture |
| `walkSpeed` | number (m/s) | pedestrians only: ground speed at which the in-place `walk` cycle doesn't slide (measured from toe velocity during stance) |
| `draws`, `kb` | number | draw calls and file size, for budgeting |

## 3. Loading: lazy, with the existing builders as fallback

The module in §12.1 (`window.AVM_RU`, ES5) does the following:

1. `AVM_RU.init('models/', null, renderer.capabilities.floatVertexTextures)` fetches `manifest.json`
   (`THREE.FileLoader`). This works under `WebViewAssetLoader` (https://appassets.androidplatform.net). Until the
   manifest arrives, everything uses the fallback.
2. `makeObj(t)` builds an outer `THREE.Group` with `AVM_RU.wrap(t, fallbackFn)` and hangs the CSS2D label on it,
   as it does today. `fallbackFn()` is the current procedural builder (`MODELS.makeSedan(...)` etc., or the
   built-in boxes when `models.js` is missing).
3. `acquire(t, id)` calls `AVM_RU.dress(g, id)`. It picks the variant for this track id (§5). If that GLB is
   already loaded, the GLB "body" goes inside the group. If not, the procedural body shows now, the GLB loads
   (each file once), and then the body is swapped in place: position, label and pool slot are untouched.
4. Bodies are cached per file (`spare`). When the per-type pool hands a group to a new track id, `dress` swaps in
   a body of that id's variant, so reuse stays cheap and the result stays deterministic.
5. If a GLB fails to load, its groups keep the procedural body and `pick()` stops choosing that file. No
   exceptions are thrown; tested with a manifest that points to a missing file.

Clones share geometry and textures (`Object3D.clone`). Only `paint` (and the linked rider/helmet/hair materials)
is cloned per instance. Optional: `AVM_RU.preload(['car', 'moto'])` once the manifest is in fetches every variant
of the common types up front, so you don't see the procedural version first.

## 4. Paint: per-instance clone, collision flash, colour

- `build()` clones `paint` per instance and sets `paint.userData.baseEm = paint.emissive.getHex()` and
  `baseEmI = paint.emissiveIntensity`. That is exactly what index.html's flash code
  (`p.emissive.setHex(COL.red)` … `p.emissive.setHex(p.userData.baseEm)`) expects. It ends up as
  `g.userData.paint` on the outer group, so the update loop needs no change.
- Like `models.js` `linkTo`, the `rider`, `helmet` and `hair` materials are cloned and share the paint's
  `emissive` Color object and intensity, so the rider flashes with the scooter. For pedestrians the "paint" is the
  `person` material, so the whole person flashes.
- Colour: only when the manifest entry has `tint: true` (or for the procedural fallback):
  `paint.color.setHex(hex).convertSRGBToLinear()`. Palette hex values are sRGB. The renderer uses
  `sRGBEncoding`, so without the conversion the colours come out washed out.
- Fixed liveries (`tint: false`): `car_taxi` (Taiwan taxi yellow), `suv_generic` (black), `van_postvan` (postal
  livery), `truck` (white box truck, one material for the whole model), `bus` (white/red/orange livery),
  `bigbike_rider` (dark Z1000 paint), `cone`.

Palette (sRGB, repeated entries = weight). It follows the colour mix you see in Taiwan traffic: mostly white,
silver, grey and black, then dark blue, deep red, champagne and brown; scooters add pastel and two-tone colours;
city buses use operator colours.

```text
car:  white ×3, pearl 0xeceae4, silver 0xc4c8cc ×2, light grey 0xa9adb2, grey 0x8a8f95, gunmetal 0x55595e,
      black 0x1d1f22 ×3, navy 0x1f3352, blue 0x2a4a73, deep red 0x8e1b22, champagne 0xc9b48f, brown 0x5b4636
van:  white ×3, silver, grey, black, navy        bus: green, blue, red, yellow, white, sky blue
moto: white ×2, black ×2, silver, grey, red, blue, pastel blue 0x9fc9d6, cream 0xd9c7a3
bike: black, white, silver, green, red, yellow, blue
```

(Exact values are in `PALETTE` in §12.1. Pedestrians aren't tinted: variety comes from the four characters.)

## 5. Choosing a variant from the track id

`pick(t, id)` hashes the track id (Knuth multiplicative hash, exact in doubles for ids < 2^20) to [0, 1) and walks
the variant list with weights (`WEIGHT`: `car_taxi.glb` 0.6, everything else 1). The colour uses a second hash
(`id * 7 + 13`) so variant and colour are independent. The same id always gives the same model and colour, also
after pool reuse and after a late GLB load. In a 6 × 17-object cycle test every car variant appeared and the
taxi appeared least often.

## 6. Pedestrians (skinned)

- **Clone with skeleton.** `gltf.scene.clone()` shares the `Skeleton` and binds every copy to the template's
  bones, which are not in the scene, so every pedestrian freezes in the same pose. Use `SkeletonUtils.clone`
  semantics. **`lib/SkeletonUtils.js` was not downloaded.** `AVM_RU.cloneSkinned()` in §12.1 is a 15-line ES5
  port of r128's `THREE.SkeletonUtils.clone` (no `Map`): parallel traversal, then
  `bind(new THREE.Skeleton(clonedBones, boneInverses), bindMatrix)`. If you would rather use the official
  file, it is `https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/utils/SkeletonUtils.js`
  (`THREE.SkeletonUtils.clone`), loaded after `three.min.js`.
- **One `AnimationMixer` per pedestrian.** Both clips play all the time and their weights cross-fade over about
  0.3 s. Speed > 0.3 m/s means `walk`, otherwise `idle`. `walk.timeScale = clamp(speed / walkSpeed, 0.5, 2)`, so
  the steps match the ground speed (`walkSpeed`: man / worker 1.02, woman 1.22, child 0.80 m/s). Start phases are
  random so a group doesn't walk in step. `walk` is an in-place 1.2 s loop (pelvis drift 0 mm) and the position
  still comes from the tracker. `idle` is 11.7–15.5 s.
- **Driving it:** index.html already calls `userData.animate(ts, speed)` every frame with
  `speed = |(rvx, rvz + egoMps)|` (ground speed). The module puts the mixer's update in `userData.animate`, so
  nothing else is needed.
- **Face the direction of motion.** In the update loop, before the yaw smoothing (vehicle frame: x right, z
  forward; the scene uses −z forward; the object is turned `Math.PI + yaw`):

  ```js
  var gvz = tr.rvz === null ? null : tr.rvz + egoMps;            // 對地速度（車身座標：x 右、z 前）
  var spd = gvz === null ? 0 : Math.hypot(tr.rvx || 0, gvz);
  if (tr.t === 'ped' && spd > 0.3) tr.yawT = Math.atan2(tr.rvx || 0, -gvz) - Math.PI;   // 臉朝走的方向
  ```

  Checked: walking forward gives `rotation.y = π` (faces −Z), walking right gives `π/2` (faces +X). Below
  0.3 m/s the last heading is kept.
- **GPU requirement.** The Rocketbox skeletons have 80 bones. three r128 needs float vertex textures for that
  (WebGL2, or WebGL1 with `OES_texture_float`; the Mali-G52 WebView has WebGL2). The uniform path tops out
  around 59 bones on a GPU that reports 256 vertex uniform vectors. `init(..., renderer.capabilities.floatVertexTextures)` makes `pick('ped')` return
  nothing when it's unsupported, so the procedural person is used instead.
- `Box3.setFromObject()` on a SkinnedMesh measures the bind pose (T-pose: 1.35 m wide). Use the manifest
  dimensions for anything size-related.

## 7. Label heights

The module sets `g.userData.labelY = height + 0.45` (`+ 0.6` when `height > 2.5 m`), using the manifest
`height`, which is the bounding box. On every body swap it also moves `g.userData.lab.position.y`. Results:
cars 1.85–2.2, vans 2.25–2.45, truck 3.9, buses 3.6–3.65, two-wheelers 2.1–2.25, pedestrians 1.9–2.3, cone
1.15. For rigid models `new THREE.Box3().setFromObject(gltf.scene)` gives the same numbers. For pedestrians
don't use Box3 (see §6).

## 8. Collision radius

`RADIUS` (rough half-width used in `clr = dist(ego box, object centre) − RADIUS[t]`):

| car | van | truck | bus | moto | bigbike | bike | ped | cone |
|---|---|---|---|---|---|---|---|---|
| 0.9 | 1.0 | 1.2 | **1.3** | 0.4 | 0.4 | 0.4 | 0.3 | 0.2 |

index.html currently has `bus: 1.2`. Change it to 1.3: the new buses are 2.5 m wide at the body and 2.75–2.84 m
across the mirrors. The other values match the model widths (cars 1.68–1.92 m body, two-wheelers 0.70–0.75 m,
pedestrians about 0.5 m). `AVM_RU.RADIUS` has the same table.

## 9. Performance (Mali-G52, 1920 × 720, pixel ratio 1)

- Per instance: 1–7 draw calls and 1.1k–14.5k triangles (table in §1). The ego E60 adds 85.5k triangles and 41
  draws.
- A typical urban mix of 15 objects (2 sedans, hatch, Evoque, taxi, minivan, city bus, truck, 4 scooters,
  big bike, 2 pedestrians) is about **153k triangles and 68 draw calls**. Worst cases: 15 trucks = 218k
  triangles; 15 scooters/big bikes = 105 draw calls. The test scene with 17 road users measured 62 road-user
  draw calls (desktop Chrome, `renderer.info`). A review scene with 20 road users (all 19 variants, cones ×2)
  had 174k road-user triangles and 71 road-user draw calls.
- GPU texture memory is paid once per loaded file: up to 7.7 MB each (`car_sedan` 0.01 MB, pedestrians 0.7–1 MB),
  **62 MB if all 19 variants are loaded**, roughly 30–45 MB for a normal scene with 10–12 variants in use. If memory gets tight, lower `tex` in
  `MODELS`/`RIDERS` in `tools/prep_models.py` to 256 (4× less) or drop variants from the manifest or set their
  `WEIGHT` to 0.
- CPU: rigid templates get `matrixAutoUpdate = false` (clones inherit it), so a static car costs no matrix
  updates. Each pedestrian runs 80 bones and two actions through its mixer. That is cheap for a few, but don't
  keep idle pedestrians animating when they are off screen; pooled ones aren't updated anyway.
- The first time a new material combination appears, its shader compiles (22 programs in the test scene) and
  that can hitch for a frame on Mali. If it shows, call `renderer.compile(scene, camera)` right after a template
  loads, or `AVM_RU.preload([...])` early and compile once.
- `glass` is alpha BLEND and the module sets `depthWrite = false`, like index.html does for the E60. Shadows
  stay off.
- Never `dispose()` geometry or textures on release: they are shared by every instance and the template.

## 10. Rebuilding the models

```sh
python3 tools/prep_models.py --gltfpack <path>/gltfpack            # all models + manifest.json + CREDITS.md
python3 tools/prep_models.py --gltfpack <path>/gltfpack --only bus,car_logan
```

Needs numpy, Pillow and gltfpack 0.25 (`npm i gltfpack`; used only for simplification, no compression
extensions). Sources are in `../avm-assets3d/models` (provenance and licences in
`../avm-assets3d/RESEARCH_RESULT.json`). A full run takes about 15 s and is deterministic: unchanged inputs give
byte-identical GLBs. `--only` rebuilds the named ids and rewrites the whole manifest/CREDITS, reusing the
existing entries for the rest. The CREDITS section above the `<!-- prep_models.py: road users -->` marker (the
E60) is preserved.

## 11. Licences

All sources are CC BY 4.0 (Sketchfab authors) or MIT (Microsoft Rocketbox: pedestrians and riders).
`models/CREDITS.md` lists author, source URL, licence URL and changes for every GLB, plus the MIT licence text.
It must ship with the APK, and an "about" screen should link to it. Every GLB also carries the same
attribution in `asset.copyright`. Some source vehicles are real models (VW Passat, Renault Logan, Range Rover
Evoque with visible lettering, Yamaha Vino with the sticker removed, Kawasaki Z1000); CREDITS.md states the
trademark owners and that the project is not affiliated with them.

## 12. Code

### 12.1 `AVM_RU` module (ES5, tested)

Paste it into a new `bev3d/roadusers.js` and load it after `lib/GLTFLoader.js` and `models.js`, or inline it in
index.html. It parses as ECMAScript 5 (checked with acorn `ecmaVersion: 5`) and uses only r128 APIs.

```js
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
```

### 12.2 Changes in index.html (sketch)

```js
// renderer 建好之後
AVM_RU.init('models/', null, renderer.capabilities.floatVertexTextures);
// 選用：AVM_RU.preload(['car', 'moto']);

// RADIUS：bus 改成 1.3，其他不變（AVM_RU.RADIUS 是同一張表）

var objN = 0;
function makeObj(t) {
  var seed = (++objN) * 7919;
  var g = AVM_RU.wrap(t, function () {   // 程式建模備援（原本 makeObj 的內容；顏色之後由 dress 依 track id 換）
    var col = 0xc4c8cc;
    if (MODELS) {
      return t === 'van' ? MODELS.makeVan(col) : t === 'truck' ? MODELS.makeTruck(col)
        : t === 'bus' ? MODELS.makeBus(col) : t === 'moto' ? MODELS.makeScooter(col, seed)
        : t === 'bigbike' ? MODELS.makeMotorcycle(col, seed) : t === 'bike' ? MODELS.makeBicycle(col, seed)
        : t === 'ped' ? MODELS.makePerson(0x5ef08a, seed) : t === 'cone' ? MODELS.makeCone()
        : MODELS.makeSedan(col);
    }
    return t === 'truck' || t === 'bus' ? makeTruck(0x8f7fd6)
      : t === 'moto' || t === 'bigbike' || t === 'bike' ? makeScooter(0xe0a63a)
      : t === 'ped' ? makePerson(0x5ef08a) : t === 'cone' ? makeCone() : makeCar(0x1f7fae);
  });
  var el = document.createElement('div');
  el.className = 'tag ' + t;
  var lab = new THREE.CSS2DObject(el);
  lab.position.set(0, t === 'truck' ? 3.3 : t === 'cone' ? 1.15 : 2.3, 0);   // 換上模型時 dress 會改成模型高度
  g.add(lab);
  g.userData.el = el;
  g.userData.lab = lab;
  return g;
}
function acquire(t, id) {
  var g = POOL[t].pop() || makeObj(t);
  AVM_RU.dress(g, id);                 // 依 track id 決定車款、車色；GLB 沒載完先用程式建模
  g.userData.el.style.display = '';
  scene.add(g);
  return g;
}
// associate()：
//   var id = nextId++;
//   best = { id: id, t: o.t, mesh: acquire(o.t, id), ... };
// update()：在 yaw 平滑之前加 §6 的三行（行人臉朝運動方向），anim(ts, spd) 用同一個 spd。
// release()、閃紅、標籤文字都不用改；PAINT / paintFor() 可以刪掉（改由 AVM_RU.colourFor）。
```
