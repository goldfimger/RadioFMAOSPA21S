#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <dlfcn.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "A21sFmNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr const char* kRadioDevicePaths[] = {
    "/dev/radio0",
    "/dev/radio1",
    "/dev/fmradio",
    "/dev/s610_fm",
    "/dev/fm"
};

int gRadioFd = -1;
bool gCapLowFreq = true; // true = 62.5 Hz units (V4L2_TUNER_CAP_LOW)
void* gVendorLibHandle = nullptr;
std::string gLastError = "";
std::string gOpenedNode = "";

// Optional vendor symbol hooks for Samsung Exynos SLSI FM library
using VendorSetFreqFn = int (*)(int freqKhz);
using VendorSeekFn = int (*)(int up, int spacingKhz);
VendorSetFreqFn gVendorSetFreq = nullptr;
VendorSeekFn gVendorSeek = nullptr;

void tryLoadVendorFmLib() {
    if (gVendorLibHandle != nullptr) return;
    const char* candidates[] = {
        "/vendor/lib64/libfm_slsi.so",
        "/vendor/lib64/libfm.so",
        "/system/lib64/libfm_slsi.so"
    };
    for (const char* path : candidates) {
        void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (handle != nullptr) {
            gVendorLibHandle = handle;
            gVendorSetFreq = reinterpret_cast<VendorSetFreqFn>(dlsym(handle, "fm_set_frequency"));
            gVendorSeek = reinterpret_cast<VendorSeekFn>(dlsym(handle, "fm_seek_frequency"));
            LOGI("Loaded vendor FM library: %s", path);
            break;
        }
    }
}

uint32_t khzToV4l2Freq(int freqKhz) {
    // V4L2 frequency unit: 62.5 Hz when V4L2_TUNER_CAP_LOW is set, else 62.5 kHz
    if (gCapLowFreq) {
        return static_cast<uint32_t>(freqKhz * 16); // (freqKhz * 1000) / 62.5
    }
    return static_cast<uint32_t>(freqKhz / 62.5);
}

