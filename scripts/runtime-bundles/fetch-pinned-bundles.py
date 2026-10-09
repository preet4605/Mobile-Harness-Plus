#!/usr/bin/env python3
"""Fetch the release archives pinned by this checkout; never accept a hash mismatch."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def verify_archive(path, bundle):
    if not path.is_file() or path.stat().st_size != bundle["compressedBytes"]:
        return False
    algorithm = "sha512" if "sha512" in bundle else "sha256"
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == bundle[algorithm]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    directory = project / "dist/runtime-bundles"
    manifest = json.loads((directory / "manifest.json").read_text())
    staging = directory / "sources"
    staging.mkdir(exist_ok=True)
    for bundle in manifest["bundles"].values():
        name = bundle["file"]
        if Path(name).name != name:
            raise ValueError("Archive filename must be a basename")
        destination = directory / name
        if verify_archive(destination, bundle):
            print("VERIFIED", name, flush=True)
            continue
        if args.verify_only or destination.exists():
            raise RuntimeError("Missing or invalid pinned archive: " + name)
        partial = staging / (name + ".download")
        for attempt in range(5):
            result = subprocess.run([
                "curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                "--connect-timeout", "15", "--max-time", "1800", "--retry", "4", "--continue-at", "-",
                bundle.get("url", "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4/" + name),
                "--output", str(partial),
            ])
            if result.returncode == 0:
                break
            if attempt == 4:
                raise RuntimeError("Download failed; partial archive retained: " + name)
            time.sleep(2 ** attempt)
        if not verify_archive(partial, bundle):
            partial.unlink()
            raise RuntimeError("Downloaded archive failed size/checksum: " + name)
        partial.replace(destination)
        print("VERIFIED", name, flush=True)


if __name__ == "__main__":
    main()
