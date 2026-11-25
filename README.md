# Pixel 11 Band Lock

An independent LSPosed module that adds **Pixel 11 Band Lock** under **Band** on NSG's **Forcing** screen. The panel shows supported and selected LTE or NR bands for logical SIM 1. It reads back changes and offers to restore the masks saved before the first lock.

This build targets the rooted Pixel 11 Pro XL (`kodiak`), NSG qtrun 4.8.14 (version code 4081430), and LSPosed API 102. The module runs alongside NSG Tweaks and uses [Pixel MediaTek Radio](https://github.com/starks/pixel-mediatek-radio) for ATCI. The root manager must allow NSG to use `su`.

## Run

Download the debug APK from [Releases](https://github.com/starks/pixel-11-band-lock/releases). In LSPosed, enable **Pixel 11 Band Lock** and select **Network Signal Guru** as its scope. Restart NSG and open **Forcing Control → Band → Pixel 11 Band Lock**.

**Select known bands** uses bands enabled in the current or saved mask. You can also select individual bands the modem advertises. **Apply lock** checks the modem readback, and **Restore** uses the masks saved before the first change.

## Build

Place a checkout of Pixel MediaTek Radio next to this project or set `-PpixelAtciScript=/path/to/pixel-atci.sh`. The v0.1.3 APK bundles the client from [commit 6f09464](https://github.com/starks/pixel-mediatek-radio/commit/6f0946422c7326c74988699de765d2eb810de4fc). Build with Android SDK 37 and `./gradlew :app:assembleDebug`. The LSPosed API is a compile-only dependency. To use a local API 102 JAR, pass `-PxposedApiJar=/path/to/api.jar`.
