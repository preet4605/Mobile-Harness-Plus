#!/bin/sh
# One-command online build + install + launch on the connected phone.
# Usage: sh scripts/install-phone.sh
set -e
cd "$(dirname "$0")/.."
./gradlew :app:installOnlineDebug -x lint
# Launch the component the debug build installed. Its applicationId is the base id plus the debug
# applicationIdSuffix (default ".dev", app/build.gradle.kts). The activity class stays in the
# namespace, so it is passed fully qualified: a relative name would resolve under the suffixed id.
namespace=$(sed -n 's/^ *namespace = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -n 1)
base_id=$(sed -n 's/^ *applicationId = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -n 1)
suffix=.dev
if grep -q '^appIdSuffix=' gradle.properties; then
    suffix=$(sed -n 's/^appIdSuffix=//p' gradle.properties | head -n 1)
fi
activity=$(sed -n '/<activity/,/>/s/.*android:name="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml | head -n 1)
case "$activity" in
    "") echo "install-phone: no activity declared in app/src/main/AndroidManifest.xml" >&2; exit 1 ;;
    .*) activity="$namespace$activity" ;;
esac
adb shell am start -n "$base_id$suffix/$activity" --activity-single-top
