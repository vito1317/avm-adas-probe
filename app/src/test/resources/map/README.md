# 地圖輔助測試資料

道路資料 © OpenStreetMap 貢獻者，以 [ODbL](https://opendatacommons.org/licenses/odbl/) 授權
（https://www.openstreetmap.org/copyright）。2026-11-03 透過 Overpass API 錄下，查詢與 `OsmTiles.query()` 相同
（可行車 highway + `highway=traffic_signals` 節點；`out body; >; out skel qt;`）。

| 檔案 | 地點 | bbox (S, W, N, E) | 重點 |
|---|---|---|---|
| `taipei_zhongxiao.json` | 台北 忠孝東路四段（復興南路 → 光復南路） | 25.0370, 121.5420, 25.0465, 121.5590 | 雙向分隔的單行車道（兩向相距約 13 m）、密集號誌、路口右轉 |
| `yangde.json` | 陽明山 仰德大道一段～二段 | 25.0995, 121.5365, 25.1150, 121.5525 | 雙向 2 車道山路彎道、迴轉 |
| `tainan_west.json` | 台南 中西區（中成路、慈音街、公園路） | 22.9925, 120.1980, 22.9995, 120.2060 | 巷弄、慢速、兩次路口轉彎、圓環 |

`trace_*.json`：沿真實道路的合成 1 Hz GPS 軌跡（`make_traces.py`）。車子沿車道中心（道路中心線偏右 0.8–1.75 m）行駛，
速度受彎道側向加速度、停等、轉彎限速限制；GPS 誤差 = AR(1) 相關雜訊（τ = 15 s，σ = 5–7 m）+ 白雜訊，
航向在 < 2 m/s 時幾乎是亂數（σ 70°）、停車時一半沒有航向。每筆附真值（位置、航向、所在 way、沿路距離）。

重新產生：

```bash
python3 record_overpass.py roads taipei_zhongxiao.json 25.0370 121.5420 25.0465 121.5590   # 會自動重試、換備援伺服器
python3 make_traces.py <osm 目錄> <輸出目錄>
```
