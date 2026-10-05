/*
 * Weekly schedule from export files.
 *
 * Every week is a file js/schedule/YYYY_WW.js that declares `const schedule_YYYY_WW = {...}`.
 * The newest published week replaces the day list of the current week (#current-week);
 * the earlier weeks of the current month go into "Earlier weeks" as collapsed blocks.
 * The block of the previous hand-made week is moved there too. Weeks of past months are not loaded.
 * If no files exist, the page stays as it is. Add ?today=YYYY-MM-DD to the URL to test another date.
 */
(function () {
    var DIR = 'js/schedule/';
    var DAY = 864e5;
    var TOP = { '⭐': 1, '🔝': 1, '👍': 1, '🔥': 1 };
    var NEW = '🆕';

    var param = /[?&]today=(\d{4}-\d{2}-\d{2})/.exec(location.search);
    var today = param ? param[1] : new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(new Date());

    function utc(iso) { var p = iso.split('-').map(Number); return Date.UTC(p[0], p[1] - 1, p[2]); }
    function iso(ms) { return new Date(ms).toISOString().slice(0, 10); }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }
    function fmt(isoDate, opts) {
        opts.timeZone = 'UTC';
        return new Date(utc(isoDate)).toLocaleDateString('en-US', opts);
    }

    // ---------- which files to try ----------
    // Week numbers are taken as in the files, so try the number counted from 1 January and its neighbours;
    // a file is accepted by the dates inside it, not by its name.
    function candidates() {
        var t = utc(today);
        var monday = t - ((new Date(t).getUTCDay() + 6) % 7) * DAY;
        var monthStart = Date.UTC(new Date(t).getUTCFullYear(), new Date(t).getUTCMonth(), 1);
        var names = {};
        for (var m = monday; m + 6 * DAY >= monthStart; m -= 7 * DAY) {
            var year = new Date(m).getUTCFullYear();
            var est = Math.floor((m - Date.UTC(year, 0, 1)) / DAY / 7) + 1;
            [year, new Date(m + 6 * DAY).getUTCFullYear()].forEach(function (y) {
                for (var n = est - 1; n <= est + 1; n++) if (n >= 1) names[y + '_' + n] = [y, n];
            });
        }
        return Object.keys(names).map(function (k) { return names[k]; });
    }

    function load(year, week) {
        var name = 'schedule_' + year + '_' + week;
        return new Promise(function (resolve) {
            var s = document.createElement('script');
            s.src = DIR + year + '_' + week + '.js';
            s.onload = function () {
                var data = null;
                try { data = (0, eval)('typeof ' + name + ' !== "undefined" ? ' + name + ' : null'); } catch (e) { /* broken file */ }
                resolve(data);
            };
            s.onerror = function () { resolve(null); };   // no such week yet
            document.head.appendChild(s);
        });
    }

    // ---------- markup ----------
    function tag(e) {
        if (e.emoji === NEW) return ' <b class="tag">New</b>';
        if (TOP[e.emoji]) return ' <b class="tag tag--gold">Top</b>';
        return '';
    }

    // the export may give a bare page name (viva-lingua-club.html): club pages live in clubs/
    function safeHref(link) {
        if (/^[\w\-]+\.html$/.test(link || '')) return 'clubs/' + link;
        return /^(https?:\/\/|[\w\-.\/]+\.html)/.test(link || '') ? link : '#';
    }

    function row(e) {
        return '<li><time>' + esc(e.time) + '</time> <a href="' + esc(safeHref(e.link)) + '" target="_blank">' + esc(e.club) + '</a>' +
            (e.online || !e.place ? '' : ' <span class="where">' + esc(e.place) + '</span>') + tag(e) + '</li>';
    }

    function byTime(a, b) { return a.time < b.time ? -1 : a.time > b.time ? 1 : 0; }

    function dayHtml(day, live) {
        var events = (day.events || []).slice().sort(byTime);
        var offline = events.filter(function (e) { return !e.online; });
        var online = events.filter(function (e) { return e.online; });
        var n = events.length;
        return '<section class="day"' + (live ? ' id="day-' + day.date + '" data-date="' + day.date + '"' : '') + '>' +
            '<h3>' + fmt(day.date, { weekday: 'long', month: 'long', day: 'numeric' }) + ' <small>' + n + ' meetup' + (n === 1 ? '' : 's') + '</small></h3>' +
            (offline.length ? '<ul class="route">' + offline.map(row).join('') + '</ul>' : '') +
            (online.length ? '<p class="route-label">Online</p><ul class="route route--alt">' + online.map(row).join('') + '</ul>' : '') +
            '</section>';
    }

    function metaHtml(week) {
        return '<p class="meta">' + fmt(String(week.generatedAt).slice(0, 10), { month: 'long', day: 'numeric', year: 'numeric' }) +
            ' by <a href="#contacts">Alexey Leonov</a></p>';
    }

    function title(week) { return 'This Week in Moscow: ' + week.weekRange; }

    function foldFromWeek(week) {
        var el = document.createElement('details');
        el.className = 'fold';
        el.innerHTML = '<summary>' + esc(title(week)) + '</summary><article class="prose">' + metaHtml(week) +
            '<h2>Weekly Schedule</h2>' + week.days.map(function (d) { return dayHtml(d, false); }).join('') + '</article>';
        return el;
    }

    // ---------- page ----------
    function start(week) { return week.days[0].date; }
    function end(week) { return week.days[week.days.length - 1].date; }

    function apply(weeks) {
        var current = document.getElementById('current-week');
        var archive = document.querySelector('section[aria-label="Earlier weeks"]');
        if (!weeks.length || !current || !archive) return;

        var latest = weeks[0];
        var older = weeks.slice(1);
        var covered = {};
        weeks.forEach(function (w) { w.days.forEach(function (d) { covered[d.date] = 1; }); });
        var folds = older.map(function (w) { return { start: start(w), el: foldFromWeek(w) }; });

        // the hand-made current week goes to the archive unless a file already covers it
        var manual = Array.prototype.slice.call(current.querySelectorAll(':scope > section.day'));
        if (manual.length && !covered[manual[0].dataset.date]) {
            var fold = document.createElement('details');
            fold.className = 'fold';
            var inner = document.createElement('article');
            inner.className = 'prose';
            for (var node = current.querySelector('.meta'); node; node = node.nextElementSibling) {
                var copy = node.cloneNode(true);
                if (copy.removeAttribute) {
                    copy.removeAttribute('id');
                    copy.removeAttribute('data-date');
                    Array.prototype.forEach.call(copy.querySelectorAll('[id],[data-date]'), function (x) {
                        x.removeAttribute('id');
                        x.removeAttribute('data-date');
                    });
                }
                inner.appendChild(copy);
                if (node === manual[manual.length - 1]) break;
            }
            var summary = document.createElement('summary');
            summary.textContent = current.querySelector('h2').textContent;
            fold.append(summary, inner);
            folds.push({ start: manual[0].dataset.date, el: fold });
        }

        // hand-made folds that a file now covers are dropped, to avoid showing a week twice
        Array.prototype.forEach.call(archive.querySelectorAll('details.fold'), function (f) {
            var d = f.querySelector('section.day[data-date]');
            if (d && covered[d.dataset.date]) f.remove();
        });

        folds.sort(function (a, b) { return a.start < b.start ? 1 : -1; });
        var label = archive.querySelector('.label');
        archive.hidden = false;
        folds.reverse().forEach(function (f) { label.insertAdjacentElement('afterend', f.el); });

        // the current week
        manual.forEach(function (s) { s.remove(); });
        current.querySelector('h2').textContent = title(latest);
        var meta = current.querySelector('.meta');
        if (meta) meta.outerHTML = metaHtml(latest);
        var legend = current.querySelector('.legend');
        legend.insertAdjacentHTML('afterend', latest.days.map(function (d) { return dayHtml(d, true); }).join(''));
        if (window.Metro) window.Metro.init(current);
    }

    function ready() { document.dispatchEvent(new Event('schedule:ready')); }

    var loads = candidates().map(function (c) { return load(c[0], c[1]); });
    Promise.all(loads).then(function (all) {
        var t = utc(today), monthStart = Date.UTC(new Date(t).getUTCFullYear(), new Date(t).getUTCMonth(), 1);
        var seen = {};
        var weeks = all.filter(function (w) {
            if (!w || !Array.isArray(w.days) || !w.days.length) return false;
            if (start(w) > today || utc(end(w)) < monthStart || seen[start(w)]) return false;
            seen[start(w)] = 1;
            return true;
        }).sort(function (a, b) { return start(a) < start(b) ? 1 : -1; });
        try { apply(weeks); } finally { ready(); }
    });
})();
