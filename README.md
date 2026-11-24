# A21s FM Radio (Exynos 850 / LineageOS 22.2)

Aplikasi Radio FM ringan dan kompatibel untuk Samsung Galaxy A21s (`a21s`, Exynos 850 / s5e3830) pada ROM LineageOS 22.2.

## Stack Build Terbaru (2026)
- **Android Gradle Plugin (AGP)**: `9.0.1` (Built-in Kotlin)
- **Gradle**: `9.3.1`
- **Kotlin Compose Compiler**: `2.3.10` + Compose BOM `2026.04.01`
- **Compile SDK**: `36` / Target SDK: `35` (LineageOS 22.2 / Android 15)
- **GitHub Actions**: `actions/checkout@v7`, `actions/setup-java@v6` (JDK 21), `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v4`

## Cara Compile & Build APK di GitHub Actions
1. Ekstrak file ZIP ini dan upload/push seluruh isi foldernya ke repositori GitHub baru (branch `main` atau `master`).
2. Pastikan folder `.github/workflows/build-apk.yml` ikut terunggah ke root repositori.
3. Buka tab **Actions** di GitHub repositori Anda — workflow **Build A21s FM Radio APK** akan berjalan otomatis (atau klik **Run workflow**).
4. Setelah selesai, unduh APK dari bagian **Artifacts**:
   - `A21s-FM-Radio-Debug-APK` (sudah ditandatangani debug key, siap langsung di-install di A21s)
   - `A21s-FM-Radio-Release-Unsigned-APK` (untuk ditandatangani keystore sendiri atau platform key ROM)

## Cara Build di Tree LineageOS 22.2
Salin folder ini ke `packages/apps/A21sFMRadio`, tambahkan `A21sFMRadio` ke `PRODUCT_PACKAGES`, dan ikuti panduan di `device/samsung/a21s/lineage_fm_setup.md`.
