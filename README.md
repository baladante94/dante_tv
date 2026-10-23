# Dante TV

A tvOS-style home screen for Android TV and Google TV, with a Top Shelf of what's trending on your
streaming services, a left menu rail and a remote-button quick settings panel. It also includes the
tools to debloat the TV. **No root, nothing uninstalled, and a one-line restore.**

![Dante TV home: Trending now shelf, left menu rail and a six-app dock](docs/dante-home.png)

Built and tested on a Philips Smart 4K Google TV (Skyworth eleonas, Google TV 14, 1.8 GB RAM).
It's a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv), which did the
debloat tooling and the original launcher.

## What you get

**Home**
- **Left menu rail** with Home, All Apps, Inputs, Wi-Fi, Quick, Optimize and Settings. Press Left from anywhere on Home to open it.
- **Top Shelf** above the dock shows the Netflix Top 10 for your country plus Apple TV and Crunchyroll picks from TMDB. It has Explore, Movies and Shows tabs and rotates every 5 minutes.
- **Six-app dock**, plus a **Jump back in** row of the apps you opened last.
- **tvOS focus motion**: tiles tilt, drift and catch a glint when you focus them.
- **Aurora wallpaper with a moving sky**, a clock and greeting at the top right, and an Aurora screensaver.

**Panels that open in place, no jumping into the TV's settings screens**
- **All Apps** opens as a Launchpad-style grid.
- **Wi-Fi** lists nearby networks. Saved and open networks join directly, and secured ones ask for the password.
- **Inputs** lists HDMI ports with the names you gave them in Settings, their connection state, and Live TV.
- **Quick** has a sleep timer (hold OK to sleep now), Picture & sound, and Bluetooth headphones.
- **Optimize** frees RAM by force-stopping background apps, then shows how much it freed.

**The remote's ⚙ button** opens a quick settings panel from the right edge, over any app. It has
Wi-Fi, Bluetooth and Inputs lists inside the panel, Picture, MEMC, Sound, the sleep timer, Optimize
and All settings. Optimize from here leaves the app you're watching running. Home or Back closes it.

**MEMC** (motion smoothing) switches between Off, Standard, Sports and Smooth while the video keeps
playing. MediaTek TVs only, with the TV menu in English. The TV keeps MEMC per picture mode, so set
it once while watching SDR, HDR and Dolby Vision content.

**Lights** (experimental) drives a Magic Home Wi-Fi LED controller behind the TV, locally, with no
cloud. The row appears in the ⚙ panel only when a controller answers on your Wi-Fi.
- **Static**: one colour and brightness. **Sound**: pulses with what's playing, following the lows,
  mids and highs, with a flash on each kick. The **Rainbow** colour fades through every hue, or
  drifts and jumps with the beat in Sound.
- The strip turns on and off with the TV and picks up again by itself after a Wi-Fi change.
- Sound reads the TV's own audio mix. When an HDMI input sends Dolby straight to the soundbar
  (a Fire TV, say), it uses the TV's microphone instead. Only levels are worked out, on the TV,
  and nothing is recorded. It needs one permission:
  `adb shell pm grant com.dante.tv android.permission.RECORD_AUDIO`
- The TV and the controller have to be on the same router. Tested only with an AK001-ZJ21412
  RGB controller (Zengge, model 0x33); addressable (per-LED) strips use other commands.

**Arranging apps**: hold OK on a tile and choose Move, then use the arrows and press OK to drop it.
To reset the order, hold OK on Settings and choose Reset app order.

**Safety net**: if Home crashes 3 times in 5 minutes it starts in safe mode, without the shelf.
If the TV switches the accessibility services off, Home turns them back on by itself.

## Lightweight by design

The point is a faster TV than stock Google TV, so every feature has to cost nothing while idle.
Measured on the 1.8 GB Philips:

- **391 KB** APK, plain Java, no frameworks beyond RecyclerView.
- **About 60 MB** of memory on Home, mostly poster images. When a game or film needs memory, Home hands
  the posters back (about 18 MB) and redraws them when you return.
- **0% CPU** while idle, on Home or behind another app. No background service polls anything; the
  shelf refreshes only when you come back to Home and its data is stale.
- **About 220 ms** to come back to Home, with 60 fps focus motion.

## Setting up the Top Shelf

