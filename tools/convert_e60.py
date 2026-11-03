"""
把 Sketchfab 的 BMW E60 GLB 轉成 3D 面板用的自車模型（assets/bev3d/models/e60.glb）。

- 拿掉 SketchUp 的邊線（LINES），同材質的三角面合併成一個 mesh（減少 draw call）
- 換成公尺、車頭朝 +Z、y=0 貼地、x/z 置中，長度縮放到實車 4.841 m
- 車身漆（原 Color_008）改銀灰色金屬漆並命名 paint；玻璃改深色；尾燈/頭燈命名 taillight/headlight
- 保留原作者署名（CC BY 4.0）並註明修改

用法：python3 tools/convert_e60.py 原始.glb app/src/main/assets/bev3d/models/e60.glb
原始模型：「BMW 5 Series 525i MK5 (E60) 2003-2010」by szymonpasterczyk (Merc_TV)，CC BY 4.0，
https://sketchfab.com/3d-models/bmw-5-series-525i-mk5-e60-2003-2010-ad6779e1ca534601947735b16094fc4e
"""
import json
import struct
import sys

import numpy as np

CAR_LENGTH = 4.841

# 原材質名 → (新名稱, PBR 覆寫)。沒列到的保留原樣。
OVERRIDES = {
    # 銀灰色金屬漆（sRGB #8f969d 的線性值）；3D 面板載入後會換成帶透明漆的材質
    "Color_008": ("paint", {"baseColorFactor": [0.27, 0.30, 0.33, 1.0], "metallicFactor": 0.75, "roughnessFactor": 0.33}),
    "Translucent_Glass_Gray": ("glass", {"baseColorFactor": [0.01, 0.012, 0.015, 0.86], "metallicFactor": 0.0, "roughnessFactor": 0.05}),
    "vray_BMW-fl-glas1": ("glass_lamp", {"baseColorFactor": [0.8, 0.82, 0.85, 0.35], "metallicFactor": 0.0, "roughnessFactor": 0.05}),
    "lichter__env_1": ("taillight", {"baseColorFactor": [0.55, 0.02, 0.02, 1.0], "metallicFactor": 0.1, "roughnessFactor": 0.3}),
    "vray_BMW-fl-bulb1": ("headlight", {"baseColorFactor": [0.95, 0.95, 0.9, 1.0], "metallicFactor": 0.1, "roughnessFactor": 0.2}),
    "Color_008_1": ("tire", {"baseColorFactor": [0.015, 0.015, 0.017, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.9}),
    "Polished_Chrome": ("rim_chrome", {"baseColorFactor": [0.6, 0.62, 0.64, 1.0], "metallicFactor": 0.9, "roughnessFactor": 0.3}),
    # 內裝改黑色（原本棕色/白色，透過車窗看太亮）
    "0035_Tan": ("interior", {"baseColorFactor": [0.03, 0.03, 0.03, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.8}),
    "Color_000": ("interior_2", {"baseColorFactor": [0.06, 0.06, 0.06, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.8}),
    "leder__env_15_s1": ("interior_3", {"baseColorFactor": [0.03, 0.03, 0.03, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.7}),
}

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def load(path):
    b = open(path, "rb").read()
    magic, _, _ = struct.unpack("<III", b[:12])
    assert magic == 0x46546C67, "not a GLB"
    jl, _ = struct.unpack("<II", b[12:20])
    j = json.loads(b[20:20 + jl])
    off = 20 + jl
    bl, _ = struct.unpack("<II", b[off:off + 8])
    return j, b[off + 8:off + 8 + bl]


def accessor(j, binb, i):
    a = j["accessors"][i]
    bv = j["bufferViews"][a["bufferView"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    n = NC[a["type"]]
    dt = CT[a["componentType"]]
    item = np.dtype(dt).itemsize * n
    stride = bv.get("byteStride", 0)
    if stride and stride != item:
        raw = np.frombuffer(binb, np.uint8, a["count"] * stride, off).reshape(-1, stride)[:, :item]
        return raw.copy().view(dt).reshape(-1, n)
    return np.frombuffer(binb, dt, a["count"] * n, off).reshape(-1, n)


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], float).reshape(4, 4).T
    x, y, z, w = n.get("rotation", [0, 0, 0, 1])
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r @ np.diag(n.get("scale", [1, 1, 1]))
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


def main(src, dst):
    j, binb = load(src)
    groups = {}   # material index → lists

    def walk(i, parent):
        n = j["nodes"][i]
        w = parent @ node_matrix(n)
        if "mesh" in n:
            for pr in j["meshes"][n["mesh"]]["primitives"]:
                if pr.get("mode", 4) != 4:
                    continue   # SketchUp 邊線
                p = accessor(j, binb, pr["attributes"]["POSITION"]).astype(np.float64)
                p = (w[:3, :3] @ p.T).T + w[:3, 3]
                if "NORMAL" in pr["attributes"]:
                    nrm = accessor(j, binb, pr["attributes"]["NORMAL"]).astype(np.float64)
                    nrm = (np.linalg.inv(w[:3, :3]).T @ nrm.T).T
                else:
                    nrm = np.zeros_like(p)
                uv = accessor(j, binb, pr["attributes"]["TEXCOORD_0"]).astype(np.float32) \
                    if "TEXCOORD_0" in pr["attributes"] else None
                idx = accessor(j, binb, pr["indices"]).reshape(-1).astype(np.int64) \
                    if "indices" in pr else np.arange(len(p))
                g = groups.setdefault(pr.get("material", -1), {"p": [], "n": [], "uv": [], "i": [], "nv": 0})
                g["i"].append(idx + g["nv"])
                g["p"].append(p)
                g["n"].append(nrm)
                g["uv"].append(uv if uv is not None else np.zeros((len(p), 2), np.float32))
                g["nv"] += len(p)
        for c in n.get("children", []):
            walk(c, w)

    for r in j["scenes"][j.get("scene", 0)]["nodes"]:
        walk(r, np.eye(4))

    allp = np.vstack([np.vstack(g["p"]) for g in groups.values()])
    mn, mx = allp.min(0), allp.max(0)
    s = CAR_LENGTH / (mx[0] - mn[0])      # 原檔車長在 x 軸，車頭朝 +x
    c = np.array([(mn[0] + mx[0]) / 2, mn[1], (mn[2] + mx[2]) / 2])

    def xf(p):   # 置中、貼地、縮放；繞 y 轉 -90°：車頭 +x → +z
        q = (p - c) * s
        return np.stack([-q[:, 2], q[:, 1], q[:, 0]], 1)

    def xn(nrm):
        q = np.stack([-nrm[:, 2], nrm[:, 1], nrm[:, 0]], 1)
        ln = np.linalg.norm(q, axis=1, keepdims=True)
        ln[ln == 0] = 1
        return q / ln

    out_bin = bytearray()
    views, accs, meshes, nodes, mats = [], [], [], [], []
    mat_map = {}

    def add_view(arr, target):
        while len(out_bin) % 4:
            out_bin.append(0)
        views.append({"buffer": 0, "byteOffset": len(out_bin), "byteLength": arr.nbytes, "target": target})
        out_bin.extend(arr.tobytes())
        return len(views) - 1

    def add_acc(arr, ctype, typ, target, minmax=False):
        v = add_view(arr, target)
        a = {"bufferView": v, "componentType": ctype, "count": len(arr), "type": typ}
        if minmax:
            a["min"] = arr.min(0).tolist()
            a["max"] = arr.max(0).tolist()
        accs.append(a)
        return len(accs) - 1

    src_mats = j.get("materials", [])
    textured = set()
    for mi, g in sorted(groups.items()):
        m = dict(src_mats[mi]) if mi >= 0 else {"name": "default"}
        name = m.get("name", "m%d" % mi)
        if name in OVERRIDES:
            new_name, pbr = OVERRIDES[name]
            m["name"] = new_name
            base = dict(m.get("pbrMetallicRoughness", {}))
            base.update(pbr)
            m["pbrMetallicRoughness"] = base
            if pbr.get("baseColorFactor", [1, 1, 1, 1])[3] < 1:
                m["alphaMode"] = "BLEND"
        has_tex = "baseColorTexture" in m.get("pbrMetallicRoughness", {})
        if has_tex:
            textured.add(mi)
        mat_map[mi] = len(mats)
        mats.append(m)

        p = xf(np.vstack(g["p"])).astype(np.float32)
        nrm = xn(np.vstack(g["n"])).astype(np.float32)
        idx = np.concatenate(g["i"]).astype(np.uint32)
        attrs = {
            "POSITION": add_acc(p, 5126, "VEC3", 34962, True),
            "NORMAL": add_acc(nrm, 5126, "VEC3", 34962),
        }
        if has_tex:
            attrs["TEXCOORD_0"] = add_acc(np.vstack(g["uv"]).astype(np.float32), 5126, "VEC2", 34962)
        ia = add_acc(idx, 5125, "SCALAR", 34963)
        meshes.append({"name": m["name"], "primitives": [{"attributes": attrs, "indices": ia, "material": mat_map[mi]}]})
        nodes.append({"name": m["name"], "mesh": len(meshes) - 1})

    out = {
        "asset": {
            "version": "2.0",
            "generator": "avm-adas-probe tools/convert_e60.py",
            "copyright": "BMW 5 Series 525i MK5 (E60) 2003-2010 by szymonpasterczyk (Merc_TV), CC BY 4.0. "
                         "Modified: edge lines removed, merged by material, rescaled/reoriented, recoloured.",
            "extras": j.get("asset", {}).get("extras", {}),
        },
        "scene": 0,
        "scenes": [{"nodes": [len(nodes)]}],
        "nodes": nodes + [{"name": "E60", "children": list(range(len(nodes)))}],
        "meshes": meshes,
        "materials": mats,
        "accessors": accs,
        "bufferViews": views,
        "buffers": [{"byteLength": 0}],
    }
    # 貼圖（車牌等）：原樣搬過來
    if textured and j.get("images"):
        img_views = []
        for im in j["images"]:
            sbv = j["bufferViews"][im["bufferView"]]
            data = np.frombuffer(binb, np.uint8, sbv["byteLength"], sbv.get("byteOffset", 0))
            while len(out_bin) % 4:
                out_bin.append(0)
            views.append({"buffer": 0, "byteOffset": len(out_bin), "byteLength": int(data.nbytes)})
            out_bin.extend(data.tobytes())
            img_views.append({"bufferView": len(views) - 1, "mimeType": im.get("mimeType", "image/jpeg")})
        out["images"] = img_views
        out["textures"] = j.get("textures", [])
        if j.get("samplers"):
            out["samplers"] = j["samplers"]
    while len(out_bin) % 4:
        out_bin.append(0)
    out["buffers"][0]["byteLength"] = len(out_bin)

    js = json.dumps(out, separators=(",", ":")).encode()
    while len(js) % 4:
        js += b" "
    glb = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(out_bin))
    glb += struct.pack("<II", len(js), 0x4E4F534A) + js
    glb += struct.pack("<II", len(out_bin), 0x004E4942) + bytes(out_bin)
    open(dst, "wb").write(glb)

    tri = sum(accs[mm["primitives"][0]["indices"]]["count"] // 3 for mm in meshes)
    allq = xf(allp)
    print("materials/meshes:", len(meshes), "triangles:", tri, "bytes:", len(glb))
    print("size (x,y,z) m:", (allq.max(0) - allq.min(0)).round(3), "y min:", allq[:, 1].min().round(4))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
