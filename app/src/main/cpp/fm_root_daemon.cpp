#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <linux/videodev2.h>
#if __has_include(<sound/asound.h>)
#include <sound/asound.h>
#define HAS_ALSA_UAPI 1
#else
#define HAS_ALSA_UAPI 0
#endif
#include <cstdint>
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

void ensureRadioUnmutedAndVolume(int fd) {
    if (fd < 0) return;
    struct v4l2_control muteCtrl {};
    muteCtrl.id = V4L2_CID_AUDIO_MUTE;
    muteCtrl.value = 0;
    ioctl(fd, VIDIOC_S_CTRL, &muteCtrl);

    struct v4l2_queryctrl qctrl {};
    qctrl.id = V4L2_CID_AUDIO_VOLUME;
    int maxVol = 15;
    if (ioctl(fd, VIDIOC_QUERYCTRL, &qctrl) == 0 && qctrl.maximum > qctrl.minimum) {
        maxVol = qctrl.maximum;
    }
    struct v4l2_control volCtrl {};
    volCtrl.id = V4L2_CID_AUDIO_VOLUME;
    volCtrl.value = maxVol;
    ioctl(fd, VIDIOC_S_CTRL, &volCtrl);
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
            lower.find("spum") == std::string::npos &&
            lower.find("wdma2") == std::string::npos &&
            lower.find("vpcmin_dai4") == std::string::npos &&
            lower.find("vpcmin_dai7") == std::string::npos &&
            lower.find("bd_srccom6") == std::string::npos) {
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
            if (items > 32) items = 32;
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

std::string enableFmAlsaRoute(bool useAsrc = true) {
    // Sesuai /vendor/etc/mixer_paths.xml Exynos 850:
    // <path name="route-fm"> -> NSRC2=SPDY, SIFM2=WDMA, WDMA2->VPCMIN_DAI4 & VPCMIN_DAI7
    bool sw = setAlsaControl("ABOX SPDY CAP SPDY Switch", "1");
    setAlsaControl("ABOX SPDY Rate", "40000");
    setAlsaControl("ABOX SPDY Width", "16");
    setAlsaControl("ABOX SPDY Channel", "2");
    bool nsrc = setAlsaControl("ABOX NSRC2", "SPDY");
    bool sifm = setAlsaControl("ABOX SIFM2", "WDMA");
    setAlsaControl("ABOX SIFM2 Width", "16");
    if (useAsrc) {
        setAlsaControl("ABOX SIFM2 Rate", "48000");
        setAlsaControl("ABOX SPUM ASRC2 IS", "BCLK_SPDY");
        setAlsaControl("ABOX SPUM ASRC2 OS", "ABOX");
        setAlsaControl("ABOX SPUM ASRC2", "1");
        setAlsaControl("ABOX WDMA2 Rate", "48000");
    } else {
        setAlsaControl("ABOX SIFM2 Rate", "40000");
        setAlsaControl("ABOX SPUM ASRC2", "0");
        setAlsaControl("ABOX WDMA2 Rate", "40000");
    }
    setAlsaControl("ABOX WDMA2 Width", "16");
    setAlsaControl("ABOX WDMA2 Channel", "2");
    setAlsaControl("ABOX WDMA2 Period", "240");
    bool v4 = setAlsaControl("ABOX VPCMIN_DAI4_A", "WDMA2") || setAlsaControl("ABOX VPCMIN_DAI4_B", "WDMA2");
    bool v7 = setAlsaControl("ABOX VPCMIN_DAI7_A", "WDMA2") || setAlsaControl("ABOX VPCMIN_DAI7_B", "WDMA2");

    std::ostringstream oss;
    oss << (nsrc ? "OK " : "ERR ")
        << "NSRC2=SPDY(" << (nsrc ? 1 : 0) << ") "
        << "SIFM2=WDMA(" << (sifm ? 1 : 0) << ") "
        << "SPDY_SW(" << (sw ? 1 : 0) << ") "
        << "VDAI4(" << (v4 ? 1 : 0) << ") "
        << "VDAI7(" << (v7 ? 1 : 0) << ")";
    return oss.str();
}

void disableFmAlsaRoute() {
    setAlsaControl("ABOX NSRC2", "RESERVED");
    setAlsaControl("ABOX SPUM ASRC2", "0");
    setAlsaControl("ABOX VPCMIN_DAI4_A", "None");
    setAlsaControl("ABOX VPCMIN_DAI4_B", "None");
    setAlsaControl("ABOX VPCMIN_DAI7_A", "None");
    setAlsaControl("ABOX VPCMIN_DAI7_B", "None");
}

void listV4l2Controls(int fd) {
    if (fd < 0) {
        std::cout << "ERR radio_not_open" << std::endl;
        return;
    }
    std::ostringstream oss;
    int count = 0;
    auto appendCtrl = [&](const struct v4l2_queryctrl& q) {
        struct v4l2_control c {};
        c.id = q.id;
        int val = (ioctl(fd, VIDIOC_G_CTRL, &c) == 0) ? c.value : -999999;
        if (count > 0) oss << "; ";
        oss << reinterpret_cast<const char*>(q.name)
            << "(0x" << std::hex << q.id << std::dec << ")=" << val
            << "[" << q.minimum << ".." << q.maximum << "]";
        count++;
    };

    struct v4l2_queryctrl q {};
    q.id = V4L2_CTRL_FLAG_NEXT_CTRL;
    while (ioctl(fd, VIDIOC_QUERYCTRL, &q) == 0 && count < 64) {
        appendCtrl(q);
        q.id |= V4L2_CTRL_FLAG_NEXT_CTRL;
    }
    for (__u32 id = V4L2_CID_AUDIO_VOLUME; id <= V4L2_CID_AUDIO_LOUDNESS + 8; ++id) {
        struct v4l2_queryctrl aq {};
        aq.id = id;
        if (ioctl(fd, VIDIOC_QUERYCTRL, &aq) == 0) appendCtrl(aq);
    }
    for (__u32 id = V4L2_CID_PRIVATE_BASE; id < V4L2_CID_PRIVATE_BASE + 32; ++id) {
        struct v4l2_queryctrl pq {};
        pq.id = id;
        if (ioctl(fd, VIDIOC_QUERYCTRL, &pq) == 0) appendCtrl(pq);
    }
    std::cout << "OK " << (count > 0 ? oss.str() : "no_v4l2_ctrls") << std::endl;
}

bool configureHwParams(
    int pcmFd,
    unsigned int reqRate,
    unsigned int channels,
    unsigned int minPeriodFrames,
    unsigned int maxPeriodFrames,
    unsigned int minPeriods,
    unsigned int maxPeriods,
    unsigned int& outRate,
    unsigned int& outPeriodFrames,
    unsigned int& outBufferFrames
) {
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
    if (reqRate > 0) {
        setInterval(SNDRV_PCM_HW_PARAM_RATE, reqRate, reqRate);
    } else {
        setInterval(SNDRV_PCM_HW_PARAM_RATE, 8000, 96000);
    }
    setInterval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE, minPeriodFrames, maxPeriodFrames);
    setInterval(SNDRV_PCM_HW_PARAM_PERIODS, minPeriods, maxPeriods);

    if (ioctl(pcmFd, SNDRV_PCM_IOCTL_HW_PARAMS, &params) < 0) {
        return false;
    }

    outRate = params.intervals[SNDRV_PCM_HW_PARAM_RATE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL].min;
    outPeriodFrames = params.intervals[SNDRV_PCM_HW_PARAM_PERIOD_SIZE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL].min;
    outBufferFrames = params.intervals[SNDRV_PCM_HW_PARAM_BUFFER_SIZE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL].min;
    if (outPeriodFrames < 64 || outPeriodFrames > 8192) outPeriodFrames = 240;
    if (outBufferFrames < outPeriodFrames * 2) outBufferFrames = outPeriodFrames * 4;
    if (outRate == 0) outRate = reqRate > 0 ? reqRate : 48000;
    return true;
}

