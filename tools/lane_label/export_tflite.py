#!/usr/bin/env python3
"""
車道線模型 → TFLite（給 360 引擎用）。PyTorch → ONNX → onnx2tf → TFLite（float32、float16、動態範圍量化各一份）。

輸入 1x272x480x3（NHWC，RGB 0–1，模型裡自己做 ImageNet 正規化），輸出 1x272x480x2 = 背景／車道線兩類的原始分數（機率 = sigmoid(第 2 個 − 第 1 個)）。
用法（要用 Python 3.12 + tensorflow 的環境）：
  ~/avm-data/.venv312/bin/python tools/lane_label/export_tflite.py --ckpt ~/avm-data/lane_runs/v2/best.pt --out ~/avm-data/lane_runs/v2/tflite
注意：用 WoodScape 預訓練過的權重只能放私有 release（Valeo 的條件：不得散布），不要放進公開 repo。
"""
import argparse, glob, os, shutil, subprocess, sys
import torch, torch.nn as nn, torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import train as T   # noqa: E402


class Deploy(nn.Module):
    """包一層：NCHW 0–1 輸入、內含正規化、輸出線的機率（softmax 第 1 類）。"""
    def __init__(self, net):
        super().__init__(); self.net = net
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

    def forward(self, x):
        # 跟 LaneNet.forward 一樣，但放大的目標大小寫死（輸入固定 272x480）：
        # 用 x.shape 算大小會在 ONNX 裡留下 Shape 運算，onnx2tf 會把輸出形狀推錯（實測變成 1x1x480x2）
        n = self.net
        x = (x - self.mean) / self.std
        a = n.s1(x); b = n.s2(a); c = n.s3(b); d = n.s4(c)
        # 每次都剛好放大 2 倍（17x30→34x60→68x120→136x240→272x480）：用 scale_factor，ONNX 裡就不會有 Shape
        up = lambda t: F.interpolate(t, scale_factor=2.0, mode="bilinear", align_corners=False)
        y = n.d3(torch.cat([up(d), c], 1))
        y = n.d2(torch.cat([up(y), b], 1))
        y = n.d1(torch.cat([up(y), a], 1))
        # 直接輸出兩類的原始分數（1x272x480x2，NHWC）；機率在車機上算 sigmoid(線 − 背景)。
        # 在模型裡做 softmax／切片，onnx2tf 會把輸出形狀轉錯（實測變成 1x1x480x2）。
        return up(n.head(y))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--ckpt", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.ckpt, map_location="cpu")); net.eval()
    m = Deploy(net).eval()
    onnx_path = os.path.join(a.out, "lane.onnx")
    torch.onnx.export(m, torch.zeros(1, 3, 272, 480), onnx_path, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
    tf_dir = os.path.join(a.out, "tf")
    shutil.rmtree(tf_dir, ignore_errors=True)
    # onnx2tf 會下載一份測試影像驗證轉換；網路下載常壞掉，先在工作目錄放一份自己的（20x128x128x3）
    import numpy as np
    from PIL import Image
    imgs = sorted(glob.glob(os.path.expanduser("~/avm-data/lane_ds/img/*.jpg")))[::40][:20]
    if imgs:
        arr = np.stack([np.asarray(Image.open(f).convert("RGB").resize((128, 128)), np.float32) / 255.0 for f in imgs])
        while len(arr) < 20: arr = np.concatenate([arr, arr])[:20]
        np.save("calibration_image_sample_data_20x128x128x3_float32.npy", arr)
    subprocess.check_call([sys.executable, "-m", "onnx2tf", "-i", onnx_path, "-o", tf_dir], stdout=subprocess.DEVNULL)
    for f in glob.glob(os.path.join(tf_dir, "*.tflite")):
        print(os.path.basename(f), os.path.getsize(f) // 1024, "KB")


if __name__ == "__main__":
    main()
