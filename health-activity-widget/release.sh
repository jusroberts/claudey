#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# release.sh — build, sign, tag, and publish a GitHub release
#
# Usage: ./release.sh <version>
#   e.g. ./release.sh 1.1
#
# Requires:
#   - RELEASE_KEYSTORE env var pointing to your .jks file, OR pass --keystore
#   - RELEASE_KEY_ALIAS env var, OR pass --alias
#   - gh CLI installed and authenticated (gh auth login)
#   - ANDROID_HOME set (or apksigner on PATH)
# ---------------------------------------------------------------------------

VERSION=""
KEYSTORE="${RELEASE_KEYSTORE:-}"
KEY_ALIAS="${RELEASE_KEY_ALIAS:-health-widget}"

usage() {
    echo "Usage: $0 <version> [--keystore path/to/release.jks] [--alias key-alias]"
    exit 1
}

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --keystore) KEYSTORE="$2"; shift 2 ;;
        --alias)    KEY_ALIAS="$2"; shift 2 ;;
        -h|--help)  usage ;;
        *)
            if [[ -z "$VERSION" ]]; then VERSION="$1"; shift
            else echo "Unknown argument: $1"; usage
            fi ;;
    esac
done

[[ -z "$VERSION" ]] && { echo "Error: version is required."; usage; }
[[ -z "$KEYSTORE" ]] && { echo "Error: keystore path required (set RELEASE_KEYSTORE or pass --keystore)."; exit 1; }
[[ ! -f "$KEYSTORE" ]] && { echo "Error: keystore not found at '$KEYSTORE'."; exit 1; }

TAG="v${VERSION}"
APK_NAME="health-activity-widget-${VERSION}.apk"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED="app/build/outputs/apk/release/${APK_NAME}"

# Resolve apksigner
if command -v apksigner &>/dev/null; then
    APKSIGNER="apksigner"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
    APKSIGNER=$(find "${ANDROID_HOME}/Sdk/build-tools/" -name apksigner 2>/dev/null | sort -V | tail -1)
    [[ -z "$APKSIGNER" ]] && { echo "Error: apksigner not found in ANDROID_HOME."; exit 1; }
else
    echo "Error: apksigner not found. Set ANDROID_HOME or add apksigner to PATH."
    exit 1
fi

echo "==> Building release APK (version ${VERSION})..."
./gradlew assembleRelease

echo "==> Signing APK..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$SIGNED" \
    "$UNSIGNED"

"$APKSIGNER" verify "$SIGNED"
echo "    Signature verified."

echo "==> Updating versionName in build.gradle.kts..."
sed -i "s/versionName = \".*\"/versionName = \"${VERSION}\"/" app/build.gradle.kts

echo "==> Updating fdroid-metadata.yml..."
VERSION_CODE=$(grep 'versionCode' app/build.gradle.kts | grep -o '[0-9]*')
sed -i "s/versionName: '.*'/versionName: '${VERSION}'/" fdroid-metadata.yml
sed -i "s/versionCode: .*/versionCode: ${VERSION_CODE}/" fdroid-metadata.yml
sed -i "s/commit: .*/commit: ${TAG}/" fdroid-metadata.yml

echo "==> Committing version bump and tagging ${TAG}..."
git add app/build.gradle.kts fdroid-metadata.yml
git diff --cached --quiet || git commit -m "Release ${TAG}"
git tag -a "$TAG" -m "Release ${TAG}"

echo "==> Pushing tag to origin..."
git push origin "$TAG"

echo "==> Creating GitHub release and uploading APK..."
gh release create "$TAG" "$SIGNED" \
    --title "${TAG}" \
    --notes "See [CHANGELOG](CHANGELOG.md) for details." \
    --verify-tag

echo ""
echo "Done. Release ${TAG} published."
echo "APK: ${SIGNED}"
