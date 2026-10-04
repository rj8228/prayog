"""MkDocs hook: make links inside the standalone HTML pages work on the site.

The interactive pages in docs/overview/*.html are copied to the site unchanged, so they still open
from disk with `open docs/overview/<name>.html`. Their links to Markdown files (for example
`../learnings/README.md`) only make sense on disk. After the build, this hook rewrites those links
in the copies under site/ (never the sources):

- a link to a Markdown page becomes the page's site URL (`../learnings/README.md` ->
`../learnings/`);
- a link that climbs out of docs/ (a repository file) becomes a github.com URL.
"""

from __future__ import annotations

import posixpath
import re
from pathlib import Path

REPO_BLOB = "https://github.com/rj8228/prayog/blob/main/"

# src_uri of every Markdown page -> its URL relative to the site root (for example "learnings/").
_page_urls: dict[str, str] = {}
# src_uri of every static .html file copied from docs/.
_static_html: list[tuple[str, str]] = []

_LINK = re.compile(r'(?P<attr>\b(?:href|src))="(?P<url>[^"#?]*)(?P<rest>[#?][^"]*)?"')


def on_files(files, config):
    _page_urls.clear()
    _static_html.clear()
    for f in files:
        if f.is_documentation_page():
            _page_urls[f.src_uri] = f.url
        elif f.src_uri.endswith(".html"):
            _static_html.append((f.src_uri, f.dest_uri))
    return files


def _rewrite(src_uri: str, dest_uri: str, url: str) -> str | None:
    """Return the site URL for a link found in a static HTML page, or None to leave it alone."""
    if not url or "://" in url or url.startswith(("/", "mailto:", "data:", "javascript:")):
        return None
    target = posixpath.normpath(posixpath.join(posixpath.dirname(src_uri), url))
    if target.startswith("../"):
        # Outside docs/: a repository file such as ../../services/x.py.
        return REPO_BLOB + target[3:]
    if target in _page_urls:
        page_url = _page_urls[target] or "./"
        rel = posixpath.relpath(page_url, posixpath.dirname(dest_uri) or ".")
        return rel + ("/" if page_url.endswith("/") and not rel.endswith("/") else "")
    return None


def on_post_build(config):
    site = Path(config["site_dir"])
    for src_uri, dest_uri in _static_html:
        path = site / dest_uri
        text = path.read_text(encoding="utf-8")

        def repl(m: re.Match, src_uri=src_uri, dest_uri=dest_uri) -> str:
            new = _rewrite(src_uri, dest_uri, m.group("url"))
            if new is None:
                return m.group(0)
            return f'{m.group("attr")}="{new}{m.group("rest") or ""}"'

        new_text = _LINK.sub(repl, text)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
