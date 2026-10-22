#!/usr/bin/env python3
"""
離線評估：拿 360 錄影檔驗證「偵測行不行、精度多少」。
不受車機算力限制，建議在 Mac 或 DGX Spark 上跑。

用法：
    pip install ultralytics opencv-python
    python tools/offline_eval.py /path/to/360clip.mp4
    python tools/offline_eval.py clip.mp4 --model yolo11s --conf 0.25 --show

輸出：
    - 標註後的影片 runs/.../clip.mp4（畫出 ADAS 類別的框）
    - 終端印出：平均推論 FPS、各類別累計偵測數
"""
import argparse
import sys
import time
from collections import Counter

# ADAS 關心的 COCO 類別：person/bicycle/car/motorcycle/bus/truck
ADAS = [0, 1, 2, 3, 5, 7]
NAMES = {0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="360 錄影檔路徑")
    ap.add_argument("--model", default="yolo11n", help="yolo11n / yolo11s / yolov8n …")
    ap.add_argument("--conf", type=float, default=0.30)
    ap.add_argument("--iou", type=float, default=0.45)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--show", action="store_true", help="即時顯示視窗")
    ap.add_argument("--device", default=None, help="cuda / cpu / mps（預設自動）")
    args = ap.parse_args()

    try:
        from ultralytics import YOLO
    except ImportError:
        print("請先：pip install ultralytics opencv-python", file=sys.stderr)
        return 1

    model = YOLO(f"{args.model}.pt")

    counts = Counter()
    frames = 0
    infer_ms_total = 0.0
    t_start = time.time()

    # stream=True 逐幀，省記憶體
    results = model.predict(
        source=args.video,
        classes=ADAS,
        conf=args.conf,
        iou=args.iou,
        imgsz=args.imgsz,
        device=args.device,
        stream=True,
        save=True,       # 存標註後影片到 runs/detect/predict*/
        show=args.show,
        verbose=False,
    )

    for r in results:
        frames += 1
        if r.boxes is not None:
            for c in r.boxes.cls.tolist():
                counts[int(c)] += 1
        # r.speed: {'preprocess','inference','postprocess'} 毫秒
        infer_ms_total += float(r.speed.get("inference", 0.0))

    wall = time.time() - t_start
    print("\n================ 離線評估結果 ================")
    print(f"模型           : {args.model}  imgsz={args.imgsz}  conf={args.conf}")
    print(f"總幀數         : {frames}")
    if frames:
        print(f"平均純推論     : {infer_ms_total / frames:.1f} ms/frame "
              f"(≈ {1000 * frames / infer_ms_total:.1f} FPS 僅推論)" if infer_ms_total else "")
        print(f"端到端吞吐     : {frames / wall:.1f} FPS（含解碼/存檔）")
    print("各類別累計偵測 :")
    for cls_id in ADAS:
        print(f"    {NAMES[cls_id]:12s}: {counts.get(cls_id, 0)}")
    print("=============================================")
    print("標註影片在 runs/detect/ 底下。拿它看哪種鏡頭角度/車距抓得準。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
