#!/usr/bin/env bash
# Builds Hourlock.apk with Android's command-line tools (no Gradle, no
# Google libraries). Also runs the rules tests first.
#
# Needs a JDK (17 or newer) and, from the Android SDK:
#   ANDROID_JAR  path to platforms/android-33/android.jar
#   BUILD_TOOLS  path to build-tools/36.x (has aapt2, zipalign, lib/d8.jar, lib/apksigner.jar)
#   AAPT2        path to aapt2 (defaults to $BUILD_TOOLS/aapt2)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_JAR:?Set ANDROID_JAR to platforms/android-33/android.jar}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to an Android build-tools folder}"
AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2}"
KEYSTORE="${KEYSTORE:-signing/unscroll.keystore}"
KS_PASS_FILE="${KS_PASS_FILE:-signing/keystore-password.txt}"

# No release key (e.g. a fresh clone or CI): sign with a throwaway local key.
# Such an APK works fine but can't update one signed with the release key.
if [ ! -f "$KEYSTORE" ]; then
    echo "No release key at $KEYSTORE: using a local debug key instead."
    mkdir -p build
    KEYSTORE=build/debug.keystore
    KS_PASS_FILE=build/debug-password.txt
    if [ ! -f "$KEYSTORE" ]; then
        printf 'android' > "$KS_PASS_FILE"
        keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias debug \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass android -keypass android -dname "CN=Hourlock debug" >/dev/null 2>&1
    fi
fi

VERSION_CODE=4
VERSION_NAME=1.3
OUT=build/app
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" build/test

echo "1/6 Rules tests"
javac -Xlint:all -Werror -d build/test src/app/unscroll/Engine.java test/app/unscroll/EngineTest.java
java -cp build/test app.unscroll.EngineTest

echo "2/6 Resources"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" \
    -o "$OUT/base.apk" "$OUT/res.zip"

echo "3/6 Java"
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -Xlint:all,-options -Werror \
    -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')

echo "4/6 Dex"
java -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 29 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "5/6 Package + align"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q ../unaligned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -p -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "6/6 Sign"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass "file:$KS_PASS_FILE" \
    --out build/Hourlock.apk "$OUT/aligned.apk"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --min-sdk-version 29 build/Hourlock.apk

echo "Built build/Hourlock.apk"
