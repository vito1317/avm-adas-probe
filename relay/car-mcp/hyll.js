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
  account: process.env.CYHY_ACCOUNT || '',
  pwsha: process.env.CYHY_PWSHA || '',
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

function frame(code, extraBody = {}) {
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
    body: { tid: CFG.tid, ...extraBody },
  };
  const json = JSON.stringify(obj);
  const sig = CFG.txnkey ? crypto.createHmac('sha1', CFG.txnkey).update(json).digest('hex').toUpperCase().slice(0, 8) : '00000000';
  return Buffer.from(json + sig, 'utf8');
}

/** 送一個指令碼到雲端。回傳 {ok, ret_code, ret_msg, raw} 或丟錯。 */
/** 送一包到 cyhy 雲端，回完整回應 JSON。 */
function call(code, extraBody = {}) {
  const body = frame(code, extraBody);
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
          resolve(j);
        }
      });
      sock.on('error', () => { clearTimeout(to); tryHost(i + 1); });
    };
    tryHost(0);
  });
}

export async function sendCode(code, retry = true) {
  const j = await call(code);
  const sh = j.sys_head || {};
  if (sh.ret_code !== '000000' && retry && String(sh.ret_code || '').startsWith('SE') && await login()) return sendCode(code, false);
  return { ok: sh.ret_code === '000000', ret_code: sh.ret_code || '', ret_msg: sh.ret_msg || '', raw: j };
}

/** vst（8 bytes hex）→ 車鎖／引擎／車門等（照官方 parseVstStatusCy，CY103）。 */
function decodeVst(vst, o) {
  if (!vst || vst.length < 16) return;
  const b = []; for (let i = 0; i < 8; i++) b.push(parseInt(vst.substr(i * 2, 2), 16) || 0);
  o.locked = (b[2] & 0x20) === 0; o.engine = (b[2] & 0x10) !== 0; o.acc = (b[2] & 0x08) !== 0;
  o.ac = (b[2] & 0x04) !== 0; o.handbrake = (b[2] & 0x01) !== 0; o.highBeam = (b[0] & 0x40) !== 0; o.charging = (b[5] & 0x01) !== 0;
  o.doors = { lf: !!(b[0] & 1), lb: !!(b[0] & 2), rf: !!(b[0] & 4), rb: !!(b[0] & 8), trunk: !!(b[0] & 16), hood: !!(b[0] & 32) };
  o.anyDoor = (b[0] & 0x3f) !== 0;
  o.windows = { lf: !!(b[1] & 1), lb: !!(b[1] & 2), rf: !!(b[1] & 4), rb: !!(b[1] & 8), top: !!(b[1] & 16) };
  o.anyWindow = (b[1] & 0x1f) !== 0;
}

/** 用 account + SHA-1 密碼重新登入亮波，更新 ssk/txnkey 並寫回 .env。回成功與否。 */
export async function login() {
  if (!CFG.account || !CFG.pwsha) return false;
  try {
    const body = frame('10001003', { name: CFG.account, paswd: CFG.pwsha, agreement: '1', timezone: '28800', pushid: '', pushchnl: '' });
    // 登入 app_head 無 uid/ssk、尾碼 FFFFFFFF：直接自組，不用 frame 的指令格式
    const d = new Date(); const p = (n) => String(n).padStart(2, '0');
    const obj = { sys_head: { ver: '1.0', code: '10001003', consumer_ssn: ssn(), trans_date: `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}`, trans_time: `${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`, chnl: 'android', build_make: 'Google', build_model: 'Pixel', build_release: '14', distribute: CFG.distribute, aver: CFG.aver, caver: '', skin: '', csver: '', lver: '' }, app_head: { uid: '', ssk: '', tid: '', lang: 'en', timezone: '28800' }, body: { name: CFG.account, paswd: CFG.pwsha, agreement: '1', timezone: '28800', pushid: '', pushchnl: '' } };
    const j = await rawCall(JSON.stringify(obj) + 'FFFFFFFF');
    const b = j.body; if (!b || (j.sys_head || {}).ret_code !== '000000') return false;
    const tid = (b.dev && b.dev[0] && b.dev[0].tid) || b.uname || CFG.tid;
    setCreds({ ssk: b.ssk, uid: b.uid, tid, txnkey: b.txnkey });
    return true;
  } catch { return false; }
}

/** 低階：送一段已組好的字串（含尾碼），回完整回應 JSON。 */
function rawCall(str) {
  const bodyBuf = Buffer.from(str, 'utf8');
  const payload = Buffer.concat([Buffer.from(String(bodyBuf.length).padStart(8, '0')), bodyBuf]);
  return new Promise((resolve, reject) => {
    const hosts = [...CFG.hosts];
    const tryHost = (i) => {
      if (i >= hosts.length) return reject(new Error('cyhy 連不上'));
      const sock = net.connect({ host: hosts[i], port: CFG.port });
      let hdr = null, need = 0, buf = Buffer.alloc(0);
      const to = setTimeout(() => { sock.destroy(); tryHost(i + 1); }, 6000);
      sock.on('connect', () => sock.write(payload));
      sock.on('data', (c) => { buf = Buffer.concat([buf, c]); if (hdr === null && buf.length >= 8) { need = parseInt(buf.subarray(0, 8).toString('ascii'), 10) || 0; hdr = true; buf = buf.subarray(8); } if (hdr && buf.length >= need) { clearTimeout(to); sock.destroy(); let j = {}; try { j = JSON.parse(buf.subarray(0, need).toString('utf8')); } catch {} resolve(j); } });
      sock.on('error', () => { clearTimeout(to); tryHost(i + 1); });
    };
    tryHost(0);
  });
}

/** 車況（碼 203101）→ 解析後的物件，或 null。 */
export async function status(retry = true) {
  const j = await call('203101', { rows: [{ tid: CFG.tid }] });
  if ((j.sys_head || {}).ret_code !== '000000') { if (retry && await login()) return status(false); return null; }
  const r = (j.body && j.body.rows && j.body.rows[0]) || j.body || {};
  const o = { speed: Number(r.speed) || 0, volt: r.volt ? Number(r.volt) / 10 : null, temp: r.temp != null ? Number(r.temp) : null,
    fuel: r.fuel_avg || r.fuel || null, mileage: r.mileage, lat: Number(r.lat) || null, lng: Number(r.lng) || null,
    gsmol: r.gsmol === '1', gpsol: r.gpsol === '1', vst: r.vst || '' };
  decodeVst(r.vst, o);
  return o;
}
