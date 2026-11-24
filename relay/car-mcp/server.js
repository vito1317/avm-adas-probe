// E60 Car MCP relay（照 android-control relay 的架構；多使用者、多台車）
//   手機 App --ws-->   /device            Bearer <device token，從 /api/device-pair 拿>
//   AI      --http->  /mcp               Bearer <OAuth access token 或使用者 MCP key>
//   瀏覽器  --http->  /  + /api/*        passkey 登入 -> session cookie
// 手機 App 在車上連車機（中控台，熱點區網），這裡只轉發：車況、360 偵測、360 畫面、導航。
// 沒有任何車控工具（車窗、車鎖、引擎、遠端模組都不經過這裡）。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import * as store from './store.js';
import { createOAuth } from './oauth.js';
import * as hyll from './hyll.js';
import {
  generateRegistrationOptions, verifyRegistrationResponse,
  generateAuthenticationOptions, verifyAuthenticationResponse,
} from '@simplewebauthn/server';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8792);
const HOST = process.env.HOST || '127.0.0.1';
const LEGACY_MCP = process.env.MCP_TOKEN || process.env.TOKEN || '';
const LEGACY_DEV = process.env.DEVICE_TOKEN || process.env.TOKEN || '';
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const COOKIE_SECURE = process.env.COOKIE_SECURE !== 'false';
const CALL_TIMEOUT_MS = 65_000;
// REGISTRATION: open (anyone) | invite (needs INVITE_CODE) | off
const REGISTRATION = ['open', 'invite', 'off'].includes(process.env.REGISTRATION) ? process.env.REGISTRATION : 'open';
const INVITE_CODE = process.env.INVITE_CODE || '';
const REG_PER_IP_PER_HOUR = Number(process.env.REG_PER_IP_PER_HOUR || 5);
const ORIGIN = process.env.ORIGIN || 'https://car-mcp.vito1317.com';
const RP_ID = process.env.RP_ID || new URL(ORIGIN).hostname;
const RP_NAME = process.env.RP_NAME || 'E60 Car MCP';

