#!/usr/bin/env bash
# Build NgheNhớ trên máy Windows này (Git Bash). Dùng: bash build.sh [task...]
# Mặc định: chạy unit test + build APK release đã ký.
set -e
cd "$(dirname "$0")"
L="$(cygpath -u "$LOCALAPPDATA")/Android"
export JAVA_HOME="$(cygpath -w "$L/jdk17")"
export ANDROID_HOME="$(cygpath -w "$L/Sdk")"
export PATH="$L/jdk17/bin:$L/gradle-8.11.1/bin:$PATH"
# Kaspersky chặn TLS: cho Java tin kho chứng chỉ Windows
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"

if [ ! -f keystore/keystore.properties ]; then
  mkdir -p keystore
  PASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
  keytool -genkeypair -keystore keystore/nghenho.jks -alias nghenho -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=NgheNho, O=Personal, C=VN" >/dev/null
  printf 'storeFile=keystore/nghenho.jks\nstorePassword=%s\nkeyAlias=nghenho\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore/keystore.properties
  echo "Đã tạo khoá ký APK ở keystore/ — hãy sao lưu thư mục này (cần để cập nhật app sau này)."
fi

TASKS="${*:-testDebugUnitTest assembleRelease}"
gradle --console=plain $TASKS
VER="$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | grep -o '[0-9][0-9.]*')"
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  mkdir -p dist
  cp app/build/outputs/apk/release/app-release.apk "dist/Flowme-$VER.apk"
  echo "APK: $(pwd)/dist/Flowme-$VER.apk"
fi
