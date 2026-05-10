#!/usr/bin/env bash
# Common dev shortcuts. Run from repo root.
#
#   scripts/dev.sh check       # the same gates CI runs (fast-fail)
#   scripts/dev.sh ci          # full CI pipeline locally
#   scripts/dev.sh format      # spotlessApply
#   scripts/dev.sh coverage    # generate Kover HTML and print a one-line summary
#   scripts/dev.sh install     # build + adb install the debug APK
#   scripts/dev.sh release     # build release APK + AAB into dist/<version>/
#
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="./gradlew --no-daemon"

cmd_check() {
    $GRADLE spotlessCheck checkArchitecture testDebugUnitTest lintDebug
}

cmd_ci() {
    $GRADLE \
        spotlessCheck \
        checkArchitecture \
        testDebugUnitTest testReleaseUnitTest \
        lintDebug lintRelease \
        koverHtmlReport koverXmlReport koverVerify \
        :app:assembleDebug :app:assembleRelease :app:bundleRelease
}

cmd_format() {
    $GRADLE spotlessApply
}

cmd_coverage() {
    $GRADLE koverHtmlReport koverXmlReport
    if [ -f build/reports/kover/report.xml ]; then
        python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('build/reports/kover/report.xml').getroot()
for kind in ('LINE', 'INSTRUCTION', 'BRANCH'):
    c = root.find(f'counter[@type="{kind}"]')
    covered = int(c.get('covered'))
    missed = int(c.get('missed'))
    total = covered + missed
    pct = (100.0 * covered / total) if total else 0
    print(f'{kind:<12} {covered:>6}/{total:<6} {pct:>5.1f}%')
PY
        echo
        echo "HTML report: file://$(pwd)/build/reports/kover/html/index.html"
    fi
}

cmd_install() {
    $GRADLE :app:installDebug
    echo "Installed dev.josu.hypecar (debug). Launch with:"
    echo "  adb shell am start -n dev.josu.hypecar/.MainActivity"
}

cmd_release() {
    local version
    version="$(grep 'versionName = "' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    if [ -z "$version" ]; then
        echo "Could not parse versionName from app/build.gradle.kts" >&2
        exit 1
    fi
    echo "Building release artifacts for $version..."
    $GRADLE :app:assembleDebug :app:assembleRelease :app:bundleRelease
    mkdir -p "dist/$version"
    cp app/build/outputs/apk/debug/app-debug.apk \
        "dist/$version/hype-car-$version-debug-installable.apk"
    cp app/build/outputs/apk/release/app-release-unsigned.apk \
        "dist/$version/hype-car-$version-release-unsigned.apk"
    cp app/build/outputs/bundle/release/app-release.aab \
        "dist/$version/hype-car-$version-release-unsigned.aab"
    echo "Staged dist/$version/"
    ls -lh "dist/$version/"
}

usage() {
    sed -n '/^# /p' "$0" | sed 's/^# \?//'
    exit 1
}

case "${1:-}" in
    check) cmd_check ;;
    ci) cmd_ci ;;
    format) cmd_format ;;
    coverage) cmd_coverage ;;
    install) cmd_install ;;
    release) cmd_release ;;
    -h|--help|help|"") usage ;;
    *) echo "Unknown command: $1" >&2; usage ;;
esac
