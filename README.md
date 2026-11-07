# Liberate-P3 — Realme P3 (RMX5070) one-click jailbreak (Shizuku)

A Shizuku-based one-click jailbreak app **only for the Realme P3 (RMX5070 / RE608,
kernel `6.1.157-android14-11-o-g00d81d97d759`)**. It runs the full exploit chain on
the phone and late-loads KernelSU through rootd.

> ⚠️ For personal device research only. The kernel constants are tied to one
> image; **a different kernel or model means wrong addresses are written**. The
> app therefore strictly verifies model and kernel release and refuses to run
> on anything else.

---

## Key limitations

- **Only RMX5070 / RE608 + the exact kernel release.** Any mismatch aborts.
- **No system-partition writes.** The exploit only mutates kernel memory; the
  manager APK is installed to `/data/app` via `pm`. The bundled `ksud` is used
  only for `late-load` — **`ksud install` is never run** (it installs a userspace
  component to `/system`).
- **Not persistent.** KernelSU is an LKM; it is gone after a reboot, so run the
  one-click flow once per boot.
- **Leftover guard.** A failure path leaves an intentionally un-reaped `t98260`
  process (to avoid `rb_erase` hitting another page). If the app detects it, it
  asks for a **cold reboot**. Do not `kill -9` it.

## Safety checks

Verified before and during the run; any failure stops the flow:

1. Shizuku binder alive and authorized.
2. Kernel release exactly `6.1.157-android14-11-o-g00d81d97d759`.
3. Model identifiers contain `rmx5070` / `re608`.
4. `/data/local/tmp` writable and not `noexec`; tracefs present.
5. If KernelSU is not loaded and a leftover `t98260` process exists → refuse and
   ask for a cold reboot.
6. `--slide-probe` must yield `SLIDE_DERIVED=0x...`; otherwise nothing is written.
7. `--auto` will not enter the credential stage unless SELinux became permissive;
   the app additionally checks the streamed log for `SELINUX=permissive` /
   `SELINUX=already` before `STAGE=task`/`STAGE=cred`.
8. Kernel log policy:
   - **fatal** (`Unable to handle ...`, `Oops`, `Kernel panic`, `kernel BUG`) →
     abort and advise an immediate cold reboot;
   - **expected** (`timerqueue_del` / `posix_cpu_timers_exit` / `rb_next`, and any
     `RECLAIM_MISS_RETRY` / `SELINUX_MISS`) → this is inherent to the exploit's
     own victim reap; mark the boot **dirty** and continue. The dirty state is
     shown in a dialog when you tap the **Jailbroken** card.
9. The parked plant is **never** signalled or killed.

## Usage

1. Start Shizuku (wireless debugging or an existing root) and grant access to
   this app. The app checks permission on launch and shows a dialog if missing.
2. The home screen shows whether the device is jailbroken, plus model, kernel,
   SELinux and `su` state; when not jailbroken it shows **One-click jailbreak**.
3. Tap it → slide probe / SELinux / escalation+rootd / `late-load` / `su` verify.
4. On first use, if the KernelSU manager is missing, install it from the in-app
   button (bundled APK); the automatic `pm install` falls back to the system
   installer if ColorOS refuses it.
5. Done. Repeat after each reboot.

## Build

Requires JDK 17+, Android SDK (platform 37 / build-tools 37.0.0).

```bash
./build.sh                     # -> app/build/outputs/apk/debug/app-debug.apk
./build.sh assembleRelease     # -> app/build/outputs/apk/release/app-release-unsigned.apk
```

## Bundled assets and reproducible builds

`app/src/main/assets/rmx5070/`:

| File | Purpose | SHA-256 |
| --- | --- | --- |
| `t98260` | RMX5070 exploit engine (pure-C build) | `12387c97…dd23e1` |
| `rootdclient` | talk to rootd and run root commands | `aad95f80…3e414e67` |
| `ksud` | KernelSU CLI, used only for `late-load` | `049491e2…73bfb1b` |
| `KernelSU.apk` | KernelSU manager v3.3.1 | `b4a059ac…42abe450` |

`t98260` and `rootdclient` are **byte-for-byte reproducible** from the
exploit sources vendored in this repository:

| Source | Builds |
| --- | --- |
| `exploit/src/t98260_plant.c` (+ `t98260_rmx5070.h`, `t98260_phy110_61.h`) | `t98260` |
| `tools/rootdclient.c` | `rootdclient` |

```bash
# t98260: 6.1 engine + RMX5070 constant header (no Rust wrapper)
aarch64-linux-android31-clang -O2 -fPIC -fno-stack-protector -D_GNU_SOURCE -pthread \
    -DTARGET_RMX5070 -DTARGET_PHY110_61 exploit/src/t98260_plant.c -o t98260
# rootdclient
aarch64-linux-android31-clang -O2 tools/rootdclient.c -o rootdclient
```

Rebuild and verify everything with no external checkout:

```bash
./tools/build-assets.sh
```

(`ksud` / `KernelSU.apk` are upstream KernelSU v3.3.1 prebuilts, redistributed
as-is under GPL-2.0. Point `PYB110_SRC` at a `PHY110-JailBreak` checkout that
contains `prebuilt/kernelsu/` if you also want to refresh those two files.)

## Layout

```
.
├── build.sh                          # APK build entry point
├── exploit/src/                      # vendored t98260 plant + RMX5070 constants
├── tools/
│   ├── build-assets.sh               # rebuild + verify the bundled native assets
│   └── rootdclient.c                 # rootd client source
└── app/src/main/
    ├── AndroidManifest.xml
    ├── assets/rmx5070/               # t98260 / rootdclient / ksud / KernelSU.apk
    ├── res/                          # theme, icon, FileProvider paths
    └── java/com/rmx5070/onekey/
        ├── MainActivity.kt           # Material 3 Compose UI
        ├── JailbreakEngine.kt        # orchestration + all safety checks
        ├── DeviceInfo.kt             # model/kernel/KMI constants and target checks
        ├── Privileged.kt             # Shizuku process wrapper
        └── HistoryStore.kt           # local run logs
```
