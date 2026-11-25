#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#if __has_include(<sound/asound.h>)
#include <sound/asound.h>
#define HAS_ALSA_UAPI 1
#else
#define HAS_ALSA_UAPI 0
#endif
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr const char* kRadioPaths[] = {
    "/dev/radio0",
    "/dev/radio1",
    "/dev/fmradio",
    "/dev/s610_fm",
    "/dev/fm"
};

int gFd = -1;
bool gCapLow = true;

uint32_t khzToV4l2(int freqKhz) {
    return gCapLow ? static_cast<uint32_t>(freqKhz * 16) : static_cast<uint32_t>(freqKhz / 62.5);
}

int v4l2ToKhz(uint32_t raw) {
    return gCapLow ? static_cast<int>(raw / 16) : static_cast<int>(raw * 62.5);
}

std::string toLowerStr(std::string s) {
    for (char& ch : s) ch = static_cast<char>(tolower(static_cast<unsigned char>(ch)));
    return s;
}

#if HAS_ALSA_UAPI
void listFmAlsaControls() {
    int ctlFd = open("/dev/snd/controlC0", O_RDWR);
    if (ctlFd < 0) {
        std::cout << "ERR open /dev/snd/controlC0: " << strerror(errno) << std::endl;
        return;
    }
    struct snd_ctl_elem_list list {};
    if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0 || list.count == 0) {
        std::cout << "ERR elem_list: " << strerror(errno) << std::endl;
        close(ctlFd);
        return;
    }
    std::vector<struct snd_ctl_elem_id> ids(list.count);
    list.offset = 0;
    list.space = list.count;
    list.pids = ids.data();
    if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) {
        std::cout << "ERR elem_list2: " << strerror(errno) << std::endl;
        close(ctlFd);
        return;
    }
    std::string out;
    for (unsigned int i = 0; i < list.used; ++i) {
        std::string name(reinterpret_cast<const char*>(ids[i].name));
        std::string lower = toLowerStr(name);
        if (lower.find("fm") == std::string::npos &&
            lower.find("spdy") == std::string::npos &&
            lower.find("sifm") == std::string::npos &&
            lower.find("nsrc") == std::string::npos &&
            lower.find("sifs") == std::string::npos &&
            lower.find("spusm") == std::string::npos) {
            continue;
        }
        struct snd_ctl_elem_info info {};
        info.id = ids[i];
        if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) continue;

        struct snd_ctl_elem_value val {};
        val.id = ids[i];
        ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_READ, &val);

        out += name + "=";
        if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            unsigned int curIdx = val.value.enumerated.item[0];
            struct snd_ctl_elem_info enumInfo = info;
            enumInfo.value.enumerated.item = curIdx;
            if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_INFO, &enumInfo) == 0) {
                out += enumInfo.value.enumerated.name;
            } else {
                out += std::to_string(curIdx);
            }
            out += " [";
            unsigned int items = info.value.enumerated.items;
            if (items > 12) items = 12;
            for (unsigned int k = 0; k < items; ++k) {
                enumInfo.value.enumerated.item = k;
                if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_INFO, &enumInfo) == 0) {
                    if (k > 0) out += ",";
                    out += enumInfo.value.enumerated.name;
                }
            }
            out += "]";
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER || info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
            out += std::to_string(val.value.integer.value[0]);
        } else {
            out += "?";
        }
        out += "; ";
    }
    close(ctlFd);
    std::cout << "OK " << (out.empty() ? "none" : out) << std::endl;
}

bool setAlsaControl(const std::string& ctlName, const std::string& ctlVal) {
    int ctlFd = open("/dev/snd/controlC0", O_RDWR);
    if (ctlFd < 0) return false;

    struct snd_ctl_elem_list list {};
    if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0 || list.count == 0) {
        close(ctlFd);
        return false;
    }
    std::vector<struct snd_ctl_elem_id> ids(list.count);
    list.offset = 0;
    list.space = list.count;
    list.pids = ids.data();
    if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) {
        close(ctlFd);
        return false;
    }

    bool ok = false;
    for (unsigned int i = 0; i < list.used; ++i) {
        if (ctlName != reinterpret_cast<const char*>(ids[i].name)) continue;
        struct snd_ctl_elem_info info {};
        info.id = ids[i];
        if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) break;

        struct snd_ctl_elem_value val {};
        val.id = ids[i];
        ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_READ, &val);

        if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            for (unsigned int k = 0; k < info.value.enumerated.items; ++k) {
                struct snd_ctl_elem_info eInfo = info;
                eInfo.value.enumerated.item = k;
                if (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_INFO, &eInfo) == 0 &&
                    ctlVal == eInfo.value.enumerated.name) {
                    for (unsigned int ch = 0; ch < info.count && ch < 128; ++ch) {
                        val.value.enumerated.item[ch] = k;
                    }
                    ok = (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) == 0);
                    break;
                }
            }
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER || info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
            long numVal = strtol(ctlVal.c_str(), nullptr, 10);
            for (unsigned int ch = 0; ch < info.count && ch < 128; ++ch) {
                val.value.integer.value[ch] = numVal;
            }
            ok = (ioctl(ctlFd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) == 0);
        }
        break;
    }
    close(ctlFd);
    return ok;
}

