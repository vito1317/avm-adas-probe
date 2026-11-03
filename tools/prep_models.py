#!/usr/bin/env python3
"""
道路使用者 3D 模型前處理（tools/convert_e60.py 的通用版）。

把 avm-assets3d/models/ 裡授權確認過的來源模型（CC BY 4.0 / MIT）轉成 3D 面板用的輕量 GLB，
輸出到 app/src/main/assets/bev3d/models/，並產生 manifest.json 與 CREDITS.md 的署名段落。

每個模型的處理：
- 攤平節點樹，把節點變換烘進頂點（負行列式的節點會反轉三角形繞序）；只留三角形（拿掉線、點）
- 依設定旋轉成車頭朝 +Z（每台都用頭燈／尾燈畫面確認過，見 MODELS 的 yaw），縮放到該類別的實際尺寸，
  x/z 置中、最低點 y=0
- 拿掉 KHR_materials_*（clearcoat、transmission…）；有 transmission 的玻璃改成一般 alpha BLEND
- 拿掉法線貼圖、AO 貼圖與 TANGENT（車機上看不出差別，省一張貼圖取樣）；幾乎單色的貼圖直接換成材質係數
- 沒有貼圖的小材質（鍍鉻、橡膠、燈殼…）合併成一張 16×16 調色盤貼圖的單一材質（trim／glass），
  每台車只剩 2～5 個 draw call；車燈的自發光也放進調色盤
- 車身色材質改名 paint（二輪車是車殼；行人沒有），給 3D 面板做碰撞警示閃爍；可換色的車款（tint）把貼圖上的
  車漆改成近白色，執行時 paint.color 乘上去就是車色
- 二輪車繞 x 軸扶正，前後輪都貼地；公車長、高分開縮放到台灣市區公車的 12 m × 3 m
- 計程車車漆換成台灣計程車黃；其他貼圖也能換色（tex_recolor，例：公車原檔的亮藍輪框改銀灰）；車輪跟車身共用
  paint 貼圖時拆成 wheel 材質（wheels_in_paint），換車色時輪框才不會被染色
- 用 gltfpack 簡化到三角形預算（-noq，不量化、不加任何 extension），貼圖縮到 ≤512 px
- asset.copyright 寫原作者、授權、修改內容

騎士（scooter_rider / bigbike_rider / bicycle_rider）：拿 Rocketbox 坐姿（sit 動畫第 0 格），
上半身前傾、手臂／腿用兩段式 IK 擺到把手、腳踏板，再用 three.js r128 的蒙皮算法烘成靜態網格，跟車合成一個 GLB。

行人保留骨架與 walk / idle 動畫：貼圖縮小並把身體、頭合成一張圖集（少一個 draw call），
idle 動畫重取樣到 15 Hz 並刪掉不動的軌道；量出 walk 的自然步速寫進 manifest（walkSpeed）。

整合方式（manifest 格式、載入、換色、行人動畫）見 docs/road-user-models.md。

用法：
  python3 tools/prep_models.py                      # 全部
  python3 tools/prep_models.py --only car_taxi,bus  # 只重做某幾個；其他沿用 manifest.json 的舊資料，
                                                    # manifest.json / CREDITS.md 仍整份重寫
  參數：--src 來源資料夾（預設 ../avm-assets3d/models）--out 輸出資料夾 --gltfpack gltfpack 執行檔
"""
import argparse
import colorsys
import io
import json
import math
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[1]
DEF_SRC = REPO.parent / "avm-assets3d" / "models"
DEF_OUT = REPO / "app" / "src" / "main" / "assets" / "bev3d" / "models"

