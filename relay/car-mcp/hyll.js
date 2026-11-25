// 亮波（hyll / cyhy）雲端遠端控制客戶端。
//
// 車上的遠端模組（CY103）自己有車聯網（SIM），指令可經 cyhy 雲端直達，不需要中控台或手機在車上——
// 車熄火、中控台關著也能發。協定由官方 App（com.hyll.cyhy2）反組譯得出：
//   連 TCP（預設 6050，明文）：送 `%08d`(長度) + JSON + 8 碼（JSON 用 ssk/uid/tid 認；伺服器不驗那 8 碼，
//   這裡照樣算正確值）。回應同樣是 `%08d` 長度 + JSON，`ret_code=000000` 代表雲端已收下並轉給車。
//
// 憑證（CYHY_SSK/UID/TID）等於車鑰匙：只放在使用者自己的 Spark relay 的環境變數，不進版控、不外傳。
// 這裡只做「使用者在手機 App 開啟遠端控制、而且每個動作都明確確認」後才會被呼叫的白名單指令。
import net from 'node:net';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

const CFG = {
  ssk: process.env.CYHY_SSK || '',
  uid: process.env.CYHY_UID || '',
  tid: process.env.CYHY_TID || '',
  txnkey: process.env.CYHY_TXNKEY || '',
  hosts: (process.env.CYHY_HOSTS || 's1.cyhycdd.com,s2.cyhycdd.com').split(',').map((s) => s.trim()).filter(Boolean),
  port: Number(process.env.CYHY_PORT || 6050),
  aver: process.env.CYHY_AVER || '1.1.15',
  distribute: process.env.CYHY_DISTRIBUTE || 'cyhy2',
};

export const configured = () => !!(CFG.ssk && CFG.uid && CFG.tid);

/** 手機登入後推來的新憑證：更新執行中的設定並寫回 .env（手機 app 自己登入亮波換 key 後呼叫）。 */
export function setCreds({ ssk, uid, tid, txnkey }) {
  if (ssk) CFG.ssk = String(ssk);
  if (uid) CFG.uid = String(uid);
  if (tid) CFG.tid = String(tid);
  if (txnkey !== undefined) CFG.txnkey = String(txnkey || '');
  try {
    const fs = require('node:fs'); const path = process.env.CYHY_ENV_FILE || '.env';
    let t = ''; try { t = fs.readFileSync(path, 'utf8'); } catch {}
    const set = (k, v) => { const re = new RegExp('^' + k + '=.*$', 'm'); t = re.test(t) ? t.replace(re, k + '=' + v) : (t + (t.endsWith('\n') || !t ? '' : '\n') + k + '=' + v + '\n'); };
    set('CYHY_SSK', CFG.ssk); set('CYHY_UID', CFG.uid); set('CYHY_TID', CFG.tid); set('CYHY_TXNKEY', CFG.txnkey);
    fs.writeFileSync(path, t, { mode: 0o600 });
  } catch {}
}

// 指令碼（反組譯 BleCY02.getCmd / ctrl.xml 確認）。只收這些；發動／熄火另有上層閘。
export const CODES = {
  lock: '303111', unlock: '303117',
  windows_up: '303132', windows_down: '303133',
  trunk_open: '303130', trunk_close: '303131',
  start_engine: '303120', stop_engine: '303125',
};

let seq = Math.floor(Math.random() * 9000);
function ssn() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  const t = `${String(d.getFullYear()).slice(2)}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
  seq = (seq + 1) % 1000000;
  return `${CFG.uid}${t}${p(seq, 4)}`;
}

function frame(code) {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const obj = {
    sys_head: {
      ver: '1.0', code, consumer_ssn: ssn(),
      trans_date: `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`,
      trans_time: `${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`,
      chnl: 'android', build_make: 'Google', build_model: 'Pixel', build_release: '14',
      distribute: CFG.distribute, aver: CFG.aver, caver: '', skin: '', csver: '', lver: '',
    },
    app_head: { lang: 'zh', ssk: CFG.ssk, uid: CFG.uid, timezone: '8', devcfg: '', appcfg: '', skin: '' },
    body: { tid: CFG.tid },
  };
  const json = JSON.stringify(obj);
  const sig = CFG.txnkey ? crypto.createHmac('sha1', CFG.txnkey).update(json).digest('hex').toUpperCase().slice(0, 8) : '00000000';
  return Buffer.from(json + sig, 'utf8');
}

/** 送一個指令碼到雲端。回傳 {ok, ret_code, ret_msg, raw} 或丟錯。 */
export function sendCode(code) {
  const body = frame(code);
  const payload = Buffer.concat([Buffer.from(String(body.length).padStart(8, '0')), body]);
  return new Promise((resolve, reject) => {
    const hosts = [...CFG.hosts];
    const tryHost = (i) => {
      if (i >= hosts.length) return reject(new Error('cyhy 雲端連不上（模組的車聯網 SIM 在線嗎？）'));
      const sock = net.connect({ host: hosts[i], port: CFG.port });
      let hdr = null, need = 0, buf = Buffer.alloc(0);
      const to = setTimeout(() => { sock.destroy(); tryHost(i + 1); }, 6000);
      sock.on('connect', () => sock.write(payload));
      sock.on('data', (c) => {
        buf = Buffer.concat([buf, c]);
        if (hdr === null && buf.length >= 8) { need = parseInt(buf.subarray(0, 8).toString('ascii'), 10) || 0; hdr = true; buf = buf.subarray(8); }
        if (hdr && buf.length >= need) {
          clearTimeout(to); sock.destroy();
          let j = {}; try { j = JSON.parse(buf.subarray(0, need).toString('utf8')); } catch (_) {}
          const sh = j.sys_head || {};
          resolve({ ok: sh.ret_code === '000000', ret_code: sh.ret_code || '', ret_msg: sh.ret_msg || '', raw: j });
        }
      });
      sock.on('error', () => { clearTimeout(to); tryHost(i + 1); });
    };
    tryHost(0);
  });
}
