# Pixel 11 Band Lock

An independent LSPosed module that adds **Pixel 11 Band Lock** under **Band** on NSG's **Forcing** screen. The panel shows supported and selected LTE or NR bands for logical SIM 1. It reads back changes and offers to restore the masks saved before the first lock.

This build targets the rooted Pixel 11 Pro XL (`kodiak`), NSG qtrun 4.8.14 (version code 4081430), and LSPosed API 102. The module runs alongside NSG Tweaks and uses [Pixel MediaTek Radio](https://github.com/starks/pixel-mediatek-radio) for ATCI. The root manager must allow NSG to use `su`.

## Run

Install the debug APK. In LSPosed, enable **Pixel 11 Band Lock** and select **Network Signal Guru** as its scope. Restart NSG and open **Forcing Control → Band → Pixel 11 Band Lock**.

## Build

Place a checkout of Pixel MediaTek Radio next to this project or set `-PpixelAtciScript=/path/to/pixel-atci.sh`. Build with Android SDK 37 and `./gradlew :app:assembleDebug`. The LSPosed API is a compile-only dependency. To use a local API 102 JAR, pass `-PxposedApiJar=/path/to/api.jar`.
