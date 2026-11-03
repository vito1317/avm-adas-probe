# 3D 模型署名

## e60.glb — 自車（BMW 525i E60）

- 原始模型：「BMW 5 Series 525i MK5 (E60) 2003-2010」
- 作者：szymonpasterczyk（Sketchfab：Merc_TV，https://sketchfab.com/szymonpasterczyk）
- 來源：https://sketchfab.com/3d-models/bmw-5-series-525i-mk5-e60-2003-2010-ad6779e1ca534601947735b16094fc4e
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：移除 SketchUp 邊線、依材質合併網格、換成公尺並轉向（車頭朝 +Z）、車身改銀灰色、玻璃與內裝改深色、命名車燈材質。
  轉換工具：`tools/convert_e60.py`。

BMW 為 BMW AG 的商標；本專案與 BMW 無關。

<!-- prep_models.py: road users -->

## 其他道路使用者（tools/prep_models.py 產生）

下列模型都經過修改。共通的修改：拿掉線段與 KHR_materials 擴充（透射玻璃改成一般半透明）、拿掉法線／AO 貼圖，依材質合併網格，沒有貼圖的小材質合併成一張調色盤貼圖，換成公尺並轉向（車頭／臉朝 +Z）、置中貼地、縮放到實際尺寸，以 gltfpack 簡化並縮小貼圖。每個 GLB 的 asset.copyright 也寫了署名、授權連結與修改說明。

### `car_taxi.glb`

- 原始模型：「Generic passenger car pack (sedan)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`car_taxi.glb`：縮放到 長 4.55 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；原檔偏橄欖色的黃色車漆改成台灣計程車黃，當計程車用（車色固定）

### `car_sedan.glb`

- 原始模型：「Volkswagen Passat B5.5 (2004)」
- 作者：I3D (Sketchfab @I3D99)
- 來源：https://sketchfab.com/3d-models/volkswagen-passat-480c0793f02b43d78b0d0c4a0eb6acb6
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`car_sedan.glb`：縮放到 長 4.70 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；車身改珍珠白單色車漆
- 商標：Volkswagen、Passat 為 Volkswagen AG 的商標；本專案與該公司無關。

### `car_logan.glb`

- 原始模型：「Renault Logan 2004」
- 作者：Denis Petrushin (Sketchfab @goooddenis)
- 來源：https://sketchfab.com/3d-models/renault-logan-2004-e4f9463f6e004b90bb977d12f6375b9c
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`car_logan.glb`：縮放到 長 4.25 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；紅色車漆（舊車質感）改成近白色，尾燈保留紅色
- 商標：Renault 為 Renault s.a.s. 的商標；本專案與該公司無關。

### `car_hatch.glb`

- 原始模型：「Generic passenger car pack (hatchback)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`car_hatch.glb`：縮放到 長 4.10 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；黃色車漆改成近白色

### `suv_evoque.glb`

- 原始模型：「Range Rover Evoque」
- 作者：minghauLoh (Sketchfab @minghau)
- 來源：https://sketchfab.com/3d-models/range-rover-evoque-e0bb09118ab84fe0a0530bd0df2fd74a
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`suv_evoque.glb`：縮放到 長 4.36 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；原檔車頭朝 -Z，轉 180° 改成朝 +Z；灰色車漆改成近白色
- 商標：Range Rover、Evoque 為 Jaguar Land Rover Limited 的商標（車頭／尾門有 RANGE ROVER 字樣）；本專案與該公司無關。

### `suv_generic.glb`

- 原始模型：「Generic passenger car pack (SUV)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`suv_generic.glb`：縮放到 長 4.61 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint

### `van_minivan.glb`

- 原始模型：「Generic passenger car pack (minivan)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`van_minivan.glb`：縮放到 長 4.70 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；紅色車漆改成近白色

### `van_postvan.glb`

- 原始模型：「Generic civil service vehicles pack (postal van)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-civil-service-vehicles-pack-8ff2a13f30914932a70c7950cfa58465
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`van_postvan.glb`：縮放到 長 5.34 m，簡化到 12000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint

