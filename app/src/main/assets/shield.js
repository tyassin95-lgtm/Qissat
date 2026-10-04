/*
 * Qissat shield. Injected at document start into every frame (ES5 on purpose: it must run on
 * the old WebViews found on first-generation Fire TV sticks).
 *
 *  1. Neutralises window.open / programmatic new-window navigation (pop-ups & pop-unders).
 *  2. Stops off-site and new-window link clicks inside third-party frames.
 *  3. Removes invisible click-jacking overlays that ad scripts stack above the player.
 *  4. Hides common ad containers.
 *  5. Relays play/pause/seek/mute commands from the app down to the <video> element, however
 *     deeply it is nested, and tells the app when a video starts playing so it can offer
 *     the native player.
 */
(function () {
  'use strict';

  var W = window;
  if (W.__qsh) return;
  try { Object.defineProperty(W, '__qsh', { value: 1 }); } catch (e) { W.__qsh = 1; }

  var NONCE = '__QSH_NONCE__';
  var BRIDGE = 'https://__QSH_BRIDGE__/b';

  var host = (location.hostname || '').toLowerCase();
  var firstParty = host === 'qisat.tv' || /\.qisat\.tv$/.test(host);

  function hostOf(u) {
    try { return (new URL(u, location.href).hostname || '').toLowerCase(); } catch (e) { return ''; }
  }

  function sameSite(h) {
    if (h === host) return true;
    return firstParty && (h === 'qisat.tv' || /\.qisat\.tv$/.test(h));
  }

  function offsite(href) {
    var h = hostOf(href);
    return !!h && !sameSite(h);
  }

  function noop() {}

  /* ---------------------------------------------------------------- 0. anti-adblock bait */

  // Several player pages decide "ad blocker present" when a bait script such as /js/pop.js did
  // not define its globals, and then nag or throttle playback. Define the globals up front so
  // the (blocked) ad scripts are not missed.
  if (!firstParty) {
    try {
      if (W.cRAds === undefined) W.cRAds = true;
      if (W.xRds === undefined) W.xRds = false;
      if (W.adbon === undefined) W.adbon = 0;
      if (W.canRunAds === undefined) W.canRunAds = true;
    } catch (e) { /* ignore */ }
  }

  /* ---------------------------------------------------------------- 1. window.open */

  function fakeWindow() {
    var loc = {};
    ['assign', 'replace', 'reload'].forEach(function (k) { loc[k] = noop; });
    ['href', 'pathname', 'search', 'hash', 'hostname', 'host', 'origin', 'protocol'].forEach(function (k) {
      try {
        Object.defineProperty(loc, k, { get: function () { return ''; }, set: noop });
      } catch (e) { /* ignore */ }
    });
    var doc = {
      write: noop, writeln: noop, open: noop, close: noop,
      createElement: function () { return { style: {}, setAttribute: noop, appendChild: noop }; },
      getElementsByTagName: function () { return []; },
      body: { appendChild: noop, style: {} }, documentElement: { style: {} }
    };
    return {
      closed: false, name: '', opener: null, location: loc, document: doc,
      close: noop, focus: noop, blur: noop, postMessage: noop, print: noop, stop: noop,
      addEventListener: noop, removeEventListener: noop, moveTo: noop, resizeTo: noop
    };
  }

  function openStub(url) {
    try {
      // The site itself may open one of its own pages in a "new window": keep it in this view.
      if (firstParty && url && !offsite(String(url)) && !/^(javascript|data|blob):/i.test(String(url))) {
        W.location.href = String(url);
        return null;
      }
    } catch (e) { /* ignore */ }
    return fakeWindow();
  }

  try {
    Object.defineProperty(W, 'open', { value: openStub, writable: false, configurable: false });
  } catch (e) { try { W.open = openStub; } catch (e2) { /* ignore */ } }

  /* ---------------------------------------------------------------- 2. links & forms */

  function closestAnchor(n) {
    while (n && n.nodeType === 1) {
      if (n.tagName === 'A' || n.tagName === 'AREA') return n;
      n = n.parentNode;
    }
    return null;
  }

  function newWindowTarget(t) {
    t = (t || '').toLowerCase();
    return t === '_blank' || t === '_top' || t === '_parent' || (t !== '' && t !== '_self');
  }

  function onClick(e) {
    var a = closestAnchor(e.target);
    if (!a) return;
    var href = a.href || a.getAttribute('href') || '';
    if (!href || /^javascript:/i.test(href) || href.charAt(0) === '#') return;
    if (!firstParty) {
      // Third-party frames (players): they have no business navigating anywhere but themselves.
      if (offsite(href) || newWindowTarget(a.target)) {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
      return;
    }
    // First-party page: keep same-site links in this view; off-site ones are refused natively.
    if (!offsite(href) && newWindowTarget(a.target)) a.target = '_self';
  }
  document.addEventListener('click', onClick, true);
  document.addEventListener('auxclick', function (e) {
    var a = closestAnchor(e.target);
    if (a && (!firstParty || offsite(a.href))) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);

  try {
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try {
        if (!document.contains(this) && (offsite(this.href) || newWindowTarget(this.target))) return;
      } catch (e) { /* ignore */ }
      return origAnchorClick.apply(this, arguments);
    };
  } catch (e) { /* ignore */ }

  try {
    var origSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function () {
      try {
        if (newWindowTarget(this.target) || (!firstParty && offsite(this.action))) return;
      } catch (e) { /* ignore */ }
      return origSubmit.apply(this, arguments);
    };
  } catch (e) { /* ignore */ }

  /* ---------------------------------------------------------------- 3. overlays */

  var SKIP_TAGS = { VIDEO: 1, CANVAS: 1, IFRAME: 1, OBJECT: 1, EMBED: 1, SCRIPT: 1, STYLE: 1,
                    LINK: 1, HEAD: 1, BODY: 1, HTML: 1, META: 1, NOSCRIPT: 1, IMG: 1 };

  function isGhostOverlay(el) {
    if (el.nodeType !== 1 || SKIP_TAGS[el.tagName]) return false;
    var cs;
    try { cs = W.getComputedStyle(el); } catch (e) { return false; }
    var pos = cs.position;
    if (pos !== 'fixed' && pos !== 'absolute') return false;
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    var vw = W.innerWidth || document.documentElement.clientWidth || 0;
    var vh = W.innerHeight || document.documentElement.clientHeight || 0;
    if (!vw || !vh) return false;
    var r = el.getBoundingClientRect();
    if (r.width < vw * 0.7 || r.height < vh * 0.7) return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z) || z < 10) return false;
    // Anything the viewer could actually see or use is left alone.
    if (el.querySelector('video,canvas,input,button,select,textarea,iframe,object,embed,img,svg')) return false;
    if ((el.textContent || '').replace(/\s+/g, '') !== '') return false;
    var bg = cs.backgroundColor;
    var clearBg = bg === 'transparent' || /rgba\(.*,\s*0(\.0+)?\)\s*$/.test(bg);
    var op = parseFloat(cs.opacity);
    return (op < 0.1) || (clearBg && cs.backgroundImage === 'none');
  }

  function sweepList(list) {
    var removed = 0;
    for (var i = 0; i < list.length && i < 80; i++) {
      var el = list[i];
      if (isGhostOverlay(el)) {
        try { el.parentNode.removeChild(el); removed++; } catch (e) { /* ignore */ }
      }
    }
    return removed;
  }

  function sweep() {
    try {
      if (document.body) sweepList(document.body.children);
      sweepList(document.documentElement.children);
    } catch (e) { /* ignore */ }
  }

  var sweepTimer = 0;
  var sweepCount = 0;
  function scheduleSweep(delay) {
    if (sweepTimer) return;
    sweepTimer = setTimeout(function () {
      sweepTimer = 0;
      sweepCount++;
      sweep();
    }, delay || 350);
  }

  function startObserver() {
    try {
      var mo = new MutationObserver(function () {
        if (sweepCount < 400) scheduleSweep(350);
      });
      mo.observe(document.documentElement, { childList: true });
      if (document.body) mo.observe(document.body, { childList: true });
    } catch (e) { /* ignore */ }
    scheduleSweep(150);
    setTimeout(sweep, 1500);
    setTimeout(sweep, 4000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startObserver, false);
  } else {
    startObserver();
  }

  /* ---------------------------------------------------------------- 4. cosmetic filters */

  try {
    var css = [
      'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads"]', '[id^="div-gpt-ad"]',
      'iframe[src*="doubleclick"]', 'iframe[src*="googlesyndication"]',
      '[class*="popunder"]', '[id*="popunder"]', '.ad-banner', '.ads-banner', '.advert',
      'a[href*="aarems.org"]', 'a[href*="llvpn.com"]'
    ].join(',') + '{display:none!important;visibility:hidden!important}';
    var st = document.createElement('style');
    st.setAttribute('data-qsh', '1');
    st.appendChild(document.createTextNode(css));
    (document.head || document.documentElement).appendChild(st);
  } catch (e) { /* ignore */ }

  /* ---------------------------------------------------------------- 5. media bridge */

  function mainVideo() {
    var vs = document.getElementsByTagName('video');
    var best = null, bestArea = -1;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      var a = r.width * r.height;
      if (a > bestArea) { best = vs[i]; bestArea = a; }
    }
    return best;
  }

  function safePlay(v) {
    try {
      var p = v.play();
      if (p && p.catch) p.catch(noop);
    } catch (e) { /* ignore */ }
  }

  function relay(msg) {
    var frames = document.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      try { frames[i].contentWindow.postMessage(msg, '*'); } catch (e) { /* ignore */ }
    }
  }

  function handle(msg) {
    var v = mainVideo();
    if (v) {
      try {
        switch (msg.cmd) {
          case 'toggle': if (v.paused) safePlay(v); else v.pause(); break;
          case 'play': safePlay(v); break;
          case 'pause': v.pause(); break;
          case 'mute': v.muted = !v.muted; break;
          case 'seek':
            var d = v.duration;
            var t = v.currentTime + (Number(msg.v) || 0);
            if (t < 0) t = 0;
            if (isFinite(d) && t > d) t = d;
            v.currentTime = t;
            break;
        }
      } catch (e) { /* ignore */ }
    }
    relay(msg);
  }

  W.addEventListener('message', function (e) {
    var d = e.data;
    if (d && typeof d === 'object' && d.__qsh === NONCE && d.cmd) handle(d);
  }, false);

  W.__qshCmd = function (cmd, value) {
    handle({ __qsh: NONCE, cmd: String(cmd), v: value || 0 });
  };

  function beacon(type, v) {
    try {
      var r = v.getBoundingClientRect();
      var dur = v.duration;
      var q = 'k=' + NONCE +
        '&e=' + type +
        '&t=' + (v.currentTime || 0).toFixed(1) +
        '&d=' + (isFinite(dur) ? Math.round(dur) : (dur > 0 ? -1 : 0)) +
        '&w=' + Math.round(r.width) +
        '&h=' + Math.round(r.height) +
        '&m=' + (v.muted ? 1 : 0) +
        '&s=' + encodeURIComponent(/^https?:/i.test(v.currentSrc || '') ? v.currentSrc : '') +
        '&o=' + encodeURIComponent(location.href) +
        '&r=' + Math.random().toString(36).slice(2);
      new Image().src = BRIDGE + '?' + q;
    } catch (e) { /* ignore */ }
  }

  document.addEventListener('play', function (e) {
    var v = e.target;
    if (e.isTrusted && v && v.tagName === 'VIDEO') beacon('play', v);
  }, true);
})();