const log = (...a) => console.log(new Date().toISOString(), ...a);
const safeEq = (a, b) => {
  if (!a || !b) return false;
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
const bearer = (req) => (req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim();
const clientIp = (req) => (req.headers['x-forwarded-for'] || '').split(',')[0].trim() || req.headers['x-real-ip'] || req.socket.remoteAddress;

// ---------------- auth ----------------
const sessions = new Map(); // sid -> { user, admin, exp }
const SESSION_TTL = 7 * 24 * 3600 * 1000;

function authFromBearer(req) {
  const t = bearer(req);
  if (!t) return null;
  if (safeEq(t, LEGACY_MCP)) return { user: ADMIN_USER, admin: true };
  if (t.startsWith('at_')) {
    const tok = store.findOAuthToken(t, 'access');
    return tok ? { user: tok.user, admin: tok.admin, via: 'oauth' } : null;
  }
  return store.findByMcpKey(t);
}
function authFromCookie(req) {
  const m = /(?:^|;\s*)sid=([\w-]+)/.exec(req.headers.cookie || '');
  if (!m) return null;
  const s = sessions.get(m[1]);
  if (!s || s.exp < Date.now()) { sessions.delete(m[1]); return null; }
  return s;
}

const httpErr = (status, msg) => Object.assign(new Error(msg), { status });

// simple sliding-window limiter: key -> timestamps
const hits = new Map();
function limit(key, max, windowMs, msg) {
  const now = Date.now();
  const arr = (hits.get(key) || []).filter((t) => now - t < windowMs);
  if (arr.length >= max) throw httpErr(429, msg);
  arr.push(now); hits.set(key, arr);
}

// ---- passkey ceremonies: flow id -> { challenge, kind, user?, setup?, exp } ----
const flows = new Map();
function newFlow(data) {
  const id = crypto.randomBytes(16).toString('base64url');
  flows.set(id, { ...data, exp: Date.now() + 5 * 60_000 });
  return id;
}
function takeFlow(id, kind) {
  const f = flows.get(String(id || '')); flows.delete(String(id || ''));
  if (!f || f.kind !== kind || f.exp < Date.now()) throw httpErr(400, '驗證逾時，請重試');
  return f;
}

/** Start passkey registration. mode: new account | add to logged-in account | setup link for existing account. */
async function registerOptions(req, body, ctx) {
  let user, mode;
  if (body.setup) {
    user = store.peekSetupCode(String(body.setup));
    if (!user) throw httpErr(400, '設定連結無效或已過期');
    mode = 'setup';
  } else if (body.add) {
    if (!ctx) throw httpErr(401, 'login required');
    user = ctx.user; mode = 'add';
  } else {
    if (REGISTRATION === 'off') throw httpErr(403, '目前不開放註冊');
    if (REGISTRATION === 'invite' && (!INVITE_CODE || !safeEq(String(body.invite || ''), INVITE_CODE))) throw httpErr(403, '邀請碼錯誤');
    user = String(body.username || '').trim();
    if (!store.validUsername(user)) throw httpErr(400, '帳號需 2-32 字元，只能用英數字與 _ . -');
    if (store.userExists(user)) throw httpErr(409, '帳號已存在');
    limit('reg:' + clientIp(req), REG_PER_IP_PER_HOUR * 3, 3600_000, '註冊太頻繁，請稍後再試');
    mode = 'new';
  }
  const u = store.getUser(user);
  const opts = await generateRegistrationOptions({
    rpName: RP_NAME, rpID: RP_ID, userName: user, userDisplayName: user,
    userID: u ? Buffer.from(u.webauthnId, 'base64url') : crypto.randomBytes(16),
    attestationType: 'none',
    excludeCredentials: (u?.credentials || []).map((c) => ({ id: c.id, transports: c.transports })),
    authenticatorSelection: { residentKey: 'required', userVerification: 'preferred' },
  });
  return { flow: newFlow({ kind: 'reg', challenge: opts.challenge, user, mode, setup: body.setup, userId: opts.user.id }), options: opts };
}

async function registerVerify(req, body) {
  const f = takeFlow(body.flow, 'reg');
  const v = await verifyRegistrationResponse({
    response: body.response, expectedChallenge: f.challenge,
    expectedOrigin: ORIGIN, expectedRPID: RP_ID, requireUserVerification: false,
  }).catch((e) => { throw httpErr(400, 'Passkey 驗證失敗：' + e.message); });
  if (!v.verified) throw httpErr(400, 'Passkey 驗證失敗');
  if (f.mode === 'new') {
    if (store.userExists(f.user)) throw httpErr(409, '帳號已存在');
    limit('regok:' + clientIp(req), REG_PER_IP_PER_HOUR, 3600_000, '註冊太頻繁，請稍後再試');
    store.createUser(f.user, false, f.userId); // keep the user handle the authenticator stored
  }
  if (f.mode === 'setup' && store.consumeSetupCode(f.setup) !== f.user) throw httpErr(400, '設定連結無效或已過期');
  const c = v.registrationInfo.credential;
  store.addCredential(f.user, {
    id: c.id, publicKey: Buffer.from(c.publicKey).toString('base64url'), counter: c.counter,
    transports: c.transports || body.response?.response?.transports || [],
    deviceType: v.registrationInfo.credentialDeviceType, backedUp: v.registrationInfo.credentialBackedUp,
    label: String(body.label || '').slice(0, 60) || null,
  });
  log(`passkey ${f.mode} for ${f.user} from ${clientIp(req)}`);
  const u = store.getUser(f.user);
  return { user: f.user, admin: !!u.admin };
}

async function loginOptions() {
  const opts = await generateAuthenticationOptions({ rpID: RP_ID, userVerification: 'preferred', allowCredentials: [] });
  return { flow: newFlow({ kind: 'auth', challenge: opts.challenge }), options: opts };
}

async function loginVerify(req, body) {
  limit('login:' + clientIp(req), 20, 5 * 60_000, '嘗試太多次，請稍後再試');
  const f = takeFlow(body.flow, 'auth');
  const found = store.findCredential(String(body.response?.id || ''));
  if (!found) throw httpErr(401, '找不到這個 Passkey，請先註冊');
  const v = await verifyAuthenticationResponse({
    response: body.response, expectedChallenge: f.challenge,
    expectedOrigin: ORIGIN, expectedRPID: RP_ID, requireUserVerification: false,
    credential: { id: found.cred.id, publicKey: Buffer.from(found.cred.publicKey, 'base64url'), counter: found.cred.counter, transports: found.cred.transports },
  }).catch((e) => { throw httpErr(401, 'Passkey 驗證失敗：' + e.message); });
  if (!v.verified) throw httpErr(401, 'Passkey 驗證失敗');
  store.updateCounter(found.cred.id, v.authenticationInfo.newCounter);
  log(`passkey login ${found.user} from ${clientIp(req)}`);
  return { user: found.user, admin: found.admin };
}

// ---- device pairing codes (issued in the web console, typed into the phone app) ----
const pairCodes = new Map(); // code -> { user, exp }
const PAIR_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
function newPairCode(user) {
  let code = '';
  for (const b of crypto.randomBytes(8)) code += PAIR_ALPHABET[b % PAIR_ALPHABET.length];
  code = code.slice(0, 4) + '-' + code.slice(4);
  pairCodes.set(code, { user, exp: Date.now() + 10 * 60_000 });
  return code;
}
function usePairCode(req, code) {
  limit('pair:' + clientIp(req), 10, 10 * 60_000, '嘗試太多次，請 10 分鐘後再試');
  const norm = String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  const key = norm.length === 8 ? norm.slice(0, 4) + '-' + norm.slice(4) : '';
  const p = pairCodes.get(key);
  if (!p || p.exp < Date.now()) throw httpErr(401, '配對碼錯誤或已過期');
  return p.user; // reusable until expiry, so one code can pair several phones
}

function startSession(res, who) {
  const sid = crypto.randomBytes(32).toString('base64url');
  sessions.set(sid, { user: who.user, admin: who.admin, exp: Date.now() + SESSION_TTL });
  return `sid=${sid}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_TTL / 1000}${COOKIE_SECURE ? '; Secure' : ''}`;
}

// ---------------- devices ----------------
/** key "owner/name" -> { key, owner, name, ws, info, pending, connectedAt, alive } */
const devices = new Map();
let seq = 0;

const idFor = (ctx, d) => (d.owner === ctx.user ? d.name : `${d.owner}/${d.name}`);
function visible(ctx) {
  return [...devices.values()].filter((d) => d.owner === ctx.user || ctx.admin).map((d) => ({ id: idFor(ctx, d), d }));
}
const globRe = (g) => new RegExp('^' + g.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');

/** Resolve a target spec to connected devices. Returns { targets:[{id,d}], offline:[ids] }. */
function resolveTargets(ctx, spec) {
  const vis = visible(ctx);
  const byId = new Map(vis.map((v) => [v.id, v]));
  const hint = 'Pass "device" with the car name from list_cars.';
  if (spec === undefined || spec === null || spec === '' || (Array.isArray(spec) && spec.length === 0)) {
    if (vis.length === 1) return { targets: vis, offline: [] };
    if (vis.length === 0) throw new Error('no car connected. Open the E60 phone app in the car (remote MCP switched on, paired).');
    throw new Error(`${vis.length} devices connected (${vis.map((v) => v.id).join(', ')}). ${hint}`);
  }
  const parts = (Array.isArray(spec) ? spec : String(spec).split(',')).map((s) => String(s).trim()).filter(Boolean);
  const out = new Map(); const offline = [];
  const add = (id) => { const v = byId.get(id); v ? out.set(id, v) : offline.push(id); };
  for (const p of parts) {
    if (p === 'all' || p === '*') vis.forEach((v) => out.set(v.id, v));
    else if (p.startsWith('group:')) {
      const g = store.getGroups(ctx.user)[p.slice(6)];
      if (!g) throw new Error(`no group '${p.slice(6)}'. Groups: ${Object.keys(store.getGroups(ctx.user)).join(', ') || 'none'}`);
      g.forEach(add);
    } else if (p.includes('*')) {
      const re = globRe(p); vis.filter((v) => re.test(v.id)).forEach((v) => out.set(v.id, v));
    } else add(p);
  }
  if (out.size === 0) throw new Error(`no connected device matches ${JSON.stringify(spec)}. Connected: ${vis.map((v) => v.id).join(', ') || 'none'}`);
  return { targets: [...out.values()], offline };
}

function callDevice(d, method, params = {}) {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { d.pending.delete(id); reject(new Error(`timed out on ${method}`)); }, CALL_TIMEOUT_MS);
    d.pending.set(id, { resolve, reject, timer });
    d.ws.send(JSON.stringify({ id, method, params }));
  });
}