bool tryStreamSingleDevice(
    int card,
    int dev,
    unsigned int reqRate,
    unsigned int targetOutRate,
    unsigned int channels,
    bool useAsrc,
    bool probeOnly,
    int pollTimeoutMs,
    std::string& detailMsg
) {
    enableFmAlsaRoute(useAsrc);

    char devPath[64];
    snprintf(devPath, sizeof(devPath), "/dev/snd/pcmC%dD%dc", card, dev);
    int pcmFd = open(devPath, O_RDWR);
    if (pcmFd < 0) pcmFd = open(devPath, O_RDONLY);
    if (pcmFd < 0) {
        detailMsg = std::string(devPath) + " open=" + strerror(errno);
        return false;
    }

    unsigned int actualRate = reqRate;
    unsigned int periodFrames = 240;
    unsigned int bufferFrames = 960;
    // Prefer PrdSz=240 #Prds=2..8 (matching samsung-abox-vdma), then fallback broader range
    bool hwOk = configureHwParams(pcmFd, reqRate, channels, 240, 240, 2, 8, actualRate, periodFrames, bufferFrames) ||
                configureHwParams(pcmFd, reqRate, channels, 240, 4096, 2, 16, actualRate, periodFrames, bufferFrames) ||
                (reqRate != 0 && configureHwParams(pcmFd, 0, channels, 240, 4096, 2, 16, actualRate, periodFrames, bufferFrames));
    if (!hwOk) {
        int err1 = errno;
        detailMsg = std::string(devPath) + "@" + std::to_string(reqRate) + " hw_params=" + strerror(err1);
        close(pcmFd);
        return false;
    }

    struct snd_pcm_sw_params sw {};
    sw.tstamp_mode = SNDRV_PCM_TSTAMP_NONE;
    sw.period_step = 1;
    sw.avail_min = periodFrames;
    sw.start_threshold = 1;
    sw.stop_threshold = bufferFrames;
    ioctl(pcmFd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw);

    if (ioctl(pcmFd, SNDRV_PCM_IOCTL_PREPARE) < 0) {
        detailMsg = std::string(devPath) + " prepare=" + strerror(errno);
        close(pcmFd);
        return false;
    }
    if (ioctl(pcmFd, SNDRV_PCM_IOCTL_START) < 0 && errno != EBADFD && errno != EISCONN) {
        detailMsg = std::string(devPath) + " start=" + strerror(errno);
        close(pcmFd);
        return false;
    }

    struct pollfd pfd {};
    pfd.fd = pcmFd;
    pfd.events = POLLIN | POLLERR | POLLHUP;
    int pr = poll(&pfd, 1, pollTimeoutMs);
    if (pr <= 0 || (pfd.revents & (POLLERR | POLLHUP)) != 0) {
        struct snd_pcm_status st {};
        int stOk = ioctl(pcmFd, SNDRV_PCM_IOCTL_STATUS, &st);
        detailMsg = std::string(devPath) + "@" + std::to_string(actualRate) +
                    " p=" + std::to_string(periodFrames) +
                    (pr == 0 ? " poll_timeout" : " poll_err(" + std::to_string(pfd.revents) + ")") +
                    (stOk == 0 ? " st=" + std::to_string(st.state) : "");
        close(pcmFd);
        return false;
    }

    size_t frameBytes = static_cast<size_t>(channels) * 2;
    std::vector<int16_t> inBuf(periodFrames * channels);
    std::vector<int16_t> outBuf;

    if (probeOnly) {
        struct snd_xferi xfer {};
        xfer.buf = inBuf.data();
        xfer.frames = periodFrames;
        if (ioctl(pcmFd, SNDRV_PCM_IOCTL_READI_FRAMES, &xfer) < 0) {
            detailMsg = std::string(devPath) + "@" + std::to_string(actualRate) + " readi=" + strerror(errno);
            close(pcmFd);
            return false;
        }
        detailMsg = std::string(devPath) + "@" + std::to_string(actualRate) + " OK frames=" + std::to_string(xfer.result);
        close(pcmFd);
        return true;
    }

    while (true) {
        struct snd_xferi xfer {};
        xfer.buf = inBuf.data();
        xfer.frames = periodFrames;
        if (ioctl(pcmFd, SNDRV_PCM_IOCTL_READI_FRAMES, &xfer) < 0) {
            if (errno == EPIPE || errno == ESTRPIPE) {
                ioctl(pcmFd, SNDRV_PCM_IOCTL_PREPARE);
                ioctl(pcmFd, SNDRV_PCM_IOCTL_START);
                continue;
            }
            detailMsg = std::string(devPath) + " readi=" + strerror(errno);
            close(pcmFd);
            return false;
        }
        if (xfer.result <= 0) continue;

        size_t inFrames = static_cast<size_t>(xfer.result);
        if (actualRate == targetOutRate || actualRate == 0) {
            ssize_t written = write(STDOUT_FILENO, inBuf.data(), inFrames * frameBytes);
            if (written <= 0) break;
        } else {
            size_t outFrames = (inFrames * targetOutRate) / actualRate;
            if (outFrames == 0) continue;
            outBuf.resize(outFrames * channels);
            for (size_t i = 0; i < outFrames; ++i) {
                double srcPos = (static_cast<double>(i) * actualRate) / targetOutRate;
                size_t idx0 = static_cast<size_t>(srcPos);
                if (idx0 >= inFrames) idx0 = inFrames - 1;
                size_t idx1 = (idx0 + 1 < inFrames) ? (idx0 + 1) : idx0;
                double frac = srcPos - static_cast<double>(idx0);
                for (unsigned int ch = 0; ch < channels; ++ch) {
                    double s0 = static_cast<double>(inBuf[idx0 * channels + ch]);
                    double s1 = static_cast<double>(inBuf[idx1 * channels + ch]);
                    outBuf[i * channels + ch] = static_cast<int16_t>(s0 + (s1 - s0) * frac);
                }
            }
            ssize_t written = write(STDOUT_FILENO, outBuf.data(), outFrames * frameBytes);
            if (written <= 0) break;
        }
    }
    close(pcmFd);
    return true;
}