int v4l2FreqToKhz(uint32_t rawFreq) {
    if (gCapLowFreq) {
        return static_cast<int>(rawFreq / 16);
    }
    return static_cast<int>(rawFreq * 62.5);
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeOpenTuner(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (gRadioFd >= 0) return JNI_TRUE;

    tryLoadVendorFmLib();
    gLastError.clear();
    gOpenedNode.clear();

    const int openFlags[] = { O_RDWR | O_NONBLOCK, O_RDWR, O_RDONLY | O_NONBLOCK, O_RDONLY };
    int primaryErrno = ENOENT;
    std::string attemptLog;

    for (const char* devPath : kRadioDevicePaths) {
        for (int flags : openFlags) {
            errno = 0;
            int fd = open(devPath, flags);
            if (fd >= 0) {
                gRadioFd = fd;
                gOpenedNode = devPath;

                struct v4l2_tuner tuner {};
                tuner.index = 0;
                tuner.type = V4L2_TUNER_RADIO;
                if (ioctl(gRadioFd, VIDIOC_G_TUNER, &tuner) == 0) {
                    gCapLowFreq = (tuner.capability & V4L2_TUNER_CAP_LOW) != 0;
                    tuner.audmode = V4L2_TUNER_MODE_STEREO;
                    ioctl(gRadioFd, VIDIOC_S_TUNER, &tuner);
                }

                // Unmute S610/V4L2 FM demodulator upon power on
                struct v4l2_control muteCtrl {};
                muteCtrl.id = V4L2_CID_AUDIO_MUTE;
                muteCtrl.value = 0;
                ioctl(gRadioFd, VIDIOC_S_CTRL, &muteCtrl);

                LOGI("Opened FM tuner node %s (fd=%d, flags=%d, capLow=%d)", devPath, fd, flags, gCapLowFreq);
                return JNI_TRUE;
            }
            int err = errno;
            if (strcmp(devPath, "/dev/radio0") == 0 && flags == (O_RDWR | O_NONBLOCK)) {
                primaryErrno = err;
            }
        }
        int checkErr = errno;
        attemptLog += std::string(devPath) + ": " + strerror(checkErr) + " (" + std::to_string(checkErr) + "); ";
    }

    if (primaryErrno == EACCES || primaryErrno == EPERM) {
        gLastError = "EACCES (" + std::to_string(primaryErrno) + "): /dev/radio0 ada, tetapi akses ditolak oleh SELinux / izin aplikasi biasa. Gunakan Root (su) atau install sebagai priv-app LineageOS.";
    } else if (primaryErrno == ENOENT) {
        gLastError = "ENOENT (2): Node /dev/radio0 tidak ditemukan di kernel. Pastikan kernel LineageOS 22.2 A21s mengaktifkan CONFIG_RADIO_S610=y.";
    } else {
        gLastError = "Gagal membuka tuner (" + std::to_string(primaryErrno) + "): " + attemptLog;
    }

    LOGE("Failed to open FM device node: %s", gLastError.c_str());
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeGetLastError(
        JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeGetDiagnosticReport(
        JNIEnv* env, jobject /* thiz */) {
    std::string report;

    report += "Node /dev/radio* & /dev/fm*:\n";
    DIR* devDir = opendir("/dev");
    int foundNodes = 0;
    if (devDir != nullptr) {
        struct dirent* ent;
        while ((ent = readdir(devDir)) != nullptr) {
            if (strncmp(ent->d_name, "radio", 5) == 0 ||
                strncmp(ent->d_name, "fm", 2) == 0 ||
                strncmp(ent->d_name, "s610", 4) == 0) {
                std::string full = std::string("/dev/") + ent->d_name;
                int acc = access(full.c_str(), R_OK | W_OK);
                report += " - " + full + (acc == 0 ? " [RW OK]" : std::string(" [") + strerror(errno) + "]") + "\n";
                foundNodes++;
            }
        }
        closedir(devDir);
    } else {
        report += " - Tidak bisa membaca /dev (" + std::string(strerror(errno)) + ")\n";
    }
    if (foundNodes == 0) {
        report += " - Tidak ada node radio/fm terdeteksi di /dev\n";
    }

    report += "Sysfs V4L2 (/sys/class/video4linux):\n";
    DIR* v4lDir = opendir("/sys/class/video4linux");
    if (v4lDir != nullptr) {
        struct dirent* ent;
        int count = 0;
        while ((ent = readdir(v4lDir)) != nullptr) {
            if (ent->d_name[0] == '.') continue;
            report += " - " + std::string(ent->d_name) + "\n";
            count++;
        }
        if (count == 0) report += " - Kosong\n";
        closedir(v4lDir);
    } else {
        report += " - " + std::string(strerror(errno)) + "\n";
    }

    report += "Vendor libfm:\n";
    const char* libs[] = { "/vendor/lib64/libfm_slsi.so", "/vendor/lib64/libfm.so", "/system/lib64/libfm_slsi.so" };
    for (const char* lib : libs) {
        bool exists = (access(lib, F_OK) == 0);
        report += " - " + std::string(lib) + (exists ? " [ADA]" : " [TIDAK ADA]") + "\n";
    }

    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeCloseTuner(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (gRadioFd >= 0) {
        close(gRadioFd);
        gRadioFd = -1;
    }
    if (gVendorLibHandle != nullptr) {
        dlclose(gVendorLibHandle);
        gVendorLibHandle = nullptr;
        gVendorSetFreq = nullptr;
        gVendorSeek = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeSetFrequency(
        JNIEnv* /* env */, jobject /* thiz */, jint freqKhz) {
    if (freqKhz < 87500 || freqKhz > 108000) return JNI_FALSE;

    if (gVendorSetFreq != nullptr && gVendorSetFreq(freqKhz) == 0) {
        return JNI_TRUE;
    }
    if (gRadioFd < 0) return JNI_FALSE;

    struct v4l2_frequency freq {};
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    freq.frequency = khzToV4l2Freq(freqKhz);

    if (ioctl(gRadioFd, VIDIOC_S_FREQUENCY, &freq) < 0) {
        LOGE("VIDIOC_S_FREQUENCY(%d kHz) failed: %s", freqKhz, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeGetFrequency(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (gRadioFd < 0) return -1;

    struct v4l2_frequency freq {};
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    if (ioctl(gRadioFd, VIDIOC_G_FREQUENCY, &freq) < 0) {
        return -1;
    }
    return v4l2FreqToKhz(freq.frequency);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeGetSignalInfo(
        JNIEnv* env, jobject /* thiz */) {
    // Returns [rssiDbm, signalPercent, isStereo(0|1), afcOffsetHz]
    jint out[4] = {-104, 0, 0, 0};

    if (gRadioFd >= 0) {
        struct v4l2_tuner tuner {};
        tuner.index = 0;
        tuner.type = V4L2_TUNER_RADIO;
        if (ioctl(gRadioFd, VIDIOC_G_TUNER, &tuner) == 0) {
            // V4L2 signal range is 0..65535 -> map cleanly to -108..-42 dBm
            int normalizedPct = static_cast<int>((tuner.signal * 100L) / 65535L);
            int rssiDbm = -108 + ((normalizedPct * 66) / 100);
            int isStereo = (tuner.rxsubchans & V4L2_TUNER_SUB_STEREO) ? 1 : 0;
            out[0] = rssiDbm;
            out[1] = normalizedPct;
            out[2] = isStereo;
            out[3] = tuner.afc;
        }
    }

    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, out);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeSeekFrequency(
        JNIEnv* /* env */, jobject /* thiz */, jboolean seekUp, jint spacingKhz) {
    if (gVendorSeek != nullptr) {
        int res = gVendorSeek(seekUp ? 1 : 0, spacingKhz);
        if (res >= 87500) return res;
    }
    if (gRadioFd < 0) return -1;

    struct v4l2_hw_freq_seek seek {};
    seek.tuner = 0;
    seek.type = V4L2_TUNER_RADIO;
    seek.seek_upward = seekUp ? 1 : 0;
    seek.wrap_around = 1;
    seek.spacing = static_cast<__u32>(spacingKhz * 1000);

    if (ioctl(gRadioFd, VIDIOC_S_HW_FREQ_SEEK, &seek) < 0) {
        LOGE("VIDIOC_S_HW_FREQ_SEEK failed: %s", strerror(errno));
        return -1;
    }

    struct v4l2_frequency freq {};
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    if (ioctl(gRadioFd, VIDIOC_G_FREQUENCY, &freq) < 0) {
        return -1;
    }
    return v4l2FreqToKhz(freq.frequency);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_exynos850_fmradio_nativebridge_FmNativeTuner_nativeSetMute(
        JNIEnv* /* env */, jobject /* thiz */, jboolean mute) {
    if (gRadioFd < 0) return JNI_FALSE;

    struct v4l2_control ctrl {};
    ctrl.id = V4L2_CID_AUDIO_MUTE;
    ctrl.value = mute ? 1 : 0;
    return ioctl(gRadioFd, VIDIOC_S_CTRL, &ctrl) == 0 ? JNI_TRUE : JNI_FALSE;
}