/** Run one device method on every target in parallel. */
async function fanout(ctx, spec, method, params) {
  const { targets, offline } = resolveTargets(ctx, spec);
  const results = await Promise.all(targets.map(async ({ id, d }) => {
    try { return { id, ok: true, result: await callDevice(d, method, params) }; }
    catch (e) { return { id, ok: false, error: e.message }; }
  }));
  for (const id of offline) results.push({ id, ok: false, error: 'offline' });
  return results;
}

// ---------------- MCP tools（車機：只讀＋導航，沒有車控）----------------
const DEV = {
  device: {
    type: ['string', 'array'], items: { type: 'string' },
    description: 'Which car/phone (name from list_cars). Optional when only one is connected.',
  },
};
const obj = (properties, required) => ({ type: 'object', properties, ...(required ? { required } : {}) });
const RO = { readOnlyHint: true, openWorldHint: false };

const TOOLS = [
  { name: 'list_cars', description: 'List the cars (phone apps paired in the car) that are online now.', inputSchema: obj({}), annotations: { ...RO, title: '列出車輛' } },
  { name: 'car_status', description: 'Current vehicle state from the head unit: speed, gear, rpm, fuel, range, coolant/oil temp, battery voltage, outside temp, fuel economy, doors/hood/trunk, lights, turn signals, handbrake, seatbelt, current road name + speed limit, navigation progress, accessories. Values may be missing when the CAN bus has not reported them.', inputSchema: obj({ ...DEV, raw: { type: 'boolean', default: false, description: 'Also return the raw JSON' } }), annotations: { ...RO, title: '車況' } },
  { name: 'car_surroundings', description: 'What the 360 cameras detect right now (relative to the car: x = metres to the right, z = metres forward from the car centre): vehicles, motorbikes, pedestrians and cones with distance/lane, lane lines and their type, blind-spot warnings, lead-car braking, traffic light, driver-aid warnings, parking slots, parking sensor distances.', inputSchema: obj({ ...DEV, raw: { type: 'boolean', default: false } }), annotations: { ...RO, title: '周圍狀況' } },
  { name: 'car_camera', description: 'Actual 360 camera imagery stitched by the head unit: "top" = bird\'s-eye ground image around the car, "panorama" = the surround (bowl) texture unwrapped 360° around the car. Takes a few seconds the first time (the head unit starts producing it on request).', inputSchema: obj({ ...DEV, view: { type: 'string', enum: ['top', 'panorama', 'both'], default: 'top' } }), annotations: { ...RO, title: '360 畫面' } },
  { name: 'phone_location', description: 'GPS position of the phone in the car (lat, lon, accuracy, speed, heading, age) with an OpenStreetMap link.', inputSchema: obj({ ...DEV }), annotations: { ...RO, title: '手機定位' } },
  { name: 'nav_status', description: 'Head-unit navigation: destination, total distance/time, next manoeuvre, distance to it, lane guidance.', inputSchema: obj({ ...DEV }), annotations: { ...RO, title: '導航狀態' } },
  { name: 'nav_search', description: 'Search a destination (Taiwan street address incl. house number, shop name or landmark), nearest to the car first. Returns candidates with lat/lon; pass one to nav_start.', inputSchema: obj({ ...DEV, query: { type: 'string' } }, ['query']), annotations: { ...RO, title: '搜尋目的地' } },
  { name: 'nav_start', description: 'Start head-unit navigation to a destination. Give lat+lon (+name) from nav_search, or just "query" to navigate to the best search match. Confirm with the user first if the match is ambiguous.', inputSchema: obj({ ...DEV, query: { type: 'string' }, name: { type: 'string' }, lat: { type: 'number' }, lon: { type: 'number' } }), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false, title: '開始導航' } },
  { name: 'nav_stop', description: 'Stop the head-unit navigation.', inputSchema: obj({ ...DEV }), annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false, title: '結束導航' } },
  {
    name: 'car_control',
    description: 'Remote-control the car. lock, unlock, windows_up, windows_down, trunk_open, trunk_close, start_engine, stop_engine go straight to the car\'s own cellular module (work even when the car is off and the head unit is asleep). ac_on/ac_off, ambient_* and rear_* go through the phone + head unit. ALWAYS confirm with the user in plain words before each call (e.g. "要幫你鎖車嗎？"), one action per call. start_engine remotely cranks the car (the module enforces park/brake) — never in an enclosed space (carbon monoxide). stop_engine is refused while the car is moving.',
    inputSchema: obj({
      ...DEV,
      action: { type: 'string', enum: ['lock', 'unlock', 'windows_up', 'windows_down', 'trunk_open', 'trunk_close', 'start_engine', 'stop_engine', 'ac_on', 'ac_off', 'ambient_on', 'ambient_off', 'ambient_color', 'ambient_brightness', 'rear_message', 'rear_auto'] },
      color: { type: 'string', description: 'ambient_color: hex like "#ff3300"' },
      brightness: { type: 'integer', minimum: 5, maximum: 100 },
      slot: { type: 'integer', minimum: 0, maximum: 99, description: 'rear_message preset index' },
    }, ['action']),
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true, title: '車控' },
  },
];
// 手機 App 認得的方法（給 /api/call 用；網頁控制台只用得到只讀的）
const DEVICE_METHODS = new Set(['status', 'scene', 'camera', 'location', 'route', 'search', 'nav_to', 'nav_stop', 'control']);
const WEB_METHODS = new Set(['status', 'scene', 'location', 'route']);

