# E60 Car MCP relay

讓 AI（Claude 等 MCP 客戶端）透過放在車上的「E60 車機」手機 App 看車況、360 偵測與畫面，並設定車機導航。
架構照 android-control relay：

```
AI ──https /mcp（OAuth 2.1 + passkey 登入）──▶ relay（Spark） ◀──wss /device（裝置 token）── 手機 App ──熱點區網──▶ 中控台 ──▶ 360 引擎
```

- 只讀＋導航：`car_status`、`car_surroundings`、`car_camera`、`phone_location`、`nav_status`、`nav_search`、`nav_start`、`nav_stop`。**沒有車控工具**。
- 登入：passkey（WebAuthn）；AI 客戶端用 OAuth 2.1（動態註冊、PKCE S256、refresh token 輪替），或固定 MCP key。
- 手機配對：網頁控制台「新增裝置」→ 配對碼 → App「車機」頁「遠端 AI（MCP）」。

## 部署（Spark）

```
npm ci --omit=dev
cp .env.example .env   # 填 PORT/HOST/ORIGIN/ADMIN_USER/REGISTRATION
node users.js setup-link <帳號> --admin   # 一次性連結，綁 passkey
```

nginx `:8083` 加站台（`/device` 要 WebSocket upgrade），前面由 WAF 處理 TLS。資料存在 `data.json`（權限 600，不進版控）。
