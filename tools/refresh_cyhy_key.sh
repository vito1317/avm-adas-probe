#!/usr/bin/env bash
# 從手機的亮波 App 讀出目前的 cyhy 憑證（ssk/uid/tid/txnkey），推到 Spark 的 car-mcp relay。
# 用在：亮波被登出→重登後 ssk 換了，雲端車控失效時。手機要 adb 連得到（USB 或 wireless）。
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.hyll.cyhy2
SPARK="${SPARK:-vito@192.168.1.114}"
D=/data/data/$PKG

xml=$("$ADB" shell run-as $PKG cat $D/shared_prefs/$PKG.xml 2>/dev/null)
get(){ printf '%s' "$xml" | sed -n "s/.*<string name=\"$1\">\\([^<]*\\)<.*/\\1/p" | head -1; }
SSK=$(get llogin.ssk); UID_=$(get llogin.uid); TID=$(get llogin.tid)
TMP=$(mktemp); "$ADB" shell run-as $PKG cat $D/files/cyhy2/app.db > "$TMP" 2>/dev/null
TXN=$(sqlite3 "$TMP" "SELECT key,val FROM keyval;" 2>/dev/null | grep -oiE 'txnkey"[: ]*"[A-Za-z0-9]+' | grep -oE '[A-Za-z0-9]+$' | head -1)
rm -f "$TMP"
[ -n "$SSK" ] && [ -n "$UID_" ] && [ -n "$TID" ] || { echo "讀不到憑證（手機 adb 連得到嗎？亮波登入了嗎？）"; exit 1; }
echo "ssk=$SSK uid=$UID_ tid=$TID txnkey=$TXN"
ssh "$SPARK" "cd ~/car-mcp-relay && sed -i \
  -e 's|^CYHY_SSK=.*|CYHY_SSK=$SSK|' \
  -e 's|^CYHY_UID=.*|CYHY_UID=$UID_|' \
  -e 's|^CYHY_TID=.*|CYHY_TID=$TID|' \
  -e 's|^CYHY_TXNKEY=.*|CYHY_TXNKEY=$TXN|' .env && systemctl --user restart car-mcp-relay && sleep 2 && systemctl --user is-active car-mcp-relay"
echo "已更新 relay 並重啟。"