### `truck.glb`

- 原始模型：「Japanese Box Truck (Fictional)」
- 作者：WorldBuildingNonsense（Sketchfab，舊帳號名 Calne）
- 來源：https://sketchfab.com/3d-models/japanese-box-truck-fictional-656908d5ba1d4754850437966f8f0a14
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`truck.glb`：縮放到 長 5.50 m, 寬 2.00 m, 高 3.30 m，簡化到 15000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；原檔 6.0 × 2.2 × 3.76 m、貨箱偏高，長寬高各自縮放（車輪上下約扁 4%）

### `bus.glb`

- 原始模型：「Generic Town Bus」
- 作者：own.guest (Sketchfab)
- 來源：https://sketchfab.com/3d-models/generic-town-bus-14fe03d792914d51b6c6250b393c44fd
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`bus.glb`：縮放到 長 12.00 m, 寬 2.84 m, 高 3.00 m，簡化到 15000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；長 12 m、高 3.0 m（原比例偏矮）；白色車身、紅色車頂、橘色飾條三個材質合併成一個調色盤 paint 材質；原檔亮藍色的輪框改成銀灰色

### `bus_city.glb`

- 原始模型：「Generic civil service vehicles pack (city bus)」
- 作者：Comrade1280 (Sketchfab @comrade1280)
- 來源：https://sketchfab.com/3d-models/generic-civil-service-vehicles-pack-8ff2a13f30914932a70c7950cfa58465
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`bus_city.glb`：縮放到 長 12.00 m, 寬 2.75 m, 高 3.05 m，簡化到 15000 三角形以內，貼圖 ≤ 512 px；車身材質命名 paint；長 12 m、高 3.05 m（原比例偏矮）；車輪從車身貼圖材質拆成 wheel，換車色時輪框不跟著變色

### `cone.glb`

- 原始模型：「Worn Traffic Cone」
- 作者：Kuutti Siitonen (Sketchfab @kuuttisiitonen)
- 來源：https://sketchfab.com/3d-models/worn-traffic-cone-1cbffb4169684e5a84ba186882bd55d7
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`cone.glb`：縮放到 高 0.70 m，簡化到 1500 三角形以內，貼圖 ≤ 256 px；車身材質命名 paint；高度放大到台灣常見的 70 cm

### `scooter_rider.glb`

- 原始模型：「Yamaha Vino」
- 作者：minghauLoh (Sketchfab @minghau)
- 來源：https://sketchfab.com/3d-models/vino-2205f4e69bc64df3aab03c71ab9d7492
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`scooter_rider.glb`：縮放到 長 1.78 m，車簡化到 8000 三角形以內（連騎士共 11437），貼圖 ≤ 512 px；車身材質命名 paint；原檔偏航約 12°，轉正；拿掉 Yamaha 貼紙；淺藍車殼改成近白色
- 商標：Yamaha、Vino 為 Yamaha Motor Co., Ltd. 的商標（車身小貼紙已移除）；本專案與該公司無關。

### `bigbike_rider.glb`

- 原始模型：「Kawasaki Z1000 2014」
- 作者：e-restrepo1114 (Sketchfab @EmanuelRestrepoVelez)
- 來源：https://sketchfab.com/3d-models/z1000-2014-70cecfa5568347428c5ad72e9a156be9
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`bigbike_rider.glb`：縮放到 長 2.05 m，車簡化到 7800 三角形以內（連騎士共 11814），貼圖 ≤ 512 px；車身材質命名 paint；原檔前輪比後輪高 2.4 cm（俯仰約 1°），繞 x 軸扶正讓兩輪都貼地
- 商標：Kawasaki、Z1000 為 Kawasaki Heavy Industries, Ltd. 的商標；本專案與該公司無關。

### `bicycle_rider.glb`

