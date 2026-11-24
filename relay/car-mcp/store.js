// JSON-file store: users + passkey credentials, MCP keys, device tokens, groups, one-time setup codes.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const FILE = process.env.DATA_FILE || path.join(DIR, 'data.json');
const EMPTY = () => ({ users: {}, deviceTokens: {}, groups: {}, setupCodes: {}, oauthClients: {}, oauthTokens: {} });

let db = EMPTY();
let mtime = 0;
// Reload when the file changed on disk (users.js CLI may run while the server is up).
function fresh() {
  try {
    const m = fs.statSync(FILE).mtimeMs;
    if (m !== mtime) { db = { ...EMPTY(), ...JSON.parse(fs.readFileSync(FILE, 'utf8')) }; mtime = m; }
  } catch {}
}
fresh();

function save() {
  const tmp = FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, FILE);
  mtime = fs.statSync(FILE).mtimeMs;
}

export const newToken = (prefix) => prefix + crypto.randomBytes(24).toString('base64url');
const eq = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
export const validUsername = (n) => /^[\w.\-]{2,32}$/.test(n);

// ---------- users ----------
export function createUser(name, admin = false, webauthnId = null) {
  fresh();
  if (!validUsername(name)) throw new Error('帳號需 2-32 字元，只能用英數字與 _ . -');
  if (db.users[name]) throw new Error('帳號已存在');
  db.users[name] = {
    admin: !!admin, mcpKey: newToken('mk_'), webauthnId: webauthnId || crypto.randomBytes(16).toString('base64url'),
    credentials: [], created: new Date().toISOString(),
  };
  save();
  return db.users[name];
}
export function getUser(name) {
  fresh();
  const u = db.users[name];
  if (u && !u.webauthnId) { u.webauthnId = crypto.randomBytes(16).toString('base64url'); u.credentials ||= []; save(); }
  return u;
}
export const userExists = (name) => (fresh(), !!db.users[name]);
export function setAdmin(name, admin) { fresh(); if (!db.users[name]) throw new Error('no such user'); db.users[name].admin = !!admin; save(); }
export function resetMcpKey(name) { fresh(); const u = db.users[name]; if (!u) throw new Error('no such user'); u.mcpKey = newToken('mk_'); save(); return u.mcpKey; }
export function deleteUser(name) {
  fresh(); delete db.users[name]; delete db.groups[name];
  for (const [t, d] of Object.entries(db.deviceTokens)) if (d.user === name) delete db.deviceTokens[t];
  save();
}
export const listUsers = () => (fresh(), Object.entries(db.users).map(([name, u]) => ({ name, admin: !!u.admin, passkeys: (u.credentials || []).length, created: u.created })));
export function findByMcpKey(key) {
  fresh(); if (!key) return null;
  for (const [name, u] of Object.entries(db.users)) if (u.mcpKey && eq(u.mcpKey, key)) return { user: name, admin: !!u.admin };
  return null;
}

// ---------- passkeys ----------
export function addCredential(name, cred) {
  fresh(); const u = db.users[name]; if (!u) throw new Error('no such user');
  u.credentials ||= [];
  u.credentials.push({ ...cred, created: new Date().toISOString() });
  save();
}
export function findCredential(id) {
  fresh();
  for (const [name, u] of Object.entries(db.users)) {
    const c = (u.credentials || []).find((c) => c.id === id);
    if (c) return { user: name, admin: !!u.admin, cred: c };
  }
  return null;
}
export function updateCounter(id, counter) {
  fresh();
  for (const u of Object.values(db.users)) {
    const c = (u.credentials || []).find((c) => c.id === id);
    if (c) { c.counter = counter; c.lastUsed = new Date().toISOString(); save(); return; }
  }
}
export function removeCredential(name, id) {
  fresh(); const u = db.users[name]; if (!u) return 0;
  const before = u.credentials.length;
  u.credentials = u.credentials.filter((c) => c.id !== id); save();
  return before - u.credentials.length;
}

// ---------- one-time setup codes (bind a passkey to an existing account) ----------
export function createSetupCode(name, ttlMin = 30) {
  fresh();
  const code = crypto.randomBytes(18).toString('base64url');
  db.setupCodes[code] = { user: name, exp: Date.now() + ttlMin * 60_000 };
  save();
  return code;
}
export function peekSetupCode(code) {
  fresh(); const s = db.setupCodes[code];
  return s && s.exp > Date.now() ? s.user : null;
}
export function consumeSetupCode(code) {
  fresh(); const s = db.setupCodes[code];
  delete db.setupCodes[code];
  for (const [c, v] of Object.entries(db.setupCodes)) if (v.exp < Date.now()) delete db.setupCodes[c];
  save();
  return s && s.exp > Date.now() ? s.user : null;
}

