// Runs at document start in every frame of the player (top page, the source picker and the
// video player iframes). Talks to the app through the injected `dpmBridge` object.
//
// While the app catches a stream (window.__dpmSniff), a page either runs hidden behind the
// loading screen (kept silent and nudged to play) or is shown to the viewer
// (window.__dpmVisible), who can press play themselves; either way the stream address the
// player loads is reported. Once the app plays that stream itself, the page is kept silent
// (window.__dpmSilent) until it is given back to the viewer.
(function () {
    if (window.__dpmFrame) return;
    window.__dpmFrame = true;

    var bridge = window.dpmBridge;
    var MIN_DURATION = 300; // shorter clips are ads or trailers: not tracked, not resumed
    var startedAt = Date.now();

    function post(obj) {
        try { if (bridge) bridge.postMessage(JSON.stringify(obj)); } catch (e) { }
    }

    function sniffing() { return !!window.__dpmSniff; }
    function hidden() { return !!window.__dpmSniff && !window.__dpmVisible; }
    function silent() { return hidden() || !!window.__dpmSilent; }

    function addStyle(css) {
        var s = document.createElement('style');
        s.textContent = css;
        var target = document.head || document.documentElement;
        if (target) {
            target.appendChild(s);
            return;
        }
        // At document start the <html> element may not exist yet.
        new MutationObserver(function (records, observer) {
            var t = document.head || document.documentElement;
            if (t) {
                observer.disconnect();
                t.appendChild(s);
            }
        }).observe(document, { childList: true, subtree: true });
    }

    // Lets the app reach this frame with commands (mute, mode changes) from the start.
    post({ type: 'hello' });

    // ---- Popups and cosmetic ad filtering -------------------------------------------------
    try {
        window.open = function () { return null; };
    } catch (e) { }

    addStyle(
        'ins.adsbygoogle,[id^="yandex_rtb"],[id^="adfox_"],[id*="google_ads_iframe"],[class*="adsbox"],' +
        '[class*="ad-banner"],[id*="ad-banner"],div[id^="beacon_"],a[href*="/click?"][target="_blank"]' +
        '{display:none!important}'
    );

    // ---- Stream addresses requested by script (hls.js, dash.js, Playerjs) -----------------
    // The app's network hook sees only the first address of a request; manifests behind a
    // redirect, or with no telling extension, are recognised here by what actually came back.
    var MANIFEST_TYPE = /mpegurl|dash\+xml/i;
    var MANIFEST_URL = /\.(m3u8|mpd)(\?|#|$)|\.m3u8\//i;

    // a stream address went out from this frame: its player is going, no more clicks needed
    var streamSeen = false;

    function reportManifest(url, kind) {
        if (!sniffing() || !/^https?:/i.test(url || '')) return;
        streamSeen = true;
        reportEpisode(); // what plays goes first: the app may close the page once it has the stream
        post({ type: 'media', url: url, referer: location.href, kind: kind || '' });
    }

    function kindOf(type, url, head) {
        if (/dash\+xml/i.test(type) || /\.mpd(\?|#|$)/i.test(url) || (head && /<MPD[\s>]/.test(head))) return 'dash';
        return 'hls';
    }

    // Wrapped functions still read as native code: some players and bot checks look.
    var nativeToString = Function.prototype.toString;
    var disguised = typeof WeakMap === 'function' ? new WeakMap() : null;
    function disguise(fake, real) {
        if (disguised) disguised.set(fake, real);
        return fake;
    }
    if (disguised) {
        try {
            var toString = function toString() {
                return nativeToString.call(disguised.get(this) || this);
            };
            disguised.set(toString, nativeToString);
            Function.prototype.toString = toString;
        } catch (e) { }
    }

    try {
        var XHR = window.XMLHttpRequest && XMLHttpRequest.prototype;
        if (XHR && XHR.open && XHR.send) {
            var realOpen = XHR.open, realSend = XHR.send;
            XHR.open = disguise(function open(method, url) {
                try { this.__dpmUrl = String(url); } catch (e) { }
                return realOpen.apply(this, arguments);
            }, realOpen);
            XHR.send = disguise(function send() {
                var x = this;
                if (sniffing()) {
                    try {
                        x.addEventListener('load', function () {
                            try {
                                var url = x.responseURL || x.__dpmUrl || '';
                                var type = x.getResponseHeader('content-type') || '';
                                var head = '';
                                var hit = MANIFEST_TYPE.test(type) || MANIFEST_URL.test(url);
                                if (!hit && (x.responseType === '' || x.responseType === 'text')) {
                                    head = (x.responseText || '').slice(0, 512);
                                    hit = head.indexOf('#EXTM3U') === 0 || /<MPD[\s>]/.test(head);
                                }
                                if (hit) reportManifest(url, kindOf(type, url, head));
                            } catch (e) { }
                        });
                    } catch (e) { }
                }
                return realSend.apply(this, arguments);
            }, realSend);
        }
    } catch (e) { }

    try {
        var realFetch = window.fetch;
        if (realFetch) {
            window.fetch = disguise(function fetch(input, init) {
                var result = realFetch.apply(window, arguments);
                if (!sniffing() || !result || !result.then) return result;
                return result.then(function (res) {
                    try {
                        var url = res.url || (typeof input === 'string' ? input : (input && input.url)) || '';
                        var type = (res.headers && res.headers.get('content-type')) || '';
                        if (MANIFEST_TYPE.test(type) || MANIFEST_URL.test(url)) reportManifest(url, kindOf(type, url, ''));
                    } catch (e) { }
                    return res;
                });
            }, realFetch);
        }
    } catch (e) { }

    // ---- Source / voice-over picker (kinobox select) -> native bottom sheet ------------------
    var KB_CSS =
        '.player-block{padding:0!important;height:100vh!important}' +
        '.player-block__label{display:none!important}' +
        '#kinobox_player .kinobox_container{border-radius:0!important;box-shadow:none!important}' +
        '#kinobox_player iframe,#kinobox_player .kinobox_iframe_container,#kinobox_player .kinobox_iframe{border-radius:0!important}' +
        '#kinobox_player select.kinobox_menu.kbt_select{margin:0!important;border:0!important;border-radius:0!important;' +
        'border-bottom:1px solid rgba(255,255,255,.08)!important;background-color:#14141d!important}' +
        'html.dpm-native-menu #kinobox_player select.kinobox_menu{display:none!important}';

    var kb = { styled: false, select: null, lastSig: '' };

    function currentSelect() {
        // Svelte may re-create the <select>, so always look it up again.
        return document.querySelector('#kinobox_player select.kinobox_menu') || kb.select;
    }

    function sendSources() {
        var sel = currentSelect();
        if (!sel || !sel.options.length) return;
        var items = [];
        for (var i = 0; i < sel.options.length; i++) {
            items.push((sel.options[i].text || '').replace(/^[\s▶️]+/, '').trim());
        }
        var sig = items.join('|') + '#' + sel.selectedIndex;
        if (sig === kb.lastSig) return;
        kb.lastSig = sig;
        post({ type: 'sources', items: items, selected: sel.selectedIndex });
    }

    function scanKinobox() {
        if (!document.getElementById('kinobox_player')) return false;
        if (!kb.styled) { addStyle(KB_CSS); kb.styled = true; }
        var sel = document.querySelector('#kinobox_player select.kinobox_menu');
        if (sel && sel !== kb.select) {
            kb.select = sel;
            kb.lastSig = '';
            sel.addEventListener('change', function () { setTimeout(sendSources, 50); });
        }
        sendSources();
        return true;
    }

    function selectSource(index) {
        var sel = currentSelect();
        if (!sel || index < 0 || index >= sel.options.length) return;
        sel.selectedIndex = index;
        sel.dispatchEvent(new Event('change', { bubbles: true }));
    }

    var scans = 0;
    var scanTimer = setInterval(function () {
        scans++;
        var found = scanKinobox();
        // keep watching the picker (options change when a player fails); stop early in other frames
        if ((!found && scans > 60) || scans > 600) clearInterval(scanTimer);
    }, 500);

    // ---- Video progress tracking (resume playback, PiP state) ------------------------------
    var active = null;
    var lastSent = 0;
    var lastTick = 0;

    function dur(v) {
        var d = v.duration;
        return isFinite(d) ? d : 0;
    }

    function silence(v) {
        if (!v.muted) { v.muted = true; v.__dpmMuted = true; }
    }

    function onMedia(e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO') return;
        var d = dur(v);
        switch (e.type) {
            case 'loadstart':
                v.__dpmAnnounced = false;
                reportSource(v);
                break;
            case 'playing':
                active = v;
                reportSource(v);
                if (silent()) silence(v);
                if (d >= MIN_DURATION && !v.__dpmAnnounced) {
                    v.__dpmAnnounced = true;
                    post({ type: 'video', t: v.currentTime, d: d });
                }
                post({ type: 'state', playing: true });
                break;
            case 'pause':
                if (d >= MIN_DURATION) post({ type: 'progress', t: v.currentTime, d: d });
                post({ type: 'state', playing: false });
                break;
            case 'ended':
                if (d >= MIN_DURATION) post({ type: 'progress', t: d, d: d });
                post({ type: 'state', playing: false });
                break;
            case 'timeupdate':
                if (d >= MIN_DURATION) {
                    var now = Date.now();
                    // once a second: lets the app skip intros in the site's own player too
                    if (now - lastTick > 900) {
                        lastTick = now;
                        active = v;
                        post({ type: 'time', t: v.currentTime, d: d });
                    }
                    if (now - lastSent > 10000) {
                        lastSent = now;
                        post({ type: 'progress', t: v.currentTime, d: d });
                    }
                }
                break;
        }
    }

    // A <video src="…m3u8"> is played by the system media stack and never shows up as a page
    // request, so its address is reported directly.
    function reportSource(v) {
        var src = v.currentSrc || v.src || '';
        if (/^https?:/.test(src) && src !== v.__dpmSrc) {
            v.__dpmSrc = src;
            if (sniffing()) streamSeen = true;
            reportEpisode();
            post({ type: 'media', url: src, referer: location.href });
        }
    }

    ['loadstart', 'playing', 'pause', 'ended', 'timeupdate'].forEach(function (name) {
        window.addEventListener(name, onMedia, true);
    });

    // ---- Getting the player going ---------------------------------------------------------------
    // Players load their stream only once playback starts, and many wait for a click on their
    // own play button. Pages behind a bot check can take ten seconds and more to show the
    // player, so this keeps trying while the app waits for the stream (a hidden page is also
    // kept silent), until the viewer touches the shown page.
    var KNOWN_PLAY =
        '.vjs-big-play-button,.plyr__control--overlaid,.jw-display-icon-display,.jw-icon-display,.fp-play,' +
        '.mejs__overlay-play,.ytp-large-play-button,.play-button,.play_button,.playButton,.btn-play,.big-play,' +
        '.start-button,[data-plyr="play"]';
    var clicks = 0;
    var lastClick = 0;
    var clicked = typeof WeakMap === 'function' ? new WeakMap() : null;
    // once the viewer touches a shown page, it is theirs: no more help
    var touched = false;
    window.addEventListener('pointerdown', function (e) { if (e.isTrusted) touched = true; }, true);

    function label(el) {
        var cls = el.className;
        if (cls && typeof cls !== 'string') cls = cls.baseVal || '';
        return ((cls || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' +
            (el.getAttribute('title') || '')).toLowerCase();
    }

    function looksLikePlay(el) {
        var s = label(el);
        if (!/play|воспроизв|смотреть|старт/.test(s)) return false;
        // containers and lists named after the player are not its button
        if (/playlist|player|display|autoplay|playback|playing|plays/.test(s) &&
            !/play[-_ ]?(btn|button|icon|big|large|start|overlay)|big[-_ ]?play/.test(s)) return false;
        return !/skip|пропуст|close|закры/.test(s);
    }

    function box(el) {
        var r = el.getBoundingClientRect();
        var w = window.innerWidth, h = window.innerHeight;
        if (r.width < 20 || r.height < 20 || r.width > w * 0.8 || r.height > h * 0.8) return null;
        if (r.bottom <= 0 || r.right <= 0 || r.top >= h || r.left >= w) return null;
        return r;
    }

    function usable(el) {
        if (!el || el.tagName === 'IFRAME' || el.tagName === 'HTML' || el.tagName === 'BODY') return false;
        var link = el.closest && el.closest('a[href]');
        if (link && !/^(javascript:|#)/.test(link.getAttribute('href') || '')) return false; // links lead to ads
        return !clicked || (clicked.get(el) || 0) < 2;
    }

    function press(el) {
        var r = el.getBoundingClientRect();
        var opts = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
        var kinds = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
        for (var i = 0; i < kinds.length; i++) {
            try {
                var E = kinds[i].indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
                el.dispatchEvent(new E(kinds[i], opts));
            } catch (e) { }
        }
        try { el.click(); } catch (e) { }
    }

    function anyPlaying(videos) {
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            if (!v.paused && !v.ended && v.readyState > 2) return true;
        }
        return false;
    }

    function nudge() {
        if (Date.now() - startedAt > 180000) { clearInterval(nudger); return; }
        if (!sniffing() || touched || !document.body) return;
        // a page shown while the app plays its stream (episode picking) is the viewer's alone
        if (window.__dpmSilent && !hidden()) return;
        var videos = document.getElementsByTagName('video');
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            if (hidden()) silence(v);
            if (v.paused && !v.ended && (v.currentSrc || v.src)) {
                try { var p = v.play(); if (p && p.catch) p.catch(function () { }); } catch (e) { }
            }
        }
        if (streamSeen || anyPlaying(videos)) return;
        var now = Date.now();
        if (now - startedAt < 1500 || now - lastClick < 2500 || clicks >= 12) return;
        var target = null;
        var known = document.querySelectorAll(KNOWN_PLAY);
        for (var j = 0; j < known.length && !target; j++) {
            if (box(known[j]) && usable(known[j])) target = known[j];
        }
        if (!target) {
            var all = document.querySelectorAll('[class*="play" i],[id*="play" i],[aria-label],[title],button');
            for (var k = 0; k < all.length && !target; k++) {
                if (looksLikePlay(all[k]) && box(all[k]) && usable(all[k])) target = all[k];
            }
        }
        // players that start on a click anywhere on the picture (Playerjs and others)
        if (!target && videos.length && clicks < 6) {
            var center = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
            if (usable(center)) target = center;
        }
        if (!target) return;
        clicks++;
        lastClick = now;
        if (clicked) clicked.set(target, (clicked.get(target) || 0) + 1);
        press(target);
    }

    var nudger = setInterval(nudge, 1000);

    function pauseAll() {
        var list = document.getElementsByTagName('video');
        for (var i = 0; i < list.length; i++) {
            try { list[i].pause(); } catch (e) { }
        }
    }

    // The viewer watches the page now: give the sound back to what this script silenced.
    function shown() {
        var list = document.getElementsByTagName('video');
        for (var i = 0; i < list.length; i++) {
            if (list[i].__dpmMuted) { list[i].muted = false; list[i].__dpmMuted = false; }
        }
    }

    // ---- Auto-skip for ads that slipped through -------------------------------------------
    var SKIP_RE = /пропустить|skip/i;
    var WAIT_RE = /через|\d/;
    setInterval(function () {
        if (!document.body || !document.getElementsByTagName('video').length) return;
        var cands = document.querySelectorAll('[class*="skip" i],[id*="skip" i]');
        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            var text = (el.textContent || '').trim();
            if (el.offsetParent !== null && text.length < 40 && SKIP_RE.test(text) && !WAIT_RE.test(text)) {
                try { el.click(); } catch (e) { }
            }
        }
    }, 1000);

    // ---- Which episode the page plays ---------------------------------------------------------
    // A stream caught from a player carries no season or episode number, and the intro of a show
    // is looked up by them. Players show the chosen ones in a picker or as a highlighted button
    // ("Сезон 2", "5 серия", "S02E05"); some also carry them in their address.
    var NOT_LETTER = '(?![a-zа-яё])'; // "сезон", not "сезонов"; "серия", not "серии"
    var SEASON_W = '(?:сезон|season)' + NOT_LETTER;
    var EPISODE_W = '(?:серия|эпизод|episode)' + NOT_LETTER;
    var BETWEEN = '[^\\da-zа-яё]{0,6}';
    var BOTH_RES = [
        /(?:^|[^a-z])s(\d{1,2})\s*e(\d{1,4})(?!\d)/i,
        new RegExp(SEASON_W + '\\s*№?\\s*(\\d{1,2})' + BETWEEN + EPISODE_W + '\\s*№?\\s*(\\d{1,4})', 'i'),
        new RegExp('(\\d{1,2})\\s*-?\\s*(?:й\\s+)?' + SEASON_W + BETWEEN + '(\\d{1,4})\\s*-?\\s*(?:я\\s+)?' + EPISODE_W, 'i')
    ];
    function oneRes(word, digits, ending) {
        return [
            new RegExp(word + '\\s*№?\\s*(\\d{1,' + digits + '})(?!\\d)', 'i'),
            new RegExp('(?:^|\\D)(\\d{1,' + digits + '})\\s*-?\\s*(?:' + ending + '\\s+)?' + word, 'i')
        ];
    }
    var SEASON_RES = oneRes(SEASON_W, 2, 'й');
    var EPISODE_RES = oneRes(EPISODE_W, 4, 'я');
    var SEASON_ANY = new RegExp(SEASON_W, 'i');
    var EPISODE_ANY = new RegExp(EPISODE_W, 'i');

    function firstNumber(res, text) {
        for (var i = 0; i < res.length; i++) {
            var m = res[i].exec(text);
            if (m && +m[1] > 0) return +m[1];
        }
        return null;
    }

    /** {s, e} from a label like "Сезон 1 Серия 3", "3 серия" or "S01E03"; null if it names neither. */
    function episodeIn(text) {
        text = String(text || '').replace(/\s+/g, ' ').trim();
        // "(1-8 сезон)" next to a voice-over tells what it has, not what plays
        if (!text || text.length > 80 || /\d\s*[-–—]\s*\d/.test(text)) return null;
        for (var i = 0; i < BOTH_RES.length; i++) {
            var m = BOTH_RES[i].exec(text);
            if (m && +m[1] > 0 && +m[2] > 0) return { s: +m[1], e: +m[2] };
        }
        var hasS = SEASON_ANY.test(text), hasE = EPISODE_ANY.test(text);
        if (hasS && hasE) return null; // both, in an order not understood: no guessing
        if (hasE) { var e = firstNumber(EPISODE_RES, text); return e ? { s: null, e: e } : null; }
        if (hasS) { var s = firstNumber(SEASON_RES, text); return s ? { s: s, e: null } : null; }
        return null;
    }

    // a bare number in a highlighted button: the list around it says what it counts
    var SEASON_LIST = /season|sezon|сезон/;
    var EPISODE_LIST = /episod|(?:^|[^a-z])(?:series|seriya|serii|seria)(?:[^a-z]|$)|сери[ия](?![а-яё])/;
    function bareNumber(el, text) {
        if (!/^\s*\d{1,4}\s*$/.test(text) || +text <= 0) return null;
        for (var i = 0, p = el.parentElement; i < 3 && p; i++, p = p.parentElement) {
            var s = label(p) + ' ' + ((p.getAttribute('name') || '') + ' ' + (p.getAttribute('data-type') || '')).toLowerCase();
            if (SEASON_LIST.test(s)) return { s: +text, e: null };
            if (EPISODE_LIST.test(s)) return { s: null, e: +text };
        }
        return null;
    }

    var ACTIVE = '[class*="active" i],[class*="selected" i],[class*="current" i],[class*="chosen" i],' +
        '[aria-selected="true"],[aria-current]:not([aria-current="false"])';

    function scanEpisode() {
        var found = { s: null, e: null };
        function take(h) {
            if (!h) return;
            if (found.s == null && h.s) found.s = h.s;
            if (found.e == null && h.e) found.e = h.e;
        }
        // pickers: their chosen option
        var selects = document.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var opt = selects[i].options && selects[i].options[selects[i].selectedIndex];
            if (opt) take(episodeIn(opt.text) || bareNumber(opt, opt.text));
        }
        // highlighted buttons and list items
        var marked = document.querySelectorAll(ACTIVE);
        for (var j = 0; j < marked.length && j < 300; j++) {
            var text = marked[j].textContent || '';
            if (text.length <= 80) take(episodeIn(text) || bareNumber(marked[j], text));
        }
        take(episodeIn(document.title));
        // the address the player was opened with: what it started on, if nothing above says more
        try {
            var q = new URLSearchParams(location.search);
            var qs = parseInt(q.get('season'), 10), qe = parseInt(q.get('episode'), 10);
            take({ s: qs > 0 && qs < 100 ? qs : null, e: qe > 0 && qe < 10000 ? qe : null });
        } catch (e) { }
        return found;
    }

    var lastEpisode = ':';
    function reportEpisode() {
        if (!document.documentElement || lastEpisode === undefined) return;
        var h;
        try { h = scanEpisode(); } catch (e) { return; }
        var sig = (h.s || '') + ':' + (h.e || '');
        if (sig === lastEpisode) return;
        lastEpisode = sig;
        post({ type: 'episode', season: h.s, episode: h.e });
    }
    setTimeout(reportEpisode, 700);
    setInterval(reportEpisode, 2000);
    // a new video starts loading: the picker has just changed
    window.addEventListener('loadstart', function () { setTimeout(reportEpisode, 100); }, true);

    // ---- Commands from the app ----------------------------------------------------------------
    if (bridge) {
        bridge.onmessage = function (event) {
            var m;
            try { m = JSON.parse(event.data); } catch (e) { return; }
            switch (m.cmd) {
                case 'seek':
                    if (active) { try { active.currentTime = m.t; } catch (e) { } }
                    break;
                case 'pause':
                    pauseAll();
                    break;
                case 'mute':
                    // the stream is taken over by the app: this page must stay silent from now on
                    window.__dpmSilent = true;
                    var vs = document.getElementsByTagName('video');
                    for (var k = 0; k < vs.length; k++) { silence(vs[k]); try { vs[k].pause(); } catch (e) { } }
                    break;
                case 'release':
                    // the page is the player again
                    window.__dpmSilent = false;
                    shown();
                    break;
                case 'play':
                    if (active) { try { active.play(); } catch (e) { } }
                    break;
                case 'mode':
                    window.__dpmSniff = !!m.sniff;
                    window.__dpmVisible = !!m.visible;
                    if (m.visible && !window.__dpmSilent) shown();
                    break;
                case 'source':
                    selectSource(m.index);
                    break;
                case 'nativeMenu':
                    document.documentElement.classList.add('dpm-native-menu');
                    break;
            }
        };
    }
})();
