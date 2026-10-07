#!/usr/bin/env python3
"""
Club link previews (og:image) generator.

Reads the club list from clubs.html, renders a 1200x630 Telegram-style preview
for every club page in clubs/, saves it to img/og/<slug>.jpg and writes personal
og:title / og:description / og:image into each club page.

Runs inside the Pages deploy workflow, so the previews are always in sync with
clubs.html. Locally you can run it too (set PW_CHANNEL=msedge if the bundled
Chromium is not installed):

    PW_CHANNEL=msedge python tools/generate_og.py
"""

import json, re, os, html, threading, functools, sys
import http.server, socketserver
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OGDIR = os.path.join(ROOT, "img", "og")
SITE = "https://alexey0097.github.io/time-to-learn-english"

COLOR = {
    "Group Meetings": "#4cc764",
    "Speed Meetings": "#4cc764",
    "Online": "#4aa8ff",
    "Board Games": "#ffcd1c",
    "Stand-up": "#c171bc",
}
DAYS = [("Mon", "Mo"), ("Tue", "Tu"), ("Wed", "We"), ("Thu", "Th"),
        ("Fri", "Fr"), ("Sat", "Sa"), ("Sun", "Su")]

CLOCK = ('<svg viewBox="0 0 100 100" width="30" height="30"><circle cx="50" cy="50" r="39" fill="none" '
         'stroke="#4cc764" stroke-width="12"/><path d="M50 27 V50 L64 64" fill="none" stroke="#4cc764" '
         'stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/></svg>')


def load_clubs():
    t = open(os.path.join(ROOT, "clubs.html"), encoding="utf-8").read()
    i = t.find('"name":')
    s = t.rfind("[", 0, i)
    depth = 0
    for j in range(s, len(t)):
        if t[j] == "[":
            depth += 1
        elif t[j] == "]":
            depth -= 1
            if depth == 0:
                e = j + 1
                break
    return json.loads(t[s:e])


def short_time(t):
    t = re.sub(r",\s*Moscow time\.?$", "", t or "").strip()
    return re.sub(r",\s*Moscow time", "", t)


def first_sentence(desc):
    m = re.match(r"(.*?[.!?])(\s|$)", desc or "")
    return m.group(1).strip() if m else (desc or "").strip()


def type_color(types):
    for ty in types:
        if ty in COLOR and ty not in ("Group Meetings", "Speed Meetings"):
            return COLOR[ty]
    return "#4cc764"


