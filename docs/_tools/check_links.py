"""Check every link in the built docs site. No dependencies beyond the standard library.

Usage: python docs/_tools/check_links.py [site_dir] [base_path]
       (defaults: site /prayog/)

Fails (exit 1) when, in any HTML file under site_dir:
- an internal href or src does not resolve to a file in site_dir, or
- a link points at a Markdown doc on GitHub (https://github.com/rj8228/prayog/blob/main/docs/...);
  docs must link to the site instead. The theme's "view source" button is the one exception.
"""

from __future__ import annotations

import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

GITHUB_DOCS = (
    "https://github.com/rj8228/prayog/blob/main/docs/",
    "https://github.com/rj8228/prayog/tree/main/docs/",
)
EXTERNAL = ("http:", "https:", "mailto:", "data:", "javascript:", "tel:", "blob:")


class Links(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, str, str]] = []  # (tag, url, class)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        for name in ("href", "src", "xlink:href"):
            if a.get(name) is not None:
                self.links.append((tag, a[name].strip(), a.get("class") or ""))


def resolve(site: Path, page: Path, url: str, base_path: str) -> Path | None:
    path = unquote(urlsplit(url).path)
    if not path:
        return page  # "#anchor" or "?query": same page
    if path.startswith("/"):
        if not path.startswith(base_path):
            return None
        target = site / path[len(base_path) :]
    else:
        target = page.parent / path
    if path.endswith("/") or target.is_dir():
        target = target / "index.html"
    return target


def main() -> int:
    site = Path(sys.argv[1] if len(sys.argv) > 1 else "site").resolve()
    base_path = sys.argv[2] if len(sys.argv) > 2 else "/prayog/"
    if not site.is_dir():
        print(f"no site directory at {site}; run mkdocs build first")
        return 1

    errors: list[str] = []
    pages = sorted(site.rglob("*.html"))
    checked = 0
    for page in pages:
        parser = Links()
        parser.feed(page.read_text(encoding="utf-8"))
        rel_page = page.relative_to(site)
        for tag, url, cls in parser.links:
            if url.startswith(GITHUB_DOCS):
                if "md-content__button" not in cls:
                    errors.append(f"{rel_page}: links a doc on GitHub instead of the site: {url}")
                continue
            if url.lower().startswith(EXTERNAL) or url.startswith("//"):
                continue
            checked += 1
            target = resolve(site, page, url, base_path)
            if target is None or not target.resolve().is_file():
                errors.append(f"{rel_page}: <{tag}> {url} does not resolve to a file in the site")
            elif site not in target.resolve().parents:
                errors.append(f"{rel_page}: <{tag}> {url} points outside the site")

    for e in errors:
        print(e)
    print(f"checked {checked} internal links in {len(pages)} HTML files: {len(errors)} problems")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
