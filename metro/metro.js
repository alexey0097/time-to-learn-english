/*!
 * Metro JS 1.0 — small helpers for Metro CSS. No dependencies.
 * Everything is driven by data-attributes; nothing runs unless the attribute is on the page.
 *
 *   data-date="2026-10-04"      on a .linemap link or a .day — today's one gets marked
 *   data-clamp                  folds long text to a few lines and adds "Read more"
 *   data-clamp="3"              … to 3 lines
 *   data-copy="+7 900 000-00-00" a button that copies its value and says "Copied"
 *   data-collapse-below="60rem" on <details>: starts closed on narrow screens
 *   data-theme-toggle           a button that switches light / dark and remembers it
 *   data-gallery                on a .gallery: adds arrows and a "1 / 5" counter
 *
 * Content rendered later (for example from a JS array) is handled with Metro.init(container).
 * The whole page is initialised automatically on load.
 */
(function () {
    'use strict';

    var TIME_ZONE = 'Europe/Moscow';   // "today" is counted in Moscow time, like the schedule

    // ---------- today ----------
    function todayISO() {
        // en-CA gives YYYY-MM-DD
        return new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE }).format(new Date());
    }

    function markToday(root) {
        var today = todayISO();
        root.querySelectorAll('[data-date]').forEach(function (el) {
            if (el.dataset.date !== today) return;
            if (el.tagName === 'A') el.setAttribute('aria-current', 'date');
            el.classList.add('is-today');
        });
    }

    // ---------- read more ----------
    function setupClamp(root) {
        root.querySelectorAll('[data-clamp]').forEach(function (el) {
            if (el.dataset.clampReady) return;
            el.dataset.clampReady = '1';
            if (el.dataset.clamp) el.style.setProperty('--lines', el.dataset.clamp);
            el.classList.add('is-clamped');
            if (el.scrollHeight <= el.clientHeight + 8) {   // short enough, nothing to fold
                el.classList.remove('is-clamped');
                return;
            }
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'link-btn clamp-toggle';
            button.textContent = 'Read more';
            button.setAttribute('aria-expanded', 'false');
            button.addEventListener('click', function () {
                var open = el.classList.toggle('is-clamped') === false;
                button.textContent = open ? 'Show less' : 'Read more';
                button.setAttribute('aria-expanded', String(open));
            });
            el.insertAdjacentElement('afterend', button);
        });
    }

    // ---------- copy to clipboard ----------
    function copyText(text) {
        if (navigator.clipboard && window.isSecureContext) {
            // the API can refuse (no focus, no permission): fall back instead of failing silently
            return navigator.clipboard.writeText(text).catch(function () { return legacyCopy(text); });
        }
        return legacyCopy(text);
    }

    // fallback for http:// and older browsers
    function legacyCopy(text) {
        var area = document.createElement('textarea');
        area.value = text;
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        area.remove();
        return ok ? Promise.resolve() : Promise.reject(new Error('copy failed'));
    }

    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-copy]');
        if (!button || button.classList.contains('is-done')) return;
        var label = button.textContent;
        copyText(button.dataset.copy).then(function () {
            button.style.minWidth = button.offsetWidth + 'px';   // keep the width steady
            button.classList.add('is-done');
            button.textContent = 'Copied';
            setTimeout(function () {
                button.textContent = label;
                button.style.minWidth = '';
                button.classList.remove('is-done');
            }, 1200);
        }).catch(function () { /* copying is blocked: keep the label, no error in the console */ });
    });

    // ---------- details that start closed on phones ----------
    function setupCollapse(root) {
        root.querySelectorAll('details[data-collapse-below]').forEach(function (el) {
            if (window.matchMedia('(max-width: ' + el.dataset.collapseBelow + ')').matches) {
                el.removeAttribute('open');
            }
        });
    }

    // ---------- theme ----------
    var THEME_KEY = 'metro-theme';
    function readTheme() {
        try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; }
    }
    function applyTheme(theme) {
        if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
        else delete document.documentElement.dataset.theme;
    }
    applyTheme(readTheme());   // as early as possible to avoid a flash

    document.addEventListener('click', function (event) {
        if (!event.target.closest('[data-theme-toggle]')) return;
        var current = document.documentElement.dataset.theme ||
            (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
        var next = current === 'dark' ? 'light' : 'dark';
        applyTheme(next);
        try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* private mode: fine */ }
    });

    // ---------- gallery ----------
    var ARROW = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"/></svg>';

    function setupGallery(root) {
        root.querySelectorAll('[data-gallery]').forEach(function (gallery) {
            if (gallery.dataset.galleryReady) return;
            var track = gallery.querySelector('.gallery__track');
            if (!track) return;
            gallery.dataset.galleryReady = '1';
            var slides = track.children.length;
            if (slides < 2) return;

            var prev = document.createElement('button');
            prev.type = 'button';
            prev.className = 'gallery__nav gallery__nav--prev';
            prev.setAttribute('aria-label', 'Previous photo');
            prev.innerHTML = ARROW.replace('M9 6l6 6-6 6', 'M15 6l-6 6 6 6');
            var next = document.createElement('button');
            next.type = 'button';
            next.className = 'gallery__nav gallery__nav--next';
            next.setAttribute('aria-label', 'Next photo');
            next.innerHTML = ARROW;
            var count = document.createElement('span');
            count.className = 'gallery__count';
            count.setAttribute('aria-live', 'polite');
            gallery.append(prev, next, count);

            function index() { return Math.round(track.scrollLeft / track.clientWidth); }
            function update() {
                var i = index();
                count.textContent = (i + 1) + ' / ' + slides;
                prev.disabled = i <= 0;
                next.disabled = i >= slides - 1;
            }
            function go(step) { track.scrollTo({ left: (index() + step) * track.clientWidth }); }
            prev.addEventListener('click', function () { go(-1); });
            next.addEventListener('click', function () { go(1); });
            track.addEventListener('scroll', function () { window.requestAnimationFrame(update); }, { passive: true });
            gallery.addEventListener('keydown', function (e) {
                if (e.key === 'ArrowLeft') go(-1);
                if (e.key === 'ArrowRight') go(1);
            });
            update();
        });
    }

    // ---------- public API ----------
    function init(root) {
        root = root || document;
        markToday(root);
        setupClamp(root);
        setupGallery(root);
    }

    window.Metro = { init: init, copyText: copyText, today: todayISO };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { setupCollapse(document); init(document); });
    } else {
        setupCollapse(document);
        init(document);
    }
})();