function deviceList(ctx) {
  return visible(ctx).map(({ id, d }) => ({ id, owner: d.owner, connectedAt: d.connectedAt, info: d.info }));
}

const T = { car: '汽車', van: '廂型車', truck: '卡車', bus: '公車', moto: '機車', bike: '自行車', bigbike: '重機', ped: '行人', cone: '三角錐' };
const LINE = { w: '白', y: '黃', r: '紅（路緣）' };
const IND = { left: '左轉', right: '右轉', straight: '直行', 'slight left': '靠左', 'slight right': '靠右', 'sharp left': '左轉', 'sharp right': '右轉', uturn: '迴轉', through: '直行', none: '直行' };
const f1 = (v) => (typeof v === 'number' && isFinite(v) ? Math.round(v * 10) / 10 : null);
const has = (v) => v !== undefined && v !== null && v !== '';

function where(x, z) {
  const lane = Math.abs(x) < 1.75 ? '同車道' : x < 0 ? (x > -5.25 ? '左邊車道' : '更左邊') : (x < 5.25 ? '右邊車道' : '更右邊');
  const fb = z > 2.4 ? '前方' : z < -2.4 ? '後方' : '旁邊';
  return `${lane}${fb}`;
}

function statusText(s) {
  const L = [];
  if (!s || !Object.keys(s).length) return '車機沒有回應（手機有連上車機的熱點嗎？中控台有開嗎？）';
  L.push(`車速 ${has(s.speed) ? Math.round(s.speed) + ' km/h' : '—'}　檔位 ${s.gear || '—'}${has(s.rpm) ? '　轉速 ' + s.rpm + ' rpm' : ''}`);
  const m = [];
  if (has(s.fuel)) m.push(`油量 ${s.fuel} L`); if (has(s.range)) m.push(`續航 ${s.range} km`);
  if (has(s.water)) m.push(`水溫 ${s.water}°C`); if (has(s.oil)) m.push(`機油 ${s.oil}°C`);
  if (has(s.volt)) m.push(`電壓 ${s.volt} V`); if (has(s.out)) m.push(`車外 ${s.out}°C`);
  if (has(s.avgFuel)) m.push(`平均油耗 ${s.avgFuel}`); if (has(s.odo)) m.push(`里程 ${s.odo} km`);
  if (m.length) L.push(m.join('　'));
  const d = s.doors || {}; const open = Object.entries({ fl: '左前門', fr: '右前門', rl: '左後門', rr: '右後門', trunk: '後車廂', hood: '引擎蓋' }).filter(([k]) => d[k]).map(([, v]) => v);
  L.push(open.length ? `沒關：${open.join('、')}` : (s.doors ? '車門都關著' : '車門狀態未知'));
  const lt = [];
  if (s.lowBeam) lt.push('近光燈'); if (s.highBeam) lt.push('遠光燈'); if (s.fogF) lt.push('前霧燈'); if (s.fogR) lt.push('後霧燈');
  if (s.hazard) lt.push('警示燈'); else { if (s.turnL) lt.push('左方向燈'); if (s.turnR) lt.push('右方向燈'); }
  if (s.brake) lt.push('踩煞車'); if (s.handbrake) lt.push('手煞車拉著'); if (s.belt === false) lt.push('安全帶沒繫');
  if (lt.length) L.push(lt.join('、'));
  if (s.road && (s.road.name || s.road.maxspeed)) L.push(`道路：${s.road.name || '—'}${s.road.maxspeed ? '　速限 ' + s.road.maxspeed : ''}`);
  if (s.dest) L.push(`導航中 → ${s.dest.name || ''}${s.nav && !s.nav.arrive ? `；${Math.round(s.nav.dist)} m 後${s.nav.act || ''}${s.nav.name ? '（' + s.nav.name + '）' : ''}` : ''}`);
  if (!s.can) L.push(`CAN：${s.canStatus || '沒有資料'}`);
  if (s.acc) L.push(`配件：氛圍燈 ${s.acc.ambient || '—'}；後擋看板 ${s.acc.rear || '—'}`);
  if (s.phoneGps) L.push('車機正在用手機的 GPS');
  return L.join('\n');
}

