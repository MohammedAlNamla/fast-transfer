#!/usr/bin/env bash
# Builds the APK with the Android SDK tools directly, no Gradle and no downloads.
# Usage: bash build-apk.sh   ->  out/FastTransfer.apk
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
JDK="${JAVA_HOME:-/c/Program Files/Java/jdk-17}"
SRC=app/src/main
OUT=out
B=$OUT/build

rm -rf "$OUT"
mkdir -p "$B/res" "$B/gen" "$B/classes" "$B/dex"

# The Gradle manifest takes its package from build.gradle; aapt2 needs it in the file.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.fasttransfer.app">|' \
    "$SRC/AndroidManifest.xml" > "$B/AndroidManifest.xml"

echo "1/5 resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$B/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$B/AndroidManifest.xml" \
    --min-sdk-version 29 --target-sdk-version 34 --version-code 1 --version-name 1.0 \
    --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "2/5 java"
find "$SRC/java" "$B/gen" -name '*.java' > "$B/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 17 -target 17 -nowarn -Xlint:-options \
    -classpath "$JAR" -d "$B/classes" @"$B/sources.txt"

echo "3/5 dex"
CLASSES=$(find "$B/classes" -name '*.class')
"$BT/d8.bat" --release --min-api 29 --lib "$JAR" --output "$B/dex" $CLASSES

echo "4/5 package"
cp "$B/base.apk" "$B/unsigned.apk"
( cd "$B/dex" && "$JDK/bin/jar" uf ../unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "5/5 sign"
"$BT/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$OUT/FastTransfer.apk" "$B/aligned.apk"
"$BT/apksigner.bat" verify "$OUT/FastTransfer.apk"

ls -la "$OUT/FastTransfer.apk"