def build_html(c, base):
    types = c.get("type") or []
    label = " · ".join(types)
    color = type_color(types)
    on = set(c.get("daysOfWeek") or [])
    week = "".join(
        '<li class="on">%s</li>' % ab if full in on else '<li>%s</li>' % ab
        for full, ab in DAYS)
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{{font-family:'Unbounded';src:url('{base}metro/fonts/unbounded.woff') format('woff');font-weight:100 900;font-display:block;}}
@font-face{{font-family:'Golos';src:url('{base}metro/fonts/golos-text.woff') format('woff');font-weight:100 900;font-display:block;}}
*{{margin:0;padding:0;box-sizing:border-box}}
html,body{{width:1200px;height:630px;overflow:hidden;background:#121418}}
.card{{display:flex;width:1200px;height:630px}}
.photo{{width:470px;height:630px;object-fit:cover;object-position:{c.get('imagePosition','50% 50%')};flex:none;background:#1b1e24}}
.panel{{flex:1;background:#121418;padding:52px 62px 56px 62px;display:flex;flex-direction:column}}
.brand{{display:flex;align-items:center;gap:12px;color:#eceef2;font:700 24px/1 'Unbounded',sans-serif}}
.type{{color:{color};font:700 28px/1 'Unbounded',sans-serif;margin-top:46px}}
.name{{color:#eceef2;font:800 64px/1.02 'Unbounded',sans-serif;margin-top:10px;letter-spacing:-.5px}}
.sub{{color:#eceef2;font:400 28px/1.2 'Golos',sans-serif;margin-top:14px}}
.desc{{color:#9aa1ad;font:400 26px/1.5 'Golos',sans-serif;margin-top:30px;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}}
.wk{{--col:65px;--dot:31px;--day:20px;--link:10px;list-style:none;padding:0;margin:0;display:grid;grid-template-columns:repeat(7,var(--col));position:relative;width:max-content;margin-top:auto;align-self:flex-start}}
.wk::before{{content:"";position:absolute;top:calc(var(--dot)/2 - 1px);left:calc(100%/14);right:calc(100%/14);height:3px;background:#2b2f37}}
.wk li.on:has(+ li.on)::after{{content:"";position:absolute;z-index:-1;top:calc(var(--dot)/2 - var(--link)/2);height:var(--link);left:50%;width:100%;background:{color}}}
.wk li:not(.on) + li.on:not(:has(+ li.on))::after,.wk li.on:first-child:not(:has(+ li.on))::after{{content:"";position:absolute;z-index:-1;top:calc(var(--dot)/2 - var(--link)/2);height:var(--link);border-radius:4px;left:calc(50% - var(--dot)*1.25);width:calc(var(--dot)*2.5);background:{color}}}
.wk li.on:first-child:not(:has(+ li.on))::after{{left:50%;width:calc(var(--dot)*1.25)}}
.wk li:not(.on) + li.on:last-child:not(:has(+ li.on))::after{{left:calc(50% - var(--dot)*1.25);width:calc(var(--dot)*1.25)}}
.wk li{{position:relative;z-index:1;display:grid;justify-items:center;gap:10px;font:600 var(--day)/1 'Golos',sans-serif;color:#9aa1ad}}
.wk li::before{{content:"";width:var(--dot);height:var(--dot);border-radius:50%;box-sizing:border-box;background:#121418;border:4px solid color-mix(in srgb, #9aa1ad 45%, #121418)}}
.wk li.on{{color:#eceef2}}
.wk li.on::before{{background:{color};border-color:{color};border-width:max(6px,calc(var(--dot)/4))}}
</style></head><body>
<div class="card"><img class="photo" src="{base + c['image']}">
<div class="panel">
  <div class="brand">{CLOCK}<span>Time to Learn English</span></div>
  <div class="type">{html.escape(label)}</div>
  <div class="name">{html.escape(c['name'])}</div>
  <div class="sub">{html.escape(short_time(c.get('time','')))}</div>
  <div class="desc">{html.escape(c.get('desc',''))}</div>
  <ol class="wk">{week}</ol>
</div></div></body></html>"""


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

    # Браузер между клубами перезагружает страницу и обрывает незавершённые запросы (шрифты,
    # фото). socketserver печатает это как «Exception occurred during processing of request» —
    # обрыв безвреден для генерации, поэтому гасим его и не засоряем лог.
    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)


def set_og(page_html, prop, val):
    tag = '<meta content="%s" property="og:%s"/>' % (html.escape(val, quote=True), prop)
    pat = re.compile(r'<meta[^>]*property="og:%s"[^>]*>' % re.escape(prop))
    return pat.sub(lambda m: tag, page_html, count=1) if pat.search(page_html) else page_html


def main():
    os.makedirs(OGDIR, exist_ok=True)
    clubs = load_clubs()
    httpd = Server(("127.0.0.1", 0), functools.partial(Quiet, directory=ROOT))
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}/"

    channel = os.environ.get("PW_CHANNEL") or None
    done = 0
    with sync_playwright() as p:
        browser = p.chromium.launch(channel=channel) if channel else p.chromium.launch()
        page = browser.new_page(viewport={"width": 1200, "height": 630}, device_scale_factor=1)
        for c in clubs:
            link = c.get("link")
            if not link or not c.get("image"):
                continue
            slug = os.path.basename(link)[:-5]
            page_path = os.path.join(ROOT, "clubs", slug + ".html")
            if not os.path.exists(page_path):
                continue
            page.set_content(build_html(c, base), wait_until="load")
            page.evaluate("() => document.fonts.ready")
            page.wait_for_timeout(120)
            page.evaluate("""() => {
                const name = document.querySelector('.name');
                const desc = document.querySelector('.desc');
                const lines = Math.round(name.getBoundingClientRect().height / parseFloat(getComputedStyle(name).lineHeight));
                desc.style.webkitLineClamp = lines >= 3 ? 1 : lines === 2 ? 2 : 4;
            }""")
            page.screenshot(path=os.path.join(OGDIR, slug + ".jpg"), type="jpeg", quality=88)

            types = c.get("type") or []
            cost = "Paid" if c.get("cost") == 1 else "Free"
            title = f"{c['name']} — Time to Learn English"
            desc = f"{' · '.join(types)} · {short_time(c.get('time',''))} · {cost}. {first_sentence(c.get('desc',''))}"
            img = f"{SITE}/img/og/{slug}.jpg"

            raw = open(page_path, encoding="utf-8").read()
            orig = raw
            raw = set_og(raw, "title", title)
            raw = set_og(raw, "description", desc)
            raw = set_og(raw, "image", img)
            if raw != orig:
                open(page_path, "w", encoding="utf-8", newline="").write(raw)
            done += 1
        browser.close()
    httpd.shutdown()
    print("club previews generated:", done)


if __name__ == "__main__":
    main()
