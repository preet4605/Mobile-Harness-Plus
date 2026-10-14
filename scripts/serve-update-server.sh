#!/usr/bin/env bash
#
# Builds a newer debug APK and serves it (with a matching
# mobile-harness-update.json) from a local HTTP directory so the in-app
# updater can be exercised end-to-end without publishing to GitHub.
#
# Usage:
#   ./scripts/serve-update-server.sh PORT BASE_URL [VERSION_CODE] [VERSION_NAME]
#
# Examples:
#   ./scripts/serve-update-server.sh 8080 https://example.trycloudflare.com
#
# Paste BASE_URL + /mobile-harness-update.json into the debug app's
# Settings → Update channel and tap Use & check.

set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
port="${1:-8080}"
base_url="${2:-}"
version_defaults="$(python3 - "$project_dir/app/build.gradle.kts" "${3:-}" "${4:-}" <<'PY'
import pathlib, re, sys
build = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
current_code = int(re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', build, re.MULTILINE).group(1))
current_name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', build, re.MULTILINE).group(1)
code = int(sys.argv[2]) if sys.argv[2] else current_code + 1
if not 0 < code <= 2147483647:
    raise ValueError("VERSION_CODE must be a positive Android version code")
print(code)
print(sys.argv[3] if sys.argv[3] else current_name + "-test")
PY
)"
version_code="${version_defaults%%$'\n'*}"
version_name="${version_defaults#*$'\n'}"

if [[ "$base_url" != https://* ]]; then
  echo "BASE_URL must be the HTTPS URL from Cloudflare Tunnel or ngrok." >&2
  echo "Usage: $0 PORT https://your-tunnel.example [VERSION_CODE] [VERSION_NAME]" >&2
  exit 1
fi
serve_dir="$project_dir/dist/update-test"
flavor="online"
variant="debug"
apk_name="mobile-harness-${flavor}-${variant}.apk"

cd "$project_dir"

echo "==> Preparing $serve_dir"
rm -rf "$serve_dir"
mkdir -p "$serve_dir"

echo "==> Building debug APK with versionCode $version_code, versionName $version_name"
./gradlew ":app:assembleOnlineDebug" \
    -PappVersionCode="$version_code" \
    -PappVersionName="$version_name" \
    --quiet

apk_src="$project_dir/app/build/outputs/apk/$flavor/$variant/app-${flavor}-${variant}.apk"
if [[ ! -f "$apk_src" ]]; then
    echo "Expected APK not found: $apk_src" >&2
    exit 1
fi

cp "$apk_src" "$serve_dir/$apk_name"
sha="$(python3 - "$serve_dir" "$apk_name" "$version_code" "$version_name" "$flavor" "$base_url" <<'PY'
import hashlib, json, pathlib, sys
directory, name, code, version, flavor, base = sys.argv[1:]
apk = pathlib.Path(directory) / name
digest = hashlib.sha256()
with apk.open("rb") as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(chunk)
sha = digest.hexdigest()
manifest = {
    "versionCode": int(code), "versionName": version,
    "notes": f"Local test build served from {directory}.",
    "artifacts": {flavor: {"url": base.rstrip("/") + "/" + name,
        "sha256": sha, "sizeBytes": apk.stat().st_size}},
}
with (pathlib.Path(directory) / "mobile-harness-update.json").open("w", encoding="utf-8") as output:
    json.dump(manifest, output, indent=2)
    output.write("\n")
print(sha)
PY
)"

cat > "$serve_dir/README.txt" <<TXT
Local update test server.

Files:
  - mobile-harness-update.json
  - $apk_name  (sha256: $sha)

APK URL baked into the manifest:
  $base_url/$apk_name

In a debug build of Mobile Harness, open Settings → Update channel, paste:
  $base_url/mobile-harness-update.json
and tap Use & check.

The app accepts this URL because the tunnel provides HTTPS. No LAN IP or
cleartext network-security exception is required.
TXT

echo "==> Manifest ready at $base_url/mobile-harness-update.json"
echo "    APK at $base_url/$apk_name"
echo "    Files:"
ls -lh "$serve_dir"
echo
echo "==> Starting HTTP server on port $port (Ctrl-C to stop)"

cd "$serve_dir"
exec python3 -m http.server "$port"
