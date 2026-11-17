#!/usr/bin/env bash
#
# Builds a debug-signed APK without Gradle.
#
# Headless Droid has two dependencies (Shizuku api + provider, pulled from Maven
# as AARs) and no AndroidX, so the whole build is aapt2 -> javac -> kotlinc ->
# d8 -> zipalign -> apksigner. That runs on the development device itself, which
# is why this script exists next to the Gradle files.
#
# Stages, in order:
#   resources     aapt2 compile + link (via qemu on an arm64 host)
#   java          javac of app/src/main/java (the engine, shell, HTTP, recorder)
#   kotlin        kotlinc of app/src/main/java (Application, activities, views)
#   dex           d8 of both class sets plus the Kotlin runtime
#   package       jar + zipalign + apksigner
#
# Environment overrides:
#   JAVA_HOME      JDK 17+ (javac for the Java stage)
#   KOTLIN_JAVA    JDK 17 (kotlinc needs 17-23; JDK 25 is too new for it)
#   KOTLIN_LIB     directory with the Kotlin compiler jars
#   ANDROID_JAR    path to android.jar (compileSdk 36 / API 36)
#   BUILD_TOOLS    path to an Android build-tools directory
#   SYSROOT        amd64 runtime for qemu, needed on a non-x86-64 host
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build/local"
DEPS="$BUILD/deps"
JARS="$DEPS/jars"
GEN="$BUILD/gen"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"

JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    for candidate in \
        /tmp/opencode/jdk-17.0.20.1+1 \
        "${HOME:-}/.jdks/temurin-17" \
        /usr/lib/jvm/java-21-openjdk-arm64 \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk-arm64 \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk \
        /usr/lib/jvm/default-java; do
        if [ -x "$candidate/bin/javac" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "JDK 17+ not found. Set JAVA_HOME." >&2
    exit 1
fi

# The Kotlin compiler needs a JDK it supports: 17-23. JDK 25 is too new.
KOTLIN_JAVA="${KOTLIN_JAVA:-${JAVA_JAVA_HOME:-}}"
if [ -z "$KOTLIN_JAVA" ] || [ ! -x "$KOTLIN_JAVA/bin/java" ]; then
    for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk-amd64 "$JAVA_HOME"; do
        if [ -x "$candidate/bin/java" ]; then
            KOTLIN_JAVA="$candidate"
            break
        fi
    done
fi
KOTLIN_LIB="${KOTLIN_LIB:-/opt/kotlin-lib}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
KOTLIN_STDLIB="$KOTLIN_LIB/kotlin-stdlib-$KOTLIN_VERSION.jar"
if [ ! -f "$KOTLIN_STDLIB" ]; then
    echo "kotlin-stdlib-$KOTLIN_VERSION.jar not found in $KOTLIN_LIB. Set KOTLIN_LIB." >&2
    echo "Download kotlin-compiler-embeddable, -daemon-embeddable, kotlin-stdlib," >&2
    echo "kotlin-script-runtime, kotlinx-coroutines-core-jvm, trove4j and org.jetbrains" >&2
    echo "annotations from Maven Central into it." >&2
    exit 1
fi

ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
    for candidate in \
        "${ANDROID_HOME:-}/platforms/android-36/android.jar" \
        "${HOME:-}/Android/Sdk/platforms/android-36/android.jar" \
        /opt/android-sdk/platforms/android-36/android.jar \
        /tmp/opencode/p36/android-36/android.jar; do
        if [ -f "$candidate" ]; then
            ANDROID_JAR="$candidate"
            break
        fi
    done
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "android.jar (API 36) not found. Set ANDROID_JAR." >&2
    exit 1
fi

BUILD_TOOLS="${BUILD_TOOLS:-}"
if [ -z "$BUILD_TOOLS" ]; then
    for candidate in \
        "${ANDROID_HOME:-}/build-tools/35.0.0" \
        "${HOME:-}/Android/Sdk/build-tools/35.0.0" \
        /opt/android-sdk/build-tools/35.0.0 \
        /tmp/opencode/sdk/build-tools/35.0.0; do
        if [ -x "$candidate/aapt2" ]; then
            BUILD_TOOLS="$candidate"
            break
        fi
    done
fi
if [ -z "$BUILD_TOOLS" ] || [ ! -x "$BUILD_TOOLS/aapt2" ]; then
    echo "build-tools (aapt2) not found. Set BUILD_TOOLS." >&2
    exit 1
fi

JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
KJAVA="$KOTLIN_JAVA/bin/java"

# The Android build tools are x86-64 binaries. On an arm64 host (this app is
# developed on a phone) they run through qemu-user, which needs an amd64 runtime
# to load them:
#   dpkg-deb -x libc6_*_amd64.deb /tmp/x86root  (plus libgcc-s1 and libstdc++6)
# Set SYSROOT to where that runtime lives, or leave it empty on an x86-64 host.
SYSROOT="${SYSROOT:-}"
case "$(uname -m)" in
    x86_64 | amd64) native() { "$@"; } ;;
    *) native() {
        if [ -x /usr/bin/qemu-x86_64 ] && [ -n "$SYSROOT" ] && [ -d "$SYSROOT" ]; then
            QEMU_LD_PREFIX="$SYSROOT" qemu-x86_64 -L "$SYSROOT" "$@"
        else
            echo "cannot run $1: arm64 host needs qemu-user-static plus an amd64 sysroot" >&2
            return 127
        fi
    } ;;
esac

