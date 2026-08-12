#!/usr/bin/env bash
#
# Собирает ядро mihomo под Android и раскладывает по jniLibs.
#
# Ядро кладётся с именем libmihomo.so, хотя это исполняемый файл, а не библиотека:
# nativeLibraryDir — единственный каталог приложения, из которого Android
# разрешает запускать файлы (W^X). Скопировать бинарь в filesDir и сделать
# chmod +x начиная с Android 10 не работает.
#
# Нужны Go и Android NDK.
#
#   ANDROID_NDK=~/Library/Android/sdk/ndk/29.0.14206865 tools/build_core.sh
#
set -euo pipefail

BRANCH="${MIHOMO_BRANCH:-Alpha}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-$(mktemp -d)}"
SRC_DIR="$WORK_DIR/mihomo"

if [ -z "${ANDROID_NDK:-}" ]; then
    echo "Укажи ANDROID_NDK — путь к NDK." >&2
    echo "Например: ANDROID_NDK=~/Library/Android/sdk/ndk/29.0.14206865 $0" >&2
    exit 1
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "Не найден toolchain: $TOOLCHAIN" >&2
    exit 1
fi

# Должно совпадать с minSdk в app/build.gradle.kts.
API=26

if [ ! -d "$SRC_DIR" ]; then
    git clone --depth 1 -b "$BRANCH" https://github.com/MetaCubeX/mihomo.git "$SRC_DIR"
fi

cd "$SRC_DIR"
VERSION="$BRANCH-$(git rev-parse --short HEAD)"
LDFLAGS="-X 'github.com/metacubex/mihomo/constant.Version=$VERSION' -w -s -buildid="

build() {
    local goarch="$1" abi="$2" cc="$3" extra="${4:-}"
    echo ">>> $abi"
    # GOOS=android обязателен: только он умеет читать системные корневые
    # сертификаты Android (/system/etc/security/cacerts). Сборка под GOOS=linux
    # запускается, но валит TLS с "certificate signed by unknown authority",
    # из-за чего отваливается DNS-over-TLS и не качается подписка.
    env GOOS=android GOARCH="$goarch" $extra \
        CGO_ENABLED=1 CC="$TOOLCHAIN/$cc" \
        go build -tags with_gvisor -trimpath -ldflags "$LDFLAGS" \
        -o "$PROJECT_DIR/app/src/main/jniLibs/$abi/libmihomo.so"
}

mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a" \
         "$PROJECT_DIR/app/src/main/jniLibs/armeabi-v7a"

build arm64 arm64-v8a "aarch64-linux-android$API-clang"
build arm armeabi-v7a "armv7a-linux-androideabi$API-clang" "GOARM=7"

echo
ls -la "$PROJECT_DIR/app/src/main/jniLibs"/*/libmihomo.so
echo "Готово, версия ядра: $VERSION"
