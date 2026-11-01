import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.tse_sync import (
    DATASETS, canonical, catalog_packages, digest, safe_http_url, sync,
)


class TSEArchiveSyncTest(unittest.TestCase):
    def fixtures(self):
        return [{
            "id": "resultados-2026",
            "title": "Resultados",
            "metadata_modified": "2026-10-28",
            "resources": [{
                "id": "test-official-resource-uuid",
                "dataset": "resultados-2026",
                "url": "https://cdn.tse.jus.br/eleicoes/test.csv",
                "name": "Presidente - Votação por seção",
                "format": "CSV",
                "size": 1024,
                "created": "2026-10-26",
                "last_modified": "2026-10-27",
                "metadata_modified": "2026-10-27",
                "hash": "",
                "mimetype": "text/csv"
            }]
        }]

    def test_discovery_creates_immutable_snapshot_only_when_changed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first = sync(self.fixtures(), now="2026-11-01T09:00:00Z", archive_dir=root)
            self.assertEqual(first["versions_discovered"], 1)
            first_index = json.loads((root / "index.json").read_text())
            snapshot = root / first_index["snapshots"][0]["snapshot"]
            before = snapshot.read_bytes()
            second = sync(self.fixtures(), now="2026-11-01T15:00:00Z", archive_dir=root)
            self.assertEqual(second["versions_discovered"], 0)
            self.assertEqual(snapshot.read_bytes(), before)
            self.assertEqual(len(json.loads((root / "index.json").read_text())["snapshots"]), 1)
            self.assertEqual(len(list((root / "polls").rglob("*.json"))), 2)

    def test_metadata_change_keeps_previous_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sync(self.fixtures(), now="2026-11-01T09:00:00Z", archive_dir=root)
            changed = self.fixtures()
            changed[0]["resources"][0]["last_modified"] = "2026-11-02"
            sync(changed, now="2026-11-02T09:00:00Z", archive_dir=root)
            index = json.loads((root / "index.json").read_text())
            self.assertEqual(len(index["snapshots"]), 2)
            self.assertNotEqual(index["snapshots"][0]["snapshot"], index["snapshots"][1]["snapshot"])
            for item in index["snapshots"]:
                self.assertTrue((root / item["snapshot"]).exists())

    def test_catalog_uses_only_three_curated_official_datasets(self):
        def fake_get(url):
            dataset = url.split("=")[-1]
            return {"success": True, "result": {"title": dataset, "resources": []}}
        result = catalog_packages(reader=fake_get)
        self.assertEqual([item["id"] for item in result], list(DATASETS))

    def test_only_https_official_domains_are_downloadable(self):
        self.assertEqual(safe_http_url("https://cdn.tse.jus.br/foo.zip"),
                         "https://cdn.tse.jus.br/foo.zip")
        for url in ("http://cdn.tse.jus.br/data.zip",
                    "https://attacker.example/foo.zip",
                    "https://cdn.tse.jus.br.evil.example/foo.zip",
                    "https://localhost/secret"):
            with self.assertRaises(ValueError):
                safe_http_url(url)

    def test_byte_identical_json_has_stable_sha(self):
        self.assertEqual(digest(canonical({"a": 1, "b": 2})),
                         digest(canonical({"b": 2, "a": 1})))


if __name__ == "__main__":
    unittest.main()