function sceneText(sc) {
  if (!sc || !sc.live) return '360 目前沒有送偵測結果（360 背景偵測沒開，或車機沒連上）。';
  const L = [];
  const objs = (sc.objs || []).map((o) => ({ ...o, d: Math.hypot(o.x, o.z) })).sort((a, b) => a.d - b.d);
  L.push(`偵測到 ${objs.length} 個物件${sc.stat ? `（${f1(sc.stat.fps)} fps，${sc.stat.eng || ''}）` : ''}${sc.lot ? '；目前在停車場' : ''}`);
  for (const o of objs.slice(0, 15)) {
    const extra = [o.still ? '停著' : null, o.blink ? '打方向燈' : null].filter(Boolean).join('、');
    L.push(`- ${T[o.t] || o.t}：${where(o.x, o.z)} ${f1(o.d)} m（x=${f1(o.x)}, z=${f1(o.z)}）${extra ? '，' + extra : ''}`);
  }
  if (objs.length > 15) L.push(`- …另外 ${objs.length - 15} 個`);
  const lines = sc.road?.lines || [];
  if (lines.length) {
    const side = lines.filter((l) => Math.abs(l.z2 - l.z1) > Math.abs(l.x2 - l.x1)).map((l) => ({ x: (l.x1 + l.x2) / 2, l })).sort((a, b) => a.x - b.x);
    L.push('車道線（左到右）：' + side.map(({ x, l }) => `${LINE[l.k] || l.k}${l.d ? '虛' : '實'}線 ${f1(x)} m`).join('、'));
  }
  if (sc.lca && (sc.lca.L || sc.lca.R)) L.push(`盲點：${sc.lca.L ? '左側' + (sc.lca.L >= 2 ? '有車快速接近' : '有車') : ''}${sc.lca.L && sc.lca.R ? '；' : ''}${sc.lca.R ? '右側' + (sc.lca.R >= 2 ? '有車快速接近' : '有車') : ''}`);
  if (sc.lead?.brake) L.push('前車正在煞車');
  if (sc.cutin) L.push(`${sc.cutin.side === 'L' ? '左' : '右'}側有車切入`);
  if (sc.ldw && (sc.ldw.l || sc.ldw.r)) L.push(`偏離車道（${sc.ldw.l ? '左' : '右'}）`);
  if (sc.tl) L.push(`紅綠燈：${{ red: '紅燈', yellow: '黃燈', green: '綠燈' }[sc.tl.s] || sc.tl.s}${sc.tl.ar ? '（' + sc.tl.ar + '）' : ''}`);
  for (const a of sc.aids || []) L.push(`提醒：${a.t}`);
  const slots = sc.slots || [];
  if (slots.length) L.push(`停車格 ${slots.length} 個，空的 ${slots.filter((s) => !s.o).length} 個`);
  const pdc = sc.veh?.pdc;
  if (pdc) {
    const mn = (a) => (Array.isArray(a) ? Math.min(...a.filter((v) => typeof v === 'number' && v < 90)) : Infinity);
    const f = mn(pdc.f), r = mn(pdc.r);
    if (isFinite(f) || isFinite(r)) L.push(`雷達：${isFinite(f) ? '前方最近 ' + f1(f) + ' m' : ''}${isFinite(f) && isFinite(r) ? '，' : ''}${isFinite(r) ? '後方最近 ' + f1(r) + ' m' : ''}`);
  }
  if (sc.map?.name) L.push(`地圖：${sc.map.name}${sc.map.maxspeed ? '，速限 ' + sc.map.maxspeed : ''}`);
  return L.join('\n');
}

function navText(st, route) {
  const L = [];
  if (!route && !st?.dest) return '沒有在導航。';
  if (route?.dest) L.push(`目的地：${route.dest.name || ''}（${route.dest.lat}, ${route.dest.lon}）`);
  if (route?.dist) L.push(`全程 ${(route.dist / 1000).toFixed(1)} km，約 ${Math.round(route.dur / 60)} 分鐘（規劃當時）`);
  const n = st?.nav;
  if (n?.arrive) L.push('已抵達目的地附近');
  else if (n) {
    L.push(`下一步：${Math.round(n.dist)} m 後${n.act || ''}${n.name ? '，進入 ' + n.name : ''}`);
    if (n.lanes?.length) L.push('車道：' + n.lanes.map((l, i) => `${i + 1}:${(l.ind || []).map((k) => IND[k] || k).join('/') || '直行'}${l.valid ? '✓' : ''}${n.cur === i ? '（目前）' : ''}`).join('　'));
    if (n.tip) L.push(n.tip);
  } else L.push('（車機還沒算出目前位置在路線上的哪裡：360 沒有定位，或手機沒分享 GPS）');
  return L.join('\n');
}

async function one(ctx, device, method, params) {
  const results = await fanout(ctx, device, method, params);
  if (results.length !== 1) throw new Error('pick one car with "device"');
  const r = results[0];
  if (!r.ok) throw new Error(r.error);
  return { id: r.id, result: r.result };
}
const text = (t) => ({ content: [{ type: 'text', text: t }] });

