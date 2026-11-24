#!/usr/bin/env node
// Account admin (passkey-based). Run on the server:
//   node users.js setup-link <name> [--admin]   one-time link (30 min) to bind a passkey; creates the user if missing
//   node users.js admin <name> on|off
//   node users.js key <name>        show MCP key
//   node users.js reset-key <name>
//   node users.js del <name>
//   node users.js list
import * as s from './store.js';
const ORIGIN = process.env.ORIGIN || 'https://car-mcp.vito1317.com';
const [cmd, name, arg] = process.argv.slice(2);
try {
  switch (cmd) {
    case 'setup-link': {
      if (!s.userExists(name)) s.createUser(name, arg === '--admin');
      else if (arg === '--admin') s.setAdmin(name, true);
      console.log(`${ORIGIN}/#setup=${s.createSetupCode(name)}`);
      console.log('(valid 30 minutes, single use)');
      break;
    }
    case 'admin': s.setAdmin(name, arg === 'on'); console.log('ok'); break;
    case 'key': console.log(s.getUser(name)?.mcpKey ?? 'no such user'); break;
    case 'reset-key': console.log(s.resetMcpKey(name)); break;
    case 'del': s.deleteUser(name); console.log('deleted'); break;
    case 'list': console.table(s.listUsers()); break;
    default: console.log('commands: setup-link | admin | key | reset-key | del | list');
  }
} catch (e) { console.error('error:', e.message); process.exit(1); }
