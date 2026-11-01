import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.tse_live_extract import (
    build_atlas, find_resources, parse_zip, president, OFFICIAL_FILES,
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