// ---------- device tokens ----------
export function issueDeviceToken(user, name) {
  fresh();
  for (const [t, d] of Object.entries(db.deviceTokens)) if (d.user === user && d.name === name) delete db.deviceTokens[t];
  const tok = newToken('dt_');
  db.deviceTokens[tok] = { user, name, created: new Date().toISOString() };
  save();
  return tok;
}
export function findDeviceToken(tok) {
  fresh(); if (!tok) return null;
  for (const [t, d] of Object.entries(db.deviceTokens)) if (eq(t, tok)) return d;
  return null;
}
export function revokeDevice(user, name) {
  fresh(); let n = 0;
  for (const [t, d] of Object.entries(db.deviceTokens)) if (d.user === user && d.name === name) { delete db.deviceTokens[t]; n++; }
  save(); return n;
}
export const pairedDevices = (user) => (fresh(), Object.values(db.deviceTokens).filter((d) => d.user === user).map((d) => ({ name: d.name, created: d.created })));

// ---------- groups ----------
export const getGroups = (user) => (fresh(), db.groups[user] || {});
export function setGroup(user, group, members) {
  fresh(); db.groups[user] ||= {};
  if (!members || members.length === 0) delete db.groups[user][group];
  else db.groups[user][group] = [...new Set(members)];
  save();
}

// ---------- OAuth: clients (dynamic registration) + tokens (stored as sha256) ----------
const sha = (t) => crypto.createHash('sha256').update(String(t)).digest('hex');
export function registerClient(meta) {
  fresh();
  const id = 'cl_' + crypto.randomBytes(12).toString('base64url');
  const secret = meta.token_endpoint_auth_method && meta.token_endpoint_auth_method !== 'none' ? newToken('cs_') : null;
  db.oauthClients[id] = { ...meta, secretHash: secret ? sha(secret) : null, created: new Date().toISOString() };
  save();
  return { client_id: id, client_secret: secret };
}
export const getClient = (id) => (fresh(), db.oauthClients[String(id || '')] || null);
export const checkClientSecret = (c, secret) => !c.secretHash || (secret && eq(sha(secret), c.secretHash));

export function issueOAuthTokens({ user, client_id, scope, resource, family }) {
  fresh();
  const access = newToken('at_'), refresh = newToken('rt_');
  const fam = family || crypto.randomBytes(8).toString('hex');
  const now = Date.now();
  db.oauthTokens[sha(access)] = { type: 'access', user, client_id, scope, resource, family: fam, exp: now + 3600_000 };
  db.oauthTokens[sha(refresh)] = { type: 'refresh', user, client_id, scope, resource, family: fam, exp: now + 30 * 86400_000, created: new Date(now).toISOString() };
  for (const [k, v] of Object.entries(db.oauthTokens)) if (v.exp < now) delete db.oauthTokens[k];
  save();
  return { access_token: access, refresh_token: refresh, expires_in: 3600 };
}
export function findOAuthToken(tok, type) {
  fresh();
  const t = db.oauthTokens[sha(tok)];
  if (!t || t.type !== type || t.exp < Date.now()) return null;
  if (!db.users[t.user]) return null;
  return { ...t, admin: !!db.users[t.user].admin };
}
/** Refresh-token rotation: consume the old refresh token. Reuse of a consumed one revokes the whole family. */
export function consumeRefresh(tok) {
  fresh();
  const h = sha(tok); const t = db.oauthTokens[h];
  if (!t || t.type !== 'refresh') return null;
  if (t.used) { revokeFamily(t.family); return null; }
  if (t.exp < Date.now()) return null;
  t.used = true; t.exp = Math.min(t.exp, Date.now() + 60_000); // short grace window for reuse detection
  save();
  return t;
}
export function revokeFamily(family) {
  fresh();
  for (const [k, v] of Object.entries(db.oauthTokens)) if (v.family === family) delete db.oauthTokens[k];
  save();
}
export function revokeToken(tok) {
  fresh(); const t = db.oauthTokens[sha(tok)];
  if (t) revokeFamily(t.family);
}
export function listGrants(user) {
  fresh();
  const m = new Map();
  for (const v of Object.values(db.oauthTokens)) {
    if (v.user !== user || v.type !== 'refresh' || v.used || v.exp < Date.now()) continue;
    const c = db.oauthClients[v.client_id];
    const g = m.get(v.client_id) || { client_id: v.client_id, client_name: c?.client_name || v.client_id, created: v.created, sessions: 0 };
    g.sessions++; m.set(v.client_id, g);
  }
  return [...m.values()];
}
export function revokeClientGrants(user, client_id) {
  fresh(); let n = 0;
  for (const [k, v] of Object.entries(db.oauthTokens)) if (v.user === user && v.client_id === client_id) { delete db.oauthTokens[k]; n++; }
  save(); return n;
}
