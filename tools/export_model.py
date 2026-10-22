#!/usr/bin/env python3
"""
把 YOLO11n 匯出成 TFLite，並複製到 app/src/main/assets/model.tflite。

用法：
    pip install ultralytics          # 會連帶裝 torch / tensorflow / onnx2tf 等
    python tools/export_model.py     # 預設 yolo11n，float32，imgsz=640

想要更小/更快可改 --model yolo11n 或加 --int8（int8 要校正資料，先別用）。
"""
import argparse
import glob
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "model.tflite")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="yolo11n", help="yolo11n / yolov8n …")
    ap.add_argument("--imgsz", type=int, default=640)
    args = ap.parse_args()

    try:
        from ultralytics import YOLO
    except ImportError:
        print("請先：pip install ultralytics", file=sys.stderr)
        return 1

    m = YOLO(f"{args.model}.pt")
    m.export(format="tflite", imgsz=args.imgsz)  # 會產生 *_saved_model/*_float32.tflite

    # 找出剛匯出的 tflite（新版 ultralytics/LiteRT 直接輸出 <model>.tflite；
    # 舊版放在 <model>_saved_model/ 底下）
    patterns = [
        f"{args.model}.tflite",
        f"{args.model}_saved_model/*float32.tflite",
        f"{args.model}_saved_model/*.tflite",
        "**/*.tflite",
    ]
    found = []
    for p in patterns:
        found = glob.glob(p, recursive=True)
        if found:
            break
    if not found:
        print("找不到匯出的 .tflite，請手動複製到 assets/model.tflite", file=sys.stderr)
        return 2

    src = max(found, key=os.path.getmtime)
    os.makedirs(os.path.dirname(ASSETS), exist_ok=True)
    shutil.copy(src, ASSETS)
    size_mb = os.path.getsize(ASSETS) / 1e6
    print(f"✅ 已複製 {src} → {ASSETS}  ({size_mb:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