The shelf uses [TMDB](https://www.themoviedb.org) for posters, and each TV needs its own free key.
No key ships with the app.

1. Get a key at themoviedb.org → Settings → API. Either the API key or the Read Access Token works.
2. On the TV, hold OK on **Settings** in the rail and choose **Top Shelf setup**.
3. Scan the QR code with your phone, which must be on the same Wi-Fi, and paste the key. The TV checks the key and saves it.

The region comes from the TV's country setting. The Netflix Top 10 comes from Netflix's public
Tudum data and needs no key. The key is stored only in the app's private storage on the TV.

## Install

### 1. Turn on debugging on the TV

Settings → System → About → press OK on **Build** seven times. Then go to Developer options and turn on
**Wireless debugging** (Google TV) or USB/Network debugging (Android TV). Keep the TV on.

### 2. Get adb on your computer

**macOS**

```bash
brew install android-platform-tools
```

**Windows** (PowerShell)

```powershell
winget install Google.PlatformTools
```

Then open a new PowerShell window so `adb` is on the PATH. Without winget, download
[SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), unzip it and run `.\adb.exe`
from that folder.

### 3. Connect to the TV

On Google TV, Wireless debugging shows a pairing code and two ports (Pair device with pairing code). The
commands are the same on macOS and Windows:

```bash
adb pair <tv ip>:<pairing port>          # once, enter the code shown on the TV
adb connect <tv ip>:<port>
```

The port changes every time the TV restarts; `adb mdns services` lists the current one. On an Android
TV with network debugging, it is just `adb connect <tv ip>`.

### 4. Install Dante TV

**From a release:** download `dante-tv-<version>.apk` from
[Releases](https://github.com/baladante94/dante_tv/releases), then (macOS or Windows):

```bash
adb install -r dante-tv-1.1.0.apk
adb shell pm grant com.dante.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd package set-home-activity --user 0 com.dante.tv/.MainActivity
```

Press Home on the remote. The `pm grant` line lets Home turn its accessibility services on by itself, now
and whenever the TV switches them off; `set-home-activity` makes Dante TV the home screen.

**From source:** you need JDK 17 and the Android SDK (platform 34).

macOS:

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
cd launcher && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Windows (PowerShell). The easiest route is Android Studio, which brings its own JDK and the SDK;
open it once and let it finish setting up, then install Android 14 (API 34) under More Actions → SDK Manager:

```powershell
winget install Google.AndroidStudio
cd launcher
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Then run the `pm grant` and `set-home-activity` lines from above. Builds are signed with the debug key
unless `launcher/keystore.properties` points at a release key.

On an Android TV with plain network ADB (port 5555), the scripts do the whole install, including switching
Home and enabling the remote-key service: `TV_HOST=<tv ip> INPUT_KEY_SERVICE=1 launcher/build-and-install.sh`
on macOS or Linux, `launcher\build-and-install.ps1 -TvHost <tv ip> -InputKeyService` on Windows.

Dante TV runs two accessibility services, which switch themselves on once `pm grant` is done:

| Service | What it does |
|---|---|
| Dante TV (InputKeyService) | The remote's ⚙ opens the quick settings panel and the Input button opens the input switcher. Every other key passes through. |
| Dante TV Optimize | Presses Force stop in TV Settings for each background app, under a cover screen. It only acts while Optimize runs. |

The app targets SDK 28, which lets it join Wi-Fi networks and connect Bluetooth headphones directly.

**Configure** dock apps, hidden apps, the Live TV package and the column count in
[`config.xml`](launcher/app/src/main/res/values/config.xml), or push a theme without rebuilding
using `tools/theme.sh themes/aurora` (`tools\theme.ps1 -TvHost <tv ip> themes\aurora` on Windows).

## Debloating

The tools from cleanyourtv still work. They need Python 3 only:

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=<tv ip>

tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/disable.sh examples/<your tv>/debloat.txt              # disable a list of packages
tools/restore.sh examples/<your tv>/debloat.txt              # undo it
```

On Windows (PowerShell), with Python 3 from [python.org](https://www.python.org/downloads/) or
`winget install Python.Python.3.12`:

```powershell
python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
tools\venv\Scripts\python.exe -I tools\tv.py --probe
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <tv ip> examples\<your tv>\debloat.txt
powershell -ExecutionPolicy Bypass -File tools\restore.ps1 -TvHost <tv ip> examples\<your tv>\debloat.txt
```

[`examples/`](examples/) has tested lists for 66 TV models. Packages are disabled with
`pm disable-user`, not removed, so restore brings everything back. If your TV isn't listed, paste
[`PROMPT.md`](PROMPT.md) into a coding assistant and it will find the bloat on your TV.

## Restoring

```bash
TV_HOST=<tv ip> tools/restore.sh examples/<your tv>/debloat.txt com.google.android.apps.tv.launcherx  # Google TV
```

This re-enables the listed packages and puts the stock launcher back as Home. Dante TV stays
installed; remove it from Settings → Apps if you want it gone.

Common problems are covered in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). The one most
likely to catch you: **on Google TV, only disable the stock launcher after Dante TV is installed
and running**, or Home lands on a broken setup screen.

## Credits

- [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv): debloat tools, TV lists and the original launcher.
- [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv): inspiration for the shelf card style.
- Movie and show data from [TMDB](https://www.themoviedb.org). This product uses the TMDB API but is not endorsed or certified by TMDB.
- [Outfit](https://fonts.google.com/specimen/Outfit) typeface, SIL Open Font License.

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font License, see
`launcher/FONT-LICENSE-OFL.txt`.