async function callTool(ctx, name, args = {}) {
  const dev = args.device;
  switch (name) {
    case 'list_cars': return text(JSON.stringify(deviceList(ctx), null, 2));
    case 'car_status': {
      const { result } = await one(ctx, dev, 'status', {});
      return text(statusText(result) + (args.raw ? '\n\n' + JSON.stringify(result) : ''));
    }
    case 'car_surroundings': {
      const { result } = await one(ctx, dev, 'scene', {});
      if (result) { delete result.bowl; delete result.ground; }
      return text(sceneText(result) + (args.raw ? '\n\n' + JSON.stringify(result) : ''));
    }
    case 'car_camera': {
      const view = ['top', 'panorama', 'both'].includes(args.view) ? args.view : 'top';
      const { result } = await one(ctx, dev, 'camera', { view });
      const content = [];
      const img = (u, label) => {
        const m = /^data:(image\/[\w+]+);base64,(.+)$/.exec(u || '');
        if (m) { content.push({ type: 'text', text: label }); content.push({ type: 'image', mimeType: m[1], data: m[2] }); }
      };
      if (view !== 'panorama') img(result.ground, '俯視（車子在正中間，上方是車頭方向）');
      if (view !== 'top') img(result.bowl, '環景展開（橫向 360°，中間是車頭方向）');
      if (!content.length) return { content: [{ type: 'text', text: result.error || '360 沒有送畫面（360 背景偵測要開著、車機連著；剛開可能要多等幾秒再試）' }], isError: true };
      return { content };
    }
    case 'phone_location': {
      const { result: l } = await one(ctx, dev, 'location', {});
      if (!l || l.lat == null) return text('手機目前沒有定位。');
      return text(`${l.lat}, ${l.lon}（誤差 ${f1(l.acc)} m，${Math.round(l.age)} 秒前${l.spd != null ? `，${Math.round(l.spd * 3.6)} km/h` : ''}${l.hdg != null ? `，航向 ${Math.round(l.hdg)}°` : ''}）\nhttps://www.openstreetmap.org/?mlat=${l.lat}&mlon=${l.lon}#map=17/${l.lat}/${l.lon}`);
    }
    case 'nav_status': {
      const [{ result: st }, { result: route }] = await Promise.all([one(ctx, dev, 'status', {}), one(ctx, dev, 'route', {})]);
      return text(navText(st, route));
    }
    case 'nav_search': {
      const q = String(args.query || '').trim(); if (!q) throw new Error('query required');
      const { result } = await one(ctx, dev, 'search', { query: q });
      if (result?.err) throw new Error(result.err);
      if (!result?.length) return text(`找不到「${q}」。`);
      return text(result.slice(0, 8).map((p, i) => `${i + 1}. ${p.name}　${p.addr}${p.dist != null ? `　距離 ${(p.dist / 1000).toFixed(1)} km` : ''}\n   lat=${p.lat}, lon=${p.lon}`).join('\n'));
    }
    case 'nav_start': {
      let { name, lat, lon } = args;
      if (typeof lat !== 'number' || typeof lon !== 'number') {
        const q = String(args.query || '').trim(); if (!q) throw new Error('give lat+lon or query');
        const { result } = await one(ctx, dev, 'search', { query: q });
        if (result?.err) throw new Error(result.err);
        if (!result?.length) return { content: [{ type: 'text', text: `找不到「${q}」，沒有開始導航。` }], isError: true };
        ({ name, lat, lon } = result[0]);
      }
      if (!(lat > -90 && lat < 90 && lon > -180 && lon < 180)) throw new Error('bad lat/lon');
      await one(ctx, dev, 'nav_to', { name: String(name || '目的地').slice(0, 80), lat, lon });
      return text(`已送到車機，開始導航到「${name || '目的地'}」（${lat}, ${lon}）。中控台會自己規劃路線。`);
    }
    case 'nav_stop': { await one(ctx, dev, 'nav_stop', {}); return text('已結束導航。'); }
    case 'car_control': {
      const CY = { lock: '鎖車', unlock: '開鎖', windows_up: '升窗', windows_down: '降窗', trunk_open: '開尾箱', trunk_close: '關尾箱', start_engine: '發動', stop_engine: '熄火' };
      // 車體／引擎：走車上遠端模組的車聯網（cyhy 雲端），車熄火、中控台關著也能發
      if (CY[args.action]) {
        if (!hyll.configured()) return { content: [{ type: 'text', text: '遠端模組（亮波）還沒在這台 relay 設定（CYHY_SSK/UID/TID）。' }], isError: true };
        // 行駛中不熄火：手機在車上、讀得到車速就先檢查；車停好／讀不到時視為停著可熄火
        if (args.action === 'stop_engine') {
          const st = await one(ctx, dev, 'status', {}).then((r) => r.result).catch(() => null);
          if (st && typeof st.speed === 'number' && st.speed > 3) return { content: [{ type: 'text', text: `車子正在移動（${Math.round(st.speed)} km/h），不熄火。` }], isError: true };
        }
        const r = await hyll.sendCode(hyll.CODES[args.action]).catch((e) => ({ ok: false, ret_msg: e.message }));
        if (!r.ok) return { content: [{ type: 'text', text: `${CY[args.action]}送出失敗：${r.ret_msg || r.ret_code || '未知'}` }], isError: true };
        log(`car_control ${ctx.user} -> ${CY[args.action]} (cyhy ${r.ret_code})`);
        const note = args.action === 'start_engine' ? '（已送發動；室內／地下室勿發動——廢氣。用 car_status 看車況）'
          : args.action === 'stop_engine' ? '（已送熄火）' : '（用 car_status 確認車門／車鎖）';
        return text(`已遠端${CY[args.action]}。${note}`);
      }
      // 氛圍燈／空調／後擋看板：走手機＋中控台
      const R = { ambient_on: ['ambient_power', '1', '氛圍燈開'], ambient_off: ['ambient_power', '0', '氛圍燈關'], ac_on: ['remote', 'AC_ON', '開空調'], ac_off: ['remote', 'AC_OFF', '關空調'], rear_auto: ['rear_idle', '', '後擋看板回到自動'] };
      let cmd, arg, label;
      if (R[args.action]) [cmd, arg, label] = R[args.action];
      else if (args.action === 'ambient_color') {
        const c = String(args.color || '').replace(/^#/, '');
        if (!/^[0-9a-fA-F]{6}$/.test(c)) throw new Error('color must be hex like #ff3300');
        [cmd, arg, label] = ['ambient_color', c.toLowerCase(), `氛圍燈顏色 #${c}`];
      } else if (args.action === 'ambient_brightness') {
        const b = Math.round(Number(args.brightness)); if (!(b >= 5 && b <= 100)) throw new Error('brightness 5-100');
        [cmd, arg, label] = ['ambient_bright', String(b), `氛圍燈亮度 ${b}`];
      } else if (args.action === 'rear_message') {
        const n = Math.round(Number(args.slot)); if (!(n >= 0 && n <= 99)) throw new Error('slot 0-99');
        [cmd, arg, label] = ['rear_play', String(n), `後擋看板第 ${n} 則`];
      } else throw new Error(`unknown action ${args.action}`);
      await one(ctx, dev, 'control', { cmd, arg });
      log(`car_control ${ctx.user} -> ${label} (phone)`);
      return text(`已送出：${label}（走中控台；用 car_status 確認）。`);
    }
  }
  throw new Error(`unknown tool ${name}`);
}

// ---------------- MCP JSON-RPC ----------------
const SUPPORTED = ['2025-06-18', '2025-03-26', '2024-11-05'];
async function handleRpc(ctx, msg) {
  const { id, method, params } = msg || {};
  const isNote = id === undefined || id === null;
  const reply = (result) => ({ jsonrpc: '2.0', id, result });
  switch (method) {
    case 'initialize': {
      const v = SUPPORTED.includes(params?.protocolVersion) ? params.protocolVersion : SUPPORTED[0];
      return reply({
        protocolVersion: v,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'e60-car', version: '1.0.0' },
        instructions: 'Read-only view of the user\'s BMW E60 (through the phone app paired in the car) plus navigation. car_status for gauges/doors/lights/road, car_surroundings for what the 360 cameras see, car_camera for the actual 360 image, nav_* to search a destination and start/stop the head-unit navigation. There are NO vehicle-control tools: never claim you can lock, unlock, open windows or start the car. Answer in the user\'s language (usually Traditional Chinese).',
      });
    }
    case 'ping': return reply({});
    case 'tools/list': return reply({ tools: TOOLS });
    case 'tools/call':
      try { return reply(await callTool(ctx, params?.name, params?.arguments || {})); }
      catch (e) { return reply({ content: [{ type: 'text', text: `Error: ${e.message}` }], isError: true }); }
    default:
      return isNote ? null : { jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } };
  }
}