CC_BY = ("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
MIT_RB = ("MIT", "https://github.com/microsoft/Microsoft-Rocketbox/blob/master/LICENSE.md")

# ---------------------------------------------------------------- 來源
SOURCES = {
    "logan": dict(file="sedan__sedan_renault_logan_goooddenis.glb", title="Renault Logan 2004",
                  author="Denis Petrushin (Sketchfab @goooddenis)",
                  url="https://sketchfab.com/3d-models/renault-logan-2004-e4f9463f6e004b90bb977d12f6375b9c",
                  lic=CC_BY, tm="Renault 為 Renault s.a.s. 的商標"),
    "passat": dict(file="sedan__sedan_vw_passat_b55_I3D.glb", title="Volkswagen Passat B5.5 (2004)",
                   author="I3D (Sketchfab @I3D99)",
                   url="https://sketchfab.com/3d-models/volkswagen-passat-480c0793f02b43d78b0d0c4a0eb6acb6",
                   lic=CC_BY, tm="Volkswagen、Passat 為 Volkswagen AG 的商標"),
    "c_sedan": dict(file="sedan__sedan_generic_comrade1280.glb", title="Generic passenger car pack (sedan)",
                    author="Comrade1280 (Sketchfab @comrade1280)",
                    url="https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5",
                    lic=CC_BY, tm=None),
    "c_hatch": dict(file="sedan__hatchback_generic_comrade1280.glb", title="Generic passenger car pack (hatchback)",
                    author="Comrade1280 (Sketchfab @comrade1280)",
                    url="https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5",
                    lic=CC_BY, tm=None),
    "c_suv": dict(file="suv__suv_generic_comrade1280.glb", title="Generic passenger car pack (SUV)",
                  author="Comrade1280 (Sketchfab @comrade1280)",
                  url="https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5",
                  lic=CC_BY, tm=None),
    "evoque": dict(file="suv__suv_rangerover_evoque_minghau.glb", title="Range Rover Evoque",
                   author="minghauLoh (Sketchfab @minghau)",
                   url="https://sketchfab.com/3d-models/range-rover-evoque-e0bb09118ab84fe0a0530bd0df2fd74a",
                   lic=CC_BY, tm="Range Rover、Evoque 為 Jaguar Land Rover Limited 的商標（車頭／尾門有 RANGE ROVER 字樣）"),
    "c_minivan": dict(file="van_minibus__van_generic_minivan_comrade1280.glb", title="Generic passenger car pack (minivan)",
                      author="Comrade1280 (Sketchfab @comrade1280)",
                      url="https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5",
                      lic=CC_BY, tm=None),
    "c_postvan": dict(file="van_minibus__van_generic_postvan_comrade1280.glb",
                      title="Generic civil service vehicles pack (postal van)",
                      author="Comrade1280 (Sketchfab @comrade1280)",
                      url="https://sketchfab.com/3d-models/generic-civil-service-vehicles-pack-8ff2a13f30914932a70c7950cfa58465",
                      lic=CC_BY, tm=None),
    "jtruck": dict(file="light_truck__truck_japanese_box_WorldBuildingNonsense.glb", title="Japanese Box Truck (Fictional)",
                   author="WorldBuildingNonsense（Sketchfab，舊帳號名 Calne）",
                   url="https://sketchfab.com/3d-models/japanese-box-truck-fictional-656908d5ba1d4754850437966f8f0a14",
                   lic=CC_BY, tm=None),
    "townbus": dict(file="city_bus__bus_generic_town_ownguest.glb", title="Generic Town Bus",
                    author="own.guest (Sketchfab)",
                    url="https://sketchfab.com/3d-models/generic-town-bus-14fe03d792914d51b6c6250b393c44fd",
                    lic=CC_BY, tm=None),
    "c_citybus": dict(file="city_bus__bus_generic_city_comrade1280.glb",
                      title="Generic civil service vehicles pack (city bus)",
                      author="Comrade1280 (Sketchfab @comrade1280)",
                      url="https://sketchfab.com/3d-models/generic-civil-service-vehicles-pack-8ff2a13f30914932a70c7950cfa58465",
                      lic=CC_BY, tm=None),
    "vino": dict(file="scooter__scooter_yamaha_vino_minghau.glb", title="Yamaha Vino",
                 author="minghauLoh (Sketchfab @minghau)",
                 url="https://sketchfab.com/3d-models/vino-2205f4e69bc64df3aab03c71ab9d7492",
                 lic=CC_BY, tm="Yamaha、Vino 為 Yamaha Motor Co., Ltd. 的商標（車身小貼紙已移除）"),
    "z1000": dict(file="motorcycle__moto_kawasaki_z1000_EmanuelRestrepo.glb", title="Kawasaki Z1000 2014",
                  author="e-restrepo1114 (Sketchfab @EmanuelRestrepoVelez)",
                  url="https://sketchfab.com/3d-models/z1000-2014-70cecfa5568347428c5ad72e9a156be9",
                  lic=CC_BY, tm="Kawasaki、Z1000 為 Kawasaki Heavy Industries, Ltd. 的商標"),
    "ybike": dict(file="bicycle__bicycle_yellow_volkovatatyana.glb", title="Yellow bicycle",
                  author="Tatyana Volkova (Sketchfab @volkovatatyana)",
                  url="https://sketchfab.com/3d-models/yellow-bicycle-4cc719f387cb4cb4aea6e12a27e06e2e",
                  lic=CC_BY, tm=None),
    "cone": dict(file="traffic_cone__1cbffb4169684e5a84ba186882bd55d7.glb", title="Worn Traffic Cone",
                 author="Kuutti Siitonen (Sketchfab @kuuttisiitonen)",
                 url="https://sketchfab.com/3d-models/worn-traffic-cone-1cbffb4169684e5a84ba186882bd55d7",
                 lic=CC_BY, tm=None),
    "rb_m05": dict(file="pedestrian__rocketbox_Male_Adult_05.glb", title="Microsoft Rocketbox Avatar Library: Male_Adult_05",
                   author="Microsoft / Rocketbox Studios",
                   url="https://github.com/microsoft/Microsoft-Rocketbox",
                   lic=MIT_RB, tm=None),
    "rb_f01": dict(file="pedestrian__rocketbox_Female_Adult_01.glb", title="Microsoft Rocketbox Avatar Library: Female_Adult_01",
                   author="Microsoft / Rocketbox Studios",
                   url="https://github.com/microsoft/Microsoft-Rocketbox",
                   lic=MIT_RB, tm=None),
    "rb_c07": dict(file="pedestrian__rocketbox_Construction_Male_07.glb",
                   title="Microsoft Rocketbox Avatar Library: Construction_Male_07",
                   author="Microsoft / Rocketbox Studios",
                   url="https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Avatars/Professions/Construction_Male_07",
                   lic=MIT_RB, tm=None),
    "rb_child": dict(file="pedestrian__rocketbox_Male_Child_01.glb", title="Microsoft Rocketbox Avatar Library: Male_Child_01",
                     author="Microsoft / Rocketbox Studios",
                     url="https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Avatars/Children",
                     lic=MIT_RB, tm=None),
    "rb_sit": dict(file="cyclist_scooter_rider__rocketbox_Male_Adult_05_sit.glb",
                   title="Microsoft Rocketbox Avatar Library: Male_Adult_05（sit 坐姿動畫）",
                   author="Microsoft / Rocketbox Studios",
                   url="https://github.com/microsoft/Microsoft-Rocketbox/tree/master/Assets/Animations/all_animations_max_motextr_static",
                   lic=MIT_RB, tm=None),
}

# ---------------------------------------------------------------- 輸出模型
# yaw：繞 y 軸轉幾度讓車頭朝 +Z（每台都看過頭燈 / 尾燈畫面確認）。dims：目標尺寸（m），
# 只給 length 就等比縮放；給多個軸就各軸分開縮放。tris：三角形預算。tex：貼圖最大邊長。
# tint=True：paint 可以在執行時用 material.color 換車色（車漆是單色係數，或貼圖上的車漆已經用 recolor
# 換成近白色，相乘後就是指定的車色）；False：車色固定（計程車黃、黑色 SUV、郵務車／公車塗裝…），只閃 emissive。
# recolor：hue＝色相範圍（lo > hi 代表跨過 0，例：紅色）、sat＝最低飽和度；grey＝(vlo, vhi, 最高飽和度) 抓灰色車漆。
# tex_recolor：{來源材質名: recolor 參數}，換非車漆的貼圖色。wheels_in_paint：車輪在 paint 貼圖裡，拆成 wheel 材質。
TWO_WHEEL = ("moto", "bigbike", "bike")
NEUTRAL = (236, 238, 240)   # 可換色車漆的底色（近白色，乘上 paint.color 就是車色）
TAXI_YELLOW = (236, 176, 0)   # 台灣計程車黃（原檔的黃偏暗、偏橄欖色，在面板上看起來像金色車；ACES 下太亮會變奶油黃）
MODELS = [
    dict(id="car_taxi", type="car", src="c_sedan", yaw=0, dims=dict(length=4.55), tris=12000, tex=512,
         paint=["Body_5"], rename={"Optics_5": "lights", "Wheel_4": "wheel"}, glass=["Glass_5"],
         recolor=dict(hue=(0.10, 0.20), sat=0.35, rgb=TAXI_YELLOW),
         note="原檔偏橄欖色的黃色車漆改成台灣計程車黃，當計程車用（車色固定）"),
    dict(id="car_sedan", type="car", src="passat", yaw=0, dims=dict(length=4.70), tris=12000, tex=512,
         paint=["paint"], paint_color=(0.88, 0.89, 0.90), paint_rough=0.3, tint=True,
         glass=["glass", "lens", "headlight_cover", "rearlight_cover"],
         flatten=["tyre", "plate"], note="車身改珍珠白單色車漆"),
    dict(id="car_logan", type="car", src="logan", yaw=0, dims=dict(length=4.25), tris=12000, tex=512,
         paint=["Kuzov1"], rename={"Podveska_Salon": "chassis"}, glass=["glass"], tint=True,
         recolor=dict(hue=(0.93, 0.06), sat=0.25, rgb=NEUTRAL, lights=1.35),
         note="紅色車漆（舊車質感）改成近白色，尾燈保留紅色"),
    dict(id="car_hatch", type="car", src="c_hatch", yaw=0, dims=dict(length=4.10), tris=12000, tex=512,
         paint=["Body_1"], rename={"Optics_1": "lights", "Wheek": "wheel"}, glass=["Glass_1"], tint=True,
         recolor=dict(hue=(0.10, 0.20), sat=0.35, rgb=NEUTRAL), note="黃色車漆改成近白色"),
    dict(id="suv_evoque", type="car", src="evoque", yaw=180, dims=dict(length=4.36), tris=12000, tex=512,
         paint=["Car_Body"], rename={"Tire": "wheel", "Ligths": "lights", "Glass": "glass"},
         flatten=["Car_Bottom", "Blockers"], glass=["Glass"], tint=True,
         recolor=dict(grey=(0.24, 0.52, 0.14), rgb=NEUTRAL),
         note="原檔車頭朝 -Z，轉 180° 改成朝 +Z；灰色車漆改成近白色"),
    dict(id="suv_generic", type="car", src="c_suv", yaw=0, dims=dict(length=4.61), tris=12000, tex=512,
         paint=["Body_7"], rename={"Optics_7": "lights", "Wheel_6": "wheel"}, glass=["Glass_7"]),
    dict(id="van_minivan", type="van", src="c_minivan", yaw=0, dims=dict(length=4.70), tris=12000, tex=512,
         paint=["Body_2"], rename={"Optics_2": "lights", "Wheel_1": "wheel"}, glass=["Glass_2"], tint=True,
         recolor=dict(hue=(0.93, 0.06), sat=0.35, rgb=NEUTRAL), note="紅色車漆改成近白色"),
    dict(id="van_postvan", type="van", src="c_postvan", yaw=0, dims=dict(length=5.34), tris=12000, tex=512,
         paint=["postvan"], rename={"optics_tex_6": "lights", "postvan_decal": "decal", "postvan_glass": "glass"},
         glass=["postvan_glass"]),
    dict(id="truck", type="truck", src="jtruck", yaw=0, dims=dict(length=5.5, width=2.0, height=3.3), tris=15000, tex=512,
         paint=["Material.001"], note="原檔 6.0 × 2.2 × 3.76 m、貨箱偏高，長寬高各自縮放（車輪上下約扁 4%）"),
    # 公車：台灣市區公車多是 12 m 長、約 2.5 m 寬、3.1～3.3 m 高。兩個來源等比縮到 11 m 時只有 2.7 m 高（比
    # 3.3 m 的箱型貨車還矮），所以長、高各自縮放（y/z 比例差 < 2%，車輪仍是圓的），寬度另外給。
    dict(id="bus", type="bus", src="townbus", yaw=0, dims=dict(length=12.0, width=2.84, height=3.0), tris=15000,
         tex=512, paint=["standard", "standard21", "standard2"], glass=["glass"],
         rename={"material": "wheel", "material_4": "wheel", "1616862037160": "decal"},
         tex_recolor={"material": dict(hue=(0.50, 0.66), sat=0.35, rgb=(196, 199, 203))},
         note="長 12 m、高 3.0 m（原比例偏矮）；白色車身、紅色車頂、橘色飾條三個材質合併成一個調色盤 paint 材質；"
              "原檔亮藍色的輪框改成銀灰色"),
    dict(id="bus_city", type="bus", src="c_citybus", yaw=0, dims=dict(length=12.0, width=2.75, height=3.05),
         tris=15000, tex=512, tint=True, wheels_in_paint=True,
         paint=["citybus"], rename={"CityBus_optics": "lights", "CityBus_decals": "decal", "citybus_glass": "glass"},
         glass=["citybus_glass"], drop=["citybus_glass_0"],
         note="長 12 m、高 3.05 m（原比例偏矮）；車輪從車身貼圖材質拆成 wheel，換車色時輪框不跟著變色"),
    dict(id="cone", type="cone", src="cone", yaw=0, dims=dict(height=0.70), tris=1500, tex=256,
         paint=["Plane_Bake1_baked"], note="高度放大到台灣常見的 70 cm"),
]
# 騎士組合：rider 由 rb_sit 烘焙。座標都是車子處理完的最終座標（m，車頭 +Z）。
RIDERS = [
    # Vino 原檔繞 y 偏了約 12°（兩輪中心連線、輪面法線都量過），要轉回來
    # pose：seat＝臀部底點放的位置；hands＝左右手腕目標；feet＝左右腳踝目標；lean＝上身前傾角
    dict(id="scooter_rider", type="moto", src="vino", yaw=12.2, dims=dict(length=1.78), tris=8000, tex=512,
         paint=["Main_Body"], drop=["Sticker"], glass=["Glass"], rename={"SmallParts": "parts", "Tire": "wheel",
                                                                         "Lamp__Glass": "lights", "Glass": "glass"},
         tint=True, recolor=dict(hue=(0.45, 0.62), sat=0.25, rgb=NEUTRAL),
         split=dict(min_tris=24, drop_diag=0.025), rider_tris=4000,
         pose=dict(seat=(0, 0.785, -0.24), lean=8, head_up=8, scale=0.95,
                   hands=[(0.25, 1.0, 0.19), (-0.25, 1.0, 0.19)],
                   feet=[(0.13, 0.27, 0.08), (-0.13, 0.27, 0.08)], knee_out=0.35,
                   helmet=dict(color=(0.93, 0.93, 0.90), style="open")),
         note="原檔偏航約 12°，轉正；拿掉 Yamaha 貼紙；淺藍車殼改成近白色"),
    dict(id="bigbike_rider", type="bigbike", src="z1000", yaw=-1.5, dims=dict(length=2.05), tris=7800, tex=512,
         paint=["negrobrillante"], rename={"negromate": "black", "aluminio": "metal"}, flatten=["cobre_0"],
         split=dict(min_tris=24, drop_diag=0.04), rider_tris=4000,
         pose=dict(seat=(0, 0.845, -0.30), lean=26, head_up=22, scale=0.95,
                   hands=[(0.30, 0.98, 0.22), (-0.30, 0.98, 0.22)],
                   feet=[(0.17, 0.40, -0.16), (-0.17, 0.40, -0.16)], knee_out=0.45,
                   helmet=dict(color=(0.08, 0.08, 0.09), style="full", visor=(0.01, 0.012, 0.016))),
         note="原檔前輪比後輪高 2.4 cm（俯仰約 1°），繞 x 軸扶正讓兩輪都貼地"),
    # 腳踏車原檔偏航 17.6°、還往側邊倒 5.6°（停車用側柱的姿勢），扶正
    dict(id="bicycle_rider", type="bike", src="ybike", yaw=17.56, roll=5.62, dims=dict(length=1.75), tris=8000, tex=512,
         paint=["Material.001"], split=dict(min_tris=24, drop_diag=0.025), rider_tris=4000, tint=True,
         pose=dict(seat=(0, 0.885, -0.20), lean=16, head_up=18, scale=0.95,
                   hands=[(0.27, 1.03, 0.12), (-0.27, 1.03, 0.12)],
                   feet=[(0.15, 0.37, 0.14), (-0.15, 0.37, -0.18)], knee_out=0.15),
         note="原檔偏航 17.6°、側傾 5.6°，扶正"),
]
PEDS = [
    dict(id="ped_man", type="ped", src="rb_m05", tex=256),
    dict(id="ped_woman", type="ped", src="rb_f01", tex=256),
    dict(id="ped_worker", type="ped", src="rb_c07", tex=256),
    dict(id="ped_child", type="ped", src="rb_child", tex=256),
]

# ================================================================ GLB 讀寫
CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def load_glb(path):
    b = Path(path).read_bytes()
    magic, _, _ = struct.unpack("<III", b[:12])
    assert magic == 0x46546C67, "not a GLB: %s" % path
    jl, _ = struct.unpack("<II", b[12:20])
    j = json.loads(b[20:20 + jl])
    off = 20 + jl
    binb = b""
    if off + 8 <= len(b):
        bl, _ = struct.unpack("<II", b[off:off + 8])
        binb = b[off + 8:off + 8 + bl]
    return j, binb


def accessor(j, binb, i):
    a = j["accessors"][i]
    n = NC[a["type"]]
    dt = CT[a["componentType"]]
    if "bufferView" not in a:
        out = np.zeros((a["count"], n), dt)
    else:
        bv = j["bufferViews"][a["bufferView"]]
        off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        item = np.dtype(dt).itemsize * n
        stride = bv.get("byteStride", 0)
        if stride and stride != item:
            raw = np.frombuffer(binb, np.uint8, (a["count"] - 1) * stride + item, off)
            out = np.lib.stride_tricks.as_strided(raw, (a["count"], item), (stride, 1)).copy().view(dt).reshape(-1, n)
        else:
            out = np.frombuffer(binb, dt, a["count"] * n, off).reshape(-1, n).copy()
    assert "sparse" not in a, "sparse accessor not supported"
    if a.get("normalized"):
        out = out.astype(np.float32) / float(np.iinfo(dt).max)
    return out


def image_bytes(j, binb, idx):
    im = j["images"][idx]
    bv = j["bufferViews"][im["bufferView"]]
    o = bv.get("byteOffset", 0)
    return binb[o:o + bv["byteLength"]], im.get("mimeType", "image/png")


class Writer:
    """組一個乾淨的 glTF：一個 buffer，沒有任何 extension。"""

    def __init__(self):
        self.bin = bytearray()
        self.j = {"bufferViews": [], "accessors": [], "images": [], "textures": [], "samplers": [],
                  "materials": [], "meshes": [], "nodes": []}
        self._img_cache = {}

    def view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        v = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            v["target"] = target
        self.j["bufferViews"].append(v)
        self.bin.extend(data)
        return len(self.j["bufferViews"]) - 1

    def acc(self, arr, typ, target=None, minmax=False, ctype=None):
        arr = np.ascontiguousarray(arr)
        if ctype is None:
            ctype = {np.dtype(np.float32): 5126, np.dtype(np.uint16): 5123, np.dtype(np.uint32): 5125,
                     np.dtype(np.uint8): 5121}[arr.dtype]
        a = {"bufferView": self.view(arr.tobytes(), target), "componentType": ctype,
             "count": int(arr.shape[0]), "type": typ}
        if minmax:
            a["min"] = [float(x) for x in arr.reshape(arr.shape[0], -1).min(0)]
            a["max"] = [float(x) for x in arr.reshape(arr.shape[0], -1).max(0)]
        self.j["accessors"].append(a)
        return len(self.j["accessors"]) - 1

    def sampler(self, s):
        if s in self.j["samplers"]:
            return self.j["samplers"].index(s)
        self.j["samplers"].append(s)
        return len(self.j["samplers"]) - 1

    def texture(self, tex):
        """tex = Tex 物件；同一個物件只放一次。"""
        key = id(tex)
        if key not in self._img_cache:
            data, mime = tex.encoded()
            self.j["images"].append({"bufferView": self.view(data), "mimeType": mime})
            smp = self.sampler(tex.sampler)
            self.j["textures"].append({"sampler": smp, "source": len(self.j["images"]) - 1})
            self._img_cache[key] = len(self.j["textures"]) - 1
        return self._img_cache[key]

    def glb(self, root_name, asset, extra=None):
        j = {k: v for k, v in self.j.items() if v}
        j["asset"] = asset
        nodes = j.get("nodes", [])
        nodes.append({"name": root_name, "children": list(range(len(nodes)))})
        j["nodes"] = nodes
        j["scene"] = 0
        j["scenes"] = [{"name": root_name, "nodes": [len(nodes) - 1]}]
        if extra:
            j.update(extra)
        while len(self.bin) % 4:
            self.bin.append(0)
        j["buffers"] = [{"byteLength": len(self.bin)}]
        return pack_glb(j, bytes(self.bin))


def pack_glb(j, binb):
    js = json.dumps(j, separators=(",", ":"), ensure_ascii=False).encode()
    while len(js) % 4:
        js += b" "
    binb = bytes(binb)
    while len(binb) % 4:
        binb += b"\0"
    out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(binb))
    out += struct.pack("<II", len(js), 0x4E4F534A) + js
    out += struct.pack("<II", len(binb), 0x004E4942) + binb
    return out