std::string probeAllFmCandidates(unsigned int targetRate) {
    struct Candidate { int card; int dev; unsigned int rate; bool asrc; };
    const std::vector<Candidate> candidates = {
        {0, 114, targetRate, true},
        {0, 117, targetRate, true},
        {0, 14, targetRate, true},
        {0, 14, 40000, false},
        {0, 19, targetRate, true}
    };
    std::string summary;
    for (const auto& c : candidates) {
        std::string msg;
        tryStreamSingleDevice(c.card, c.dev, c.rate, targetRate, 2, c.asrc, true, 450, msg);
        if (!summary.empty()) summary += "; ";
        summary += msg;
    }
    // Leave ASRC2 route active for follow-up LIST_FM_CTLS inspection
    enableFmAlsaRoute(true);
    return summary;
}

int runPcmCaptureStream(int card, int dev, unsigned int targetRate, unsigned int channels) {
    struct Candidate { int card; int dev; unsigned int rate; bool asrc; };
    std::vector<Candidate> candidates;
    if (card == 0 && dev == 14) {
        // Urutan prioritas Exynos 850 ABOX: Calliope FE (VPCMIN_DAI4/7) -> WDMA2 48k -> WDMA2 40k native
        candidates = {
            {0, 114, targetRate, true},
            {0, 117, targetRate, true},
            {0, 14, targetRate, true},
            {0, 14, 40000, false},
            {0, 19, targetRate, true}
        };
    } else {
        candidates = { {card, dev, targetRate, true}, {card, dev, 40000, false} };
    }

    std::string summary;
    for (const auto& c : candidates) {
        std::string reason;
        if (tryStreamSingleDevice(c.card, c.dev, c.rate, targetRate, channels, c.asrc, false, 800, reason)) {
            return 0;
        }
        if (!summary.empty()) summary += "; ";
        summary += reason;
    }
    enableFmAlsaRoute(true);
    fprintf(stderr, "ERR [%s]\n", summary.c_str());
    return 2;
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
                ensureRadioUnmutedAndVolume(gFd);
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
                        ensureRadioUnmutedAndVolume(gFd);
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
                ensureRadioUnmutedAndVolume(gFd);
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
            int wdmaCard = -1, wdmaDev = -1;
            int fmCard0 = -1, fmDev0 = -1;
            while (fgets(buf, sizeof(buf), fp)) {
                std::string lower = toLowerStr(buf);
                if (lower.find("capture") == std::string::npos) continue;
                int c = -1, d = -1;
                if (sscanf(buf, "%d-%d:", &c, &d) != 2) continue;
                // Hanya gunakan Card 0 (Exynos3830-Madera), JANGAN Card 2 (abox_dump)
                if (c != 0) continue;
                if (lower.find("wdma2") != std::string::npos && lower.find("dual") == std::string::npos) {
                    wdmaCard = c;
                    wdmaDev = d;
                    break;
                }
                if (fmCard0 < 0 && (lower.find("fm") != std::string::npos || lower.find("spdy") != std::string::npos)) {
                    fmCard0 = c;
                    fmDev0 = d;
                }
            }
            fclose(fp);
            if (wdmaCard >= 0) {
                std::cout << "OK " << wdmaCard << " " << wdmaDev << std::endl;
            } else if (fmCard0 >= 0) {
                std::cout << "OK " << fmCard0 << " " << fmDev0 << std::endl;
            } else {
                std::cout << "NONE" << std::endl;
            }
        } else if (cmd == "ENABLE_FM_ALSA") {
#if HAS_ALSA_UAPI
            std::cout << enableFmAlsaRoute(true) << std::endl;
#else
            std::cout << "ERR no_asound_h" << std::endl;
#endif
        } else if (cmd == "DISABLE_FM_ALSA") {
#if HAS_ALSA_UAPI
            disableFmAlsaRoute();
            std::cout << "OK" << std::endl;
#else
            std::cout << "OK" << std::endl;
#endif
        } else if (cmd == "LIST_FM_CTLS") {
#if HAS_ALSA_UAPI
            listFmAlsaControls();
#else
            std::cout << "ERR no_asound_h" << std::endl;
#endif
        } else if (cmd == "LIST_V4L2_CTLS") {
            listV4l2Controls(gFd);
        } else if (cmd == "PROBE_FM_AUDIO") {
#if HAS_ALSA_UAPI
            std::cout << probeAllFmCandidates(48000) << std::endl;
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
#if HAS_ALSA_UAPI
            disableFmAlsaRoute();
#endif
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
