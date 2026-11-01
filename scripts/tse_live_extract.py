#!/usr/bin/env python3
"""Derive a small, versioned presidential atlas directly from public TSE originals.

Original ZIP files are TEMPORARY only; nothing is uploaded to object storage.
Always record original SHA-256, metadata, observation time and parse coverage.
Uses the two national municipality/zone CSV resources from the official CKAN index.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import os
import re
import shutil
import tempfile
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from scripts.tse_sync import safe_http_url, request, canonical, digest

BASE = Path(__file__).resolve().parent.parent
MAX_COMPRESSED = 300 * 1024 * 1024
MAX_UNCOMPRESSED = 2_500 * 1024 * 1024 * 1024
MAX_CSV_ROWS = 15_000_000
OFFICIAL_FILES = {
    "votes": "/votacao_candidato_munzona/votacao_candidato_munzona_2026.zip",
    "turnout": "/detalhe_votacao_munzona/detalhe_votacao_munzona_2026.zip",
}
UF_SET = set("AC AL AM AP BA CE DF ES GO MA MG MS MT PA PB PE PI PR RJ RN RO RR RS SC SE SP TO ZZ".split())


def find_resources(index: dict) -> dict:
    found = {}
    for entry in index.get("resources", {}).values():
        url = entry.get("source_url", "")
        for kind, suffix in OFFICIAL_FILES.items():
            if urlsplit(url).path.endswith(suffix) and entry.get("dataset") == "resultados-2026":
                safe_http_url(url)
                if kind in found:
                    raise ValueError("Multiple CKAN entries for " + kind)
                found[kind] = {"url": url, "id": entry["resource_id"],
                               "metadata_sha256": entry.get("metadata_sha256", "")}
    if set(found) != set(OFFICIAL_FILES):
        raise ValueError("Missing official TSE municipality-zone dataset resources")
    return found


def download(source: dict, tmp: Path) -> dict:
    """Stream in bounded chunks; verify ZIP and only keep file in ephemeral temp."""
    h = hashlib.sha256()
    total = 0
    with request(source["url"], timeout=110) as stream, tmp.open("wb") as out:
        declared = stream.headers.get("Content-Length")
        if declared and int(declared) > MAX_COMPRESSED:
            raise ValueError("Official ZIP exceeds configured size limit")
        etag = stream.headers.get("ETag")
        modified = stream.headers.get("Last-Modified")
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > MAX_COMPRESSED:
                raise ValueError("Official ZIP exceeds maximum download size")
            h.update(block)
            out.write(block)
        if declared and total != int(declared):
            raise ValueError("Content-Length mismatch")
    if not zipfile.is_zipfile(tmp):
        raise ValueError("TSE resource is not a ZIP")
    return {**source, "sha256": h.hexdigest(), "size": total,
            "etag": etag, "last_modified": modified,
            "downloaded_at": datetime.now(timezone.utc).isoformat()}


def first(record: dict, *names: str) -> str:
    for name in names:
        v = record.get(name, "")
        if v is not None and str(v).strip():
            return str(v).strip()
    return ""


def amount(record: dict, *names: str) -> int:
    raw = first(record, *names)
    if not raw or not raw.isdigit():
        raise ValueError("Invalid or missing numeric column " + "/".join(names))
    return int(raw)


def text(record: dict, *names: str) -> str:
    result = first(record, *names)
    if not result:
        raise ValueError("Missing required column " + "/".join(names))
    return result


def president(record: dict) -> bool:
    cargo = first(record, "CD_CARGO")
    description = first(record, "DS_CARGO").upper()
    return cargo == "1" or description == "PRESIDENTE"


def parse_zip(path: Path, kind: str, records: dict, stats: dict):
    with zipfile.ZipFile(path) as container:
        candidates = [i for i in container.infolist()
                      if i.filename.lower().endswith(".csv") and not i.is_dir()]
        if not candidates:
            raise ValueError("Official ZIP contains no CSV")
        if sum(i.file_size for i in candidates) > MAX_UNCOMPRESSED:
            raise ValueError("Uncompressed content exceeds safety cap")
        for entry in candidates:
            with container.open(entry, "r") as raw:
                reader = csv.DictReader(io.TextIOWrapper(raw, encoding="iso-8859-1",
                                                           errors="strict", newline=""),
                                        delimiter=";")
                headers = set(reader.fieldnames or [])
                obligatory = {"NR_TURNO", "SG_UF", "CD_MUNICIPIO"}
                if not obligatory.issubset(headers):
                    raise ValueError("Missing key columns: " + str(obligatory - headers))
                if "CD_CARGO" not in headers and "DS_CARGO" not in headers:
                    raise ValueError("Official file lacks office filter")
                for row in reader:
                    stats["lines_read"] += 1
                    if stats["lines_read"] > MAX_CSV_ROWS:
                        raise ValueError("File contains too many CSV lines")
                    if not president(row):
                        continue
                    round_n = amount(row, "NR_TURNO")
                    if round_n not in (1, 2):
                        continue
                    uf = text(row, "SG_UF").upper()
                    if uf not in UF_SET:
                        raise ValueError("Unexpected UF " + uf)
                    municipal_code = str(amount(row, "CD_MUNICIPIO"))
                    city_name = text(row, "NM_MUNICIPIO")
                    zone = amount(row, "NR_ZONA")
                    if zone > 9999:
                        raise ValueError("Invalid zone number")
                    # Retain zone in raw key to allow exact deduplication across ZIP members.
                    key = (round_n, uf, municipal_code, zone)
                    if kind == "votes":
                        number = amount(row, "NR_CANDIDATO", "NR_VOTAVEL")
                        count = amount(row, "QT_VOTOS_NOMINAIS", "QT_VOTOS")
                        if number in (95, 96):
                            continue
                        g = records.setdefault((round_n, uf, municipal_code),
                                               {"name": city_name, "candidates": {}})
                        # Some files contain one row per candidate and zone;
                        # reject the same zone/candidate repeated in the same ZIP.
                        row_key = key + (number,)
                        if row_key in stats["seen"]:
                            raise ValueError("Duplicate presidential candidate/zone row: " + str(row_key))
                        stats["seen"].add(row_key)
                        g["candidates"][str(number)] = g["candidates"].get(str(number), 0) + count
                        stats["president_rows"] += 1
                    elif kind == "turnout":
                        apt = amount(row, "QT_APTOS")
                        turnout = amount(row, "QT_COMPARECIMENTO")
                        absent = amount(row, "QT_ABSTENCOES")
                        if turnout + absent != apt:
                            stats["arithmetic_mismatches"] += 1
                        if key in stats["seen"]:
                            raise ValueError("Duplicate presidential turnout zone row: " + str(key))
                        stats["seen"].add(key)
                        g = records.setdefault((round_n, uf, municipal_code),
                                               {"name": city_name, "registered": 0,
                                                "turnout": 0, "abstentions": 0})
                        for field, n in (("registered", apt), ("turnout", turnout),
                                         ("abstentions", absent)):
                            g[field] += n
                        stats["president_rows"] += 1
                    else:
                        raise ValueError("Unknown source kind")


def build_atlas(votes: dict, turnout: dict, sources: dict, *, observed: str) -> dict:
    # Each document is an aggregate and must never claim to reflect voter identities.
    rounds = {}
    coverage = {}
    for turn in (1, 2):
        states = {}
        for (r, uf, code), rec in votes.items():
            if r != turn:
                continue
            if rec["name"] == "":
                raise ValueError("Blank municipality")
            state = states.setdefault(uf, {"candidates": {}, "municipalities": {}})
            state["municipalities"][code] = {
                "code": code, "name": rec["name"],
                "candidates": rec["candidates"],
            }
            for number, count in rec["candidates"].items():
                state["candidates"][number] = state["candidates"].get(number, 0) + count
        for (r, uf, code), rec in turnout.items():
            if r != turn:
                continue
            state = states.setdefault(uf, {"candidates": {}, "municipalities": {}})
            municipal = state["municipalities"].setdefault(code, {"code": code, "name": rec["name"],
                                                                   "candidates": {}})
            municipal["turnout"] = {k: rec[k] for k in ("registered", "turnout", "abstentions")}
        rounds[str(turn)] = states
        coverage[str(turn)] = {
            "states_with_votes": sum(bool(s["candidates"]) for s in states.values()),
            "municipalities_with_votes": sum(
                sum(bool(m["candidates"]) for m in s["municipalities"].values())
                for s in states.values()),
            "states_with_turnout": sum(any("turnout" in m for m in s["municipalities"].values())
                                      for s in states.values())
        }
    if all(not data["states_with_votes"] and not data["states_with_turnout"]
           for data in coverage.values()):
        raise ValueError("No 2026 presidential records parsed")
    return {"schema": 1, "election_year": 2026, "office": "PRESIDENTE",
            "observed_at": observed,
            "status": "DERIVED_FROM_OFFICIAL_FILES_NOT_ORIGINAL_ARCHIVE",
            "limitation": "Aggregated from TSE CSV/ZIP; originals are not archived here. "
                          "Differences alone do not demonstrate electoral manipulation.",
            "sources": sources, "coverage": coverage, "rounds": rounds}


def run(history: Path, *, fetch=download) -> dict:
    index_file = history / "archive/index.json"
    if not index_file.is_file():
        raise ValueError("Catalog index absent. Run CKAN discovery first.")
    index = json.loads(index_file.read_text(encoding="utf-8"))
    sources = find_resources(index)
    previous_path = history / "archive/derived/current.json"
    if previous_path.is_file():
        previous = json.loads(previous_path.read_text(encoding="utf-8"))
        if all(previous.get("sources", {}).get(k, {}).get("metadata_sha256") ==
               source["metadata_sha256"] for k, source in sources.items()):
            # Metadata does not guarantee bytes unchanged; ETag requests MUST
            # still happen on each cycle to capture silent CDN republishing.
            pass
    votes, turnout = {}, {}
    stats = {}
    with tempfile.TemporaryDirectory(prefix="tse-temporary-") as td:
        for kind in ("turnout", "votes"):
            part = Path(td) / (kind + ".zip")
            sources[kind] = fetch(sources[kind], part)
            counters = {"lines_read": 0, "president_rows": 0,
                        "arithmetic_mismatches": 0, "seen": set()}
            parse_zip(part, kind, turnout if kind == "turnout" else votes, counters)
            if counters["president_rows"] <= 0:
                raise ValueError("No President data for source " + kind)
            stats[kind] = {k: v for k, v in counters.items() if k != "seen"}
    observed = datetime.now(timezone.utc).isoformat()
    atlas = build_atlas(votes, turnout, sources, observed=observed)
    atlas["parse_stats"] = stats
    serialized = canonical(atlas)
    # Do not create duplicate data versions if only the observation time changed.
    sources_hash = digest(canonical({k: v["sha256"] for k, v in sources.items()}))
    atlas["derived_version"] = sources_hash
    dest = history / "archive/derived/versions" / sources_hash / "atlas.json"
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(atlas, ensure_ascii=False, separators=(",", ":")) + "\n",
                        encoding="utf-8")
    history_file = history / "archive/derived/history.json"
    old_history = json.loads(history_file.read_text(encoding="utf-8")) if history_file.exists() else {"versions": []}
    if all(x.get("derived_version") != sources_hash for x in old_history["versions"]):
        old_history["versions"].append({
            "derived_version": sources_hash, "observed_at": observed,
            "source_sha256": {k: v["sha256"] for k, v in sources.items()},
            "coverage": atlas["coverage"]})
        history_file.parent.mkdir(parents=True, exist_ok=True)
        history_file.write_text(json.dumps(old_history, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8")
    current = {"status": atlas["status"], "observed_at": observed,
               "derived_version": sources_hash, "data_path": str(dest.relative_to(history)),
               "sources": sources, "coverage": atlas["coverage"], "parse_stats": stats}
    previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.exists() else {}
    # Existing snapshot references remain immutable; pointer may update.
    if previous.get("derived_version") != sources_hash:
        previous_path.parent.mkdir(parents=True, exist_ok=True)
        previous_path.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
    return current


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--history-dir", default="history")
    args = cli.parse_args()
    result = run(Path(args.history_dir))
    print(json.dumps({k: v for k, v in result.items() if k not in ("sources",)},
                     ensure_ascii=False))