- 原始模型：「Yellow bicycle」
- 作者：Tatyana Volkova (Sketchfab @volkovatatyana)
- 來源：https://sketchfab.com/3d-models/yellow-bicycle-4cc719f387cb4cb4aea6e12a27e06e2e
- 授權：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
- 修改：`bicycle_rider.glb`：縮放到 長 1.75 m，車簡化到 8000 三角形以內（連騎士共 11161），貼圖 ≤ 512 px；車身材質命名 paint；原檔偏航 17.6°、側傾 5.6°，扶正

### `ped_man.glb`

- 原始模型：「Microsoft Rocketbox Avatar Library: Male_Adult_05」
- 作者：Microsoft / Rocketbox Studios
- 來源：https://github.com/microsoft/Microsoft-Rocketbox
- 授權：MIT（https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md）
- 修改：`ped_man.glb`：保留骨架與 walk / idle 動畫；貼圖縮到 256 px，身體與頭合成一張圖集，拿掉全白頂點色，idle 動畫重取樣到 15 Hz、刪掉不動的軌道

### `ped_woman.glb`

- 原始模型：「Microsoft Rocketbox Avatar Library: Female_Adult_01」
- 作者：Microsoft / Rocketbox Studios
- 來源：https://github.com/microsoft/Microsoft-Rocketbox
- 授權：MIT（https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md）
- 修改：`ped_woman.glb`：保留骨架與 walk / idle 動畫；貼圖縮到 256 px，身體與頭合成一張圖集，拿掉全白頂點色，idle 動畫重取樣到 15 Hz、刪掉不動的軌道

### `ped_worker.glb`

- 原始模型：「Microsoft Rocketbox Avatar Library: Construction_Male_07」
- 作者：Microsoft / Rocketbox Studios
- 來源：https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Avatars/Professions/Construction_Male_07
- 授權：MIT（https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md）
- 修改：`ped_worker.glb`：保留骨架與 walk / idle 動畫；貼圖縮到 256 px，身體與頭合成一張圖集，拿掉全白頂點色，idle 動畫重取樣到 15 Hz、刪掉不動的軌道

### `ped_child.glb`

- 原始模型：「Microsoft Rocketbox Avatar Library: Male_Child_01」
- 作者：Microsoft / Rocketbox Studios
- 來源：https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Avatars/Children
- 授權：MIT（https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md）
- 修改：`ped_child.glb`：保留骨架與 walk / idle 動畫；貼圖縮到 256 px，身體與頭合成一張圖集，拿掉全白頂點色，idle 動畫重取樣到 15 Hz、刪掉不動的軌道

### `scooter_rider.glb`, `bigbike_rider.glb`, `bicycle_rider.glb`

- 原始模型：「Microsoft Rocketbox Avatar Library: Male_Adult_05（sit 坐姿動畫）」
- 作者：Microsoft / Rocketbox Studios
- 來源：https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Animations/all_animations_max_motextr_static
- 授權：MIT（https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md）
- 修改：
  - `scooter_rider.glb` 的騎士：取 sit 動畫第 0 格，身高縮到 95%，上身前傾 8°，手腳用兩段式 IK 擺到把手與腳踏板，烘成靜態網格（4000 三角形以內）後跟車合成一個檔；加上程式產生的安全帽，拿掉頭髮那層透明貼圖
  - `bigbike_rider.glb` 的騎士：取 sit 動畫第 0 格，身高縮到 95%，上身前傾 26°，手腳用兩段式 IK 擺到把手與腳踏桿，烘成靜態網格（4000 三角形以內）後跟車合成一個檔；加上程式產生的安全帽，拿掉頭髮那層透明貼圖
  - `bicycle_rider.glb` 的騎士：取 sit 動畫第 0 格，身高縮到 95%，上身前傾 16°，手腳用兩段式 IK 擺到把手與踏板，烘成靜態網格（4000 三角形以內）後跟車合成一個檔

### Microsoft Rocketbox 授權全文（MIT）

行人與騎士模型（`ped_*.glb` 和 `*_rider.glb` 裡的騎士）來自 Microsoft Rocketbox Avatar Library，依 MIT 授權散布，需附上下列版權聲明與授權條款：

```
MIT License

Copyright (c) 2020 Microsoft

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
