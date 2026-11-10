#!/usr/bin/env bash
# Trae 签到助手 - 无 Gradle 轻量构建脚本
# 依赖：JDK 17+、Android SDK (build-tools 34.0.0 + platforms;android-34)
set -e

SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

cd "$(dirname "$0")"
BUILD=build
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes"

echo "[1/7] aapt2 compile ..."
"$BT/aapt2" compile --dir res -o "$BUILD/res.zip"

echo "[2/7] aapt2 link ..."
# assets 目录已删除（原生 UI 不再需要本地 HTML），仅在目录存在时打包，避免 aapt2 报错
A_ASSETS=""
if [ -d assets ]; then
  A_ASSETS="-A assets"
fi
"$BT/aapt2" link -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  $A_ASSETS \
  --java "$BUILD/gen" \
  "$BUILD/res.zip"

echo "[3/7] javac ..."
javac --release 8 -encoding UTF-8 -Xlint:-options \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  $(find "$BUILD/gen" src -name "*.java")

echo "[4/7] d8 ..."
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
  --output "$BUILD" \
  $(find "$BUILD/classes" -name "*.class")

echo "[5/7] package classes.dex ..."
(cd "$BUILD" && zip -q -j base.apk classes.dex)

echo "[6/7] zipalign ..."
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[7/7] sign ..."
# 签名凭据只从环境变量读取，脚本与仓库内不含任何口令。
# 发布构建示例（升级版本务必复用同一 keystore，否则无法覆盖安装）：
#   SIGNING_KEYSTORE=/path/to/your.keystore \
#   SIGNING_STORE_PASS='...' SIGNING_KEY_PASS='...' SIGNING_KEY_ALIAS=trae \
#   bash build.sh
KS="${SIGNING_KEYSTORE:-}"
if [ -n "$KS" ]; then
  : "${SIGNING_STORE_PASS:?请设置 SIGNING_STORE_PASS}"
  : "${SIGNING_KEY_PASS:?请设置 SIGNING_KEY_PASS}"
  ALIAS="${SIGNING_KEY_ALIAS:-trae}"
  export SIGNING_STORE_PASS SIGNING_KEY_PASS
  "$BT/apksigner" sign --ks "$KS" \
    --ks-pass "env:SIGNING_STORE_PASS" --key-pass "env:SIGNING_KEY_PASS" \
    --ks-key-alias "$ALIAS" \
    --out "TraeCheckin.apk" "$BUILD/aligned.apk"
else
  # 未提供签名凭据：生成一次性调试密钥，仅供本地构建验证。
  # 该产物签名与正式 Release 不同，不能覆盖安装，也不得作为发布版本分发。
  echo "未设置 SIGNING_* 环境变量，生成一次性调试密钥（仅用于本地验证）。"
  DEBUG_KS="$BUILD/debug.keystore"
  DEBUG_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
  export SIGNING_STORE_PASS="$DEBUG_PASS" SIGNING_KEY_PASS="$DEBUG_PASS"
  keytool -genkeypair -keystore "$DEBUG_KS" \
    -storepass "$DEBUG_PASS" -keypass "$DEBUG_PASS" \
    -alias debug -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=TraeCheckin-Debug,O=Local,C=CN" >/dev/null 2>&1
  "$BT/apksigner" sign --ks "$DEBUG_KS" \
    --ks-pass "env:SIGNING_STORE_PASS" --key-pass "env:SIGNING_KEY_PASS" \
    --ks-key-alias debug \
    --out "TraeCheckin.apk" "$BUILD/aligned.apk"
  echo "注意：本产物为调试签名，请勿作为正式版本分发。"
fi
"$BT/apksigner" verify "TraeCheckin.apk"

echo ""
echo "构建完成: $(pwd)/TraeCheckin.apk"
ls -lh TraeCheckin.apk