// ---------------- HTTP ----------------
function readBody(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(new Error('body too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}
const sendJson = (res, status, obj, headers = {}) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers });
  res.end(JSON.stringify(obj));
};
const CONSOLE_HTML = path.join(DIR, 'console.html');
const SECURITY_HEADERS = {
  'x-frame-options': 'DENY', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer',
  'content-security-policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
};

async function handleApi(req, res, url) {
  if (req.method === 'POST' && !/application\/json/.test(req.headers['content-type'] || ''))
    return sendJson(res, 415, { error: 'content-type must be application/json' });
  const body = req.method === 'POST' ? JSON.parse((await readBody(req)) || '{}') : {};

  if (url.pathname === '/api/config') return sendJson(res, 200, { registration: REGISTRATION, auth: 'passkey', mcp_url: `${ORIGIN}/mcp` });
  if (url.pathname === '/api/passkey/register/options' && req.method === 'POST')
    return sendJson(res, 200, await registerOptions(req, body, authFromCookie(req)));
  if (url.pathname === '/api/passkey/register/verify' && req.method === 'POST') {
    const who = await registerVerify(req, body);
    const ctx = authFromCookie(req);
    if (ctx && ctx.user === who.user) return sendJson(res, 200, who);
    return sendJson(res, 200, who, { 'set-cookie': startSession(res, who) });
  }
  if (url.pathname === '/api/passkey/login/options' && req.method === 'POST') return sendJson(res, 200, await loginOptions());
  if (url.pathname === '/api/passkey/login/verify' && req.method === 'POST') {
    const who = await loginVerify(req, body);
    return sendJson(res, 200, who, { 'set-cookie': startSession(res, who) });
  }
  if (url.pathname === '/api/device-pair' && req.method === 'POST') {
    const user = usePairCode(req, body.code);
    const name = String(body.device_name || 'android').replace(/[^\w.\-]/g, '_').slice(0, 64);
    const token = store.issueDeviceToken(user, name);
    log(`device paired ${user}/${name} from ${clientIp(req)}`);
    return sendJson(res, 200, { user, device_name: name, device_token: token, ws_path: '/device' });
  }
  if (url.pathname === '/api/logout' && req.method === 'POST') {
    const m = /(?:^|;\s*)sid=([\w-]+)/.exec(req.headers.cookie || ''); if (m) sessions.delete(m[1]);
    return sendJson(res, 200, { ok: true }, { 'set-cookie': 'sid=; Path=/; Max-Age=0' });
  }

  const ctx = authFromCookie(req) || authFromBearer(req);
  if (!ctx) return sendJson(res, 401, { error: 'login required' });

  if (url.pathname === '/api/me') {
    const u = store.getUser(ctx.user);
    return sendJson(res, 200, { user: ctx.user, admin: ctx.admin, mcp_key: u?.mcpKey || null, mcp_url: `${ORIGIN}/mcp`, server: ORIGIN, groups: store.getGroups(ctx.user) });
  }
  if (url.pathname === '/api/devices') return sendJson(res, 200, { devices: deviceList(ctx), paired: store.pairedDevices(ctx.user) });
  if (url.pathname === '/api/oauth/grants') return sendJson(res, 200, { grants: store.listGrants(ctx.user) });
  if (url.pathname === '/api/oauth/revoke' && req.method === 'POST')
    return sendJson(res, 200, { revoked: store.revokeClientGrants(ctx.user, String(body.client_id || '')) });
  if (url.pathname === '/api/pair/new' && req.method === 'POST') return sendJson(res, 200, { code: newPairCode(ctx.user), expires_in: 600 });
  if (url.pathname === '/api/passkeys') {
    const u = store.getUser(ctx.user);
    return sendJson(res, 200, { passkeys: (u?.credentials || []).map((c) => ({ id: c.id, label: c.label, created: c.created, lastUsed: c.lastUsed, backedUp: c.backedUp })) });
  }
  if (url.pathname === '/api/passkeys/delete' && req.method === 'POST') {
    const u = store.getUser(ctx.user);
    if ((u?.credentials || []).length <= 1) return sendJson(res, 400, { error: '至少要保留一把 Passkey' });
    return sendJson(res, 200, { removed: store.removeCredential(ctx.user, String(body.id || '')) });
  }
  if (url.pathname === '/api/call' && req.method === 'POST') {
    if (!WEB_METHODS.has(body.tool)) return sendJson(res, 400, { error: 'unknown tool' });
    const results = await fanout(ctx, body.device, body.tool, body.args || {});
    return sendJson(res, 200, { results });
  }
  if (url.pathname === '/api/groups' && req.method === 'POST') {
    store.setGroup(ctx.user, String(body.group || ''), (body.devices || []).map(String));
    return sendJson(res, 200, { groups: store.getGroups(ctx.user) });
  }
  if (url.pathname === '/api/revoke-device' && req.method === 'POST') {
    const n = store.revokeDevice(ctx.user, String(body.name || ''));
    const d = devices.get(`${ctx.user}/${body.name}`); if (d) d.ws.close(4001, 'revoked');
    return sendJson(res, 200, { revoked: n });
  }
  return sendJson(res, 404, { error: 'not found' });
}