int runPcmCaptureStream(int card, int dev, unsigned int rate, unsigned int channels) {
    char devPath[64];
    snprintf(devPath, sizeof(devPath), "/dev/snd/pcmC%dD%dc", card, dev);
    int pcmFd = open(devPath, O_RDWR);
    if (pcmFd < 0) {
        fprintf(stderr, "ERR open %s: %s\n", devPath, strerror(errno));
        return 1;
    }

    struct snd_pcm_hw_params params {};
    params.rmask = ~0U;
    for (size_t i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; ++i) {
        memset(params.masks[i].bits, 0xff, sizeof(params.masks[i].bits));
    }
    for (size_t i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; ++i) {
        params.intervals[i].min = 0;
        params.intervals[i].max = ~0U;
    }

    auto setMask = [&](int var, unsigned int bit) {
        auto* m = &params.masks[var - SNDRV_PCM_HW_PARAM_FIRST_MASK];
        memset(m, 0, sizeof(*m));
        m->bits[bit / 32] |= (1U << (bit % 32));
    };
    auto setInterval = [&](int var, unsigned int minV, unsigned int maxV) {
        auto* iv = &params.intervals[var - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
        memset(iv, 0, sizeof(*iv));
        iv->min = minV;
        iv->max = maxV;
        iv->integer = 1;
    };

    setMask(SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    setMask(SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    setMask(SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
    setInterval(SNDRV_PCM_HW_PARAM_CHANNELS, channels, channels);
    setInterval(SNDRV_PCM_HW_PARAM_RATE, rate, rate);
    setInterval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE, 1024, 1024);
    setInterval(SNDRV_PCM_HW_PARAM_PERIODS, 4, 4);

    if (ioctl(pcmFd, SNDRV_PCM_IOCTL_HW_PARAMS, &params) < 0) {
        fprintf(stderr, "ERR hw_params %s: %s\n", devPath, strerror(errno));
        close(pcmFd);
        return 2;
    }
    if (ioctl(pcmFd, SNDRV_PCM_IOCTL_PREPARE) < 0) {
        fprintf(stderr, "ERR prepare %s: %s\n", devPath, strerror(errno));
        close(pcmFd);
        return 3;
    }

    unsigned int framesPerRead = 1024;
    size_t frameBytes = static_cast<size_t>(channels) * 2;
    std::vector<char> buf(framesPerRead * frameBytes);

    while (true) {
        struct snd_xferi xfer {};
        xfer.buf = buf.data();
        xfer.frames = framesPerRead;
        if (ioctl(pcmFd, SNDRV_PCM_IOCTL_READI_FRAMES, &xfer) < 0) {
            if (errno == EPIPE || errno == ESTRPIPE) {
                ioctl(pcmFd, SNDRV_PCM_IOCTL_PREPARE);
                continue;
            }
            break;
        }
        if (xfer.result <= 0) continue;
        size_t bytes = static_cast<size_t>(xfer.result) * frameBytes;
        ssize_t written = write(STDOUT_FILENO, buf.data(), bytes);
        if (written <= 0) break;
    }
    close(pcmFd);
    return 0;
}
#endif
} // namespace

int main(int argc, char** argv) {
    if (argc >= 6 && strcmp(argv[1], "pcm") == 0) {
#if HAS_ALSA_UAPI
        int card = atoi(argv[2]);
        int dev = atoi(argv[3]);
        unsigned int rate = static_cast<unsigned int>(atoi(argv[4]));
        unsigned int ch = static_cast<unsigned int>(atoi(argv[5]));
        return runPcmCaptureStream(card, dev, rate, ch);
#else
        return 1;
#endif
    }

    std::ios::sync_with_stdio(false);
    std::cin.tie(nullptr);

    std::string line;
    while (std::getline(std::cin, line)) {
        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;
        if (cmd.empty()) continue;

        if (cmd == "OPEN") {
            if (gFd >= 0) {
                std::cout << "OK already_open" << std::endl;
                continue;
            }
            const int flagsList[] = { O_RDWR | O_NONBLOCK, O_RDWR, O_RDONLY | O_NONBLOCK, O_RDONLY };
            int lastErr = ENOENT;
            std::string opened;
            for (const char* p : kRadioPaths) {
                for (int fl : flagsList) {
                    errno = 0;
                    int fd = open(p, fl);
                    if (fd >= 0) {
                        gFd = fd;
                        opened = p;
                        struct v4l2_tuner tuner {};
                        tuner.index = 0;
                        tuner.type = V4L2_TUNER_RADIO;
                        if (ioctl(gFd, VIDIOC_G_TUNER, &tuner) == 0) {
                            gCapLow = (tuner.capability & V4L2_TUNER_CAP_LOW) != 0;
                            tuner.audmode = V4L2_TUNER_MODE_STEREO;
                            ioctl(gFd, VIDIOC_S_TUNER, &tuner);
                        }
                        struct v4l2_control muteCtrl {};
                        muteCtrl.id = V4L2_CID_AUDIO_MUTE;
                        muteCtrl.value = 0;
                        ioctl(gFd, VIDIOC_S_CTRL, &muteCtrl);
                        break;
                    }
                    lastErr = errno;
                }
                if (gFd >= 0) break;
            }
            if (gFd >= 0) {
                std::cout << "OK " << opened << std::endl;
            } else {
                std::cout << "ERR " << lastErr << " " << strerror(lastErr) << std::endl;
            }
        } else if (cmd == "TUNE") {
            int khz = 98700;
            iss >> khz;
            if (gFd < 0) {
                std::cout << "ERR not_open" << std::endl;
                continue;
            }
            struct v4l2_frequency freq {};
            freq.tuner = 0;
            freq.type = V4L2_TUNER_RADIO;
            freq.frequency = khzToV4l2(khz);
            if (ioctl(gFd, VIDIOC_S_FREQUENCY, &freq) == 0) {
                std::cout << "OK" << std::endl;
            } else {
                std::cout << "ERR " << errno << " " << strerror(errno) << std::endl;
            }
        } else if (cmd == "GETFREQ") {
            if (gFd < 0) {
                std::cout << "ERR not_open" << std::endl;
                continue;
            }
            struct v4l2_frequency freq {};
            freq.tuner = 0;
            freq.type = V4L2_TUNER_RADIO;
            if (ioctl(gFd, VIDIOC_G_FREQUENCY, &freq) == 0) {
                std::cout << "OK " << v4l2ToKhz(freq.frequency) << std::endl;
            } else {
                std::cout << "ERR " << errno << std::endl;
            }
        } else if (cmd == "SIGNAL") {
            if (gFd < 0) {
                std::cout << "OK -104 0 0 0" << std::endl;
                continue;
            }
            struct v4l2_tuner tuner {};
            tuner.index = 0;
            tuner.type = V4L2_TUNER_RADIO;
            if (ioctl(gFd, VIDIOC_G_TUNER, &tuner) == 0) {
                int pct = static_cast<int>((tuner.signal * 100L) / 65535L);
                int dbm = -108 + ((pct * 66) / 100);
                int stereo = (tuner.rxsubchans & V4L2_TUNER_SUB_STEREO) ? 1 : 0;
                std::cout << "OK " << dbm << " " << pct << " " << stereo << " " << tuner.afc << std::endl;
            } else {
                std::cout << "OK -104 0 0 0" << std::endl;
            }
        } else if (cmd == "MUTE") {
            int val = 0;
            iss >> val;
            if (gFd >= 0) {
                struct v4l2_control ctrl {};
                ctrl.id = V4L2_CID_AUDIO_MUTE;
                ctrl.value = val ? 1 : 0;
                ioctl(gFd, VIDIOC_S_CTRL, &ctrl);
            }
            std::cout << "OK" << std::endl;
        } else if (cmd == "DETECT_FM_PCM") {
            FILE* fp = fopen("/proc/asound/pcm", "r");
            if (!fp) {
                std::cout << "NONE" << std::endl;
                continue;
            }
            char buf[512];
            int foundCard = -1;
            int foundDev = -1;
            while (fgets(buf, sizeof(buf), fp)) {
                std::string lower = toLowerStr(buf);
                if (lower.find("capture") != std::string::npos &&
                    (lower.find("fm") != std::string::npos ||
                     lower.find("radio") != std::string::npos ||
                     lower.find("spdy") != std::string::npos)) {
                    int c = -1, d = -1;
                    if (sscanf(buf, "%d-%d:", &c, &d) == 2) {
                        foundCard = c;
                        foundDev = d;
                        break;
                    }
                }
            }
            fclose(fp);
            if (foundCard >= 0) {
                std::cout << "OK " << foundCard << " " << foundDev << std::endl;
            } else {
                std::cout << "NONE" << std::endl;
            }
        } else if (cmd == "LIST_FM_CTLS") {
#if HAS_ALSA_UAPI
            listFmAlsaControls();
#else
            std::cout << "ERR no_asound_h" << std::endl;
#endif
        } else if (cmd == "SET_CTL") {
            std::string rest;
            std::getline(iss >> std::ws, rest);
            size_t sep = rest.find('|');
            if (sep == std::string::npos) {
                std::cout << "ERR bad_args" << std::endl;
                continue;
            }
#if HAS_ALSA_UAPI
            std::string cname = rest.substr(0, sep);
            std::string cval = rest.substr(sep + 1);
            std::cout << (setAlsaControl(cname, cval) ? "OK" : "ERR set_failed") << std::endl;
#else
            std::cout << "ERR no_asound_h" << std::endl;
#endif
        } else if (cmd == "CLOSE") {
            if (gFd >= 0) {
                close(gFd);
                gFd = -1;
            }
            std::cout << "OK" << std::endl;
            break;
        }
    }
    return 0;
}
