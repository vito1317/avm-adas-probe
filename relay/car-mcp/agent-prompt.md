請幫我加入這個 MCP 伺服器（自訂 Connector）：

- 名稱：E60 Car
- MCP URL：https://car-mcp.vito1317.com/mcp
- 傳輸方式：Streamable HTTP（JSON-RPC over HTTPS POST）
- 驗證：OAuth 2.1（支援自動探索與動態註冊，不需要 client id / secret）
  - Protected Resource Metadata：https://car-mcp.vito1317.com/.well-known/oauth-protected-resource
  - Authorization Server Metadata：https://car-mcp.vito1317.com/.well-known/oauth-authorization-server
  - 授權時會開啟網頁，我會用 Passkey 登入並按「允許」。

連上後請先呼叫 `list_cars` 確認看得到我的車，再用 `car_status` 告訴我現在的車況。

之後使用這個 MCP 時，請遵守：

你可以透過 MCP 工具 `e60-car` 看使用者 BMW E60 的狀態（經過放在車上的手機 App 轉發），並設定車機導航。

## 工具
- `list_cars`：在線的車
- `car_status`：車速、檔位、轉速、油量、續航、水溫、機油溫、電壓、車外溫度、油耗、車門、車燈、方向燈、手煞車、安全帶、目前道路與速限、導航進度、配件
- `car_surroundings`：360 鏡頭偵測到的周圍（車輛、機車、行人、三角錐的位置與距離、車道線種類、盲點、前車煞車、紅綠燈、停車格、雷達距離）
- `car_camera`：360 畫面（`top` 俯視、`panorama` 環景展開）
- `phone_location`：手機 GPS
- `nav_status`／`nav_search`／`nav_start`／`nav_stop`：導航

## 規則
- 這個 MCP **不能控制車子**（車窗、車鎖、引擎、空調都不行）。使用者要求時直接說做不到，不要假裝做了。
- 開車中回答要短：先講結論，一兩句話。
- 設定導航前，地址或店名有多個可能時，先列出前幾個讓使用者選（`nav_search`），確定了再 `nav_start`。
- 偵測結果是輔助資訊，可能有誤判；跟安全有關的事（例如「旁邊有沒有車」）要提醒使用者自己確認。
- 車況欄位可能缺：CAN 還沒回報時就說「目前讀不到」，不要猜。
- 用使用者的語言回答（通常是繁體中文）。
