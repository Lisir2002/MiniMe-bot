#!/usr/bin/env python3
"""Build the fully offline, in-app Mini-AIBOT manual.

Inputs
  - manual-build/manifest.json + raw/*.html  (from crawl_docs.py, live site)
  - manual-build/nav-{zh,en}.json            (from extract_nav.cjs)
  - docs-site/docs/**/*.md                   (source of pages not yet published)

Output
  - app/src/main/assets/manual/**  (self-contained HTML/CSS/JS, no network)

Pages present on the public site use the crawled, already-rendered VitePress
content. Pages linked by the project but not yet deployed (404) are rendered
from the repository Markdown so the manual is complete.
"""
from __future__ import annotations

import html as html_lib
import json
import os
import re
import shutil
import sys
import urllib.parse

import markdown

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BUILD = os.path.join(ROOT, "manual-build")
DOCS = os.path.join(ROOT, "docs-site", "docs")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "manual")
WEB = os.path.join(ROOT, "scripts", "manual", "web")
SITE = "https://aicode.murk.top"

# ---------------------------------------------------------------- rebranding
PROTECT = [
    r'https?://[^\s"\'<>()]*aicode\.murk\.top[^\s"\'<>()]*',
    r'https?://[^\s"\'<>()]*github\.com/jieapi/[^\s"\'<>()]*',
    r'https?://raw\.githubusercontent\.com/jieapi/[^\s"\'<>()]*',
    r'https?://api\.github\.com/repos/jieapi/[^\s"\'<>()]*',
    r'https?://ifdian\.net/a/jieapi[^\s"\'<>()]*',
    r'jieapi%2F[A-Za-z0-9_.\-]+',
    r'jieapi/[A-Za-z0-9_.\-]+',
]
REPLACE = [
    ("com.aicode", "com.mini.bot"),
    ("com/aicode", "com/mini/bot"),
    ("AICODE", "MINIBOT"),
    ("AICode", "MINIBOT"),
    ("Aicode", "MiniBot"),
    ("AiCode", "Mini-AIBOT"),
    ("aicode", "minibot"),
]


def rebrand(text: str) -> str:
    tokens: dict[str, str] = {}

    def keep(match):
        token = f"@@UPSTREAM{len(tokens)}@@"
        tokens[token] = match.group(0)
        return token

    for pattern in PROTECT:
        text = re.sub(pattern, keep, text)
    for old, new in REPLACE:
        text = text.replace(old, new)
    for token, original in tokens.items():
        text = text.replace(token, original)
    return text


# ---------------------------------------------------------------- html utils
def find_class_div(source: str, class_name: str):
    """Return (start, end) of the first balanced <div class="... class_name ...">."""
    for match in re.finditer(r"<div[^>]*>", source):
        tag = match.group(0)
        if 'class="' not in tag:
            continue
        classes = re.search(r'class="([^"]*)"', tag).group(1).split()
        if class_name not in classes:
            continue
        depth = 0
        for tag_match in re.finditer(r"<div\b|</div>", source[match.start():]):
            if tag_match.group(0).startswith("</"):
                depth -= 1
            else:
                depth += 1
            if depth == 0:
                end = match.start() + tag_match.end()
                return match.start(), end
    return None


def strip_tags(value: str) -> str:
    value = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", value, flags=re.S | re.I)
    value = re.sub(r"<[^>]+>", " ", value)
    value = html_lib.unescape(value)
    return re.sub(r"\s+", " ", value).strip()


def clean_live_content(content: str) -> str:
    content = re.sub(r"\sdata-v-[0-9a-f]+(=\"[^\"]*\")?", "", content)
    content = re.sub(r'<a class="header-anchor"[^>]*>.*?</a>', "", content, flags=re.S)
    content = re.sub(r'<button[^>]*class="[^"]*copy[^"]*"[^>]*>.*?</button>', "", content, flags=re.S)
    content = re.sub(r'<span class="lang">.*?</span>', "", content, flags=re.S)
    content = re.sub(r' tabindex="-1"', "", content)
    content = re.sub(r' style="position:relative;"', "", content)
    return content


def extract_live(html: str) -> tuple[str, str] | None:
    found = find_class_div(html, "vp-doc")
    if not found:
        return None
    start, end = found
    content = html[start:end]
    content = content[content.find(">") + 1:content.rfind("</div>")]
    content = clean_live_content(content)
    h1 = re.search(r"<h1[^>]*>(.*?)</h1>", content, flags=re.S)
    title = strip_tags(h1.group(1)) if h1 else ""
    return title, content


