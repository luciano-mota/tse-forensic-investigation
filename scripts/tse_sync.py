#!/usr/bin/env python3
"""Monitor the TSE public CKAN catalog and preserve immutable content snapshots.

No credential is necessary for catalog-only observations. Original binary preservation
requires a separate, private S3-compatible object store. This script NEVER deletes a
previous snapshot and NEVER rewrites a content-addressed original.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen

DATASETS = (
    "resultados-2026",
    "resultados-2026-boletim-de-urna",
    "resultados-2026-arquivos-transmitidos-para-totalizacao",
)
API_ROOT = "https://dadosabertos.tse.jus.br/api/3/action/package_show?id="
ALLOWED_HOSTS = frozenset({
    "dadosabertos.tse.jus.br", "cdn.tse.jus.br", "resultados.tse.jus.br"
})
ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = ROOT / "archive"
USER_AGENT = "TSE-Forensic-Investigation/0.3 (+public catalog research; respectful polling)"
RETRY_CODES = frozenset({429, 500, 502, 503, 504})


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def canonical(obj: object) -> bytes:
    return json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_http_url(value: str) -> str:
    parsed = urlparse(value)
    if (parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise ValueError("Rejected non-official or unsafe resource URL")
    return value


class NoRedirect:
    """Reject redirects rather than trusting arbitrary redirect destinations."""

    @staticmethod
    def open(request: Request, timeout: int = 30):
        import urllib.request
        opener = urllib.request.build_opener(_RejectRedirects())
        return opener.open(request, timeout=timeout)


class _RejectRedirects(__import__("urllib.request", fromlist=["HTTPRedirectHandler"]).HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Official resource redirected; manual review required: " + newurl[:100])


def request(url: str, *, method: str = "GET", headers: dict | None = None, timeout: int = 40):
    safe_http_url(url)
    outgoing = {"User-Agent": USER_AGENT, "Accept": "*/*"}
    outgoing.update(headers or {})
    req = Request(url, headers=outgoing, method=method)
    for attempt in range(3):
        try:
            return NoRedirect.open(req, timeout=timeout)
        except HTTPError as ex:
            if ex.code not in RETRY_CODES or attempt == 2:
                raise
            time.sleep(min(2 ** attempt, 4))
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(min(2 ** attempt, 4))
    raise RuntimeError("Exhausted retries")


def read_json(url: str) -> dict:
    with request(url, timeout=50) as response:
        content = response.read(12_000_001)
        if len(content) > 12_000_000:
            raise ValueError("Catalog API response exceeds configured maximum")
    return json.loads(content)


def metadata(resource: dict) -> dict:
    return {
        "id": str(resource.get("id") or ""),
        "name": str(resource.get("name") or ""),
        "url": str(resource.get("url") or ""),
        "format": str(resource.get("format") or ""),
        "size": resource.get("size"),
        "created": resource.get("created"),
        "last_modified": resource.get("last_modified"),
        "metadata_modified": resource.get("metadata_modified"),
        "hash": resource.get("hash"),
        "mimetype": resource.get("mimetype"),
    }


def catalog_packages(reader=read_json) -> list[dict]:
    packages = []
    for dataset in DATASETS:
        result = reader(API_ROOT + quote(dataset))
        if not result.get("success") or not isinstance(result.get("result"), dict):
            raise ValueError("Unsuccessful CKAN response for " + dataset)
        package = result["result"]
        resources = package.get("resources")
        if not isinstance(resources, list):
            raise ValueError("Resources not provided for " + dataset)
        packages.append({
            "id": dataset,
            "title": str(package.get("title") or dataset),
            "metadata_modified": package.get("metadata_modified"),
            "resources": [metadata(item) for item in resources if isinstance(item, dict)],
        })
    return packages


def storage_client():
    keys = ("ARCHIVE_BUCKET", "ARCHIVE_ENDPOINT", "ARCHIVE_ACCESS_KEY_ID",
            "ARCHIVE_SECRET_ACCESS_KEY")
    if not all(os.getenv(key) for key in keys):
        return None
    endpoint = os.environ["ARCHIVE_ENDPOINT"]
    parsed = urlparse(endpoint)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("ARCHIVE_ENDPOINT must be a valid HTTPS endpoint")
    import boto3
    from botocore.config import Config
    return boto3.client(
        "s3", endpoint_url=endpoint,
        region_name=os.getenv("ARCHIVE_REGION", "auto"),
        aws_access_key_id=os.environ["ARCHIVE_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["ARCHIVE_SECRET_ACCESS_KEY"],
        config=Config(signature_version="s3v4", retries={"max_attempts": 3}),
    )


def official_sha512(resource: dict, companion_urls: dict[str, str]) -> str | None:
    sha_file = companion_urls.get(resource.get("url", "") + ".sha512")
    if not sha_file:
        return None
    with request(sha_file) as response:
        raw = response.read(8192).decode("ascii", errors="replace")
    found = re.search(r"\b([a-fA-F0-9]{128})\b", raw)
    if not found:
        raise ValueError("Official SHA-512 file had no valid digest")
    return found.group(1).lower()


def save_original(client, resource: dict, previous: dict | None,
                  companion_urls: dict[str, str], max_bytes: int) -> dict:
    url = safe_http_url(resource["url"])
    from botocore.exceptions import ClientError
    fmt = resource.get("format", "").lower()
    if fmt not in {"csv", "zip", "json", "txt", "sha512"}:
        return {"status": "unsupported_format"}
    if urlparse(url).path.lower().endswith(".sha512"):
        return {"status": "checksum_companion"}
    if previous and previous.get("status") == "stored":
        conditional = {}
        if previous.get("etag"):
            conditional["If-None-Match"] = previous["etag"]
        if previous.get("last_modified"):
            conditional["If-Modified-Since"] = previous["last_modified"]
    else:
        conditional = {}
    try:
        stream = request(url, headers=conditional, timeout=75)
    except HTTPError as ex:
        if ex.code == 304 and previous:
            return dict(previous)
        raise
    downloaded = 0
    sha256 = hashlib.sha256()
    sha512 = hashlib.sha512()
    with stream:
        etag = stream.headers.get("ETag")
        modified = stream.headers.get("Last-Modified")
        expected_length = stream.headers.get("Content-Length")
        if expected_length and int(expected_length) > max_bytes:
            return {"status": "size_limit", "content_length": int(expected_length)}
        with tempfile.NamedTemporaryFile(prefix="tse-", suffix=".blob") as tmp:
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    break
                downloaded += len(chunk)
                if downloaded > max_bytes:
                    return {"status": "size_limit", "content_length": downloaded}
                sha256.update(chunk)
                sha512.update(chunk)
                tmp.write(chunk)
            if expected_length and int(expected_length) != downloaded:
                raise ValueError("Downloaded length mismatches Content-Length")
            verified = official_sha512(resource, companion_urls)
            if verified and verified != sha512.hexdigest():
                raise ValueError("SHA-512 of original DOES NOT MATCH official companion")
            content_hash = sha256.hexdigest()
            filename = Path(urlparse(url).path).name
            safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", filename)[:160] or "file.bin"
            # Content-addressed object name; previous versions are never overwritten.
            key = f"tse/2026/{resource['dataset']}/{resource['id']}/{content_hash}/{safe_name}"
            tmp.flush()
            tmp.seek(0)
            bucket = os.environ["ARCHIVE_BUCKET"]
            # Check existing object by exact immutable key to make retries safe.
            try:
                client.head_object(Bucket=bucket, Key=key)
            except ClientError as ex:
                if ex.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 404:
                    raise
                client.upload_fileobj(tmp, bucket, key, ExtraArgs={
                    "ContentType": "application/octet-stream",
                    "Metadata": {"sha256": content_hash, "source": "tse-public"},
                })
    return {"status": "stored", "object_key": key, "sha256": content_hash,
            "sha512": sha512.hexdigest(), "official_sha512_verified": verified is not None,
            "size": downloaded, "etag": etag, "last_modified": modified}


def sync(packages: list[dict], *, now: str, archive_dir: Path, client=None,
         max_files: int = 4, max_bytes: int = 250 * 1024 * 1024) -> dict:
    archive_dir.mkdir(parents=True, exist_ok=True)
    index_path = archive_dir / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {
        "schema": 1, "resources": {}, "snapshots": [], "poll_count": 0
    }
    index.setdefault("resources", {})
    index.setdefault("snapshots", [])
    discoveries = []
    downloads = 0
    failures = []
    all_resources = []
    for package in packages:
        for resource in package["resources"]:
            if not resource["id"] or not resource["url"]:
                continue
            all_resources.append({**resource, "dataset": package["id"]})
    official_companions = {
        r["url"]: r["url"] for r in all_resources
        if r["url"].lower().endswith(".sha512")
    }
    # Deterministic priority: small files, presidential section files, BWEB, other packages.
    def rank(resource):
        name = (resource.get("name", "") + " " + resource.get("url", "")).lower()
        special = 0 if any(word in name for word in ("presidente", "votacao_secao", "bweb_")) else 1
        size = resource.get("size")
        return (special, int(size) if isinstance(size, int) else max_bytes, resource["id"])
    for resource in sorted(all_resources, key=rank):
        resource_id = resource["id"]
        current = index["resources"].get(resource_id, {})
        try:
            safe_http_url(resource["url"])
            trusted_source = True
        except ValueError:
            trusted_source = False
        fingerprint = digest(canonical(resource))
        new = fingerprint != current.get("metadata_sha256")
        capture = None
        previous = current.get("latest_original")
        if client and trusted_source and downloads < max_files:
            try:
                capture = save_original(client, resource, previous, official_companions, max_bytes)
                if capture.get("status") == "stored" and (
                        not previous or previous.get("sha256") != capture.get("sha256")):
                    downloads += 1
            except Exception as exc:
                capture = {"status": "error", "message": str(exc)[:200]}
                failures.append({"resource_id": resource_id, "message": str(exc)[:200]})
        if new or (capture and capture.get("status") == "stored"
                   and (not previous or previous.get("sha256") != capture["sha256"])):
            version = {
                "observed_at": now,
                "resource_id": resource_id,
                "dataset": resource["dataset"],
                "metadata_sha256": fingerprint,
                "resource": resource,
            }
            if capture and capture.get("status") == "stored":
                version["original"] = capture
            unique = digest(canonical(version))
            version_path = f"snapshots/{resource['dataset']}/{resource_id}/{unique}.json"
            dest = archive_dir / version_path
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.exists():
                dest.write_text(json.dumps(version, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8")
            record = {"observed_at": now, "resource_id": resource_id,
                      "dataset": resource["dataset"], "snapshot": version_path,
                      "metadata_sha256": fingerprint,
                      "name": resource["name"], "source_url": resource["url"]}
            if "original" in version:
                record["original"] = version["original"]
            index["snapshots"].append(record)
            discoveries.append({"id": resource_id, "new_metadata": new,
                                "stored_original": "original" in version})
        current.update({
            "resource_id": resource_id, "dataset": resource["dataset"],
            "name": resource["name"], "source_url": resource["url"],
            "metadata_sha256": fingerprint, "last_checked_at": now,
        })
        if not current.get("first_seen_at"):
            current["first_seen_at"] = now
        if capture and capture.get("status") == "stored":
            current["latest_original"] = capture
        if capture:
            current["last_capture_status"] = capture["status"]
        else:
            current["last_capture_status"] = ("unsafe_source" if not trusted_source else
                                              "not_configured" if client is None else "deferred")
        index["resources"][resource_id] = current
    index["last_polled_at"] = now
    index["poll_count"] = index.get("poll_count", 0) + 1
    index["catalog_datasets"] = [
        {"id": p["id"], "title": p["title"], "metadata_modified": p.get("metadata_modified"),
         "resource_count": len(p["resources"])}
        for p in packages
    ]
    index["archived_original_count"] = sum(
        1 for v in index["snapshots"] if v.get("original", {}).get("status") == "stored")
    index["version_count"] = len(index["snapshots"])
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    date = now[:10].replace("-", "/")
    moment = now[11:19].replace(":", "")
    poll_path = archive_dir / "polls" / date / (moment + ".json")
    poll_path.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "polled_at": now, "dataset_count": len(packages),
        "resources_seen": len(all_resources), "versions_discovered": len(discoveries),
        "originals_uploaded": downloads, "storage_enabled": client is not None,
        "changes": discoveries, "errors": failures,
    }
    poll_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive-dir", default=str(ARCHIVE))
    parser.add_argument("--max-files", type=int, default=4)
    parser.add_argument("--max-file-mb", type=int, default=250)
    args = parser.parse_args()
    if not 1 <= args.max_files <= 20 or not 1 <= args.max_file_mb <= 2048:
        raise ValueError("Unsafe archive limits")
    try:
        packages = catalog_packages()
        summary = sync(
            packages, now=timestamp(), archive_dir=Path(args.archive_dir),
            client=storage_client(), max_files=args.max_files,
            max_bytes=args.max_file_mb * 1024 * 1024,
        )
        print(json.dumps({key: value for key, value in summary.items() if key != "changes"},
                         ensure_ascii=False))
        return 1 if summary["errors"] else 0
    except Exception as exc:
        print("TSE catalog sync failed: " + str(exc)[:400], file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
