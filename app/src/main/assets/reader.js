/* Reader engine injected into every chapter:
   word tap dictionary, selection tracking, highlights,
   paragraph read aloud tracking, auto scroll, tap to hide bars. */
(function () {
    'use strict';
    if (window.__readerInit) return;
    window.__readerInit = true;

    var paras = [];

    function collectParagraphs() {
        paras = [];
        var blocks = document.body.querySelectorAll(
            'p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, dd, dt, td, div');
        var idx = 0;
        for (var i = 0; i < blocks.length; i++) {
            var el = blocks[i];
            if (el.tagName === 'DIV' &&
                el.querySelector('p, div, h1, h2, h3, h4, h5, h6, li, blockquote')) continue;
            var raw = el.textContent || '';
            if (raw.replace(/\s+/g, '').length < 2) continue;
            el.setAttribute('data-para', String(idx));
            // kept raw so character offsets from the speech engine match exactly
            paras.push(raw);
            idx++;
        }
        return paras;
    }

    window.getParagraphsJson = function () {
        if (!paras.length) collectParagraphs();
        return JSON.stringify(paras);
    };

    window.markPara = function (i) {
        clearTtsMark();
        var el = document.querySelector('[data-para="' + i + '"]');
        if (el) {
            el.classList.add('tts-active');
            var r = el.getBoundingClientRect();
            if (r.top < 0 || r.bottom > window.innerHeight) {
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }
    };

    function hlLayer() {
        var l = document.getElementById('tts-hl');
        if (!l) {
            l = document.createElement('div');
            l.id = 'tts-hl';
            document.body.appendChild(l);
        }
        return l;
    }

    function textNodesOf(el) {
        var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null), n, out = [];
        while ((n = w.nextNode())) out.push(n);
        return out;
    }

    /* Paints the characters the voice is saying right now and keeps them
       comfortably inside the screen. */
    var lastSpoken = null;

    window.markSpokenRange = function (paraIdx, start, end) {
        lastSpoken = { p: paraIdx, s: start, e: end };
        var layer = hlLayer();
        layer.innerHTML = '';
        var el = document.querySelector('[data-para="' + paraIdx + '"]');
        if (!el || end <= start) return;
        var nodes = textNodesOf(el);
        if (!nodes.length) return;
        var range = document.createRange();
        var pos = 0, started = false, ended = false;
        for (var i = 0; i < nodes.length; i++) {
            var len = nodes[i].textContent.length;
            if (!started && start < pos + len) {
                range.setStart(nodes[i], Math.max(0, start - pos));
                started = true;
            }
            if (started && end <= pos + len) {
                range.setEnd(nodes[i], Math.max(0, end - pos));
                ended = true;
                break;
            }
            pos += len;
        }
        if (!started) return;
        if (!ended) {
            var last = nodes[nodes.length - 1];
            range.setEnd(last, last.textContent.length);
        }
        var rects = range.getClientRects();
        if (!rects.length) return;
        var top = null, bottom = null;
        for (var j = 0; j < rects.length; j++) {
            var r = rects[j];
            if (r.width < 0.5 || r.height < 0.5) continue;
            var box = document.createElement('div');
            box.className = 'tts-word-box';
            box.style.left = (r.left + window.scrollX - 1) + 'px';
            box.style.top = (r.top + window.scrollY - 1) + 'px';
            box.style.width = (r.width + 2) + 'px';
            box.style.height = (r.height + 2) + 'px';
            layer.appendChild(box);
            if (top === null || r.top < top) top = r.top;
            if (bottom === null || r.bottom > bottom) bottom = r.bottom;
        }
        // follow the voice: keep the spoken line in the upper middle of the screen
        if (top !== null) {
            var vh = window.innerHeight;
            if (top < vh * 0.12 || bottom > vh * 0.80) {
                window.scrollBy({ top: top - vh * 0.32, behavior: 'smooth' });
            }
        }
    };

    window.clearSpokenRange = function () {
        lastSpoken = null;
        var l = document.getElementById('tts-hl');
        if (l) l.innerHTML = '';
    };

    // after a rotation the text reflows, so put the marker back where it belongs
    window.addEventListener('resize', function () {
        if (!lastSpoken) return;
        var m = lastSpoken;
        setTimeout(function () { window.markSpokenRange(m.p, m.s, m.e); }, 250);
    });

    function clearTtsMark() {
        window.clearSpokenRange();
        var old = document.querySelectorAll('.tts-active');
        for (var i = 0; i < old.length; i++) old[i].classList.remove('tts-active');
    }
    window.clearTtsMark = clearTtsMark;

    // ---------------- selection tracking ----------------
    // The native selection toolbar is customised on the Android side; here we
    // just keep Android informed of what is currently selected.

    var lastSel = '';

    function reportSelection() {
        var sel = window.getSelection();
        var t = sel ? sel.toString().trim() : '';
        if (t !== lastSel) {
            lastSel = t;
            if (window.Android && Android.onSelectionChanged) {
                Android.onSelectionChanged(t);
            }
        }
    }

    document.addEventListener('selectionchange', function () {
        clearTimeout(window.__selT);
        window.__selT = setTimeout(reportSelection, 120);
    });

    window.getSelectionText = function () {
        var sel = window.getSelection();
        return sel ? sel.toString() : '';
    };

    window.clearSelection = function () {
        var sel = window.getSelection();
        if (sel) sel.removeAllRanges();
        lastSel = '';
    };

    // ---------------- word tap ----------------

    function wordAt(x, y) {
        var range = null;
        if (document.caretRangeFromPoint) {
            range = document.caretRangeFromPoint(x, y);
        } else if (document.caretPositionFromPoint) {
            var pos = document.caretPositionFromPoint(x, y);
            if (pos) {
                range = document.createRange();
                range.setStart(pos.offsetNode, pos.offset);
                range.collapse(true);
            }
        }
        if (!range || range.startContainer.nodeType !== Node.TEXT_NODE) return null;
        var textNode = range.startContainer;
        var text = textNode.textContent;
        var off = range.startOffset;
        var isW = function (ch) { return /[A-Za-zÀ-ɏ'’\-]/.test(ch); };
        if (off >= text.length || !isW(text[off])) {
            if (off > 0 && isW(text[off - 1])) off = off - 1; else return null;
        }
        var s = off, e = off;
        while (s > 0 && isW(text[s - 1])) s--;
        while (e < text.length && isW(text[e])) e++;
        var word = text.substring(s, e).replace(/^['’\-]+|['’\-]+$/g, '');
        if (!word || word.length > 40) return null;
        var el = textNode.parentElement;
        var paraIdx = -1;
        var block = null;
        while (el && el !== document.body) {
            if (el.hasAttribute && el.hasAttribute('data-para')) {
                paraIdx = parseInt(el.getAttribute('data-para'), 10);
                block = el;
                break;
            }
            el = el.parentElement;
        }
        // the sentence around the word, so the AI can explain it in context
        var ctx = '';
        try {
            var full = block ? (block.innerText || '') : text;
            var at = full.indexOf(word);
            if (at < 0) at = 0;
            ctx = full.substring(Math.max(0, at - 220), Math.min(full.length, at + 260));
        } catch (err) { ctx = ''; }
        return { word: word, para: paraIdx, context: ctx };
    }

    var downX = 0, downY = 0, downT = 0, moved = false;
    document.addEventListener('touchstart', function (ev) {
        if (ev.touches.length !== 1) return;
        downX = ev.touches[0].clientX;
        downY = ev.touches[0].clientY;
        downT = Date.now();
        moved = false;
    }, true);
    document.addEventListener('touchmove', function (ev) {
        if (ev.touches.length !== 1) return;
        var dx = ev.touches[0].clientX - downX, dy = ev.touches[0].clientY - downY;
        if (dx * dx + dy * dy > 100) moved = true;
    }, true);
    document.addEventListener('touchend', function (ev) {
        setTimeout(function () {
            var sel = window.getSelection();
            if (sel && sel.toString().trim().length > 0) return; // selecting, not tapping
            if (moved || Date.now() - downT > 400) return;
            if (ev.target && ev.target.closest && ev.target.closest('a')) return;
            var w = wordAt(downX, downY);
            if (w) {
                if (window.Android && Android.onWordTap) {
                    Android.onWordTap(w.word, w.para, w.context || '');
                }
            } else if (window.Android && Android.onBlankTap) {
                Android.onBlankTap();
            }
        }, 60);
    }, true);

    // ---------------- highlights ----------------

    function highlightFirst(text) {
        if (!text) return false;
        var needle = text.replace(/\s+/g, ' ').trim();
        if (!needle) return false;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var node;
        while ((node = walker.nextNode())) {
            if (node.parentElement && node.parentElement.tagName === 'MARK') continue;
            var hay = node.textContent;
            var pos = hay.indexOf(needle);
            if (pos !== -1) {
                try {
                    var r = document.createRange();
                    r.setStart(node, pos);
                    r.setEnd(node, pos + needle.length);
                    var m = document.createElement('mark');
                    m.className = 'reader-hl';
                    r.surroundContents(m);
                    return true;
                } catch (e) { return false; }
            }
        }
        return false;
    }

    window.applyHighlights = function (jsonArr) {
        try {
            var arr = JSON.parse(jsonArr);
            for (var i = 0; i < arr.length; i++) highlightFirst(arr[i]);
        } catch (e) { }
    };

    window.findAndFlash = function (text) {
        var needle = (text || '').trim();
        if (!needle) return;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var node;
        while ((node = walker.nextNode())) {
            var pos = node.textContent.indexOf(needle);
            if (pos !== -1) {
                var el = node.parentElement;
                if (el) {
                    el.scrollIntoView({ block: 'center' });
                    el.classList.add('reader-flash');
                    setTimeout(function () { el.classList.remove('reader-flash'); }, 1700);
                }
                return;
            }
        }
    };

    // ---------------- pages / scroll ----------------

    function pageInfo() {
        var vh = window.innerHeight || 1;
        var total = Math.max(1, Math.ceil(document.body.scrollHeight / vh));
        var page = Math.min(total, Math.floor(window.scrollY / vh) + 1);
        var denom = Math.max(1, document.body.scrollHeight - vh);
        var frac = Math.max(0, Math.min(1, window.scrollY / denom));
        return { page: page, total: total, frac: frac };
    }

    var scrollTimer = null;
    window.addEventListener('scroll', function () {
        if (scrollTimer) return;
        scrollTimer = setTimeout(function () {
            scrollTimer = null;
            var p = pageInfo();
            if (window.Android && Android.onScroll) Android.onScroll(p.page, p.total, p.frac);
        }, 250);
    });

    window.scrollToFrac = function (f) {
        var vh = window.innerHeight || 1;
        window.scrollTo(0, f * Math.max(0, document.body.scrollHeight - vh));
    };

    window.setReaderTheme = function (cls, sizePx, lineHeightPct, marginPx) {
        document.body.classList.remove(
            'theme-light', 'theme-sepia', 'theme-dark', 'theme-lavender');
        document.body.classList.add(cls);
        document.body.style.setProperty('--reader-size', sizePx + 'px');
        document.body.style.setProperty('--reader-line', (lineHeightPct / 100).toString());
        document.body.style.setProperty('--reader-margin', (marginPx || 20) + 'px');
    };

    // ---------------- auto scroll ----------------

    var autoTimer = null, autoPaused = false;

    function maxScroll() {
        return Math.max(1, document.body.scrollHeight - window.innerHeight);
    }

    window.startAutoScroll = function (wpm) {
        window.stopAutoScroll();
        var words = (document.body.innerText || '').trim().split(/\s+/).length;
        var pxPerSec = Math.max(2, (maxScroll() / Math.max(50, words)) * (wpm / 60));
        var last = Date.now();
        autoTimer = setInterval(function () {
            var now = Date.now();
            if (autoPaused) { last = now; return; }
            window.scrollBy(0, pxPerSec * (now - last) / 1000);
            last = now;
            if (window.scrollY >= maxScroll() - 2) {
                window.stopAutoScroll();
                if (window.Android && Android.onAutoScrollEnd) Android.onAutoScrollEnd();
            }
        }, 40);
    };

    window.stopAutoScroll = function () {
        if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
    };

    document.addEventListener('touchstart', function () { autoPaused = true; }, true);
    document.addEventListener('touchend', function () {
        setTimeout(function () { autoPaused = false; }, 900);
    }, true);

    window.readerReady = function () {
        collectParagraphs();
        var p = pageInfo();
        if (window.Android && Android.onChapterReady) Android.onChapterReady(p.total);
    };
})();
