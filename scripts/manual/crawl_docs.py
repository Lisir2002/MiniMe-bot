#!/usr/bin/env python3
"""Crawl the project's public documentation site.

The site URL is taken from the links already present in the repository
(SettingsScreen USER_GUIDE_DOCS_URL / docs-site config sitemap hostname).
Outputs raw HTML + a manifest that the offline manual builder consumes.

Usage: python3 scripts/manual/crawl_docs.py [output_dir]
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SITE = "https://aicode.murk.top"
USER_AGENT = "Mini-AIBOT-Docs-Crawler/1.0"
ASSET_EXT = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".css", ".js",
    ".json", ".xml", ".txt", ".pdf", ".zip", ".apk", ".woff", ".woff2", ".ttf",
    ".mp4", ".mp3",
}
SKIP_PREFIXES = ("/assets/", "/vp-icons", "/md/")


def fetch(url: str, timeout: int = 30) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
            return resp.status, data.decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, ""
    except Exception:  # network hiccup / timeout: record as failure, retry once
        try:
            time.sleep(1)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except Exception:
            return 0, ""


def normalize(url: str) -> str | None:
    """Return a site-local path (no query/fragment), or None if not crawlable."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return None
    if parsed.netloc != urllib.parse.urlparse(SITE).netloc:
        return None
    path = parsed.path or "/"
    path = re.sub(r"/{2,}", "/", path)
    if path.lower().endswith(tuple(ASSET_EXT)):
        return None
    if path.startswith(SKIP_PREFIXES):
        return None
    if path != "/" and path.endswith("/"):
        path = path.rstrip("/")
    return path


LINK_RE = re.compile(r"""href=["']([^"'#]+)(?:#[^"']*)?["']""")


def extract_links(html: str) -> list[str]:
    out = []
    for raw in LINK_RE.findall(html):
        if raw.startswith(("mailto:", "tel:", "javascript:")):
            continue
        absolute = urllib.parse.urljoin(SITE + "/", raw)
        path = normalize(absolute)
        if path:
            out.append(path)
    return out


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "manual-build"
    raw_dir = os.path.join(out_dir, "raw")
    os.makedirs(raw_dir, exist_ok=True)

    seeds: list[str] = ["/"]
    status, sitemap = fetch(SITE + "/sitemap.xml")
    if status == 200:
        seeds += re.findall(r"<loc>(.*?)</loc>", sitemap)

    queue = []
    seen: set[str] = set()
    for seed in seeds:
        path = normalize(seed)
        if path and path not in seen:
            seen.add(path)
            queue.append(path)

    results: dict[str, dict] = {}
    while queue:
        batch, queue = queue[:8], queue[8:]
        with ThreadPoolExecutor(max_workers=4) as pool:
            fetched = list(pool.map(lambda p: (p, fetch(SITE + p)), batch))
        for path, (code, html) in fetched:
            results[path] = {"status": code, "file": None, "links": []}
            if code == 200:
                safe = path.strip("/") or "index"
                filename = safe.replace("/", "__") + ".html"
                with open(os.path.join(raw_dir, filename), "w", encoding="utf-8") as f:
                    f.write(html)
                results[path]["file"] = filename
                links = extract_links(html)
                results[path]["links"] = links
                for link in links:
                    if link not in seen:
                        seen.add(link)
                        queue.append(link)
        time.sleep(0.05)

    ok = [p for p, r in results.items() if r["status"] == 200]
    failed = {p: r["status"] for p, r in results.items() if r["status"] != 200}
    manifest = {
        "site": SITE,
        "crawled_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "pages": results,
        "ok_count": len(ok),
        "failed_count": len(failed),
    }
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(f"crawled {len(results)} urls, ok={len(ok)}, failed={len(failed)}")
    for path, code in sorted(failed.items()):
        print(f"  [{code}] {path}")


if __name__ == "__main__":
    main()
