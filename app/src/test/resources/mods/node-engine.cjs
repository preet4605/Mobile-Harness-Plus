// A stand-in for the phone's JavaScript sandbox in JVM tests (NodeModEngine): each isolate is a
// fresh vm context with its own globals and `android.getNamedPort`, driven by JSON lines on stdin.
'use strict';
const vm = require('vm');
const readline = require('readline');

const isolates = new Map();
const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');
const text = (err) => String((err && err.stack) || err);

function create(iso) {
  const port = { postMessage: (data) => { if (isolates.has(iso)) send({ t: 'msg', iso, data: String(data) }); } };
  const context = vm.createContext({ android: { getNamedPort: (name) => Promise.resolve(port) } });
  isolates.set(iso, context);
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const m = JSON.parse(line);
  const reply = (ok, value) => send({ t: 'result', req: m.req, ok, value });
  try {
    if (m.t === 'new') { create(m.iso); reply(true, 'ok'); return; }
    if (m.t === 'close') { isolates.delete(m.iso); reply(true, 'ok'); return; }
    const context = isolates.get(m.iso);
    if (!context) { reply(false, 'Isolate is closed'); return; }
    const value = vm.runInContext(m.code, context, { timeout: m.timeout || 2000 });
    if (value && typeof value.then === 'function') {
      value.then((v) => reply(true, String(v)), (err) => reply(false, text(err)));
    } else {
      reply(true, String(value));
    }
  } catch (err) {
    reply(false, text(err));
  }
});