rm -rf "$BUILD"
mkdir -p "$JARS" "$GEN" "$CLASSES" "$DEX"

# --- Shizuku AARs -----------------------------------------------------------
fetch_shizuku() {
    local artifact="$1" version="$2"
    local aar="$DEPS/shizuku-$artifact-$version.aar"
    if [ ! -f "$aar" ]; then
        echo "== downloading dev.rikka.shizuku:$artifact:$version"
        curl -fsSL -o "$aar" \
            "https://repo1.maven.org/maven2/dev/rikka/shizuku/$artifact/$version/$artifact-$version.aar"
    fi
    if [ ! -f "$JARS/shizuku-$artifact.jar" ]; then
        unzip -p "$aar" classes.jar > "$JARS/shizuku-$artifact.jar"
    fi
}

fetch_shizuku api 13.1.5
fetch_shizuku provider 13.1.5
fetch_shizuku aidl 13.1.5
fetch_shizuku shared 13.1.5

ANNOTATION_JAR="$DEPS/annotation-1.3.0.jar"
if [ ! -f "$ANNOTATION_JAR" ]; then
    curl -fsSL -o "$ANNOTATION_JAR" \
        "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar"
fi

# --- resources --------------------------------------------------------------
echo "== aapt2 compile"
native "$BUILD_TOOLS/aapt2" compile --dir "$APP/src/main/res" -o "$BUILD/res.zip"

# aapt2 requires a package attribute; AGP 9 forbids it in the source manifest,
# so inject it into a build-time copy only.
sed '0,/<manifest /s//<manifest package="com.bliss.headlessdroid" /' \
    "$APP/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"

# aapt2 wants an asset directory that exists; an empty one is fine when the app
# has no assets.
mkdir -p "$BUILD/assets"
ASSETS="$APP/src/main/assets"
if [ ! -d "$ASSETS" ]; then
    ASSETS="$BUILD/assets"
fi

echo "== aapt2 link"
native "$BUILD_TOOLS/aapt2" link \
    -o "$BUILD/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    -A "$ASSETS" \
    --manifest "$BUILD/AndroidManifest.xml" \
    --java "$GEN" \
    --custom-package com.bliss.headlessdroid \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code 1 \
    --version-name 1.0.0 \
    "$BUILD/res.zip"

# --- java -------------------------------------------------------------------
echo "== javac"
CP="$ANDROID_JAR:$ANNOTATION_JAR"
for jar in "$JARS"/*.jar "$APP"/libs/*.jar; do
    [ -f "$jar" ] && CP="$CP:$jar"
done

find "$APP/src/main/java" "$GEN" -name '*.java' > "$BUILD/java-sources.txt"
"$JAVAC" -source 17 -target 17 -nowarn -Xlint:-options \
    -classpath "$CP" \
    -d "$CLASSES" \
    @"$BUILD/java-sources.txt"

# --- kotlin -----------------------------------------------------------------
# The Kotlin sources are compiled against the Java classes that are already
# built, so the two halves see each other's types.
echo "== kotlinc"
find "$APP/src/main/java" -name '*.kt' > "$BUILD/kotlin-sources.txt"
KOTLIN_LIBS="$KOTLIN_LIB/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
for extra in kotlin-stdlib kotlin-daemon-embeddable kotlin-script-runtime \
    kotlinx-coroutines-core-jvm trove4j annotations; do
    for jar in "$KOTLIN_LIB/$extra-"*.jar; do
        [ -f "$jar" ] && KOTLIN_LIBS="$KOTLIN_LIBS:$jar"
    done
done
KOTLIN_ARGS=(-no-stdlib
    -jvm-target 1.8
    -classpath "$CP:$CLASSES:$KOTLIN_STDLIB"
    -d "$CLASSES")
if [ -s "$BUILD/kotlin-sources.txt" ]; then
    "$KJAVA" -Xmx2g -cp "$KOTLIN_LIBS" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "${KOTLIN_ARGS[@]}" \
        @"$BUILD/kotlin-sources.txt" 2>&1 | grep -v "^warning: unable to find" || true
fi

# --- dex --------------------------------------------------------------------
echo "== d8"
find "$CLASSES" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -Xmx3g -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release \
    --lib "$ANDROID_JAR" \
    --min-api 29 \
    --output "$DEX" \
    @"$BUILD/classes.txt" \
    "$JARS/shizuku-api.jar" \
    "$JARS/shizuku-provider.jar" \
    "$JARS/shizuku-aidl.jar" \
    "$JARS/shizuku-shared.jar" \
    "$KOTLIN_STDLIB"

# --- package ----------------------------------------------------------------
echo "== package"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-dexed.apk"
(cd "$DEX" && "$JAVA_HOME/bin/jar" uf "$BUILD/app-dexed.apk" classes.dex)

native "$BUILD_TOOLS/zipalign" -f 4 "$BUILD/app-dexed.apk" "$BUILD/app-aligned.apk"

KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "== generating debug keystore"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "== apksigner"
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$BUILD/headless-droid-debug.apk" \
    "$BUILD/app-aligned.apk"

"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --print-certs \
    "$BUILD/headless-droid-debug.apk" > "$BUILD/verify.txt"
sed -n '1,5p' "$BUILD/verify.txt"

echo
echo "APK:  $BUILD/headless-droid-debug.apk"
native "$BUILD_TOOLS/aapt2" dump badging "$BUILD/headless-droid-debug.apk" > "$BUILD/badging.txt"
sed -n '1,6p' "$BUILD/badging.txt"