# ---------------------------------------------------------------- markdown
CONTAINER_OPEN = re.compile(r"^:::\s*([a-zA-Z]+)(?:\s+(.*))?\s*$")

def _slugify(value: str, separator: str = "-") -> str:
    value = strip_tags(value).strip().lower()
    value = re.sub(r"[^\w\s-]", "", value)
    value = re.sub(r"[-\s]+", separator, value).strip(separator)
    return value or "section"



def render_markdown(text: str) -> tuple[str, str]:
    text = rebrand(text)
    if text.startswith("---"):
        parts = text.split("---", 2)
        if len(parts) >= 3:
            text = parts[2].lstrip("\n")

    md = markdown.Markdown(
        extensions=["extra", "sane_lists", "toc"],
        extension_configs={"toc": {"permalink": False, "baselevel": 1, "slugify": _slugify}},
    )

    lines = text.splitlines()
    blocks: list[str] = []
    buf: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if CONTAINER_OPEN.match(line):
            kind = CONTAINER_OPEN.match(line).group(1)
            title = (CONTAINER_OPEN.match(line).group(2) or kind).strip()
            index += 1
            inner: list[str] = []
            while index < len(lines) and lines[index].strip() != ":::":
                inner.append(lines[index])
                index += 1
            index += 1
            inner_html = markdown.Markdown(extensions=["extra", "sane_lists"]).convert(
                "\n".join(inner)
            )
            buf.append(
                f'@@CONTAINER{len(blocks)}@@'
            )
            blocks.append(
                f'<div class="custom-block {kind}"><p class="custom-block-title">'
                f"{html_lib.escape(title)}</p>{inner_html}</div>"
            )
            continue
        # VitePress uses ::: tip/warning/... too; the branch above handles any kind.
        buf.append(line)
        index += 1

    body = md.convert("\n".join(buf))
    for i, block in enumerate(blocks):
        body = body.replace(f"<p>@@CONTAINER{i}@@</p>", block)
        body = body.replace(f"@@CONTAINER{i}@@", block)

    h1 = re.search(r"<h1[^>]*>(.*?)</h1>", body, flags=re.S)
    title = strip_tags(h1.group(1)) if h1 else ""
    return title, body


# ---------------------------------------------------------------- routes
def route_key(route: str) -> str:
    route = urllib.parse.urlparse(route).path
    if not route.startswith("/"):
        route = "/" + route
    if route != "/" and route.endswith("/"):
        route = route.rstrip("/")
    return route or "/"


def route_to_output(route: str, dir_routes: set[str]) -> str:
    key = route_key(route)
    rel = key.strip("/")
    if not rel:
        return "index.html"
    if key in dir_routes:
        return f"{rel}/index.html"
    return f"{rel}.html"


def collect_repo_pages() -> dict[str, dict]:
    pages: dict[str, dict] = {}
    for dirpath, _dirs, files in os.walk(DOCS):
        for name in files:
            if not name.endswith(".md"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, DOCS).replace(os.sep, "/")
            if rel in ("index.md", "en/index.md"):
                continue
            if name == "index.md":
                route = "/" + os.path.dirname(rel) + "/"
            else:
                route = "/" + rel[:-3]
            with open(path, encoding="utf-8") as f:
                pages[route_key(route)] = {"route": route_key(route), "markdown": f.read()}
    return pages


def load_nav() -> tuple[list, list]:
    zh = json.load(open(os.path.join(BUILD, "nav-zh.json"), encoding="utf-8"))
    en = json.load(open(os.path.join(BUILD, "nav-en.json"), encoding="utf-8"))
    return zh.get("/guide/", []), zh.get("/advanced/", []), en.get("/en/guide/", [])


def walk_nav(nodes, out, route_map):
    for node in nodes:
        link = node.get("link")
        if link:
            key = route_key(link)
            if key in route_map:
                out.append(
                    {
                        "t": node.get("text", ""),
                        "p": route_map[key],
                        "route": key,
                    }
                )
        if node.get("items"):
            walk_nav(node["items"], out, route_map)


def build_nav_tree(nodes, route_map):
    tree = []
    for node in nodes:
        link = node.get("link")
        item = {"t": node.get("text", "")}
        if link and route_key(link) in route_map:
            item["p"] = route_map[route_key(link)]
        if node.get("items"):
            item["c"] = build_nav_tree(node["items"], route_map)
        tree.append(item)
    return tree


