"""Checks that the website's text and logo match the app's.

The paragraphs of website/index.html marked with data-listing, and its
description for search engines, must match F-Droid's listing in fastlane/
word for word, and every page of the website
must draw the app icon's paths unchanged.
"""

import re
import sys
from html.parser import HTMLParser
from pathlib import Path

LISTING = Path("fastlane/metadata/android/en-US")
WEBSITE = Path("website")
ICON = Path("android/app/src/main/res/drawable/ic_launcher_foreground.xml")


class ListingText(HTMLParser):
    """Collects the text of the elements marked with data-listing."""

    def __init__(self):
        super().__init__()
        self.parts = {"title": [], "short": [], "full": []}
        self.current = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "meta" and attrs.get("name") == "description":
            self.parts["short"].append(attrs["content"])
        listing = attrs.get("data-listing")
        if listing:
            self.current = listing
            self.parts[listing].append("")

    def handle_endtag(self, tag):
        if tag in ("h1", "p"):
            self.current = None

    def handle_data(self, data):
        if self.current:
            self.parts[self.current][-1] += data


def normalize(text):
    return " ".join(text.split())


def paragraphs(text):
    return [normalize(p) for p in re.split(r"\n\s*\n", text.strip())]


def main():
    page = ListingText()
    page.feed((WEBSITE / "index.html").read_text())
    expected = {
        "title": paragraphs((LISTING / "title.txt").read_text()),
        "short": paragraphs((LISTING / "short_description.txt").read_text()) * 2,
        "full": paragraphs((LISTING / "full_description.txt").read_text()),
    }
    failed = False
    for name, want in expected.items():
        got = [normalize(p) for p in page.parts[name]]
        if got != want:
            failed = True
            print(f'website/index.html: data-listing="{name}" differs from '
                  f"the store listing:")
            for i, (g, w) in enumerate(zip(got, want), start=1):
                if g != w:
                    print(f"  paragraph {i}\n    website: {g}\n    listing: {w}")
            if len(got) != len(want):
                print(f"  {len(got)} paragraphs, the listing has {len(want)}")

    paths = re.findall(r'android:pathData="([^"]+)"', ICON.read_text())
    for page_file in sorted(WEBSITE.glob("*.svg")) + [WEBSITE / "index.html"]:
        text = page_file.read_text()
        for path in paths:
            if f'd="{path}"' not in text:
                failed = True
                print(f"{page_file}: doesn't draw the app icon's path {path}")

    sys.exit(1 if failed else 0)


main()
