#!/usr/bin/env bash
# Common dev shortcuts. Run from repo root.
#
#   scripts/dev.sh check       # fast subset of the CI gates (fast-fail)
#   scripts/dev.sh ci          # full test/lint/coverage pipeline locally
#   scripts/dev.sh format      # spotlessApply
#   scripts/dev.sh coverage    # generate Kover HTML and print a one-line summary
#   scripts/dev.sh install     # build + adb install the debug APK
#   scripts/dev.sh profile-check # verify release profile packaging (no device)
#   scripts/dev.sh release     # build signed release APK + AAB into dist/<version>/
#
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="./gradlew --no-daemon"

run_script_tests() {
    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
}

cmd_check() {
    run_script_tests
    $GRADLE spotlessCheck checkArchitecture
    $GRADLE testDebugUnitTest
    $GRADLE lintDebug
}

cmd_ci() {
    run_script_tests
    # Keep Kotlin compilation/tests and lint analysis in separate Gradle
    # invocations. AGP 8.7 lint's FIR analyzer can otherwise race test-source
    # compilation under org.gradle.parallel and fail with an internal
    # RAW_FIR-to-ANNOTATION_ARGUMENTS error.
    $GRADLE spotlessCheck checkArchitecture
    $GRADLE testDebugUnitTest testReleaseUnitTest
    $GRADLE lintDebug lintRelease
    $GRADLE koverHtmlReport koverXmlReport koverVerify
    $GRADLE :app:minifyReleaseWithR8 :app:assembleDebug
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

cmd_profile_check() {
    $GRADLE :app:assembleRelease
    local apk="app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ ! -f "$apk" ]; then
        apk="app/build/outputs/apk/release/app-release.apk"
    fi
    if [ ! -f "$apk" ]; then
        echo "Release APK was not found." >&2
        exit 1
    fi
    local entries
    entries="$(unzip -Z1 "$apk")"
    for entry in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
        if ! grep -qx "$entry" <<<"$entries"; then
            echo "Missing $entry in $apk" >&2
            exit 1
        fi
    done
    echo "Baseline Profile packaged in $apk:"
    unzip -l "$apk" 'assets/dexopt/baseline.prof' 'assets/dexopt/baseline.profm'
}

cmd_release() {
    local version
    version="$(grep 'versionName = "' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    if [ -z "$version" ]; then
        echo "Could not parse versionName from app/build.gradle.kts" >&2
        exit 1
    fi
    echo "Building release artifacts for $version..."
    $GRADLE -PrequireReleaseSigning=true :app:assembleRelease :app:bundleRelease
    local release_apk="app/build/outputs/apk/release/app-release.apk"
    local release_aab="app/build/outputs/bundle/release/app-release.aab"
    if [ ! -f "$release_apk" ] || [ ! -f "$release_aab" ]; then
        echo "Signed release outputs were not found. Check release signing configuration." >&2
        exit 1
    fi
    mkdir -p "dist/$version"
    cp "$release_apk" \
        "dist/$version/hype-car-$version-release.apk"
    cp "$release_aab" \
        "dist/$version/hype-car-$version-release.aab"
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
    profile-check) cmd_profile_check ;;
    release) cmd_release ;;
    -h|--help|help|"") usage ;;
    *) echo "Unknown command: $1" >&2; usage ;;
esac
