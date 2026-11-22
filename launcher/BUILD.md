# Building the kiosk launcher

No Android Studio needed — this is a tiny app you can build with the command‑line
tools. Below is the exact manual pipeline used, which works on any OS with a JDK.

## Toolchain

- A **JDK** (8–17 all work for this; `javac`, `keytool`).
- Android **`build-tools`** (e.g. `30.0.3`, the last with `dx`; `d8` is used here) —
  provides `aapt`, `d8`, `zipalign`, `apksigner`.
- The **`android-19`** platform (`android.jar`) to compile against.

You can get the last two from the Android SDK, or download the component zips
directly:
`platform-tools`/`build-tools_r30.0.3` and `android-19` from Google's repository.

## Build pipeline

Assuming `AJAR=/path/to/android-19/android.jar` and the build‑tools on `PATH`:

```bash
# 1. compile
javac -source 8 -target 8 -bootclasspath "$AJAR" -d build/classes \
    src/com/walkman/kiosk/HomeActivity.java

# 2. package the manifest into a base APK
aapt package -f -M AndroidManifest.xml -I "$AJAR" -F build/app.unsigned.apk

# 3. class -> dex  (run d8 via java if the .bat/.sh wrapper misbehaves:
#    java -cp <build-tools>/lib/d8.jar com.android.tools.r8.D8 ... )
d8 --min-api 19 --lib "$AJAR" --output build/ build/classes/com/walkman/kiosk/HomeActivity.class

# 4. add classes.dex into the APK (run from the build/ dir)
( cd build && aapt add app.unsigned.apk classes.dex )

# 5. align
zipalign -f 4 build/app.unsigned.apk build/app.aligned.apk

# 6. a throwaway signing key (use your own values)
keytool -genkeypair -keystore walkman.jks -alias walkman -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass changeit -keypass changeit -dname "CN=Walkman Kiosk"

# 7. sign (v1 is REQUIRED for KitKat; keep v2 too)
apksigner sign --ks walkman.jks --ks-pass pass:changeit --key-pass pass:changeit \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out Walkman.apk build/app.aligned.apk

# 8. verify
apksigner verify --print-certs Walkman.apk
```

## Install & set as Home

```bash
adb install -r Walkman.apk
# On the device: press Home → choose "Walkman" → Always
```

## Undo

```bash
# stop being the default launcher: Settings → Apps → Walkman → Clear defaults,
# then pick your normal launcher. Or uninstall:
adb uninstall com.walkman.kiosk
```

## Notes

- `Theme.Translucent.NoTitleBar` keeps the forwarder invisible with a normal
  lifecycle. `Theme.NoDisplay` also works but is stricter about finishing before the
  window shows.
- No resources (`res/`) are needed; the app references none, so `aapt` builds fine
  without an `-S` directory. The launcher shows a default icon in the Home picker.
- Keep another launcher installed so you always have a way out.