const oauth = createOAuth({ ORIGIN, DIR, log, clientIp, authFromCookie, readBody });

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname === '/health') return sendJson(res, 200, { ok: true, devices: devices.size });

    if (await oauth.handle(req, res, url)) return;

    if (url.pathname === '/agent-prompt.md') {
      const txt = fs.readFileSync(path.join(DIR, 'agent-prompt.md'), 'utf8').replaceAll('https://car-mcp.vito1317.com', ORIGIN);
      res.writeHead(200, { 'content-type': 'text/markdown; charset=utf-8', 'cache-control': 'no-cache' });
      return res.end(txt);
    }
    if (url.pathname === '/webauthn.js') {
      res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8', 'cache-control': 'no-cache' });
      return res.end(fs.readFileSync(path.join(DIR, 'webauthn.js')));
    }

    if (url.pathname === '/mcp') {
      for (const [k, v] of Object.entries(oauth.CORS)) res.setHeader(k, v);
      if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
      const ctx = authFromBearer(req);
      if (!ctx) {
        res.writeHead(401, { 'www-authenticate': oauth.wwwAuthenticate(bearer(req) ? 'invalid_token' : null), 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: bearer(req) ? 'invalid_token' : 'unauthorized' }));
      }
      if (req.method === 'GET') { res.writeHead(405, { allow: 'POST, DELETE' }); return res.end(); }
      if (req.method === 'DELETE') { res.writeHead(200); return res.end(); }
      if (req.method !== 'POST') { res.writeHead(405); return res.end(); }
      let body;
      try { body = JSON.parse(await readBody(req)); }
      catch { return sendJson(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }); }
      const batch = Array.isArray(body);
      const out = (await Promise.all((batch ? body : [body]).map((m) => handleRpc(ctx, m)))).filter(Boolean);
      if (out.length === 0) { res.writeHead(202); return res.end(); }
      const headers = {};
      if (!batch && body.method === 'initialize') headers['mcp-session-id'] = crypto.randomUUID();
      return sendJson(res, 200, batch ? out : out[0], headers);
    }

    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url);

    if (url.pathname === '/app.apk' && req.method === 'GET') {
      const f = path.join(DIR, 'avm-phone.apk');
      if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'content-type': 'application/vnd.android.package-archive', 'content-disposition': 'attachment; filename="avm-phone.apk"', 'content-length': fs.statSync(f).size });
      return fs.createReadStream(f).pipe(res);
    }
    if (url.pathname === '/' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', ...SECURITY_HEADERS });
      return res.end(fs.readFileSync(CONSOLE_HTML));
    }
    res.writeHead(404); res.end();
  } catch (e) {
    if (!res.headersSent) sendJson(res, e.status || 500, { error: e.message });
  }
});

// ---------------- device WebSocket ----------------
const wss = new WebSocketServer({ noServer: true, maxPayload: 32 * 1024 * 1024 });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/device') return socket.destroy();
  const tok = bearer(req);
  let owner, name;
  const rec = store.findDeviceToken(tok);
  if (rec) { owner = rec.user; name = rec.name; }
  else if (safeEq(tok, LEGACY_DEV)) { owner = ADMIN_USER; name = String(req.headers['x-device-name'] || 'android'); }
  else { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return socket.destroy(); }
  name = name.replace(/[^\w.\-]/g, '_').slice(0, 64);
  wss.handleUpgrade(req, socket, head, (ws) => onDevice(ws, req, owner, name));
});

function onDevice(ws, req, owner, name) {
  const key = `${owner}/${name}`;
  const prev = devices.get(key);
  if (prev) { try { prev.ws.close(4000, 'replaced'); } catch {} }
  const d = { key, owner, name, ws, info: null, pending: new Map(), connectedAt: new Date().toISOString(), alive: true };
  devices.set(key, d);
  log(`device connected: ${key} from ${clientIp(req)}`);

  ws.on('pong', () => { d.alive = true; });
  ws.on('message', (raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'hello') { d.info = m.info; return; }
    const p = d.pending.get(m.id); if (!p) return;
    d.pending.delete(m.id); clearTimeout(p.timer);
    m.error ? p.reject(new Error(m.error)) : p.resolve(m.result);
  });
  ws.on('close', () => {
    for (const p of d.pending.values()) { clearTimeout(p.timer); p.reject(new Error('device disconnected')); }
    if (devices.get(key) === d) devices.delete(key);
    log(`device disconnected: ${key}`);
  });
}

setInterval(() => {
  for (const d of devices.values()) { if (!d.alive) { d.ws.terminate(); continue; } d.alive = false; d.ws.ping(); }
  const now = Date.now();
  for (const [k, v] of sessions) if (v.exp < now) sessions.delete(k);
  for (const [k, v] of flows) if (v.exp < now) flows.delete(k);
  for (const [k, v] of pairCodes) if (v.exp < now) pairCodes.delete(k);
  oauth.sweep();
}, 30_000);

server.listen(PORT, HOST, () => log(`relay listening on ${HOST}:${PORT}`));
