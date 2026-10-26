// Runs in the top document of the player site. Leaves only the player iframe on screen,
// stretched to the whole viewport, and hides everything else (header, notes, banners).
(function () {
    if (window.top !== window || window.__dpmTop) return;
    window.__dpmTop = true;

    var HIDE = 'dpm-hide';
    var PATH = 'dpm-path';
    var PLAYER = 'dpm-player';

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

    addStyle(
        'html,body{background:#000!important;margin:0!important;padding:0!important;overflow:hidden!important;width:100%!important;height:100%!important}' +
        'body{visibility:hidden}' +
        '.' + HIDE + '{display:none!important}' +
        '.' + PATH + '{margin:0!important;padding:0!important;border:0!important;background:#000!important;box-shadow:none!important;transform:none!important;filter:none!important;max-width:none!important;overflow:visible!important}' +
        'iframe.' + PLAYER + '{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;border:0!important;margin:0!important;border-radius:0!important;z-index:2147483647!important;background:#000!important;visibility:visible!important;display:block!important}'
    );

    function findPlayer() {
        var el = document.querySelector('#film iframe, iframe[allowfullscreen], iframe[src*="?id="]');
        if (el) return el;
        var best = null, bestArea = 0;
        var frames = document.getElementsByTagName('iframe');
        for (var i = 0; i < frames.length; i++) {
            var r = frames[i].getBoundingClientRect();
            var area = r.width * r.height;
            if (area > bestArea) { best = frames[i]; bestArea = area; }
        }
        return best;
    }

    var player = null;

    function isOnPath(node) {
        return node === player || (node.classList && node.classList.contains(PATH));
    }

    function isolate() {
        player = findPlayer();
        if (!player) return false;
        player.classList.add(PLAYER);
        var node = player;
        while (node && node !== document.documentElement) {
            var parent = node.parentElement;
            if (!parent) break;
            for (var i = 0; i < parent.children.length; i++) {
                var sib = parent.children[i];
                if (sib !== node && sib.tagName !== 'SCRIPT' && sib.tagName !== 'STYLE' && sib.tagName !== 'LINK') {
                    sib.classList.add(HIDE);
                }
            }
            if (parent !== document.body && parent !== document.documentElement) parent.classList.add(PATH);
            node = parent;
        }
        document.body.style.visibility = 'visible';
        return true;
    }

    function watchBody() {
        // Late-injected banners and overlays appended to <body> get hidden immediately.
        new MutationObserver(function (records) {
            for (var r = 0; r < records.length; r++) {
                var added = records[r].addedNodes;
                for (var i = 0; i < added.length; i++) {
                    var n = added[i];
                    if (n.nodeType === 1 && !isOnPath(n) && n.tagName !== 'SCRIPT' && n.tagName !== 'STYLE') {
                        n.classList.add(HIDE);
                    }
                }
            }
        }).observe(document.body, { childList: true });
    }

    function run() {
        if (isolate()) {
            watchBody();
        } else {
            // The iframe may be created by a script; retry for a while, then give up and show the page.
            var tries = 0;
            var timer = setInterval(function () {
                tries++;
                if (isolate()) { clearInterval(timer); watchBody(); }
                else if (tries > 40) { clearInterval(timer); document.body.style.visibility = 'visible'; }
            }, 250);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run);
    } else {
        run();
    }
})();
