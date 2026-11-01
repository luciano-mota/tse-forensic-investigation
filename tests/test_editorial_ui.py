"""Stable-contract tests for the editorial forensic atlas shell."""
import re
import unittest
import json
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTML = (ROOT / "src/main/resources/static/index.html").read_text(encoding="utf-8")
CSS = (ROOT / "src/main/resources/static/experience.css").read_text(encoding="utf-8")
ICON = (ROOT / "src/main/resources/static/favicon.svg").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/tse-catalog-sync.yml").read_text(encoding="utf-8")
APP = (ROOT / "src/main/resources/static/app.js").read_text(encoding="utf-8")

class HTMLInspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.nav_links = []
        self.stylesheets = []
        self.main = 0
        self.asides = 0
    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "id" in values: self.ids.append(values["id"])
        if tag == "aside": self.asides += 1
        if tag == "main": self.main += 1
        if tag == "a" and "nav-item" in values.get("class", "").split():
            self.nav_links.append(values)
        if tag == "link" and values.get("rel") == "stylesheet":
            self.stylesheets.append(values.get("href"))

class EditorialExperienceTest(unittest.TestCase):
    def setUp(self):
        self.html = HTMLInspector()
        self.html.feed(HTML)

    def test_all_referenced_dom_ids_exist_exactly_once(self):
        keys = set(re.findall(r'\$\("([^"]+)"\)', APP))
        self.assertFalse(keys.difference(self.html.ids),
            "Missing DOM IDs: " + str(keys.difference(self.html.ids)))
        self.assertEqual(len(self.html.ids), len(set(self.html.ids)))

    def test_new_ui_has_no_sidebar_and_is_accessible(self):
        self.assertEqual(self.html.asides, 0)
        self.assertEqual(self.html.main, 1)
        self.assertIn("/experience.css", self.html.stylesheets)
        self.assertTrue(all(item.get("href", "").startswith("#")
                            for item in self.html.nav_links))
        for item in self.html.nav_links:
            target = item["href"][1:]
            self.assertIn(target, self.html.ids)
        self.assertIn('aria-label="Navegar pelas áreas da investigação"', HTML)
        self.assertIn("prefers-reduced-motion", CSS)

    def test_favicon_and_automatic_updates(self):
        self.assertIn('href="/favicon.svg?v=2"', HTML)
        self.assertIn('aria-label="TSE Forensic"', ICON)
        self.assertIn('#96e8df', ICON)
        self.assertIn('id="live-last-check"', HTML)
        self.assertIn('id="live-snapshot-time"', HTML)
        self.assertIn('id="live-update-status"', HTML)
        self.assertIn('setInterval(pollOfficialStatus,120000)', APP)
        self.assertIn('source.version!==state.liveAtlas?.derived_version', APP)
        self.assertIn('cron: "17 * * * *"', WORKFLOW)
        self.assertNotIn('6 horas UTC por GitHub Actions', HTML)

    def test_self_hosted_geography_and_explanation(self):
        source = ROOT / "src/main/resources/static"
        states = json.loads((source / "geo/estados.geojson").read_text(encoding="utf-8"))
        self.assertEqual(len(states["features"]), 27)
        codes = {f["properties"]["sigla"] for f in states["features"]}
        self.assertEqual(len(codes), 27)
        feature_count = 0
        for uf in codes:
            path = source / "geo" / (uf + ".geojson")
            self.assertTrue(path.exists(), "Missing municipality layer: " + uf)
            data = json.loads(path.read_text(encoding="utf-8"))
            feature_count += len(data["features"])
            self.assertTrue(all("nome" in feature.get("properties", {})
                                for feature in data["features"]))
        self.assertGreater(feature_count, 5500)
        self.assertIn('id="map-zoom-in"', HTML)
        self.assertIn('id="map-zoom-out"', HTML)
        self.assertIn('id="map-reset"', HTML)
        self.assertIn('id="analysis-content"', HTML)
        self.assertIn('id="analysis-status"', HTML)
        self.assertIn('import { createAtlasMap }', APP)
        self.assertNotIn('servicodados.ibge.gov.br/api/v3/malhas', APP)
        self.assertNotIn('cdn.jsdelivr.net/npm/d3-geo', APP)

    def test_features_are_in_reading_order_not_numbered_cards(self):
        position = {ident: HTML.index('id="' + ident + '"')
                    for ident in ("map", "comparison", "municipalities", "turnout",
                                  "biometrics", "archive", "sources", "methodology")}
        order = list(position)
        self.assertEqual([position[k] for k in order], sorted(position.values()))
        self.assertNotRegex(HTML, r'<div class="eyebrow">\s*0[1-9]\s*/')
        self.assertNotIn('class="sidebar"', HTML)
        self.assertNotIn('class="sidebar-eyebrow"', HTML)

if __name__ == "__main__":
    unittest.main()
