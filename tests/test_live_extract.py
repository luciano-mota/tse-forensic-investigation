import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.tse_live_extract import (
    build_atlas, find_resources, parse_zip, president, OFFICIAL_FILES,
    MAX_COMPRESSED, unchanged_official_sources,
)

class DerivedAtlasTest(unittest.TestCase):
    def test_filter_cargo(self):
        self.assertTrue(president({"CD_CARGO": "1", "DS_CARGO": "Presidente"}))
        self.assertFalse(president({"CD_CARGO": "6", "DS_CARGO": "DEPUTADO FEDERAL"}))

    def test_only_official_catalog_sources(self):
        rows = {}
        for i, (kind, suffix) in enumerate(OFFICIAL_FILES.items()):
            rows[str(i)] = {"resource_id": str(i), "dataset": "resultados-2026",
                            "source_url": "https://cdn.tse.jus.br/estatistica/sead/odsele" + suffix,
                            "metadata_sha256": "a" * 64}
        output = find_resources({"resources": rows})
        self.assertEqual(set(output), {"votes", "turnout"})
        rows["0"]["source_url"] = "https://attacker.example/" + OFFICIAL_FILES["votes"]
        with self.assertRaises(ValueError):
            find_resources({"resources": rows})

    def test_streamed_zip_yields_two_distinct_presidential_rounds(self):
        header = "NR_TURNO;SG_UF;CD_MUNICIPIO;NM_MUNICIPIO;NR_ZONA;CD_CARGO;DS_CARGO"
        sample_vote = header + ";NR_CANDIDATO;QT_VOTOS_NOMINAIS\n" + "\n".join([
            "1;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;13;101",
            "1;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;22;73",
            "2;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;13;123",
            "2;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;22;97",
            "2;MA;12000;CIDADE TESTE;1;6;DEPUTADO FEDERAL;22;999",
        ]) + "\n"
        sample_turnout = header + ";QT_APTOS;QT_COMPARECIMENTO;QT_ABSTENCOES\n" + "\n".join([
            "1;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;300;220;80",
            "2;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;300;250;50",
        ]) + "\n"
        with tempfile.TemporaryDirectory() as d:
            files = {}
            for kind, data in (("votes", sample_vote), ("turnout", sample_turnout)):
                path = Path(d) / (kind + ".zip")
                with zipfile.ZipFile(path, "w") as zipped:
                    zipped.writestr(kind + "_BRASIL.csv", data.encode("iso-8859-1"))
                files[kind] = path
            votes, turnout = {}, {}
            for kind, target in (("votes", votes), ("turnout", turnout)):
                stats = {"lines_read": 0, "president_rows": 0, "arithmetic_mismatches": 0,
                         "seen": set()}
                parse_zip(files[kind], kind, target, stats)
                self.assertEqual(stats["president_rows"], 4 if kind == "votes" else 2)
            atlas = build_atlas(votes, turnout, {}, observed="2026-11-01T04:00:00Z")
            self.assertEqual(atlas["rounds"]["1"]["MA"]["candidates"]["13"], 101)
            self.assertEqual(atlas["rounds"]["2"]["MA"]["candidates"]["22"], 97)
            self.assertEqual(atlas["rounds"]["2"]["MA"]["municipalities"]["12000"]["turnout"]["abstentions"],50)
            self.assertEqual(atlas["coverage"]["2"]["states_with_votes"],1)

    def test_official_national_zip_can_exceed_previous_300mb_limit(self):
        self.assertGreater(MAX_COMPRESSED, 455_236_941)

    def test_prefers_brazil_presidential_csv_and_ignores_state_duplicates(self):
        columns = ("NR_TURNO;SG_UF;CD_MUNICIPIO;NM_MUNICIPIO;NR_ZONA;"
                   "CD_CARGO;DS_CARGO;NR_CANDIDATO;QT_VOTOS_NOMINAIS\n")
        row = "2;MA;12000;CIDADE TESTE;1;1;PRESIDENTE;13;100\n"
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "votes.zip"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("votacao_candidato_munzona_2026_BR.csv", columns + row)
                z.writestr("votacao_candidato_munzona_2026_BRASIL.csv", columns + row)
                z.writestr("votacao_candidato_munzona_2026_MA.csv", columns + row)
            stats = {"lines_read": 0, "president_rows": 0, "arithmetic_mismatches": 0,
                     "seen": set()}
            rows = {}
            parse_zip(path, "votes", rows, stats)
            self.assertEqual(stats["lines_read"], 1)
            self.assertEqual(rows[(2, "MA", "12000")]["candidates"]["13"], 100)
            self.assertTrue(stats["csv_member"].endswith("_BR.csv"))

    def test_remote_head_equal_etag_can_skip_re_download(self):
        from unittest.mock import Mock
        from email.message import Message
        headers = Message()
        headers.add_header("ETag", '"abc"')
        headers.add_header("Last-Modified", "Thu, 29 Oct 2026 00:00:00 GMT")
        response = Mock(status=200, headers=headers)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        sources = {"votes": {"url":"https://cdn.tse.jus.br/x.zip","metadata_sha256":"f"*64}}
        previous = {"sources":{"votes":{"sha256":"a"*64,"metadata_sha256":"f"*64,
                                         "etag":'"abc"', "last_modified":headers["Last-Modified"]}}}
        with patch("scripts.tse_live_extract.request",return_value=response) as caller:
            self.assertTrue(unchanged_official_sources(sources, previous))
            caller.assert_called_once()
        with patch("scripts.tse_live_extract.request",side_effect=OSError("offline")):
            self.assertFalse(unchanged_official_sources(sources,previous))

    def test_reject_duplicate_and_corrupt_aptos(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "t.zip"
            s = ("NR_TURNO;SG_UF;CD_MUNICIPIO;NM_MUNICIPIO;NR_ZONA;CD_CARGO;QT_APTOS;QT_COMPARECIMENTO;QT_ABSTENCOES\n"
                 "2;MA;1;CITY;1;1;100;90;10\n2;MA;1;CITY;1;1;100;90;10\n")
            with zipfile.ZipFile(path,"w") as z: z.writestr("detail.csv",s)
            stats = {"lines_read":0,"president_rows":0,"arithmetic_mismatches":0,"seen":set()}
            with self.assertRaisesRegex(ValueError,"Duplicate"):
                parse_zip(path,"turnout",{},stats)

if __name__ == "__main__":
    unittest.main()
