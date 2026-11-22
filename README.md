# Turning a Sony Xperia E4g into a Dedicated Walkman (No Root Required)

How I converted a 2015 Sony Xperia E4g (E2003, Android 4.4.4 KitKat) into a stable,
single‑purpose, kiosk‑style music player — booting straight into the Walkman app,
with ~100 apps hidden, a newer Walkman version installed, and battery tuned for
all‑day playback — **without keeping root**, and without breaking the device's
notoriously fragile sleep/suspend behavior.

> This is a write‑up of a real debugging and modding session. Every device‑specific
> secret (IMEI, bootloader unlock code, serial number) has been deliberately left out.

---

## TL;DR

| Goal | Result |
|------|--------|
| Boot straight into the music player | ✅ Custom ~30‑line HOME launcher (no root) |
| Remove the "phone" clutter | ✅ ~100 apps hidden with `pm block` (no root) |
| Keep it fast on old hardware | ✅ Animations off, background trimmed |
| All‑day battery | ✅ Airplane mode, radios/location/NFC off |
| Modern Walkman app | ✅ Updated 8.5 → 9.1.2 via signature‑matched sideload (no root) |
| Keep it **stable** | ✅ Stock ELF kernel kept — the key to avoiding the suspend freeze |

The single most important lesson: **on this Sony/MediaTek device, the boot image
*format* — not root itself — was what broke suspend.** Everything else followed from
choosing stability over a persistent `su`.

---

## The device

- **Model:** Sony Xperia E4g (`E2003`)
- **SoC:** MediaTek MT6732
- **OS:** Android 4.4.4 KitKat (API 19)
- **Firmware:** `25.0.A.1.28`, later `25.0.A.2.31`
- Bootloader unlock **allowed** (checked in the service menu, `*#*#7378423#*#*`)

KitKat on a low‑RAM (512 MB usable) MediaTek phone sets the constraints for
everything below: old `pm` semantics, Magisk's last supported version, Sony's ELF
boot format, and modern apps that technically target API 19 but assume newer UI
resources.

---

## 1. Bootloader unlock

Standard Sony route: grab the unlock code from Sony's official Open Devices program
and flash it in fastboot.

```bash
fastboot oem unlock 0x<YOUR_CODE>
```

Unlocking **wipes userdata**. The bootloader stays unlocked permanently afterwards,
which later makes re‑rooting possible even after a full stock restore.

LED/key reference on the E4g:
- **Fastboot mode** (pink LED): hold **Volume Up** while plugging USB.
- **Flashmode** (green LED, Sony S1 protocol): hold **Volume Down** while plugging USB.

---

## 2. The root attempt — and the suspend freeze saga

This is the technically interesting part.

### Magisk can't read Sony's boot format

The E4g's `boot` partition is **not** a standard Android boot image. Sony wraps the
kernel + ramdisk in an **ELF** container (with MediaTek `KERNEL`/`ROOTFS` segment
headers). Magisk (v22.1 — the last version that still supports KitKat) uses
`magiskboot`, which only understands the standard `ANDROID!` boot format:

```
Unsupported/Unknown image format
```

**Workaround:** convert the carved ELF into a standard `ANDROID!` image so
`magiskboot` can patch it, then convert back. A critical detail is the MediaTek
kernel command line:

```
bootopt=64S3,32N2,32N2      ✅ correct (matches stock ELF)
bootopt=64S3,32S1,32N2      ❌ wrong → bootloop
```

The first patched flash with `32S1` bootlooped; rebuilding with `32N2` (matching the
stock cmdline) booted and gave **working Magisk root**.

### …but then it froze on sleep

With the `ANDROID!`‑format patched boot flashed via **fastboot**, the phone would
**freeze (black screen, green LED) whenever the screen turned off on battery**.
`/proc/last_kmsg` showed the hang right after the display suspend path:

```
mt_usb_disable → primary_display_suspend end → mt_pwm_shutdown → (hang)
```

A wakelock workaround didn't help. The decisive experiment was an **A/B test**:

| Boot flashed | Format | Transport | Suspend |
|--------------|--------|-----------|---------|
| Magisk‑patched | `ANDROID!` | fastboot | ❌ freezes |
| **Unpatched stock** | `ANDROID!` | fastboot | ❌ **still freezes** |
| Stock | ELF (SIN) | flashmode | ✅ stable |

The unpatched stock kernel **in `ANDROID!` format also froze**. That rules out Magisk
and root as the cause. The culprit is the **boot image format itself**: Sony's
bootloader passes different low‑level parameters when it loads a native ELF image vs.
a converted `ANDROID!` image, and the MediaTek suspend/resume path depends on them.

### Resolution: stability over root

Because this device was destined to live **in a pocket** as a music player, reliable
suspend was non‑negotiable. The fix was to flash the **full stock firmware (FTF) via
Sony flashmode**, restoring the native ELF kernel:

- ✅ Suspend/resume works perfectly again
- ❌ Root is gone (stock boot = no Magisk)
- ✅ Bootloader stays unlocked (re‑rooting remains possible later)

**Trade‑off accepted:** a music player doesn't need `su`. Everything from here on is
done **without root** — which, as it turns out, is almost everything you'd want.

---

## 3. The kiosk launcher (no root)

Goal: **the device boots straight into Walkman and the Home button always returns to
Walkman** — a single‑app kiosk. KitKat has no Screen Pinning (that's 5.0+), so the
clean no‑root approach is a **replacement HOME launcher**.

Rather than install a third‑party kiosk app, I wrote a tiny, auditable one. It
registers as `HOME` and immediately forwards to the Walkman package:

```java
public class HomeActivity extends Activity {
    private static final String WALKMAN_PKG = "com.sonyericsson.music";

    @Override protected void onResume() {
        super.onResume();
        Intent launch = getPackageManager().getLaunchIntentForPackage(WALKMAN_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        }
        finish();
    }
}
```

Full source + build instructions are in [`launcher/`](launcher/). It was built from a
**command‑line toolchain assembled from scratch** (portable JDK + `build-tools 30.0.3`
+ `android-19` platform), compiled with `javac`, dexed with `d8`, packaged with
`aapt`, aligned with `zipalign`, and signed with `apksigner` (v1+v2 — v1 is required
for KitKat).

Install and set as default Home:

```bash
adb install -r Walkman.apk
# On the device: press Home → choose "Walkman" → Always
```

Because another launcher (Xperia Home) is left installed, there's always an escape
hatch for maintenance (Settings → Apps → Walkman launcher → Clear defaults).

---

## 4. Hiding ~100 apps without root

The obvious `pm disable` is **denied** to the adb shell user on this Sony build:

```
pm disable com.facebook.katana
  → SecurityException: Permission Denial ... uid=2000
```

But a different code path, **`pm block`**, *is* allowed to the shell on KitKat and
hides the app (makes it un‑launchable):

```bash
pm block com.google.android.youtube
  → Package com.google.android.youtube new blocked state: true
```

Verification (the `pm list packages -b` filter is broken on KitKat, so check the
per‑user state directly):

```bash
dumpsys package com.google.android.youtube | grep "User 0"
  → User 0:  installed=true blocked=true stopped=true ... enabled=0
```

I blocked **~100** user‑facing apps (social, browser, games, video, camera, maps,
email, the dialer/contacts UI, Sony/Google bloat) while **keeping the system skeleton
intact** — SystemUI, Settings, the keyboard, telephony framework, media/storage
providers, Google Play Services (blocking GMS destabilizes the system), the kiosk
launcher, and of course Walkman. FM Radio was intentionally left installed.

> ⚠️ `pm block` is reversible (`pm unblock <pkg>`), but blocking the wrong system
> service will brick basic functionality. Block **user apps**, not providers/services.

---

## 5. Speed & battery

Pure‑adb, no‑root tuning that stuck across reboots:

```bash
# Snappier UI — animations off
settings put global window_animation_scale 0
settings put global transition_animation_scale 0
settings put global animator_duration_scale 0

# Battery: kill the radios and sensors you don't need in a music player
settings put global airplane_mode_on 1            # biggest single saver
settings put secure location_mode 0               # GPS off
# NFC and Bluetooth were already off under airplane mode

# Display
settings put system screen_off_timeout 30000
settings put system screen_brightness_mode 0      # manual
settings put system screen_brightness 60
```

The biggest *real‑world* saver isn't a setting — it's the habit: **start a track,
press power to turn the screen off, pocket it.** The display dominates drain; audio
playback with the screen off is cheap.

---

## 6. Updating the Walkman app — without root

The pre‑installed Walkman was `8.5.A.3.2`. Could we install a newer one **over a
system app without root**? Only if the new APK is signed with the **exact same
certificate** as the system app (Android requires a signature match to update).

### Extract the system app's signing certificate

```bash
adb pull /system/priv-app/SemcMusic.apk current.apk
# pull the cert file out of META-INF and print it
keytool -printcert -file META-INF/SONY-ERICSSON-E-APPLICATION-SIGNING-LIVE-864F-1.RSA
```

```
Owner:  CN=Sony_Ericsson_E_Application_Signing_Live_864f, O=Sony Ericsson ...
SHA256: BC:01:A8:CD:9E:5D:... (the device's app‑signing cert)
```

### Verify a candidate before installing

I downloaded newer **Sony Music** builds (same package `com.sonyericsson.music`) and
compared **the real X.509 cert SHA‑256** — not the misleading short "signature" label
some mirrors show — against the device's. This doubles as a **security check**: a
cryptographic match proves the file is genuinely Sony‑signed and untampered.

```
Downloaded 9.1.2.A.0.0 → SHA256 BC:01:A8:CD:9E:5D:...   ✅ identical
Device system app       → SHA256 BC:01:A8:CD:9E:5D:...
```

Surprisingly, the cert **matched**, so:

```bash
adb install -r sony_music_9.1.2.apk    # overlays the system app, no root
```

`8.5.A.3.2 → 9.1.2.A.0.0`, booted and ran fine. 🎉

### Where it stops: newer ≠ better on KitKat

I also tried the newest KitKat‑compatible build, `9.4.15.A.0.18` (`minSdk 19`). The
cert matched and it installed — but it **crashed on launch** when the navigation
drawer tried to render:

```
InflateException: Error inflating class com.sonyericsson.music.ui.DrawerItemView
  Caused by: NullPointerException
```

A 2024‑era build that *declares* `minSdk 19` still assumes UI resources/attributes
that don't exist on 4.4. Reverting is a one‑liner thanks to the signature match:

```bash
adb install -r -d sony_music_9.1.2.apk   # -d allows the version downgrade
```

**`9.1.2.A.0.0` is the sweet spot**: meaningfully newer than factory, still stable on
KitKat.

---

## Key takeaways

1. **On Sony/MediaTek, the boot image *format* can affect suspend/resume** — test
   native ELF vs. converted `ANDROID!` before blaming root.
2. **You almost never need root for a kiosk.** A replacement HOME launcher +
   `pm block` gets you a locked‑down single‑app device on stock firmware.
3. **`pm block` works where `pm disable` is denied** on some KitKat builds — and it's
   reversible.
4. **System apps can be updated without root** *iff* the new APK's certificate matches
   — and verifying that cert's SHA‑256 is also your authenticity/security check.
5. **"Supports Android 4.4" in the manifest is not a promise it runs on 4.4.** Verify
   on‑device; keep a downgrade path ready.

---

## Repository contents

```
.
├── README.md            ← this article
├── LICENSE              ← MIT
└── launcher/            ← the kiosk HOME launcher
    ├── AndroidManifest.xml
    ├── src/com/walkman/kiosk/HomeActivity.java
    └── BUILD.md         ← build it yourself (javac → d8 → aapt → zipalign → apksigner)
```

## Disclaimer

For educational purposes. Modifying your device (bootloader unlock, flashing,
blocking system apps) can void your warranty or render it unusable. **Unlocking the
bootloader wipes your data.** Do this only on a device you own, and at your own risk.
Sony, Xperia, and Walkman are trademarks of their respective owners; this project is
not affiliated with or endorsed by Sony.
