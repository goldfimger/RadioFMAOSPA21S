# A21s FM Radio (Exynos 850 / LineageOS 22.2)

Aplikasi Radio FM ringan dan kompatibel untuk Samsung Galaxy A21s (`a21s`, Exynos 850 / s5e3830) pada ROM LineageOS 22.2.

## Fitur Utama
- Akses hardware V4L2 (`/dev/radio0`) & fallback `libfm_slsi.so` via JNI C++ (`fm_tuner_jni.cpp`).
- Audio streaming PCM 48kHz via Android `AudioTrack` + `AudioRecord(RADIO_TUNER)` dengan deteksi antena kabel 3.5mm dan switch output Headset/Speaker.
- Sinkronisasi frekuensi manual (step 50 / 100 / 200 kHz) dan Auto-Scan sweep berbasis ambang RSSI.
- Database SQLite lokal (`FmStationDbHelper.kt`) untuk menyimpan & mengelola stasiun favorit.
- Ekspor daftar stasiun ke format JSON melalui Storage Access Framework.
- Mode Gelap OLED untuk penggunaan malam hari.

## Cara Build
1. **Android Studio**: Buka folder root ini di Android Studio Ladybug+ (NDK & CMake 3.22.1 terpasang), lalu Build APK (`arm64-v8a`).
2. **LineageOS 22.2 Tree**: Salin folder ini ke `packages/apps/A21sFMRadio`, tambahkan `A21sFMRadio` ke `PRODUCT_PACKAGES`, dan terapkan catatan pada `device/samsung/a21s/lineage_fm_setup.md`.
