// OAuth 2.1 authorization server for the MCP endpoint (MCP authorization spec, 2025-06-18):
//   RFC 9728 protected-resource metadata, RFC 8414 AS metadata, RFC 7591 dynamic client registration,
//   authorization code + PKCE (S256 only), refresh-token rotation, RFC 7009 revocation.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import * as store from './store.js';

export function createOAuth({ ORIGIN, DIR, log, clientIp, authFromCookie, readBody }) {
  const RESOURCE = `${ORIGIN}/mcp`;
  const SCOPES = ['mcp'];
  const pending = new Map(); // authorize request id -> params
  const codes = new Map();   // auth code -> grant
  const hits = new Map();

  const json = (res, status, obj, extra = {}) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', pragma: 'no-cache', ...CORS, ...extra });
    res.end(JSON.stringify(obj));
  };
  const CORS = {
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET, POST, OPTIONS, DELETE',
    'access-control-allow-headers': 'authorization, content-type, mcp-protocol-version, mcp-session-id, last-event-id',
    'access-control-expose-headers': 'www-authenticate, mcp-session-id',
    'access-control-max-age': '600',
  };
  const limit = (key, max, windowMs) => {
    const now = Date.now(); const arr = (hits.get(key) || []).filter((t) => now - t < windowMs);
    if (arr.length >= max) return false; arr.push(now); hits.set(key, arr); return true;
  };

  const asMetadata = {
    issuer: ORIGIN,
    authorization_endpoint: `${ORIGIN}/oauth/authorize`,
    token_endpoint: `${ORIGIN}/oauth/token`,
    registration_endpoint: `${ORIGIN}/oauth/register`,
    revocation_endpoint: `${ORIGIN}/oauth/revoke`,
    response_types_supported: ['code'],
    response_modes_supported: ['query'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none', 'client_secret_post', 'client_secret_basic'],
    revocation_endpoint_auth_methods_supported: ['none', 'client_secret_post', 'client_secret_basic'],
    scopes_supported: SCOPES,
    service_documentation: ORIGIN,
  };
  const prMetadata = {
    resource: RESOURCE,
    authorization_servers: [ORIGIN],
    bearer_methods_supported: ['header'],
    scopes_supported: SCOPES,
    resource_name: 'E60 Car MCP',
  };
  const wwwAuthenticate = (err) =>
    `Bearer resource_metadata="${ORIGIN}/.well-known/oauth-protected-resource"${err ? `, error="${err}"` : ''}`;

  // ---------- redirect URI rules ----------
  const isLoopback = (u) => u.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname);
  function validRedirect(s) {
    let u; try { u = new URL(s); } catch { return false; }
    if (u.hash) return false;
    if (u.protocol === 'https:' || isLoopback(u)) return true;
    // private-use URI schemes for native apps (cursor://, vscode://, com.example.app:/cb) — never script-ish schemes
    return /^[a-z][a-z0-9+.\-]*:$/.test(u.protocol) && !['http:', 'javascript:', 'data:', 'file:', 'vbscript:', 'blob:', 'about:'].includes(u.protocol);
  }
  function redirectMatches(client, given) {
    if (client.redirect_uris.includes(given)) return true;
    // RFC 8252 §7.3: loopback redirects may use any port
    let g; try { g = new URL(given); } catch { return false; }
    if (!isLoopback(g)) return false;
    return client.redirect_uris.some((r) => {
      try { const u = new URL(r); return isLoopback(u) && u.hostname === g.hostname && u.pathname === g.pathname && u.search === g.search; } catch { return false; }
    });
  }
  const withParams = (uri, params) => {
    const u = new URL(uri);
    for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null) u.searchParams.set(k, v);
    return u.toString();
  };
  const resourceOk = (r) => !r || r === RESOURCE || r === ORIGIN || r === ORIGIN + '/' || r === RESOURCE + '/';

  // ---------- pages ----------
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (m) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
  const PAGE_HEADERS = {
    'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-frame-options': 'DENY',
    'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer',
    'content-security-policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'",
  };
  function errorPage(res, msg) {
    res.writeHead(400, PAGE_HEADERS);
    res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>授權錯誤</title><body style="font:15px system-ui;max-width:480px;margin:15vh auto;padding:0 16px"><h2>無法授權</h2><p>${esc(msg)}</p></body>`);
  }
  function authorizePage(res, reqId, client, redirectUri) {
    const tpl = fs.readFileSync(path.join(DIR, 'authorize.html'), 'utf8');
    const data = JSON.stringify({ req: reqId, client: client.client_name || '未命名應用程式', host: new URL(redirectUri).host || redirectUri, uri: client.client_uri || null })
      .replace(/</g, '\\u003c');
    res.writeHead(200, PAGE_HEADERS);
    res.end(tpl.replace('/*__DATA__*/null', data));
  }

  // ---------- endpoints ----------
  function authorizeGet(req, res, url) {
    const q = Object.fromEntries(url.searchParams);
    const client = store.getClient(q.client_id);
    if (!client) return errorPage(res, '未知的 client_id。請在應用程式中重新連線。');
    if (!q.redirect_uri || !redirectMatches(client, q.redirect_uri)) return errorPage(res, 'redirect_uri 與註冊時不符。');
    const fail = (error, desc) => { res.writeHead(302, { location: withParams(q.redirect_uri, { error, error_description: desc, state: q.state, iss: ORIGIN }) }); res.end(); };
    if (q.response_type !== 'code') return fail('unsupported_response_type', 'only response_type=code');
    if (!q.code_challenge || q.code_challenge_method !== 'S256') return fail('invalid_request', 'PKCE with S256 is required');
    if (!/^[A-Za-z0-9_-]{43,128}$/.test(q.code_challenge)) return fail('invalid_request', 'bad code_challenge');
    if (!resourceOk(q.resource)) return fail('invalid_target', 'unknown resource');
    const scope = (q.scope || 'mcp').split(' ').filter((s) => SCOPES.includes(s)).join(' ') || 'mcp';
    const id = crypto.randomBytes(16).toString('base64url');
    pending.set(id, {
      client_id: q.client_id, redirect_uri: q.redirect_uri, state: q.state, code_challenge: q.code_challenge,
      scope, resource: q.resource || RESOURCE, exp: Date.now() + 15 * 60_000,
    });
    return authorizePage(res, id, client, q.redirect_uri);
  }

  async function decision(req, res) {
    if (!/application\/json/.test(req.headers['content-type'] || '')) return json(res, 415, { error: 'json required' });
    const body = JSON.parse((await readBody(req)) || '{}');
    const p = pending.get(String(body.req || ''));
    if (!p || p.exp < Date.now()) return json(res, 400, { error: '授權請求已過期，請回到應用程式重新連線' });
    const ctx = authFromCookie(req);
    if (!ctx) return json(res, 401, { error: '請先登入' });
    pending.delete(String(body.req));
    if (!body.approve) {
      return json(res, 200, { redirect: withParams(p.redirect_uri, { error: 'access_denied', error_description: 'user denied', state: p.state, iss: ORIGIN }) });
    }
    const code = crypto.randomBytes(32).toString('base64url');
    codes.set(code, { ...p, user: ctx.user, exp: Date.now() + 5 * 60_000 });
    log(`oauth: ${ctx.user} authorized client ${p.client_id} (${store.getClient(p.client_id)?.client_name})`);
    return json(res, 200, { redirect: withParams(p.redirect_uri, { code, state: p.state, iss: ORIGIN }) });
  }

  async function parseForm(req) {
    const raw = await readBody(req, 64 * 1024);
    if (/application\/json/.test(req.headers['content-type'] || '')) return JSON.parse(raw || '{}');
    return Object.fromEntries(new URLSearchParams(raw));
  }
  function clientAuth(req, body) {
    let id = body.client_id, secret = body.client_secret;
    const m = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
    if (m) {
      const [a, b] = Buffer.from(m[1], 'base64').toString().split(':');
      id = decodeURIComponent(a || ''); secret = decodeURIComponent(b || '');
    }
    const c = store.getClient(id);
    if (!c || !store.checkClientSecret(c, secret)) return null;
    return { id, c };
  }

  async function token(req, res) {
    const body = await parseForm(req);
    const err = (status, error, error_description) => json(res, status, { error, error_description });
    const cl = clientAuth(req, body);
    if (!cl) return err(401, 'invalid_client', 'unknown client or bad secret');

    if (body.grant_type === 'authorization_code') {
      const g = codes.get(String(body.code || '')); codes.delete(String(body.code || ''));
      if (!g || g.exp < Date.now()) return err(400, 'invalid_grant', 'code invalid or expired');
      if (g.client_id !== cl.id) return err(400, 'invalid_grant', 'client mismatch');
      if (body.redirect_uri && body.redirect_uri !== g.redirect_uri) return err(400, 'invalid_grant', 'redirect_uri mismatch');
      const verifier = String(body.code_verifier || '');
      if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) return err(400, 'invalid_grant', 'bad code_verifier');
      const calc = crypto.createHash('sha256').update(verifier).digest('base64url');
      if (calc !== g.code_challenge) return err(400, 'invalid_grant', 'PKCE verification failed');
      if (body.resource && !resourceOk(body.resource)) return err(400, 'invalid_target', 'unknown resource');
      const t = store.issueOAuthTokens({ user: g.user, client_id: cl.id, scope: g.scope, resource: g.resource });
      return json(res, 200, { ...t, token_type: 'Bearer', scope: g.scope });
    }
    if (body.grant_type === 'refresh_token') {
      const old = store.consumeRefresh(String(body.refresh_token || ''));
      if (!old) return err(400, 'invalid_grant', 'refresh token invalid, expired or reused');
      if (old.client_id !== cl.id) { store.revokeFamily(old.family); return err(400, 'invalid_grant', 'client mismatch'); }
      const t = store.issueOAuthTokens({ user: old.user, client_id: cl.id, scope: old.scope, resource: old.resource, family: old.family });
      return json(res, 200, { ...t, token_type: 'Bearer', scope: old.scope });
    }
    return err(400, 'unsupported_grant_type', 'use authorization_code or refresh_token');
  }

  async function register(req, res) {
    if (!limit('dcr:' + clientIp(req), 30, 3600_000)) return json(res, 429, { error: 'too_many_requests' });
    let meta;
    try { meta = JSON.parse((await readBody(req, 64 * 1024)) || '{}'); } catch { return json(res, 400, { error: 'invalid_client_metadata', error_description: 'bad json' }); }
    const uris = Array.isArray(meta.redirect_uris) ? meta.redirect_uris.map(String) : [];
    if (!uris.length || uris.length > 10 || !uris.every(validRedirect))
      return json(res, 400, { error: 'invalid_redirect_uri', error_description: 'redirect_uris must be https, http loopback, or a private-use scheme' });
    const authMethod = ['none', 'client_secret_post', 'client_secret_basic'].includes(meta.token_endpoint_auth_method) ? meta.token_endpoint_auth_method : 'none';
    const clean = {
      client_name: String(meta.client_name || '').slice(0, 100) || null,
      client_uri: typeof meta.client_uri === 'string' && /^https:\/\//.test(meta.client_uri) ? meta.client_uri.slice(0, 200) : null,
      redirect_uris: uris,
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      token_endpoint_auth_method: authMethod,
      scope: 'mcp',
    };
    const { client_id, client_secret } = store.registerClient(clean);
    log(`oauth: registered client ${client_id} "${clean.client_name}" ${uris.join(' ')}`);
    return json(res, 201, {
      client_id, ...(client_secret ? { client_secret, client_secret_expires_at: 0 } : {}),
      client_id_issued_at: Math.floor(Date.now() / 1000), ...clean,
    });
  }

  async function revoke(req, res) {
    const body = await parseForm(req);
    if (!clientAuth(req, body)) return json(res, 401, { error: 'invalid_client' });
    store.revokeToken(String(body.token || ''));
    res.writeHead(200, CORS); res.end();
  }

  /** Returns true when the request was handled. */
  async function handle(req, res, url) {
    const p = url.pathname;
    const oauthPath = p.startsWith('/.well-known/') || p.startsWith('/oauth/');
    if (!oauthPath) return false;
    if (req.method === 'OPTIONS') { res.writeHead(204, CORS); res.end(); return true; }
    if (p === '/.well-known/oauth-protected-resource' || p === '/.well-known/oauth-protected-resource/mcp') { json(res, 200, prMetadata); return true; }
    if (p === '/.well-known/oauth-authorization-server' || p === '/.well-known/oauth-authorization-server/mcp' ||
        p === '/.well-known/openid-configuration') { json(res, 200, asMetadata); return true; }
    if (p === '/oauth/authorize' && req.method === 'GET') { authorizeGet(req, res, url); return true; }
    if (p === '/oauth/decision' && req.method === 'POST') { await decision(req, res); return true; }
    if (p === '/oauth/token' && req.method === 'POST') { await token(req, res); return true; }
    if (p === '/oauth/register' && req.method === 'POST') { await register(req, res); return true; }
    if (p === '/oauth/revoke' && req.method === 'POST') { await revoke(req, res); return true; }
    json(res, 404, { error: 'not_found' }); return true;
  }

  function sweep() {
    const now = Date.now();
    for (const [k, v] of pending) if (v.exp < now) pending.delete(k);
    for (const [k, v] of codes) if (v.exp < now) codes.delete(k);
  }

  return { handle, wwwAuthenticate, CORS, sweep };
}