# ================================================================ 貼圖
REPEAT = {"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}
NEAREST = {"magFilter": 9728, "minFilter": 9728, "wrapS": 33071, "wrapT": 33071}


class Tex:
    def __init__(self, img, kind="color", sampler=None, keep_alpha=False):
        self.img = img          # PIL Image
        self.kind = kind        # color / data（metallicRoughness）
        self.sampler = sampler or REPEAT
        self.keep_alpha = keep_alpha
        self._enc = None

    @classmethod
    def from_bytes(cls, data, kind="color", sampler=None):
        im = Image.open(io.BytesIO(data))
        im.load()
        return cls(im, kind, sampler)

    def limit(self, n):
        w, h = self.img.size
        s = min(1.0, n / max(w, h))
        if s < 1:
            self.img = self.img.resize((max(1, int(round(w * s))), max(1, int(round(h * s)))), Image.LANCZOS)
            self._enc = None
        return self

    def has_alpha(self):
        if self.img.mode in ("RGBA", "LA") or (self.img.mode == "P" and "transparency" in self.img.info):
            a = np.asarray(self.img.convert("RGBA"))[..., 3]
            return a.min() < 250
        return False

    def encoded(self):
        if self._enc is None:
            bio = io.BytesIO()
            if self.keep_alpha and self.has_alpha():
                self.img.convert("RGBA").save(bio, "PNG", optimize=True)
                self._enc = (bio.getvalue(), "image/png")
            elif self.sampler is NEAREST:
                self.img.convert("RGBA" if self.keep_alpha else "RGB").save(bio, "PNG", optimize=True)
                self._enc = (bio.getvalue(), "image/png")
            else:
                self.img.convert("RGB").save(bio, "JPEG", quality=85, optimize=True)
                self._enc = (bio.getvalue(), "image/jpeg")
        return self._enc

    def stats(self):
        a = np.asarray(self.img.convert("RGBA")).reshape(-1, 4).astype(np.float32)
        return a.mean(0), a.std(0)


def srgb_to_lin(c):
    c = np.asarray(c, np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(c):
    c = np.clip(np.asarray(c, np.float64), 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


# ================================================================ 材質
class Mat:
    """簡化後的 PBR 材質。base 為線性 RGBA。"""

    def __init__(self, name):
        self.name = name
        self.base = [1.0, 1.0, 1.0, 1.0]
        self.metal = 1.0
        self.rough = 1.0
        self.emis = [0.0, 0.0, 0.0]
        self.alpha = "OPAQUE"
        self.cutoff = 0.5
        self.double = False
        self.tex = None      # Tex（baseColor）
        self.mr = None       # Tex（metallicRoughness）
        self.palette = False

    def clone(self, name=None):
        m = Mat(name or self.name)
        m.__dict__.update({k: (list(v) if isinstance(v, list) else v) for k, v in self.__dict__.items()})
        if name:
            m.name = name
        return m

    def key(self):
        return (self.name,)


def read_materials(j, binb, tex_cache):
    """來源材質 → Mat。tex_cache：texture index → Tex（共用貼圖保持共用）。"""

    def tex_of(ref, kind):
        if ref is None:
            return None
        ti = ref["index"]
        k = (ti, kind)
        if k not in tex_cache:
            t = j["textures"][ti]
            data, _ = image_bytes(j, binb, t["source"])
            tex_cache[k] = Tex.from_bytes(data, kind, REPEAT)
        return tex_cache[k]

    out = []
    for i, src in enumerate(j.get("materials", [])):
        m = Mat(src.get("name") or "m%d" % i)
        pbr = src.get("pbrMetallicRoughness", {})
        m.base = list(pbr.get("baseColorFactor", [1, 1, 1, 1]))
        m.metal = float(pbr.get("metallicFactor", 1.0))
        m.rough = float(pbr.get("roughnessFactor", 1.0))
        m.emis = list(src.get("emissiveFactor", [0, 0, 0]))
        m.alpha = src.get("alphaMode", "OPAQUE")
        m.cutoff = float(src.get("alphaCutoff", 0.5))
        m.double = bool(src.get("doubleSided", False))
        m.tex = tex_of(pbr.get("baseColorTexture"), "color")
        m.mr = tex_of(pbr.get("metallicRoughnessTexture"), "data")
        ext = src.get("extensions", {})
        tr = ext.get("KHR_materials_transmission")
        if tr:   # 透射改成一般半透明
            m.alpha = "BLEND"
            if m.base[3] >= 0.99:
                m.base[3] = max(0.3, 1.0 - 0.7 * float(tr.get("transmissionFactor", 0)))
        m.src_bc_transform = (pbr.get("baseColorTexture") or {}).get("extensions", {}).get("KHR_texture_transform")
        out.append(m)
    return out


# ================================================================ 幾何
def trs_matrix(t=None, r=None, s=None):
    x, y, z, w = r if r is not None else (0, 0, 0, 1)
    R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = R @ np.diag(s if s is not None else (1, 1, 1))
    m[:3, 3] = t if t is not None else (0, 0, 0)
    return m


def node_local(n, over=None):
    if over is None and "matrix" in n:
        return np.array(n["matrix"], float).reshape(4, 4).T
    o = over or {}
    return trs_matrix(o.get("translation", n.get("translation")), o.get("rotation", n.get("rotation")),
                      o.get("scale", n.get("scale")))


class Part:
    """同一材質的一塊三角網格（世界座標）。"""

    def __init__(self, mat, P, N, UV, I, C=None):
        self.mat, self.P, self.N, self.UV, self.I, self.C = mat, P, N, UV, I, C


def face_normals_to_vertex(P, I):
    tri = I.reshape(-1, 3)
    fn = np.cross(P[tri[:, 1]] - P[tri[:, 0]], P[tri[:, 2]] - P[tri[:, 0]])
    N = np.zeros_like(P)
    for k in range(3):
        np.add.at(N, tri[:, k], fn)
    ln = np.linalg.norm(N, axis=1, keepdims=True)
    ln[ln == 0] = 1
    return N / ln


def apply_uv_transform(uv, tt):
    if not tt:
        return uv
    off = np.array(tt.get("offset", [0, 0]))
    sc = np.array(tt.get("scale", [1, 1]))
    rot = tt.get("rotation", 0.0)
    c, s = math.cos(rot), math.sin(rot)
    u, v = uv[:, 0] * sc[0], uv[:, 1] * sc[1]
    return np.stack([c * u + s * v + off[0], -s * u + c * v + off[1]], 1).astype(np.float32)


def flatten(j, binb, mats, skip_skinned=True):
    """節點樹攤平成 Part 清單（世界座標）。"""
    parts = []

    def walk(i, parent):
        n = j["nodes"][i]
        w = parent @ node_local(n)
        if "mesh" in n and not (skip_skinned and "skin" in n):
            flip = np.linalg.det(w[:3, :3]) < 0
            nm = np.linalg.inv(w[:3, :3]).T
            for pr in j["meshes"][n["mesh"]]["primitives"]:
                mode = pr.get("mode", 4)
                if mode not in (4, 5, 6):
                    continue   # 線、點
                at = pr["attributes"]
                P = accessor(j, binb, at["POSITION"]).astype(np.float64)
                I = accessor(j, binb, pr["indices"]).reshape(-1).astype(np.int64) if "indices" in pr \
                    else np.arange(len(P))
                if mode == 5:   # strip → list
                    I = np.array([[I[k], I[k + 1], I[k + 2]] if k % 2 == 0 else [I[k + 1], I[k], I[k + 2]]
                                  for k in range(len(I) - 2)]).reshape(-1)
                elif mode == 6:
                    I = np.array([[I[0], I[k], I[k + 1]] for k in range(1, len(I) - 1)]).reshape(-1)
                Pw = (w[:3, :3] @ P.T).T + w[:3, 3]
                if "NORMAL" in at:
                    N = (nm @ accessor(j, binb, at["NORMAL"]).astype(np.float64).T).T
                    ln = np.linalg.norm(N, axis=1, keepdims=True)
                    ln[ln == 0] = 1
                    N = N / ln
                else:
                    N = None
                mi = pr.get("material", -1)
                mat = mats[mi] if mi >= 0 else Mat("default")
                UV = accessor(j, binb, at["TEXCOORD_0"]).astype(np.float32) if "TEXCOORD_0" in at else None
                if UV is not None:
                    UV = apply_uv_transform(UV, getattr(mat, "src_bc_transform", None))
                C = accessor(j, binb, at["COLOR_0"]).astype(np.float32) if "COLOR_0" in at else None
                if flip:
                    I = I.reshape(-1, 3)[:, ::-1].reshape(-1)
                tri = I.reshape(-1, 3)
                keep = (tri[:, 0] != tri[:, 1]) & (tri[:, 1] != tri[:, 2]) & (tri[:, 0] != tri[:, 2])
                I = tri[keep].reshape(-1)
                if N is None:
                    N = face_normals_to_vertex(Pw, I)
                parts.append(Part(mat, Pw, N, UV, I, C))
        for c in n.get("children", []):
            walk(c, w)

    for r in j["scenes"][j.get("scene", 0)]["nodes"]:
        walk(r, np.eye(4))
    return parts


def bbox(parts):
    P = np.vstack([p.P[np.unique(p.I)] for p in parts])
    return P.min(0), P.max(0)


def transform_parts(parts, M3, t):
    """p' = M3 @ p + t；法線用逆轉置。"""
    nm = np.linalg.inv(M3).T
    flip = np.linalg.det(M3) < 0
    for p in parts:
        p.P = (M3 @ p.P.T).T + t
        N = (nm @ p.N.T).T
        ln = np.linalg.norm(N, axis=1, keepdims=True)
        ln[ln == 0] = 1
        p.N = N / ln
        if flip:
            p.I = p.I.reshape(-1, 3)[:, ::-1].reshape(-1)


def rot_y(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rot_z(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def rot_x(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def level_pitch(parts):
    """二輪車：繞 x 軸轉，讓前後輪的最低點一樣高（Z1000 原檔有約 1° 俯仰，前輪浮空 2.4 cm）。回傳角度（度）。"""
    total = 0.0
    for _ in range(2):
        P = np.vstack([p.P[np.unique(p.I)] for p in parts])
        lo, hi = P[:, 2].min(), P[:, 2].max()
        zc, L = (lo + hi) / 2, hi - lo
        f = P[P[:, 2] > zc + 0.2 * L]
        r = P[P[:, 2] < zc - 0.2 * L]
        a, b = f[np.argmin(f[:, 1])], r[np.argmin(r[:, 1])]
        ang = math.degrees(math.atan2(a[1] - b[1], a[2] - b[2]))   # 前輪比後輪高 → 正值，往前壓
        if abs(ang) < 1e-3:
            break
        transform_parts(parts, rot_x(ang), np.zeros(3))
        total += ang
    return total


def normalise(parts, yaw, dims, roll=0.0, level=False):
    """轉向（yaw 繞 y、roll 繞 z 扶正、level 時繞 x 讓兩輪都著地）→ 縮放到目標尺寸 → x/z 置中、最低點貼地。
    回傳 (sx, sy, sz)。"""
    if yaw:
        transform_parts(parts, rot_y(yaw), np.zeros(3))
    if roll:
        transform_parts(parts, rot_z(roll), np.zeros(3))
    if level:
        ang = level_pitch(parts)
        if abs(ang) > 0.05:
            print("    level: pitch %.2f°" % ang)
    mn, mx = bbox(parts)
    size = mx - mn
    if "length" in dims and len(dims) == 1:
        s = dims["length"] / size[2]
        sc = np.array([s, s, s])
    elif "height" in dims and len(dims) == 1:
        s = dims["height"] / size[1]
        sc = np.array([s, s, s])
    else:
        s = dims["length"] / size[2]
        sc = np.array([dims.get("width", size[0] * s) / size[0], dims.get("height", size[1] * s) / size[1], s])
    c = np.array([(mn[0] + mx[0]) / 2, mn[1], (mn[2] + mx[2]) / 2])
    transform_parts(parts, np.diag(sc), -c * sc)
    return sc


# ================================================================ 材質整理
def fold_vertex_colors(parts):
    """COLOR_0：整塊同色就併進材質係數（黑色頂點色＝黑色材質），不同色才保留屬性。"""
    out = []
    for p in parts:
        if p.C is None:
            out.append(p)
            continue
        used = np.unique(p.I)
        c = p.C[used, :3]
        if c.std(0).max() < 0.02:
            col = c.mean(0)
            if np.all(col > 0.98):
                p.C = None
            else:
                m = p.mat.clone(p.mat.name + "_vc")
                m.base = [m.base[0] * col[0], m.base[1] * col[1], m.base[2] * col[2], m.base[3]]
                if max(col) < 0.02:   # 黑色：貼圖沒有意義
                    m.tex = None
                    m.mr = None
                m.vc_of = p.mat
                p.mat = m
                p.C = None
        out.append(p)
    # 同一個來源材質產生的頂點色變體共用同一個 Mat
    seen = {}
    for p in out:
        if hasattr(p.mat, "vc_of"):
            k = (id(p.mat.vc_of), tuple(round(x, 3) for x in p.mat.base), p.mat.tex is None)
            p.mat = seen.setdefault(k, p.mat)
    return out


def simplify_textures(mats, limit):
    """幾乎單色的貼圖換成係數；其餘縮到 limit。"""
    for m in mats:
        if m.mr is not None:
            mean, std = m.mr.stats()
            if std[1] < 8 and std[2] < 8:
                m.rough *= mean[1] / 255.0
                m.metal *= mean[2] / 255.0
                m.mr = None
        if m.tex is not None:
            mean, std = m.tex.stats()
            if std[:3].max() < 7 and (m.alpha != "MASK" or std[3] < 7):
                lin = srgb_to_lin(mean[:3] / 255.0)
                m.base = [m.base[0] * lin[0], m.base[1] * lin[1], m.base[2] * lin[2], m.base[3] * mean[3] / 255.0]
                m.tex = None
    for m in mats:
        if m.tex is not None:
            m.tex.limit(limit)
            m.tex.keep_alpha = m.alpha in ("BLEND", "MASK")
        if m.mr is not None:
            m.mr.limit(min(limit, 256))


def flatten_texture(m):
    """貼圖換成平均色（小零件：輪胎、車牌…）。"""
    if m.tex is not None:
        a = np.asarray(m.tex.img.convert("RGBA")).reshape(-1, 4).astype(np.float64) / 255.0
        if m.alpha != "OPAQUE":
            w = a[:, 3] + 1e-3
            rgb = (srgb_to_lin(a[:, :3]) * w[:, None]).sum(0) / w.sum()
            al = a[:, 3].mean()
        else:
            rgb = srgb_to_lin(a[:, :3]).mean(0)
            al = 1.0
        m.base = [m.base[0] * rgb[0], m.base[1] * rgb[1], m.base[2] * rgb[2], m.base[3] * al]
        m.tex = None
    if m.mr is not None:
        a = np.asarray(m.mr.img.convert("RGB")).reshape(-1, 3).astype(np.float64) / 255.0
        m.rough *= a[:, 1].mean()
        m.metal *= a[:, 2].mean()
        m.mr = None


def recolor(tex, rgb, hue=None, sat=0.25, grey=None, lights=None):
    """把車漆像素換成 rgb，保留原本的明暗。抓車漆的方式：hue＝(lo, hi) 色相範圍且飽和度 > sat
    （lo > hi 表示跨過 0，例如紅色 (0.93, 0.06)）；grey＝(vlo, vhi, smax) 灰色車漆：亮度在 vlo..vhi 且飽和度 < smax。
    lights＝倍數：比車漆亮這麼多倍的飽和像素當成畫在貼圖上的車燈，不換色（Logan 的尾燈）。"""
    im = np.asarray(tex.img.convert("RGB")).astype(np.float64) / 255.0
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    mx, mn = im.max(-1), im.min(-1)
    s = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    d = np.maximum(mx - mn, 1e-6)
    h = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) / 6.0
    if grey is not None:
        mask = (mx >= grey[0]) & (mx <= grey[1]) & (s < grey[2])
    else:
        in_hue = (h >= hue[0]) & (h <= hue[1]) if hue[0] <= hue[1] else (h >= hue[0]) | (h <= hue[1])
        mask = in_hue & (s > sat) & (mx > 0.08)
    ref = np.percentile(mx[mask], 90) if mask.any() else 1.0
    if lights:   # 畫在車漆貼圖上的尾燈：比車漆亮 lights 倍以上又飽和的像素（往外擴 2 px）保留原色
        lamp = mask & (mx > ref * lights) & (s > 0.5)
        for _ in range(2):
            grow = lamp.copy()
            for ax in (0, 1):
                for sh in (-1, 1):
                    grow |= np.roll(lamp, sh, axis=ax)
            lamp = grow & mask
        mask &= ~lamp
        region = lamp.copy()   # 車燈範圍再往外 3 px：之後把落在裡面的三角形拆成獨立的 lights 材質，換車色時才不會被染到
        for _ in range(3):
            grow = region.copy()
            for ax in (0, 1):
                for sh in (-1, 1):
                    grow |= np.roll(region, sh, axis=ax)
            region = grow
        tex.lamp_region = region
    shade = np.clip(mx / ref, 0, 1.25)
    tgt = np.array(rgb, np.float64) / 255.0
    out = im.copy()
    out[mask] = np.clip(tgt[None, :] * shade[mask][:, None], 0, 1)
    tex.img = Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGB")
    tex._enc = None
    return mask.mean()


def build_materials(parts, spec):
    """依 spec 改名／丟棄／調色盤合併，回傳新的 parts（每個 Part.mat 是最終 Mat）。"""
    paint = set(spec.get("paint") or [])
    rename = dict(spec.get("rename") or {})
    drop = set(spec.get("drop") or [])
    glass = set(spec.get("glass") or [])
    flat = set(spec.get("flatten") or [])
    keep = set(spec.get("keep") or [])
    parts = [p for p in parts if p.mat.name not in drop]
    mats = []
    for p in parts:
        if p.mat not in mats:
            mats.append(p.mat)
    for m in mats:
        base_name = getattr(m, "vc_of", m).name
        if base_name in flat:
            flatten_texture(m)
        if base_name in glass and m.alpha == "OPAQUE":
            m.alpha = "BLEND"
            if m.base[3] >= 0.99:
                m.base[3] = 0.6
        if m.alpha == "MASK" and m.tex is None:
            m.alpha = "OPAQUE"
    simplify_textures(mats, spec.get("tex", 512))
    done = set()
    for m in mats:   # 非車漆貼圖的換色（例：公車原檔的亮藍色輪框改成銀灰色）；共用的貼圖只換一次
        rc = (spec.get("tex_recolor") or {}).get(getattr(m, "vc_of", m).name)
        if rc and m.tex is not None and id(m.tex) not in done:
            done.add(id(m.tex))
            rc = dict(rc)
            frac = recolor(m.tex, rc.pop("rgb"), **rc)
            print("    recolor %s: %.1f%% of texture" % (m.name, frac * 100))
    for m in mats:   # 只有金屬度/粗糙度貼圖、沒有顏色貼圖：換成平均值，才能併進調色盤
        if m.tex is None and m.mr is not None:
            flatten_texture(m)
    paint_mats = [m for m in mats if getattr(m, "vc_of", m).name in paint and not hasattr(m, "vc_of")]
    multi_paint = len(paint_mats) > 1 and all(m.tex is None and m.mr is None for m in paint_mats)
    for m in mats:
        base_name = getattr(m, "vc_of", m).name
        m.pal_group = None
        if m in paint_mats:
            if multi_paint:   # 多色車身（公車）：併成一個調色盤 paint 材質
                m.palette = True
                m.pal_group = "paint"
                continue
            m.name = "paint"
            if spec.get("paint_color") is not None:
                m.base = list(srgb_to_lin(spec["paint_color"])) + [1.0]
                m.tex = None
            if spec.get("paint_rough") is not None:
                m.rough = spec["paint_rough"]
            if spec.get("recolor") and m.tex is not None:
                rc = dict(spec["recolor"])
                frac = recolor(m.tex, rc.pop("rgb"), **rc)
                print("    recolor: %.1f%% of paint texture" % (frac * 100))
            m.palette = False
        elif base_name in keep:
            m.name = rename.get(base_name, base_name)
            m.palette = False
        elif m.tex is None and m.mr is None and spec.get("palette", True):
            m.palette = True
        else:
            m.name = rename.get(base_name, m.name)
            m.palette = False
        if m.alpha == "BLEND" and m.name != "paint" and not m.palette and m.tex is None:
            m.palette = True
    # 調色盤：車身（多色）一張、其他不透明一張、半透明一張
    pal = {"paint": [], "OPAQUE": [], "BLEND": []}
    for m in mats:
        if m.palette:
            pal[m.pal_group or ("BLEND" if m.alpha == "BLEND" else "OPAQUE")].append(m)
    pal_mat = {}
    for group, lst in pal.items():
        if not lst:
            continue
        mode = "BLEND" if group == "BLEND" else "OPAQUE"
        if len(lst) == 1 and group == "OPAQUE":
            lst[0].palette = False
            lst[0].name = rename.get(getattr(lst[0], "vc_of", lst[0]).name, "trim")
            continue
        if len(lst) == 1 and group == "BLEND":
            lst[0].palette = False
            lst[0].name = "glass"
            continue
        N = 16
        assert len(lst) <= N * N
        base = np.zeros((N, N, 4), np.uint8)
        mr = np.zeros((N, N, 3), np.uint8)
        em = np.zeros((N, N, 3), np.uint8)
        any_em = False
        for k, m in enumerate(lst):
            y, x = divmod(k, N)
            base[y, x, :3] = np.round(lin_to_srgb(m.base[:3]) * 255)
            base[y, x, 3] = round(min(1, max(0, m.base[3])) * 255)
            mr[y, x] = [255, round(min(1, m.rough) * 255), round(min(1, m.metal) * 255)]
            if max(m.emis) > 0:
                any_em = True
                em[y, x] = np.round(lin_to_srgb(np.clip(m.emis, 0, 1)) * 255)
            m.pal_uv = ((x + 0.5) / N, (y + 0.5) / N)
        pm = Mat({"paint": "paint", "OPAQUE": "trim", "BLEND": "glass"}[group])
        pm.alpha = mode
        pm.double = any(m.double for m in lst)
        pm.tex = Tex(Image.fromarray(base, "RGBA" if mode == "BLEND" else "RGBA"), "color", NEAREST,
                     keep_alpha=(mode == "BLEND"))
        if mode == "OPAQUE":
            pm.tex.img = pm.tex.img.convert("RGB")
        pm.mr = Tex(Image.fromarray(mr, "RGB"), "data", NEAREST)
        if any_em:
            pm.em = Tex(Image.fromarray(em, "RGB"), "color", NEAREST)
            pm.emis = [1.0, 1.0, 1.0]
        pm.metal = 1.0
        pm.rough = 1.0
        pm.members = [m.name for m in lst]
        for m in lst:
            pal_mat[id(m)] = pm
    out = []
    for p in parts:
        if id(p.mat) in pal_mat:
            pm = pal_mat[id(p.mat)]
            uv = np.tile(np.array(p.mat.pal_uv, np.float32), (len(p.P), 1))
            out.append(Part(pm, p.P, p.N, uv, p.I, None))
        else:
            if p.mat.tex is None and p.mat.mr is None:
                p.UV = None
            out.append(p)
    out = split_lamps(out)
    # 改成同一個名字、用同一張貼圖的材質合併（例：公車前後輪兩個材質共用一張輪胎貼圖）
    alias = {}
    for p in out:
        m = p.mat
        if m.palette or id(m) in alias:
            continue
        for q in out:
            n = q.mat
            if n is m:
                break
            if (not n.palette and n.name == m.name and n.tex is m.tex and n.mr is m.mr and n.alpha == m.alpha
                    and alias.get(id(n), n) is n):
                alias[id(m)] = n
                break
    for p in out:
        if id(p.mat) in alias:
            p.mat = alias[id(p.mat)]
    # 名稱不可重複
    names = {}
    for p in out:
        m = p.mat
        if id(m) in names:
            continue
        nm = m.name
        k = 2
        while nm in names.values():
            nm = "%s_%d" % (m.name, k)
            k += 1
        m.name = nm
        names[id(m)] = nm
    return out


def split_lamps(parts):
    """paint 貼圖上畫的車燈（recolor 的 lights 找到的範圍）：三個頂點的 UV 都落在範圍內的三角形拆成
    同一張貼圖的 lights 材質。paint.color 換車色時尾燈才會維持紅色。"""
    out, lamp_mat = [], {}
    for p in parts:
        region = getattr(p.mat.tex, "lamp_region", None) if p.mat.name == "paint" else None
        if region is None or p.UV is None:
            out.append(p)
            continue
        h, w = region.shape
        uv = np.mod(p.UV, 1.0)
        px = np.clip((uv[:, 0] * w).astype(int), 0, w - 1)
        py = np.clip((uv[:, 1] * h).astype(int), 0, h - 1)
        inside = region[py, px][p.I.reshape(-1, 3)].all(1)
        if not inside.any():
            out.append(p)
            continue
        if id(p.mat) not in lamp_mat:
            lm = p.mat.clone("lights")
            lm.palette = False
            lamp_mat[id(p.mat)] = lm
        tri = p.I.reshape(-1, 3)
        out.append(Part(p.mat, p.P, p.N, p.UV, tri[~inside].reshape(-1), p.C))
        out.append(Part(lamp_mat[id(p.mat)], p.P, p.N, p.UV, tri[inside].reshape(-1), p.C))
        print("    lamps: %d tris of paint -> lights" % inside.sum())
    return out


def split_paint_wheels(parts):
    """車輪跟車身共用一張 paint 貼圖時（bus_city），把車輪拆成同貼圖的 wheel 材質：換車色時輪框才不會跟著染成
    車身色，閃紅也只閃車身。先找輪胎（paint 裡貼地、高 > 0.5 m、側面近正方形、位置低的連通塊），再把外框落在
    任一輪胎外框裡（放寬 2 cm）的連通塊（輪框、輪轂、螺帽）一起拆出來。多 1 個 draw call。"""
    H = bbox(parts)[1][1]
    out, wm = [], None
    for p in parts:
        if p.mat.name != "paint":
            out.append(p)
            continue
        lab = components(p.P, p.I)
        tri = p.I.reshape(-1, 3)
        _, inv = np.unique(lab, return_inverse=True)
        V = p.P[tri]
        n = inv.max() + 1
        lo = np.full((n, 3), np.inf)
        hi = np.full((n, 3), -np.inf)
        np.minimum.at(lo, inv, V.min(1))
        np.maximum.at(hi, inv, V.max(1))
        sz = hi - lo
        tyre = (lo[:, 1] < 0.03) & (sz[:, 1] > 0.5) & (hi[:, 1] < 0.45 * H) & \
            (np.abs(sz[:, 1] - sz[:, 2]) < 0.15 * sz[:, 1])
        wheel = tyre.copy()
        for k in np.nonzero(tyre)[0]:
            wheel |= np.all(lo >= lo[k] - 0.02, 1) & np.all(hi <= hi[k] + 0.02, 1)
        sel = wheel[inv]
        if not sel.any():
            out.append(p)
            continue
        if wm is None:
            wm = p.mat.clone("wheel")
        out.append(Part(p.mat, p.P, p.N, p.UV, tri[~sel].reshape(-1), p.C))
        out.append(Part(wm, p.P, p.N, p.UV, tri[sel].reshape(-1), p.C))
        print("    wheels: %d tyres, %d tris of paint -> wheel" % (tyre.sum(), sel.sum()))
    return out


def merge_by_material(parts):
    groups = {}
    order = []
    for p in parts:
        k = id(p.mat)
        if k not in groups:
            groups[k] = []
            order.append(k)
        groups[k].append(p)
    out = []
    for k in order:
        ps = groups[k]
        nv = 0
        P, N, UV, I, C = [], [], [], [], []
        need_uv = ps[0].mat.tex is not None or ps[0].mat.mr is not None
        need_c = any(p.C is not None for p in ps)
        for p in ps:
            used, inv = np.unique(p.I, return_inverse=True)
            P.append(p.P[used])
            N.append(p.N[used])
            if need_uv:
                UV.append(p.UV[used] if p.UV is not None else np.zeros((len(used), 2), np.float32))
            if need_c:
                C.append(p.C[used, :3] if p.C is not None else np.ones((len(used), 3), np.float32))
            I.append(inv.reshape(-1) + nv)
            nv += len(used)
        out.append(Part(ps[0].mat, np.vstack(P), np.vstack(N), np.vstack(UV) if need_uv else None,
                        np.concatenate(I), np.vstack(C) if need_c else None))
    return out


def tri_count(parts):
    return sum(len(p.I) // 3 for p in parts)


# ================================================================ 寫檔
def write_parts(path, parts, root_name, asset, emit_tex=True):
    w = Writer()
    for p in parts:
        m = p.mat
        md = {"name": m.name, "pbrMetallicRoughness": {
            "baseColorFactor": [round(float(x), 5) for x in m.base],
            "metallicFactor": round(float(m.metal), 4), "roughnessFactor": round(float(m.rough), 4)}}
        if emit_tex and m.tex is not None:
            md["pbrMetallicRoughness"]["baseColorTexture"] = {"index": w.texture(m.tex)}
        if emit_tex and m.mr is not None:
            md["pbrMetallicRoughness"]["metallicRoughnessTexture"] = {"index": w.texture(m.mr)}
        if max(m.emis) > 0:
            md["emissiveFactor"] = [round(float(x), 5) for x in m.emis]
            if emit_tex and getattr(m, "em", None) is not None:
                md["emissiveTexture"] = {"index": w.texture(m.em)}
        if m.alpha != "OPAQUE":
            md["alphaMode"] = m.alpha
            if m.alpha == "MASK":
                md["alphaCutoff"] = m.cutoff
        if m.double:
            md["doubleSided"] = True
        w.j["materials"].append(md)
        attrs = {"POSITION": w.acc(p.P.astype(np.float32), "VEC3", 34962, True),
                 "NORMAL": w.acc(p.N.astype(np.float32), "VEC3", 34962)}
        if p.UV is not None and (m.tex is not None or m.mr is not None):
            attrs["TEXCOORD_0"] = w.acc(p.UV.astype(np.float32), "VEC2", 34962)
        if p.C is not None:
            attrs["COLOR_0"] = w.acc(p.C.astype(np.float32), "VEC3", 34962)
        idx = p.I.astype(np.uint16 if len(p.P) < 65536 else np.uint32)
        ia = w.acc(idx, "SCALAR", 34963)
        w.j["meshes"].append({"name": m.name, "primitives": [
            {"attributes": attrs, "indices": ia, "material": len(w.j["materials"]) - 1}]})
        w.j["nodes"].append({"name": m.name, "mesh": len(w.j["meshes"]) - 1})
    data = w.glb(root_name, asset)
    Path(path).write_bytes(data)
    return len(data)


def find_gltfpack(arg):
    for c in [arg, os.environ.get("GLTFPACK"), shutil.which("gltfpack")]:
        if c and os.path.exists(c):
            return c
    raise SystemExit("找不到 gltfpack：用 --gltfpack 指定（npm i gltfpack）")


def components(P, I, q=1e-4):
    """依共用頂點（位置焊接後）分連通塊，回傳每個三角形的塊編號。"""
    key = np.round(P / q).astype(np.int64)
    _, weld = np.unique(key, axis=0, return_inverse=True)
    weld = weld.reshape(-1)
    tri = weld[I.reshape(-1, 3)]
    par = np.arange(weld.max() + 1)

    def find(a):
        r = a
        while par[r] != r:
            r = par[r]
        while par[a] != r:
            par[a], a = r, par[a]
        return r

    for a, b, c in tri:
        ra, rb, rc = find(a), find(b), find(c)
        if rb != ra:
            par[rb] = ra
        if rc != ra and rc != rb:
            par[rc] = ra
    return np.array([find(t) for t in tri[:, 0]])


def split_components(parts, min_tris=24, drop_diag=0.025):
    """按連通塊拆開：每塊變成自己的 Part（材質名稱加 __c 編號），gltfpack 依各塊自己的大小算誤差。
    回傳 (要簡化的, 原樣保留的小塊, 丟掉的三角形數)。對角線 < drop_diag 的碎件（螺絲、小墊片）直接丟掉。"""
    work, held, dropped = [], [], 0
    for p in parts:
        lab = components(p.P, p.I)
        tri = p.I.reshape(-1, 3)
        u, inv, cnt = np.unique(lab, return_inverse=True, return_counts=True)
        V = p.P[tri]                                            # (T,3,3)
        lo = np.full((len(u), 3), np.inf)
        hi = np.full((len(u), 3), -np.inf)
        np.minimum.at(lo, inv, V.min(1))
        np.maximum.at(hi, inv, V.max(1))
        diag = np.linalg.norm(hi - lo, axis=1)
        tiny = diag < drop_diag
        dropped += int(cnt[tiny].sum())
        small = (~tiny) & (cnt < min_tris)
        if small.any():
            held.append(Part(p.mat, p.P, p.N, p.UV, tri[small[inv]].reshape(-1), p.C))
        for k in np.nonzero((~tiny) & (~small))[0]:
            m = p.mat.clone("%s__c%d" % (p.mat.name, k))
            m.split_of = p.mat
            work.append(Part(m, p.P, p.N, p.UV, tri[inv == k].reshape(-1), p.C))
    return merge_by_material(work), held, dropped


def simplify(parts, budget, gltfpack, tmpdir, tag, split=False):
    """用 gltfpack 簡化到 budget 以下；回傳新的 parts（材質用名稱對回去）。
    split=True：先按連通塊拆開再簡化（誤差依每塊自己的大小算，把手、輻條這種細件才不會被吃掉）。"""
    tri = tri_count(parts)
    if tri <= budget:
        return parts
    src = os.path.join(tmpdir, tag + "_in.glb")
    dst = os.path.join(tmpdir, tag + "_out.glb")
    held, dropped = [], 0
    if split:
        work, held, dropped = split_components(parts, **(split if isinstance(split, dict) else {}))
    else:
        work = parts
    keep = tri_count(held)
    write_parts(src, work, tag, {"version": "2.0"}, emit_tex=True)
    by_name = {p.mat.name: getattr(p.mat, "split_of", p.mat) for p in work}
    ratio = max(0.02, (budget - keep) / max(1, tri_count(work)) * 0.98)
    tries = [(0.01, []), (0.02, []), (0.05, []), (0.05, ["-sa"])] if not split else \
        [(0.01, []), (0.02, []), (0.03, []), (0.05, []), (0.08, []), (0.08, ["-sa"])]
    for se, extra in tries:
        cmd = [gltfpack, "-i", src, "-o", dst, "-noq", "-km"] + (["-sp"] if split else []) + \
            ["-si", "%.4f" % ratio, "-se", str(se)] + extra
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit("gltfpack failed: %s\n%s" % (" ".join(cmd), r.stderr))
        j, binb = load_glb(dst)
        assert not j.get("extensionsRequired"), j.get("extensionsRequired")
        mats = [by_name[m["name"]] for m in j.get("materials", [])]
        out = flatten(j, binb, mats)
        if tri_count(out) + keep <= budget:
            break
    out = out + held
    for p in out:
        if p.mat.tex is None and p.mat.mr is None:
            p.UV = None
    print("    simplify %s: %d -> %d tris (ratio %.3f, -se %s %s%s)" % (
        tag, tri, tri_count(out), ratio, se, " ".join(extra),
        " split: kept %d small, dropped %d tiny" % (keep, dropped) if split else ""))
    return merge_by_material(out)


def credit_text(src, changes):
    lic = src["lic"]
    return "%s by %s, %s (%s). Source: %s. Modified by avm-adas-probe (tools/prep_models.py): %s" % (
        src["title"], src["author"], lic[0], lic[1], src["url"], changes)


# ================================================================ 剛體模型
def load_rigid(spec, src_dir):
    src = SOURCES[spec["src"]]
    j, binb = load_glb(src_dir / src["file"])
    cache = {}
    mats = read_materials(j, binb, cache)
    parts = flatten(j, binb, mats)
    parts = fold_vertex_colors(parts)
    sc = normalise(parts, spec.get("yaw", 0), spec["dims"], spec.get("roll", 0), spec["type"] in TWO_WHEEL)
    return parts, sc


def process_rigid(spec, src_dir, out_dir, gltfpack, tmpdir):
    src = SOURCES[spec["src"]]
    print("[%s] %s" % (spec["id"], src["file"]))
    parts, sc = load_rigid(spec, src_dir)
    parts = build_materials(parts, spec)
    if spec.get("wheels_in_paint"):
        parts = split_paint_wheels(parts)
    parts = merge_by_material(parts)
    parts = simplify(parts, spec["tris"], gltfpack, tmpdir, spec["id"], split=spec.get("split", False))
    return finish(spec, parts, out_dir, changes_of(spec))


def changes_of(spec, extra=""):
    ch = ["拿掉線段與 KHR_materials 擴充、法線/AO 貼圖，依材質合併網格，小材質合併成調色盤貼圖",
          "換成公尺並轉向（車頭／臉朝 +Z）、置中貼地，縮放到 %s" % ", ".join(
              "%s %.2f m" % (k, v) for k, v in spec.get("dims", {}).items()),
          "以 gltfpack 簡化到 %d 三角形以下、貼圖縮到 %d px" % (spec.get("tris", 0), spec.get("tex", 512))]
    if spec.get("paint"):
        ch.append("車身材質命名 paint")
    if spec.get("note"):
        ch.append(spec["note"])
    if extra:
        ch.append(extra)
    return "；".join(ch)


def finish(spec, parts, out_dir, changes, extra_src=None):
    src = SOURCES[spec["src"]]
    cr = credit_text(src, changes)
    if extra_src:
        cr += " | " + credit_text(SOURCES[extra_src], "烘成靜態坐姿並擺到車上（上身前傾、兩段式 IK 擺手腳）")
    asset = {"version": "2.0", "generator": "avm-adas-probe tools/prep_models.py", "copyright": cr}
    path = out_dir / (spec["id"] + ".glb")
    n = write_parts(path, parts, spec["id"], asset)
    mn, mx = bbox(parts)
    size = (mx - mn)
    info = dict(file=path.name, length=round(float(size[2]), 3), width=round(float(size[0]), 3),
                height=round(float(size[1]), 3), tris=tri_count(parts),
                paint=any(p.mat.name == "paint" for p in parts), anim=[],
                paintTex=any(p.mat.name == "paint" and p.mat.tex is not None for p in parts),
                tint=bool(spec.get("tint", False)),
                draws=len(parts), kb=round(n / 1024, 1), materials=[p.mat.name for p in parts])
    print("    -> %s  %d tris, %d draws, %.0f KB, size L%.2f W%.2f H%.2f, ymin %.3f" % (
        path.name, info["tris"], info["draws"], n / 1024, size[2], size[0], size[1], mn[1]))
    return info


# ================================================================ 蒙皮（three.js r128 的算法）
def quat_slerp(a, b, t):
    a = np.asarray(a, float)
    b = np.asarray(b, float)
    d = float(np.dot(a, b))
    if d < 0:
        b, d = -b, -d
    if d > 0.9995:
        r = a + t * (b - a)
        return r / np.linalg.norm(r)
    th = math.acos(d)
    return (math.sin((1 - t) * th) * a + math.sin(t * th) * b) / math.sin(th)


def sample_anim(j, binb, anim, t):
    """回傳 {node: {path: value}}（LINEAR / STEP）。"""
    over = {}
    for ch in anim["channels"]:
        s = anim["samplers"][ch["sampler"]]
        times = accessor(j, binb, s["input"]).reshape(-1)
        vals = accessor(j, binb, s["output"])
        k = int(np.searchsorted(times, t, side="right") - 1)
        if k < 0:
            v = vals[0]
        elif k >= len(times) - 1 or s.get("interpolation") == "STEP":
            v = vals[min(k, len(vals) - 1)]
        else:
            f = (t - times[k]) / max(times[k + 1] - times[k], 1e-9)
            if ch["target"]["path"] == "rotation":
                v = quat_slerp(vals[k], vals[k + 1], f)
            else:
                v = vals[k] * (1 - f) + vals[k + 1] * f
        over.setdefault(ch["target"]["node"], {})[ch["target"]["path"]] = [float(x) for x in v]
    return over


def world_matrices(j, over):
    W = {}
    parent = {}
    for i, n in enumerate(j["nodes"]):
        for c in n.get("children", []):
            parent[c] = i

    def walk(i, pm):
        W[i] = pm @ node_local(j["nodes"][i], over.get(i) if over else None)
        for c in j["nodes"][i].get("children", []):
            walk(c, W[i])

    for r in j["scenes"][j.get("scene", 0)]["nodes"]:
        walk(r, np.eye(4))
    return W, parent


def skin_vertices(j, binb, node_idx, W):
    """world = Σ w·(boneWorld·IBM)·v。three.js r128 的 bindMode 'attached' 每幀用 matrixWorld 反矩陣當
    bindMatrixInverse，所以 mesh 節點自己的變換會抵消，結果跟 glTF 規格一致（已對照瀏覽器 boneTransform 數值）。"""
    n = j["nodes"][node_idx]
    skin = j["skins"][n["skin"]]
    ibm = accessor(j, binb, skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
    joints = skin["joints"]
    B = np.stack([W[jn] @ ibm[k] for k, jn in enumerate(joints)])     # (J,4,4)
    prim0 = j["meshes"][n["mesh"]]["primitives"][0]
    at = prim0["attributes"]
    P = accessor(j, binb, at["POSITION"]).astype(np.float64)
    N = accessor(j, binb, at["NORMAL"]).astype(np.float64)
    J = accessor(j, binb, at["JOINTS_0"]).astype(np.int64)
    Wt = accessor(j, binb, at["WEIGHTS_0"]).astype(np.float64)
    Wt = Wt / np.maximum(Wt.sum(1, keepdims=True), 1e-9)
    M = np.einsum("vk,vkij->vij", Wt, B[J])                           # (V,4,4)
    Pw = np.einsum("vij,vj->vi", M[:, :3, :3], P) + M[:, :3, 3]
    Nw = np.einsum("vij,vj->vi", np.linalg.inv(M[:, :3, :3]).transpose(0, 2, 1), N)
    Nw /= np.maximum(np.linalg.norm(Nw, axis=1, keepdims=True), 1e-9)
    return Pw, Nw


def subtree(j, i):
    out = [i]
    for c in j["nodes"][i].get("children", []):
        out += subtree(j, c)
    return out


def rot_between(a, b):
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if np.linalg.norm(v) < 1e-9:
        if c > 0:
            return np.eye(3)
        ax = np.cross(a, [1, 0, 0])
        if np.linalg.norm(ax) < 1e-6:
            ax = np.cross(a, [0, 1, 0])
        ax /= np.linalg.norm(ax)
        return axis_angle(ax, math.pi)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))


def axis_angle(ax, ang):
    ax = np.asarray(ax, float) / np.linalg.norm(ax)
    K = np.array([[0, -ax[2], ax[1]], [ax[2], 0, -ax[0]], [-ax[1], ax[0], 0]])
    return np.eye(3) + math.sin(ang) * K + (1 - math.cos(ang)) * K @ K


def rotate_subtree(j, W, root, R, pivot):
    M = np.eye(4)
    M[:3, :3] = R
    M[:3, 3] = pivot - R @ pivot
    for i in subtree(j, root):
        W[i] = M @ W[i]


def pos(W, i):
    return W[i][:3, 3].copy()


def two_bone_ik(j, W, a, b, c, target, pole, keep_end_rot=False):
    """a→b→c 三個關節（例：上臂→前臂→手），把 c 移到 target；pole 決定 b（手肘／膝蓋）往哪邊彎。"""
    A, B, C = pos(W, a), pos(W, b), pos(W, c)
    end_rot = W[c][:3, :3].copy()
    la, lb = np.linalg.norm(B - A), np.linalg.norm(C - B)
    T = np.asarray(target, float)
    d = np.linalg.norm(T - A)
    d = min(max(d, abs(la - lb) + 1e-4), la + lb - 1e-4)
    dirAT = (T - A) / np.linalg.norm(T - A)
    pv = np.asarray(pole, float) - np.dot(pole, dirAT) * dirAT
    pv /= np.linalg.norm(pv)
    cos_a = (la * la + d * d - lb * lb) / (2 * la * d)
    sin_a = math.sqrt(max(0.0, 1 - cos_a * cos_a))
    B2 = A + la * (cos_a * dirAT + sin_a * pv)
    rotate_subtree(j, W, a, rot_between(B - A, B2 - A), A)
    C1 = pos(W, c)
    rotate_subtree(j, W, b, rot_between(C1 - B2, A + dirAT * d - B2), B2)
    if keep_end_rot:   # 腳掌維持原本的世界方向（平踩）
        R = end_rot @ np.linalg.inv(W[c][:3, :3])
        rotate_subtree(j, W, c, R / np.cbrt(np.linalg.det(R)), pos(W, c))


# ================================================================ 騎士
def pose_rider(j, binb, pose):
    """坐姿 → 前傾 → 手腳 IK。回傳 (world matrices, 名稱→節點)。"""
    anim = j["animations"][0]
    over = sample_anim(j, binb, anim, 0.0)
    W, _ = world_matrices(j, over)
    name = {n.get("name"): i for i, n in enumerate(j["nodes"])}
    if "lean" in pose:   # lean：骨盆→脖子連線相對鉛直往前傾的角度（坐姿原本微微後仰）
        sp, pel, nk = name["Bip01_Spine1"], name["Bip01_Pelvis"], name["Bip01_Neck"]

        def tilt(th):
            R = axis_angle([1, 0, 0], th)
            v = pos(W, sp) + R @ (pos(W, nk) - pos(W, sp)) - pos(W, pel)
            return math.atan2(v[2], v[1])

        goal, lo, hi = math.radians(pose["lean"]), -1.0, 1.5
        for _ in range(40):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if tilt(mid) < goal else (lo, mid)
        rotate_subtree(j, W, sp, axis_angle([1, 0, 0], (lo + hi) / 2), pos(W, sp))
    if pose.get("head_up"):   # 前傾時頭抬回來看前方
        hd = name["Bip01_Neck"]
        rotate_subtree(j, W, hd, axis_angle([1, 0, 0], -math.radians(pose["head_up"])), pos(W, hd))
    return W, name


def rider_points(j, binb, W, name, node_mesh):
    P, _ = skin_vertices(j, binb, node_mesh, W)
    pel = pos(W, name["Bip01_Pelvis"])
    near = (np.abs(P[:, 0] - pel[0]) < 0.12) & (np.abs(P[:, 2] - pel[2]) < 0.12)
    seat = np.array([pel[0], P[near, 1].min(), pel[2]])
    return P, seat


def build_rider(spec, src_dir, veh_parts, tmpdir, gltfpack):
    """回傳騎士 parts（已放到車上的最終座標）。"""
    rs = SOURCES["rb_sit"]
    j, binb = load_glb(src_dir / rs["file"])
    pose = spec["pose"]
    W, name = pose_rider(j, binb, pose)
    node_mesh = [i for i, n in enumerate(j["nodes"]) if "skin" in n][0]
    k = pose.get("scale", 1.0)   # Rocketbox 男性 1.81 m，縮到台灣成年男性平均身高附近
    if k != 1.0:
        S = np.diag([k, k, k, 1.0])
        for i in W:
            W[i] = S @ W[i]
    # 先算目前臀部底點，整副骨架平移到座墊
    _, seat0 = rider_points(j, binb, W, name, node_mesh)
    off = np.asarray(pose["seat"], float) - seat0
    for i in W:
        W[i] = W[i].copy()
        W[i][:3, 3] += off
    # 手：上臂→前臂→手腕
    for side, tgt in (("L", pose["hands"][0]), ("R", pose["hands"][1])):
        sx = 1 if side == "L" else -1
        two_bone_ik(j, W, name["Bip01_%s_UpperArm" % side], name["Bip01_%s_Forearm" % side],
                    name["Bip01_%s_Hand" % side], tgt, pole=[sx * 0.7, -0.4, -0.6])
        if pose.get("wrist"):   # 手腕順著前臂往下壓一點，手掌握住把手
            h = name["Bip01_%s_Hand" % side]
            fa = name["Bip01_%s_Forearm" % side]
            d = pos(W, h) - pos(W, fa)
            rotate_subtree(j, W, h, axis_angle(np.cross(d, [0, 1, 0]), math.radians(pose["wrist"])), pos(W, h))
    # 腳：大腿→小腿→腳踝
    for side, tgt in (("L", pose["feet"][0]), ("R", pose["feet"][1])):
        sx = 1 if side == "L" else -1
        two_bone_ik(j, W, name["Bip01_%s_Thigh" % side], name["Bip01_%s_Calf" % side],
                    name["Bip01_%s_Foot" % side], tgt, pole=[sx * pose.get("knee_out", 0.3), 0.3, 1.0],
                    keep_end_rot=True)
    P, N = skin_vertices(j, binb, node_mesh, W)
    cache = {}
    mats = read_materials(j, binb, cache)
    at = j["meshes"][j["nodes"][node_mesh]["mesh"]]["primitives"]
    UV = accessor(j, binb, at[0]["attributes"]["TEXCOORD_0"]).astype(np.float32)
    parts = []
    head_v = None
    for pr in at:
        I = accessor(j, binb, pr["indices"]).reshape(-1).astype(np.int64)
        parts.append(Part(mats[pr["material"]], P, N, UV, I, None))
        if mats[pr["material"]].name.endswith("_head"):
            head_v = P[np.unique(I)]
    helmet = None
    if pose.get("helmet"):
        helmet = make_helmet(head_v, **pose["helmet"])
    return parts, helmet


def make_helmet(head_v, color, style="open", visor=(0.02, 0.02, 0.025)):
    """依頭部頂點做一頂安全帽（台灣常見的 3/4 罩或全罩），頂點色：帽殼 color、全罩的面罩深色。"""
    top = head_v[:, 1].max()
    cr = head_v[head_v[:, 1] > top - 0.13]
    lo, hi = cr.min(0), cr.max(0)
    pad = 0.024
    c = np.array([(lo[0] + hi[0]) / 2, top - 0.105, (lo[2] + hi[2]) / 2 - 0.005])
    r = np.array([(hi[0] - lo[0]) / 2 + pad, 0.105 + pad, (hi[2] - lo[2]) / 2 + pad])
    nt, nph = 8, 22
    full = style == "full"
    th_front, th_side, th_back = (math.radians(118), math.radians(118), math.radians(122)) if full else \
        (math.radians(66), math.radians(100), math.radians(112))
    P, N, C = [], [], []
    col = np.array(srgb_to_lin(color))
    vis = np.array(srgb_to_lin(visor))
    for i in range(nt + 1):
        t = i / nt
        for kk in range(nph):
            ph = 2 * math.pi * kk / nph                 # ph=0 → 臉的方向（+Z）
            f = max(0.0, math.cos(ph)) ** 1.5
            side = abs(math.sin(ph))
            thmax = th_back - (th_back - th_side) * side if math.cos(ph) <= 0 else th_side - (th_side - th_front) * f
            th = t * thmax
            d = np.array([math.sin(th) * math.sin(ph), math.cos(th), math.sin(th) * math.cos(ph)])
            P.append(c + d * r)
            n = d / r
            N.append(n / np.linalg.norm(n))
            in_visor = full and math.cos(ph) > 0.55 and math.radians(62) < th < math.radians(102)
            C.append(vis if in_visor else col)
    I = []
    for i in range(nt):
        for kk in range(nph):
            a = i * nph + kk
            b = i * nph + (kk + 1) % nph
            a2, b2 = a + nph, b + nph
            I += [a, a2, b, b, a2, b2]
    # 帽緣：最後一圈往內縮一點做出厚度
    base = len(P)
    for kk in range(nph):
        q = P[nt * nph + kk]
        P.append(c + (q - c) * 0.9)
        N.append(-N[nt * nph + kk])
        C.append(col)
    for kk in range(nph):
        a = nt * nph + kk
        b = nt * nph + (kk + 1) % nph
        I += [a, base + kk, b, b, base + kk, base + (kk + 1) % nph]
    m = Mat("helmet")
    m.metal, m.rough, m.double = 0.1, 0.3, True
    return Part(m, np.array(P), np.array(N), None, np.array(I, np.int64), np.array(C, np.float32))


def atlas_two(m1, m2, size):
    """兩張貼圖並排成一張 (2·size)×size 圖集，回傳 (Tex, uv 轉換函式)。"""
    a = m1.tex.img.convert("RGB").resize((size, size), Image.LANCZOS)
    b = m2.tex.img.convert("RGB").resize((size, size), Image.LANCZOS)
    im = Image.new("RGB", (size * 2, size))
    im.paste(a, (0, 0))
    im.paste(b, (size, 0))
    t = Tex(im, "color", {"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071})
    return t


def merge_atlas(parts, n1, n2, newname, size):
    """把材質 n1、n2（UV 都在 0..1）合成同一張圖集的單一材質。"""
    p1 = [p for p in parts if p.mat.name == n1]
    p2 = [p for p in parts if p.mat.name == n2]
    if not p1 or not p2:
        return parts
    t = atlas_two(p1[0].mat, p2[0].mat, size)
    m = p1[0].mat.clone(newname)
    m.tex = t
    m.mr = None
    out = []
    for p in parts:
        if p.mat.name == n1 or p.mat.name == n2:
            uv = p.UV.copy()
            uv[:, 0] = np.clip(uv[:, 0], 0.002, 0.998) * 0.5 + (0.0 if p.mat.name == n1 else 0.5)
            out.append(Part(m, p.P, p.N, uv, p.I, None))
        else:
            out.append(p)
    return out


def process_rider(spec, src_dir, out_dir, gltfpack, tmpdir):
    src = SOURCES[spec["src"]]
    print("[%s] %s + rider" % (spec["id"], src["file"]))
    parts, sc = load_rigid(spec, src_dir)
    parts = build_materials(parts, spec)
    parts = merge_by_material(parts)
    parts = simplify(parts, spec["tris"], gltfpack, tmpdir, spec["id"] + "_veh", split=spec.get("split", False))
    if spec.get("pose"):
        rp, helmet = build_rider(spec, src_dir, parts, tmpdir, gltfpack)
        rp = merge_atlas(rp, "m009_body", "m009_head", "rider", 256)
        if helmet is not None:   # 戴安全帽就不需要頭髮／睫毛那層透明貼圖
            rp = [p for p in rp if p.mat.name != "m009_opacity"]
        for p in rp:
            if p.mat.name == "m009_opacity":
                p.mat.name = "rider_hair"
                p.mat.tex.limit(128)
                p.mat.tex.keep_alpha = True
                p.mat.mr = None
        rp = merge_by_material(rp)
        rp = simplify(rp, spec["rider_tris"] - (tri_count([helmet]) if helmet is not None else 0),
                      gltfpack, tmpdir, spec["id"] + "_rider")
        parts = parts + rp + ([helmet] if helmet is not None else [])
    return finish(spec, parts, out_dir, changes_of(spec, "加上 Rocketbox 騎士（靜態坐姿）"),
                  extra_src="rb_sit" if spec.get("pose") else None)


# ================================================================ 行人（保留骨架與動畫）
def reduce_track(times, vals, path, tol):
    """貪婪刪關鍵格：線性內插（旋轉用 slerp）誤差小於 tol 的格子刪掉。"""
    keep = [0]
    n = len(times)
    i = 0
    while i < n - 1:
        k = i + 2
        while k < n:
            ok = True
            for m in range(i + 1, k):
                f = (times[m] - times[i]) / (times[k] - times[i])
                if path == "rotation":
                    v = quat_slerp(vals[i], vals[k], f)
                    err = 1 - abs(float(np.dot(v, vals[m])))
                    if err > tol * tol:
                        ok = False
                        break
                else:
                    v = vals[i] * (1 - f) + vals[k] * f
                    if np.abs(v - vals[m]).max() > tol * 100:   # 單位 cm
                        ok = False
                        break
            if not ok:
                break
            k += 1
        keep.append(k - 1)
        i = k - 1
    return times[keep], vals[keep]


def process_ped(spec, src_dir, out_dir):
    src = SOURCES[spec["src"]]
    print("[%s] %s" % (spec["id"], src["file"]))
    j, binb = load_glb(src_dir / src["file"])
    cache = {}
    mats = read_materials(j, binb, cache)
    mesh_node = [i for i, n in enumerate(j["nodes"]) if "skin" in n][0]
    mesh = j["meshes"][j["nodes"][mesh_node]["mesh"]]
    prims = mesh["primitives"]
    at = prims[0]["attributes"]
    P = accessor(j, binb, at["POSITION"])
    N = accessor(j, binb, at["NORMAL"])
    UV = accessor(j, binb, at["TEXCOORD_0"]).astype(np.float32)
    JN = accessor(j, binb, at["JOINTS_0"]).astype(np.uint16)
    WT = accessor(j, binb, at["WEIGHTS_0"]).astype(np.float32)
    names = [mats[p["material"]].name for p in prims]
    body = [n for n in names if n.endswith("_body")][0]
    head = [n for n in names if n.endswith("_head")][0]
    others = [n for n in names if n not in (body, head)]
    S = spec.get("tex", 256)
    mb, mh = mats[names.index(body)], mats[names.index(head)]
    atlas = atlas_two(mb, mh, S)
    w = Writer()
    # 頂點：身體、頭的 UV 改到圖集左右半邊（共用頂點的話複製一份）
    out_prims = []
    newP, newN, newUV, newJ, newW = [P], [N], [UV.copy()], [JN], [WT]
    nv = len(P)
    I_body = np.concatenate([accessor(j, binb, prims[names.index(n)]["indices"]).reshape(-1) for n in (body,)])
    I_head = accessor(j, binb, prims[names.index(head)]["indices"]).reshape(-1)
    ub, uh = np.unique(I_body), np.unique(I_head)
    shared = np.intersect1d(ub, uh)
    UVa = newUV[0]
    UVa[ub, 0] = np.clip(UV[ub, 0], 0.002, 0.998) * 0.5
    if len(shared):
        remap = {int(v): nv + k for k, v in enumerate(shared)}
        newP.append(P[shared]); newN.append(N[shared]); newJ.append(JN[shared]); newW.append(WT[shared])
        u2 = UV[shared].copy(); u2[:, 0] = np.clip(u2[:, 0], 0.002, 0.998) * 0.5 + 0.5
        newUV.append(u2)
        I_head = np.array([remap.get(int(v), int(v)) for v in I_head])
        nv += len(shared)
    only_h = np.setdiff1d(uh, shared)
    UVa[only_h, 0] = np.clip(UV[only_h, 0], 0.002, 0.998) * 0.5 + 0.5
    # 其他材質（頭髮、安全帽）若跟身體/頭共用頂點，UV 也要保留原值 → 複製
    other_I = {}
    for n in others:
        I = accessor(j, binb, prims[names.index(n)]["indices"]).reshape(-1)
        u = np.unique(I)
        clash = np.intersect1d(u, np.union1d(ub, uh))
        if len(clash):
            remap = {int(v): nv + k for k, v in enumerate(clash)}
            newP.append(P[clash]); newN.append(N[clash]); newJ.append(JN[clash]); newW.append(WT[clash])
            newUV.append(UV[clash].copy())
            I = np.array([remap.get(int(v), int(v)) for v in I])
            nv += len(clash)
        other_I[n] = I
    P2, N2, UV2 = np.vstack(newP), np.vstack(newN), np.vstack(newUV)
    J2, W2 = np.vstack(newJ), np.vstack(newW)
    attrs = {"POSITION": w.acc(P2.astype(np.float32), "VEC3", 34962, True),
             "NORMAL": w.acc(N2.astype(np.float32), "VEC3", 34962),
             "TEXCOORD_0": w.acc(UV2.astype(np.float32), "VEC2", 34962),
             "JOINTS_0": w.acc(J2.astype(np.uint16), "VEC4", 34962),
             "WEIGHTS_0": w.acc(W2.astype(np.float32), "VEC4", 34962)}

    def add_mat(m, tex):
        md = {"name": m.name, "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "metallicFactor": 0.0,
                                                       "roughnessFactor": 0.85}}
        if tex is not None:
            md["pbrMetallicRoughness"]["baseColorTexture"] = {"index": w.texture(tex)}
        if m.alpha != "OPAQUE":
            md["alphaMode"] = m.alpha
            if m.alpha == "MASK":
                md["alphaCutoff"] = m.cutoff
        if m.double:
            md["doubleSided"] = True
        w.j["materials"].append(md)
        return len(w.j["materials"]) - 1

    mbody = mb.clone("person")
    out_prims.append({"attributes": attrs, "material": add_mat(mbody, atlas),
                      "indices": w.acc(np.concatenate([I_body, I_head]).astype(np.uint16), "SCALAR", 34963)})
    for n in others:
        m = mats[names.index(n)]
        t = m.tex
        t.limit(S)
        t.keep_alpha = m.alpha != "OPAQUE"
        nm = "hair" if n.endswith("_opacity") else n.split("_", 1)[1]
        out_prims.append({"attributes": attrs, "material": add_mat(m.clone(nm), t),
                          "indices": w.acc(other_I[n].astype(np.uint16), "SCALAR", 34963)})
    w.j["meshes"].append({"name": spec["id"], "primitives": out_prims})
    # 節點：照抄（骨架），mesh 換掉
    nodes = []
    for i, n in enumerate(j["nodes"]):
        nn = {k: v for k, v in n.items() if k in ("name", "children", "translation", "rotation", "scale", "matrix")}
        if i == mesh_node:
            nn["mesh"] = 0
            nn["skin"] = 0
            nn["name"] = spec["id"]
        nodes.append(nn)
    skin = j["skins"][0]
    ibm = accessor(j, binb, skin["inverseBindMatrices"]).astype(np.float32)
    w.j["skins"] = [{"joints": skin["joints"], "inverseBindMatrices": w.acc(ibm, "MAT4")}]
    if "skeleton" in skin:
        w.j["skins"][0]["skeleton"] = skin["skeleton"]
    # 動畫：idle 重取樣到 15 Hz，刪掉完全不動的軌道（跟靜止姿勢相同），再做關鍵格精簡
    anims = []
    rest = {}
    for i, n in enumerate(j["nodes"]):
        rest[(i, "rotation")] = np.array(n.get("rotation", [0, 0, 0, 1]), float)
        rest[(i, "translation")] = np.array(n.get("translation", [0, 0, 0]), float)
        rest[(i, "scale")] = np.array(n.get("scale", [1, 1, 1]), float)
    anim_names = []
    for a in j.get("animations", []):
        chans, smps = [], []
        for ch in a["channels"]:
            s = a["samplers"][ch["sampler"]]
            tm = accessor(j, binb, s["input"]).reshape(-1).astype(np.float64)
            vals = accessor(j, binb, s["output"]).astype(np.float64)
            path = ch["target"]["path"]
            if a["name"] != "walk" and len(tm) > 2:
                hz = 15.0
                t2 = np.arange(0, tm[-1] + 1e-6, 1 / hz)
                if t2[-1] < tm[-1] - 1e-4:
                    t2 = np.append(t2, tm[-1])
                v2 = []
                for t in t2:
                    k = int(np.clip(np.searchsorted(tm, t, side="right") - 1, 0, len(tm) - 2))
                    f = float(np.clip((t - tm[k]) / max(tm[k + 1] - tm[k], 1e-9), 0, 1))
                    v2.append(quat_slerp(vals[k], vals[k + 1], f) if path == "rotation" else vals[k] * (1 - f) + vals[k + 1] * f)
                tm, vals = t2, np.array(v2)
            if path == "rotation":
                for k in range(1, len(vals)):   # 四元數同半球
                    if np.dot(vals[k], vals[k - 1]) < 0:
                        vals[k] = -vals[k]
            rv = rest[(ch["target"]["node"], path)]
            const = np.abs(vals - vals[0]).max() < 1e-4
            if const and (np.abs(vals[0] - rv).max() < 1e-4 or (path == "rotation" and abs(abs(np.dot(vals[0], rv)) - 1) < 1e-7)):
                continue
            if a["name"] != "walk":   # walk 是 1.2 s 循環，保留原始關鍵格（腳才不會滑）
                tm, vals = reduce_track(tm, vals, path, 0.004)
            smps.append({"input": w.acc(tm.astype(np.float32).reshape(-1, 1), "SCALAR", None, True),
                         "output": w.acc(vals.astype(np.float32), "VEC4" if path == "rotation" else "VEC3"),
                         "interpolation": "LINEAR"})
            chans.append({"sampler": len(smps) - 1, "target": {"node": ch["target"]["node"], "path": path}})
        anims.append({"name": a["name"], "channels": chans, "samplers": smps})
        anim_names.append(a["name"])
    w.j["nodes"] = nodes
    j_out = {k: v for k, v in w.j.items() if v}
    j_out["animations"] = anims
    cr = credit_text(src, "貼圖縮到 %d px，身體與頭合成一張圖集；拿掉全白的頂點色；idle 動畫重取樣到 15 Hz 並刪掉不動的軌道" % S)
    j_out["asset"] = {"version": "2.0", "generator": "avm-adas-probe tools/prep_models.py", "copyright": cr}
    j_out["scene"] = 0
    j_out["scenes"] = [{"name": spec["id"], "nodes": j["scenes"][j.get("scene", 0)]["nodes"]}]
    while len(w.bin) % 4:
        w.bin.append(0)
    j_out["buffers"] = [{"byteLength": len(w.bin)}]
    data = pack_glb(j_out, bytes(w.bin))
    path = out_dir / (spec["id"] + ".glb")
    path.write_bytes(data)
    # 尺寸：walk 第 0 格的姿勢
    j2, b2 = load_glb(path)
    walk = [a for a in j2["animations"] if a["name"] == "walk"][0]
    Wm, _ = world_matrices(j2, sample_anim(j2, b2, walk, 0.0))
    Pw, _ = skin_vertices(j2, b2, mesh_node, Wm)
    mn, mx = Pw.min(0), Pw.max(0)
    tris = sum(len(other_I[n]) for n in others) // 3 + (len(I_body) + len(I_head)) // 3
    ws = walk_speed(j2, b2, walk)
    info = dict(file=path.name, length=round(float(mx[2] - mn[2]), 3), width=round(float(mx[0] - mn[0]), 3),
                height=round(float(mx[1] - mn[1]), 3), tris=int(tris), paint=False, anim=anim_names,
                draws=len(out_prims), kb=round(len(data) / 1024, 1), materials=[m["name"] for m in w.j["materials"]],
                walkSpeed=ws)
    print("    -> %s  %d tris, %d draws, %.0f KB, H%.2f, ymin %.3f, anims %s, walk %.2f m/s" % (
        path.name, tris, len(out_prims), len(data) / 1024, mx[1] - mn[1], mn[1], anim_names, ws))
    return info


def walk_speed(j, binb, walk, n=120):
    """walk 是原地循環：腳踩地（腳尖關節最低）那段往後滑的速度就是這個動畫的自然步速（m/s）。
    3D 面板用 timeScale = 實際速度 / walkSpeed，腳才不會在地上滑。"""
    idx = {nd.get("name", ""): i for i, nd in enumerate(j["nodes"])}
    toes = [i for k, i in idx.items() if "Toe0" in k and "Nub" not in k]
    dur = max(float(accessor(j, binb, s["input"]).max()) for s in walk["samplers"])
    ts = np.linspace(0, dur, n + 1)
    Z = np.zeros((len(toes), len(ts)))
    Y = np.zeros_like(Z)
    for k, t in enumerate(ts):
        W, _ = world_matrices(j, sample_anim(j, binb, walk, t))
        for q, ti in enumerate(toes):
            Z[q, k], Y[q, k] = W[ti][2, 3], W[ti][1, 3]
    v = []
    for q in range(len(toes)):
        dz = np.gradient(Z[q], ts)[1:-1]
        planted = (Y[q] < Y[q].min() + 0.004)[1:-1]
        v += list(-dz[planted])
    return round(float(np.median(v)), 2) if v else 1.2


# ================================================================ manifest / CREDITS
TYPE_ORDER = ["car", "van", "truck", "bus", "moto", "bigbike", "bike", "ped", "cone"]


def manifest_entry(info):
    e = {k: info[k] for k in ("file", "length", "width", "height", "tris", "paint", "anim")}
    e["tint"] = bool(info.get("tint", False))
    e["paintTex"] = bool(info.get("paintTex", False))
    if info.get("walkSpeed"):
        e["walkSpeed"] = info["walkSpeed"]
    e["draws"] = info["draws"]
    e["kb"] = info["kb"]
    return e


def write_manifest(out_dir, infos):
    man = {t: [] for t in TYPE_ORDER}
    for spec, info in infos:
        man[spec["type"]].append(manifest_entry(info))
    # 慣例（JSON 不能寫註解，寫在這裡；整合說明見 docs/road-user-models.md）：
    # 模型車頭／臉朝 +Z、公尺、y=0 貼地、x/z 置中。length/width/height 是外框（含後照鏡；行人是 walk 第 0 格）。
    # paint=true：有名為 paint 的車身材質（每個實例複製一份，改 emissive 做警示閃爍）；
    # tint=true：paint.color 可以換車色（單色車漆，或貼圖車漆已改成近白色）；false：車色固定，不要改 color。
    # paintTex=true：paint 有貼圖。trim / glass 是調色盤合併材質（NEAREST 取樣），lights 是車燈貼圖材質。
    # ped 有骨架與 walk（原地循環）/ idle 動畫，多個實例要用 SkeletonUtils.clone；walkSpeed＝walk 的自然步速（m/s）。
    # draws＝draw call 數，kb＝檔案大小。署名見 CREDITS.md。
    (out_dir / "manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1) + "\n")


CREDITS_MARK = "<!-- prep_models.py: road users -->"
MIT_TEXT = """
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
"""


def write_credits(out_dir, infos):
    path = out_dir / "CREDITS.md"
    old = path.read_text() if path.exists() else "# 3D 模型署名\n"
    if CREDITS_MARK in old:
        old = old[:old.index(CREDITS_MARK)].rstrip() + "\n"
    by_src = {}
    for spec, info in infos:
        by_src.setdefault(spec["src"], []).append((spec, info))
    for spec, info in infos:   # 騎士底模放在行人後面
        if spec.get("pose"):
            by_src.setdefault("rb_sit", []).append((spec, info))
    lines = ["", CREDITS_MARK, "", "## 其他道路使用者（tools/prep_models.py 產生）", "",
             "下列模型都經過修改。共通的修改：拿掉線段與 KHR_materials 擴充（透射玻璃改成一般半透明）、"
             "拿掉法線／AO 貼圖，依材質合併網格，沒有貼圖的小材質合併成一張調色盤貼圖，"
             "換成公尺並轉向（車頭／臉朝 +Z）、置中貼地、縮放到實際尺寸，以 gltfpack 簡化並縮小貼圖。"
             "每個 GLB 的 asset.copyright 也寫了署名、授權連結與修改說明。", ""]
    for key, lst in by_src.items():
        src = SOURCES[key]
        files = ", ".join("`%s`" % info["file"] for _, info in lst)
        lines.append("### %s" % files)
        lines.append("")
        lines.append("- 原始模型：「%s」" % src["title"])
        lines.append("- 作者：%s" % src["author"])
        lines.append("- 來源：%s" % src["url"])
        lines.append("- 授權：%s（%s）" % src["lic"])
        ch = []
        for spec, info in lst:
            if key == "rb_sit":
                pz = spec["pose"]
                ch.append("`%s` 的騎士：取 sit 動畫第 0 格，身高縮到 %d%%，上身前傾 %d°，手腳用兩段式 IK 擺到把手與%s，"
                          "烘成靜態網格（%d 三角形以內）後跟車合成一個檔%s" % (
                              info["file"], round(pz.get("scale", 1) * 100), pz.get("lean", 0),
                              {"moto": "腳踏板", "bigbike": "腳踏桿", "bike": "踏板"}[spec["type"]], spec["rider_tris"],
                              "；加上程式產生的安全帽，拿掉頭髮那層透明貼圖" if pz.get("helmet") else ""))
            elif spec["type"] == "ped":
                ch.append("`%s`：保留骨架與 walk / idle 動畫；貼圖縮到 %d px，身體與頭合成一張圖集，拿掉全白頂點色，"
                          "idle 動畫重取樣到 15 Hz、刪掉不動的軌道" % (info["file"], spec.get("tex", 256)))
            else:
                d = ", ".join("%s %.2f m" % ({"length": "長", "width": "寬", "height": "高"}[k], v)
                              for k, v in spec["dims"].items())
                extra = "；".join(x for x in [
                    "車身材質命名 paint" if spec.get("paint") else "", spec.get("note", "")] if x)
                tri = "簡化到 %d 三角形以內" % spec["tris"]
                if spec.get("pose"):
                    tri = "車簡化到 %d 三角形以內（連騎士共 %d）" % (spec["tris"], info["tris"])
                ch.append("`%s`：縮放到 %s，%s，貼圖 ≤ %d px%s" % (
                    info["file"], d, tri, spec["tex"], "；" + extra if extra else ""))
        lines.append("- 修改：" + (ch[0] if len(ch) == 1 else ""))
        if len(ch) > 1:
            for c in ch:
                lines.append("  - " + c)
        if src.get("tm"):
            lines.append("- 商標：%s；本專案與該公司無關。" % src["tm"])
        lines.append("")
    if any(SOURCES[k]["lic"] is MIT_RB for k in by_src):
        lines += ["### Microsoft Rocketbox 授權全文（MIT）", "",
                  "行人與騎士模型（`ped_*.glb` 和 `*_rider.glb` 裡的騎士）來自 Microsoft Rocketbox Avatar Library，"
                  "依 MIT 授權散布，需附上下列版權聲明與授權條款：", "", "```", MIT_TEXT.strip(), "```", ""]
    path.write_text(old + "\n".join(lines))


# ================================================================ main
def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--src", default=str(DEF_SRC))
    ap.add_argument("--out", default=str(DEF_OUT))
    ap.add_argument("--gltfpack", default=None)
    ap.add_argument("--only", default="")
    ap.add_argument("--keep-tmp", action="store_true")
    a = ap.parse_args()
    src_dir, out_dir = Path(a.src), Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    gp = find_gltfpack(a.gltfpack)
    only = set(x for x in a.only.split(",") if x)
    unknown = only - set(s["id"] for s in MODELS + RIDERS + PEDS)
    if unknown:
        raise SystemExit("不認得的 --only：%s" % ", ".join(sorted(unknown)))
    # --only：沒重做的模型沿用 manifest.json 裡的舊資料，manifest / CREDITS 一樣整份重寫（不會掉項目）
    old = {}
    if only and (out_dir / "manifest.json").exists():
        for lst in json.loads((out_dir / "manifest.json").read_text()).values():
            for e in lst:
                old[e["file"]] = e
    tmp = tempfile.mkdtemp(prefix="prep_models_")
    infos = []
    try:
        for spec, fn in [(s, process_rigid) for s in MODELS] + [(s, process_rider) for s in RIDERS] + \
                        [(s, process_ped) for s in PEDS]:
            if not only or spec["id"] in only:
                args = (spec, src_dir, out_dir) if fn is process_ped else (spec, src_dir, out_dir, gp, tmp)
                infos.append((spec, fn(*args)))
            elif spec["id"] + ".glb" in old and (out_dir / (spec["id"] + ".glb")).exists():
                infos.append((spec, dict(old[spec["id"] + ".glb"])))
            else:
                print("  [%s] 沒有輸出也不在 manifest.json，略過" % spec["id"])
    finally:
        if not a.keep_tmp:
            shutil.rmtree(tmp, ignore_errors=True)
    write_manifest(out_dir, infos)
    write_credits(out_dir, infos)
    print("manifest.json / CREDITS.md updated (%d models)" % len(infos))


if __name__ == "__main__":
    main()
