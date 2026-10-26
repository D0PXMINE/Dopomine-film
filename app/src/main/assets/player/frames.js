// Runs at document start in every frame of the player (top page, the source picker and the
// video player iframes). Talks to the app through the injected `dpmBridge` object.
(function () {
    if (window.__dpmFrame) return;
    window.__dpmFrame = true;

    var bridge = window.dpmBridge;
    var MIN_DURATION = 300; // shorter clips are ads or trailers: not tracked, not resumed

    function post(obj) {
        try { if (bridge) bridge.postMessage(JSON.stringify(obj)); } catch (e) { }
    }

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

    // ---- Popups and cosmetic ad filtering -------------------------------------------------
    try {
        window.open = function () { return null; };
    } catch (e) { }

    addStyle(
        'ins.adsbygoogle,[id^="yandex_rtb"],[id^="adfox_"],[id*="google_ads_iframe"],[class*="adsbox"],' +
        '[class*="ad-banner"],[id*="ad-banner"],div[id^="beacon_"],a[href*="/click?"][target="_blank"]' +
        '{display:none!important}'
    );

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

    function dur(v) {
        var d = v.duration;
        return isFinite(d) ? d : 0;
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
                if (window.__dpmSniff) { v.muted = true; }
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
            post({ type: 'media', url: src, referer: location.href });
        }
    }

    // Sniff mode: the page plays hidden behind the native player only to reveal its stream.
    // Keep it silent and nudge players that wait for a click on their play button.
    if (window.__dpmSniff) {
        var nudges = 0;
        var nudge = setInterval(function () {
            nudges++;
            if (nudges > 25 || !document.body) { if (nudges > 25) clearInterval(nudge); return; }
            var videos = document.getElementsByTagName('video');
            for (var i = 0; i < videos.length; i++) {
                var v = videos[i];
                v.muted = true;
                if (v.paused && (v.currentSrc || v.src)) { try { v.play().catch(function () { }); } catch (e) { } }
            }
            if (nudges === 3 || nudges === 8) {
                var w = window.innerWidth, h = window.innerHeight;
                var cands = document.querySelectorAll('[class*="play" i],[id*="play" i],[aria-label*="play" i],button');
                for (var j = 0; j < cands.length; j++) {
                    var r = cands[j].getBoundingClientRect();
                    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                    if (r.width >= 36 && r.height >= 36 && r.width < w * 0.6 &&
                        Math.abs(cx - w / 2) < w * 0.2 && Math.abs(cy - h / 2) < h * 0.25) {
                        try { cands[j].click(); } catch (e) { }
                        break;
                    }
                }
            }
        }, 800);
    }

    ['loadstart', 'playing', 'pause', 'ended', 'timeupdate'].forEach(function (name) {
        window.addEventListener(name, onMedia, true);
    });

    function pauseAll() {
        var list = document.getElementsByTagName('video');
        for (var i = 0; i < list.length; i++) {
            try { list[i].pause(); } catch (e) { }
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
                    window.__dpmSniff = true;
                    var vs = document.getElementsByTagName('video');
                    for (var k = 0; k < vs.length; k++) { vs[k].muted = true; try { vs[k].pause(); } catch (e) { } }
                    break;
                case 'play':
                    if (active) { try { active.play(); } catch (e) { } }
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
