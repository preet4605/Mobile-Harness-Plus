/*
 * Mobile Harness mod shim (mod platform spec v2, sections 3 and 4.1).
 *
 * Loaded first into every logic mod's isolate. It gives the mod Claude-style hooks
 * (`on(event, matcher?, hook)`, `next`, `.catch`), the `$` interface and atoms, and talks to the
 * app over one message port named "mh":
 *   app -> mod: the app evaluates `__mh.connect()`, `__mh.start(options)`,
 *               `__mh.dispatch(id, event, e)` and `__mh.answer(callId, ok, value)`.
 *   mod -> app: JSON messages on the port:
 *               {t:'op', d, id, op, args}   a `$` call the app runs and answers
 *               {t:'done', d, value|error}  a dispatch finished
 *               {t:'error', d, event, error} a hook threw (the app counts a strike)
 *               {t:'log', level, text}      console.* and $.log
 * Each call from the app returns at once, so the app never waits on a script that may not finish.
 */
(function () {
  'use strict';

  var MAX_HOOKS = 128;
  var MAX_TIMERS = 16;
  var LEVELS = { debug: 1, info: 1, log: 1, warn: 1, error: 1 };
  var EVENT_NAME = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$/;

  var port = null;
  var factory = null;
  var started = false;
  var registering = false;
  var hookCount = 0;
  var hooks = Object.create(null);
  var calls = Object.create(null);
  var timers = Object.create(null);
  var atoms = Object.create(null);
  var nextCall = 1;
  var nextTimer = 1;

  function post(message) {
    var text;
    try {
      text = JSON.stringify(message);
    } catch (err) {
      text = JSON.stringify({ t: message.t, d: message.d, error: 'Result is not plain data: ' + describe(err) });
    }
    port.postMessage(text);
  }

  function describe(err) {
    if (err && typeof err === 'object' && err.stack) return String(err.stack).slice(0, 2000);
    return String(err).slice(0, 2000);
  }

  function format(args) {
    return Array.prototype.map.call(args, function (a) {
      if (typeof a === 'string') return a;
      if (a instanceof Error) return describe(a);
      try { return JSON.stringify(a); } catch (e) { return String(a); }
    }).join(' ').slice(0, 4000);
  }

  function log(level, args) {
    if (port) post({ t: 'log', level: level, text: format(args) });
  }

  globalThis.console = {
    log: function () { log('info', arguments); },
    info: function () { log('info', arguments); },
    debug: function () { log('debug', arguments); },
    warn: function () { log('warn', arguments); },
    error: function () { log('error', arguments); },
  };

  function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) {
      Object.freeze(value);
      Object.keys(value).forEach(function (k) { freeze(value[k]); });
    }
    return value;
  }

  function report(d, event, err) {
    post({ t: 'error', d: d, event: event, error: describe(err) });
  }

  // ---- $ ------------------------------------------------------------------------------

  function hostCall(d, op, args) {
    for (var i = 0; i < args.length; i++) {
      if (typeof args[i] === 'function') return Promise.reject(new TypeError('$.' + op + ' cannot take a function'));
    }
    var id = nextCall++;
    var text;
    try {
      text = JSON.stringify({ t: 'op', d: d, id: id, op: op, args: args });
    } catch (err) {
      return Promise.reject(new TypeError('$.' + op + ': arguments must be plain data (' + err.message + ')'));
    }
    return new Promise(function (resolve, reject) {
      calls[id] = { resolve: resolve, reject: reject };
      port.postMessage(text);
    });
  }

  function addTimer(d, args, repeat) {
    var name = repeat ? '$.clock.every' : '$.clock.after';
    var ms = Number(args[0]);
    var fn = args[1];
    if (typeof fn !== 'function') throw new TypeError(name + '(ms, fn) needs a function');
    if (!(ms >= 0)) throw new TypeError(name + '(ms, fn) needs a number of milliseconds');
    if (Object.keys(timers).length >= MAX_TIMERS) throw new Error('A mod can have at most ' + MAX_TIMERS + ' timers');
    var id = nextTimer++;
    timers[id] = { fn: fn, repeat: repeat };
    hostCall(d, repeat ? 'clock.every' : 'clock.after', [id, ms]).catch(function (err) {
      delete timers[id];
      report(d, 'clock', err);
    });
    return id;
  }

  function cancelTimer(d, id) {
    if (!(id in timers)) return false;
    delete timers[id];
    hostCall(d, 'clock.cancel', [id]).catch(function () {});
    return true;
  }

  function local(d, path, args) {
    switch (path) {
      case 'log': {
        var level = typeof args[0] === 'string' && args.length > 1 && LEVELS[args[0]] ? args.shift() : 'info';
        log(level === 'log' ? 'info' : level, args);
        return undefined;
      }
      case 'clock.now': return Date.now();
      case 'clock.after': return addTimer(d, args, false);
      case 'clock.every': return addTimer(d, args, true);
      case 'clock.cancel': return cancelTimer(d, args[0]);
      default: return hostCall(d, path, args);
    }
  }

  /** `$` for dispatch [d]: every property path is callable, `$.store.get(k)` becomes op "store.get". */
  function dollar(d) {
    function at(path) {
      return new Proxy(function () {}, {
        get: function (_, key) {
          if (typeof key !== 'string' || key === 'then' || key === 'toJSON') return undefined;
          return at(path ? path + '.' + key : key);
        },
        apply: function (_, __, args) {
          if (!path) throw new TypeError('$ is not a function');
          return local(d, path, Array.prototype.slice.call(args));
        },
      });
    }
    return at('');
  }

  // ---- hooks --------------------------------------------------------------------------

  function on(event, matcher, fn) {
    if (!registering) throw new Error('on() works only while register() runs');
    if (typeof matcher === 'function' && fn === undefined) { fn = matcher; matcher = undefined; }
    if (typeof event !== 'string' || !EVENT_NAME.test(event)) throw new TypeError('on(event, ...): bad event name ' + JSON.stringify(event));
    if (typeof fn !== 'function') throw new TypeError('on("' + event + '", ...) needs a hook function');
    if (matcher !== undefined && (matcher === null || typeof matcher !== 'object')) throw new TypeError('on("' + event + '", matcher, hook): matcher must be an object');
    if (++hookCount > MAX_HOOKS) throw new Error('A mod can add at most ' + MAX_HOOKS + ' hooks');
    var entry = { event: event, matcher: matcher, fn: fn, onError: null };
    (hooks[event] || (hooks[event] = [])).push(entry);
    var handle = {
      catch: function (handler) {
        if (typeof handler !== 'function') throw new TypeError('.catch() needs a function');
        entry.onError = handler;
        return handle;
      },
    };
    return handle;
  }

  function matchValue(want, got) {
    if (Array.isArray(want)) return want.some(function (w) { return matchValue(w, got); });
    if (want instanceof RegExp) return typeof got === 'string' && want.test(got);
    if (want !== null && typeof want === 'object') return got !== null && typeof got === 'object' && matches(want, got);
    return want === got;
  }

  function matches(matcher, e) {
    if (!matcher) return true;
    for (var key in matcher) {
      if (!matchValue(matcher[key], e == null ? undefined : e[key])) return false;
    }
    return true;
  }

  function invoke(fn, args) {
    try {
      return Promise.resolve(fn.apply(undefined, args));
    } catch (err) {
      return Promise.reject(err);
    }
  }

  /**
   * Runs hook [i] and, through `next`, the hooks after it. A hook that throws is skipped: its
   * `.catch` handler answers instead, and without one the chain goes on as if it were not there.
   */
  function chain(d, $, list, i, e) {
    if (i >= list.length) return Promise.resolve(null);
    var hook = list[i];
    var below = null;
    var next = function (input) {
      if (below) return below;
      next.called = true;
      below = chain(d, $, list, i + 1, input === undefined ? e : freeze(input));
      return below;
    };
    next.called = false;
    return invoke(hook.fn, [$, e, next]).catch(function (err) {
      report(d, hook.event, err);
      var fallback = function () { return next.called ? below : next(e); };
      if (!hook.onError) return fallback();
      return invoke(hook.onError, [$, e, next, err]).catch(function (err2) {
        report(d, hook.event, err2);
        return fallback();
      });
    });
  }

  function finish(d, run) {
    run.then(function (value) {
      post({ t: 'done', d: d, value: value === undefined ? null : value });
    }, function (err) {
      post({ t: 'done', d: d, error: describe(err) });
    });
  }

  function tick(d, e) {
    var timer = timers[e && e.timer];
    if (!timer) {
      post({ t: 'done', d: d, value: null });
      return 'gone';
    }
    if (!timer.repeat) delete timers[e.timer];
    finish(d, invoke(timer.fn, [dollar(d)]).catch(function (err) {
      report(d, 'clock', err);
      return null;
    }));
    return 'ok';
  }

  // ---- atoms ($.state) ----------------------------------------------------------------

  globalThis.atom = function (ref, initial) {
    if (typeof ref !== 'string' || !ref) throw new TypeError('atom(ref, initial) needs a name');
    return Object.freeze({ ref: ref, initial: initial });
  };
  globalThis.read = function ($, a) {
    return a.ref in atoms ? atoms[a.ref] : a.initial;
  };
  globalThis.update = function ($, a, fn) {
    var value = typeof fn === 'function' ? fn(globalThis.read($, a)) : fn;
    atoms[a.ref] = value;
    return value;
  };

  // ---- entry points for the app -------------------------------------------------------

  Object.defineProperty(globalThis, '__mh', { writable: false, configurable: false, value: Object.freeze({
    connect: function () {
      return android.getNamedPort('mh').then(function (p) {
        port = p;
        return 'ready';
      });
    },

    define: function (f) {
      if (factory) throw new Error('The module is already loaded');
      factory = f;
      return 'ok';
    },

    /** Runs the module and its register(on, options). Returns the events it hooks, as JSON. */
    start: function (options) {
      if (started) throw new Error('The mod already started');
      if (!factory) throw new Error('The module did not load');
      started = true;
      var module = { exports: {} };
      factory(module.exports, module);
      var exported = module.exports;
      var register = typeof exported === 'function' ? exported
        : exported && typeof exported.register === 'function' ? exported.register
        : exported && typeof exported.default === 'function' ? exported.default
        : exported && exported.default && typeof exported.default.register === 'function' ? exported.default.register
        : null;
      if (!register) throw new Error('The hooks module must export a function named register');
      registering = true;
      var result;
      try {
        result = register(on, freeze(options || {}));
      } finally {
        registering = false;
      }
      if (result && typeof result.then === 'function') {
        result.then(null, function (err) { report(0, 'register', err); });
      }
      return JSON.stringify({ events: Object.keys(hooks) });
    },

    dispatch: function (d, event, e) {
      if (event === 'clock.tick') return tick(d, e);
      var list = (hooks[event] || []).filter(function (h) { return matches(h.matcher, e); });
      var input = freeze(e);
      finish(d, chain(d, dollar(d), list, 0, input));
      return 'ok';
    },

    answer: function (id, ok, value) {
      var call = calls[id];
      if (!call) return 'gone';
      delete calls[id];
      if (ok) call.resolve(value === undefined ? null : value);
      else call.reject(new Error(String(value)));
      return 'ok';
    },
  }) });

  return 'ok';
})();
