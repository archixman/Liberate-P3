#!/usr/bin/env bash
#
# Rebuild the native assets bundled in app/src/main/assets/rmx5070/ from the
# sources vendored in this repository and verify them byte-for-byte.
#
#   ./tools/build-assets.sh
#
# Vendored sources:
#   exploit/src/t98260_plant.c
#   exploit/src/t98260_rmx5070.h
#   exploit/src/t98260_phy110_61.h
#   tools/rootdclient.c
#
# The bundled t98260 is a *pure-C* build of the shared 6.1 engine with the
# RMX5070 link-time constants (no Rust wrapper): the app drives it directly
# with --slide-probe / --selinux-only / --auto.
#
# Prerequisites:
#   - ANDROID_NDK_HOME pointing at an NDK r28 (linux-x86_64), or an SDK tree
#     with an ndk/ directory (auto-detected).
#   - Optional: PYB110_SRC pointing at a PHY110-JailBreak checkout. When that
#     checkout contains prebuilt/kernelsu/, its ksud + manager APK are
#     refreshed too; otherwise the bundled upstream prebuilts are kept.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
assets="$root/app/src/main/assets/rmx5070"
src="${PYB110_SRC:-$root}"

[ -f "$src/exploit/src/t98260_plant.c" ] || {
    echo "exploit sources not found at: $src/exploit/src/t98260_plant.c" >&2
    echo "set PYB110_SRC=/path/to/PHY110-JailBreak to use an external checkout" >&2
    exit 1
}

ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
    for c in "${ANDROID_HOME:-/nonexistent}"/ndk/* \
             "${ANDROID_SDK_ROOT:-/nonexistent}"/ndk/* \
             "$HOME/Android/Sdk"/ndk/*; do
        [ -d "$c" ] && ndk="$c"
    done
fi
[ -n "$ndk" ] || { echo "set ANDROID_NDK_HOME to an NDK r28" >&2; exit 1; }

cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang"
[ -x "$cc" ] || { echo "clang not found: $cc" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[*] building t98260 (RMX5070, pure C) with $cc"
"$cc" -O2 -fPIC -fno-stack-protector -D_GNU_SOURCE -pthread \
    -DTARGET_RMX5070 -DTARGET_PHY110_61 \
    "$src/exploit/src/t98260_plant.c" -o "$tmp/t98260"

echo "[*] building rootdclient"
"$cc" -O2 "$src/tools/rootdclient.c" -o "$tmp/rootdclient"

install -m 755 "$tmp/t98260" "$assets/t98260"
install -m 755 "$tmp/rootdclient" "$assets/rootdclient"

# ksud / KernelSU.apk are upstream KernelSU prebuilts, not built here.
ksu="${KERNELSU_PREBUILT:-$src/prebuilt/kernelsu}"
if [ -d "$ksu" ]; then
    echo "[*] refreshing ksud + manager from $ksu"
    install -m 755 "$ksu/ksud" "$assets/ksud"
    manager="$(ls "$ksu"/KernelSU_*.apk | head -n1)"
    install -m 644 "$manager" "$assets/KernelSU.apk"
else
    echo "[*] keeping bundled ksud + KernelSU.apk (upstream prebuilts)"
fi

echo "[*] writing SHA256SUMS"
( cd "$assets" && sha256sum t98260 rootdclient ksud KernelSU.apk | tee SHA256SUMS )

echo "[+] assets rebuilt"
