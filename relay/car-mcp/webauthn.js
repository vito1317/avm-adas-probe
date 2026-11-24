// Shared WebAuthn helpers for console.html and authorize.html
(function () {
  const b64u = {
    dec: (s) => { s = s.replace(/-/g, '+').replace(/_/g, '/'); const bin = atob(s + '='.repeat((4 - s.length % 4) % 4)); return Uint8Array.from(bin, (c) => c.charCodeAt(0)).buffer; },
    enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
  };
  function creationOpts(o) {
    if (PublicKeyCredential.parseCreationOptionsFromJSON) return PublicKeyCredential.parseCreationOptionsFromJSON(o);
    return { ...o, challenge: b64u.dec(o.challenge), user: { ...o.user, id: b64u.dec(o.user.id) },
      excludeCredentials: (o.excludeCredentials || []).map((c) => ({ ...c, id: b64u.dec(c.id) })) };
  }
  function requestOpts(o) {
    if (PublicKeyCredential.parseRequestOptionsFromJSON) return PublicKeyCredential.parseRequestOptionsFromJSON(o);
    return { ...o, challenge: b64u.dec(o.challenge), allowCredentials: (o.allowCredentials || []).map((c) => ({ ...c, id: b64u.dec(c.id) })) };
  }
  function credJSON(c) {
    try { if (c.toJSON) return c.toJSON(); } catch {}
    const r = c.response, out = { id: c.id, rawId: b64u.enc(c.rawId), type: c.type, clientExtensionResults: c.getClientExtensionResults?.() || {}, authenticatorAttachment: c.authenticatorAttachment, response: { clientDataJSON: b64u.enc(r.clientDataJSON) } };
    if (r.attestationObject) { out.response.attestationObject = b64u.enc(r.attestationObject); out.response.transports = r.getTransports?.() || []; }
    if (r.authenticatorData) { out.response.authenticatorData = b64u.enc(r.authenticatorData); out.response.signature = b64u.enc(r.signature); if (r.userHandle) out.response.userHandle = b64u.enc(r.userHandle); }
    return out;
  }
  const label = () => { const ua = navigator.userAgent; return /iPhone|iPad/.test(ua) ? 'iOS' : /Android/.test(ua) ? 'Android' : /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : 'Browser'; };
  const errText = (e) => e.name === 'NotAllowedError' ? '已取消或逾時' : e.name === 'InvalidStateError' ? '這個裝置已經有此帳號的 Passkey' : e.message;

  async function post(path, body) {
    const r = await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || r.statusText);
    return j;
  }
  async function register(extra) {
    if (!window.PublicKeyCredential) throw new Error('這個瀏覽器不支援 Passkey');
    const { flow, options } = await post('/api/passkey/register/options', extra);
    const cred = await navigator.credentials.create({ publicKey: creationOpts(options) });
    return post('/api/passkey/register/verify', { flow, response: credJSON(cred), label: label() });
  }
  async function login() {
    if (!window.PublicKeyCredential) throw new Error('這個瀏覽器不支援 Passkey');
    const { flow, options } = await post('/api/passkey/login/options', {});
    const cred = await navigator.credentials.get({ publicKey: requestOpts(options) });
    return post('/api/passkey/login/verify', { flow, response: credJSON(cred) });
  }
  window.Passkey = { register, login, errText };
})();