# ---------------------------------------------------------------- build
def main() -> None:
    manifest = json.load(open(os.path.join(BUILD, "manifest.json"), encoding="utf-8"))
    repo = collect_repo_pages()

    # Every page linked by the project is bundled, even if the public site 404s.
    dir_routes = {r for r in repo if repo[r]["route"].endswith("/")}
    all_routes = set(repo.keys())

    # Live pages that are real HTML documents (skip the binary /download endpoint).
    live: dict[str, str] = {}
    for route, meta in manifest["pages"].items():
        if meta.get("status") != 200 or not meta.get("file"):
            continue
        if route in ("/", "/download"):
            continue
        path = os.path.join(BUILD, "raw", meta["file"])
        with open(path, encoding="utf-8", errors="replace") as f:
            raw = f.read()
        if "<html" not in raw[:2000].lower():
            continue
        live[route_key(route)] = raw

    # Add live-only pages (e.g. /privacy, /terms, /en/...) to the bundle.
    for key in live:
        if key not in all_routes and key != "/":
            all_routes.add(key)

    # 赞助页随设置入口一并下线，不再打包或出现在目录。
    all_routes.discard("/guide/sponsor")
    route_map = {key: route_to_output(key, dir_routes) for key in all_routes}

    guide_nav, advanced_nav, en_nav = load_nav()
    ordered: list[str] = []
    walk_nav(guide_nav + advanced_nav + en_nav, ordered, route_map)
    # English legal pages are linked from the site nav, not the English sidebar.
    for extra in ("/en/terms", "/en/privacy"):
        if extra in route_map:
            ordered.append({"t": "Terms" if extra.endswith("terms") else "Privacy", "p": route_map[extra], "route": extra})

    seen = set()
    page_order: list[dict] = []
    for item in ordered:
        if item["route"] in seen:
            continue
        seen.add(item["route"])
        page_order.append(item)

    # Anything crawled/repo that is not in the sidebar still gets bundled.
    for key in sorted(all_routes):
        if key not in seen and key not in ("/en",):
            page_order.append({"t": "", "p": route_map[key], "route": key})

    # Content + title resolution.
    pages: list[dict] = []
    used_repo = []
    used_live = []
    for item in page_order:
        key = item["route"]
        route = key if key != "/" else "/"
        content = None
        title = item.get("t") or ""
        if key in live:
            extracted = extract_live(live[key])
            if extracted:
                live_title, content = extracted
                title = title or live_title
                used_live.append(key)
        if content is None and key in repo:
            md_title, content = render_markdown(repo[key]["markdown"])
            title = title or md_title
            used_repo.append(key)
        if content is None:
            continue
        content = rebrand(content)

        if "en" == route.strip("/").split("/")[0]:
            lang = "en"
        else:
            lang = "zh"
        section = "使用手册"
        if route.startswith("/advanced"):
            section = "进阶教程"
        elif lang == "en":
            section = "English"
        elif route.startswith(("/terms", "/privacy")) or route.startswith("/en/"):
            section = "法律条款" if lang == "zh" else "Legal"

        pages.append(
            {
                "route": key,
                "out": route_map[key],
                "title": title or key.strip("/").split("/")[-1],
                "content": content,
                "lang": lang,
                "section": section,
            }
        )

    # ------------------------------------------------------------ output
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(os.path.join(OUT, "assets"), exist_ok=True)

    shutil.copyfile(os.path.join(DOCS, "public", "logo.png"), os.path.join(OUT, "logo.png"))
    shutil.copyfile(os.path.join(WEB, "manual.css"), os.path.join(OUT, "assets", "manual.css"))
    shutil.copyfile(os.path.join(WEB, "manual.js"), os.path.join(OUT, "assets", "manual.js"))

    for page in pages:
        depth = page["out"].count("/")
        page["root"] = "../" * depth

    route_to_url = {p["route"]: p["out"] for p in pages}
    route_to_url.setdefault("/", "index.html")
    route_to_url.setdefault("/en", "index.html")
    title_by_route = {p["route"]: p["title"] for p in pages}

    def out_url(target_route: str, current_out: str, fragment: str = "") -> str:
        target = route_to_url[target_route]
        rel = os.path.relpath(target, os.path.dirname(current_out) or ".")
        rel = rel.replace(os.sep, "/")
        return rel + fragment

    def rewrite_links(content: str, page: dict) -> str:
        def replace(match):
            quote, href = match.group(1), match.group(2)
            if not href or href.startswith(("#", "mailto:", "tel:", "javascript:")):
                return match.group(0)
            absolute = urllib.parse.urljoin(SITE + page["route"], href)
            parsed = urllib.parse.urlparse(absolute)
            internal = parsed.netloc == urllib.parse.urlparse(SITE).netloc
            if internal:
                key = route_key(parsed.path)
                if key in route_to_url:
                    frag = "#" + parsed.fragment if parsed.fragment else ""
                    return f"href={quote}{out_url(key, page['out'], frag)}{quote}"
                return f"href={quote}{absolute}{quote}"
            if parsed.scheme in ("http", "https"):
                return f"href={quote}{absolute}{quote}"
            return match.group(0)

        content = re.sub(r'href=(["\'])([^"\']*)\1', replace, content)
        return content

    # Build nav tree for JS (manual-root relative paths).
    zh_tree = [{"t": "使用手册", "c": build_nav_tree(guide_nav, route_to_url)},
               {"t": "进阶教程", "c": build_nav_tree(advanced_nav, route_to_url)}]
    en_tree = [{"t": "English", "c": build_nav_tree(en_nav, route_to_url)}]
    legal_tree = []
    for extra in ("/en/terms", "/en/privacy"):
        if extra in route_to_url:
            legal_tree.append({"t": title_by_route.get(extra, extra), "p": route_to_url[extra]})
    if legal_tree:
        en_tree.append({"t": "Legal", "c": legal_tree})
    # zh legal pages already live inside the guide sidebar.

    nav_data = zh_tree + en_tree
    with open(os.path.join(OUT, "assets", "nav-data.js"), "w", encoding="utf-8") as f:
        f.write("window.MANUAL_NAV = ")
        json.dump(nav_data, f, ensure_ascii=False)
        f.write(";\n")

    # Search index (loaded only by the manual home page).
    search_rows = []
    for page in pages:
        search_rows.append(
            {
                "t": page["title"],
                "p": page["out"],
                "s": page["section"],
                "x": strip_tags(page["content"])[:3500],
            }
        )
    with open(os.path.join(OUT, "assets", "search-data.js"), "w", encoding="utf-8") as f:
        f.write("window.MANUAL_SEARCH = ")
        json.dump(search_rows, f, ensure_ascii=False)
        f.write(";\n")

    # Page shell.
    for page in pages:
        content = rewrite_links(page["content"], page)
        html = page_shell(
            f"{page['title']} · Mini-AIBOT 使用手册",
            page["out"],
            content,
            page["root"],
            lang="en" if page["lang"] == "en" else "zh-Hans",
        )
        path = os.path.join(OUT, page["out"])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(html)

    render_legal()
    build_index(pages, nav_data, route_to_url, title_by_route)

    report = {
        "crawled_at": manifest.get("crawled_at"),
        "crawled_ok": manifest.get("ok_count"),
        "crawled_failed": manifest.get("failed_count"),
        "pages_bundled": len(pages),
        "from_live_site": len(used_live),
        "from_repo_markdown": len(used_repo),
        "repo_only_pages": sorted(used_repo),
    }
    with open(os.path.join(OUT, "build-report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


LEGAL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "legal")


def page_shell(title: str, out: str, content: str, root: str, *, lang: str = "zh-Hans",
               body_class: str = "", extra_scripts: str = "") -> str:
    """One iOS-style shell for every manual and legal page."""
    class_attr = f' class="{body_class}"' if body_class else ""
    labels = {
        "zh-Hans": {
            "brand": "使用手册", "menu": "打开目录", "close": "关闭目录",
            "home": "首页", "search": "搜索", "exit": "退出",
            "toc": "目录", "search_placeholder": "搜索手册",
            "foot": "离线内置手册 · 内容基于上游 AiCode 文档整理 · GPL-3.0",
        },
        "en": {
            "brand": "Manual", "menu": "Open contents", "close": "Close contents",
            "home": "Home", "search": "Search", "exit": "Exit",
            "toc": "Contents", "search_placeholder": "Search the manual",
            "foot": "Bundled offline manual · Based on upstream AiCode docs · GPL-3.0",
        },
    }
    text = labels.get(lang, labels["zh-Hans"])
    menu_icon = (
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" '
        'stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16"/></svg>'
    )
    close_icon = (
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" '
        'stroke-linecap="round"><path d="M6 6l12 12M18 6l-12 12"/></svg>'
    )
    return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>{html_lib.escape(title)}</title>
<link rel="stylesheet" href="{root}assets/manual.css">
</head>
<body data-root="{root}" data-page="{out}" data-theme="light"{class_attr}>
<header class="topbar">
  <button class="icon-btn" id="menu-btn" aria-label="{text["menu"]}">{menu_icon}</button>
  <a class="brand" href="{root}index.html"><img src="{root}logo.png" alt=""><span>{text["brand"]}</span></a>
  <div class="top-actions">
    <a class="top-action" href="{root}index.html">{text["home"]}</a>
    <button class="top-action" id="search-open" type="button">{text["search"]}</button>
    <button class="top-action close" id="manual-close" type="button">{text["exit"]}</button>
  </div>
</header>
<div class="drawer-mask" id="mask"></div>
<div class="layout">
  <aside class="sidebar" id="sidebar">
    <div class="sidebar-head">
      <span class="sidebar-title">{text["toc"]}</span>
      <button class="icon-btn" id="drawer-close" aria-label="{text["close"]}">{close_icon}</button>
    </div>
    <form class="sidebar-search" id="nav-search-form">
      <input id="nav-search-input" type="search" placeholder="{text["search_placeholder"]}" autocomplete="off">
    </form>
    <div id="sidebar-nav"></div>
  </aside>
  <main class="main">
    <article class="vp-doc">{content}</article>
    <footer class="page-foot">{text["foot"]}</footer>
  </main>
</div>
<script src="{root}assets/nav-data.js"></script>
{extra_scripts}<script src="{root}assets/manual.js"></script>
</body>
</html>
"""


def render_legal() -> None:
    """Render the downstream terms/privacy templates into the bundled manual."""
    md = markdown.Markdown(extensions=["extra", "sane_lists", "toc"])
    targets = (
        ("terms", "用户协议", "terms.html", "", "zh-Hans"),
        ("privacy", "隐私政策", "privacy.html", "", "zh-Hans"),
        ("terms.en", "Terms of Use", "en/terms.html", "../", "en"),
        ("privacy.en", "Privacy Policy", "en/privacy.html", "../", "en"),
    )
    for source_slug, title, out_path, root, lang in targets:
        source = os.path.join(LEGAL_DIR, f"{source_slug}.md")
        if not os.path.exists(source):
            continue
        with open(source, encoding="utf-8") as f:
            body = md.convert(f.read())
            md.reset()
        html = page_shell(f"{title} · Mini-AIBOT", out_path, body, root, lang=lang)
        dest = os.path.join(OUT, out_path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(html)


def build_index(pages, nav_data, route_to_url, title_by_route) -> None:
    groups = [
        ("使用手册", [p for p in pages if p["section"] == "使用手册"]),
        ("进阶教程", [p for p in pages if p["section"] == "进阶教程"]),
        ("English", [p for p in pages if p["section"] == "English"]),
        ("法律条款 / Legal", [p for p in pages if p["section"] in ("法律条款", "Legal")]),
    ]

    def cards(items):
        links = []
        for p in items:
            links.append(f'<a class="idx-link" href="{p["out"]}"><span>{html_lib.escape(p["title"])}</span><span class="idx-arrow">›</span></a>')
        return "\n".join(links)

    sections_html = []
    for title, items in groups:
        if not items:
            continue
        sections_html.append(
            f"""<section class="idx-section">
  <div class="idx-section-head"><h2>{title}</h2><span>{len(items)} 篇</span></div>
  <div class="idx-links">{cards(items)}</div>
</section>"""
        )

    quick = ["/guide/quick-start", "/guide/providers", "/guide/container", "/guide/terminal", "/guide/mcp", "/guide/plugins"]
    quick_html = "".join(
        f'<a class="quick" href="{route_to_url[r]}">{html_lib.escape(title_by_route.get(r, r))}</a>'
        for r in quick
        if r in route_to_url
    )

    content = f"""<section class="hero">
  <img class="hero-logo" src="logo.png" alt="">
  <h1>Mini-AIBOT 使用手册</h1>
  <p>完全内置 · 离线可用 · 共 {len(pages)} 篇文档</p>
  <form class="search-box" id="search-form">
    <input id="search-input" type="search" placeholder="搜索章节、功能或关键词" autocomplete="off">
    <button type="submit">搜索</button>
  </form>
  <div class="quick-row">{quick_html}</div>
</section>
<div id="search-results" class="search-results" hidden></div>
<div id="index-body">
  {"".join(sections_html)}
  <section class="idx-note">
    <h2>关于本手册</h2>
    <p>手册内容来自上游公开文档站，未发布章节由仓库 Markdown 源补齐，全部随安装包内置，无需联网。</p>
    <p>Mini-AIBOT 基于 <a href="https://github.com/jieapi/AiCode" target="_blank" rel="noopener">jieapi/AiCode</a> 二次开发，感谢上游作者与所有贡献者。</p>
  </section>
</div>"""
    html = page_shell(
        "Mini-AIBOT 使用手册",
        "index.html",
        content,
        "",
        body_class="home",
        extra_scripts='<script src="assets/search-data.js"></script>\n',
    )
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)


if __name__ == "__main__":
    main()
