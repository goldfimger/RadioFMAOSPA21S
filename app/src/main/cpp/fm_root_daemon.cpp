#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#include <iostream>
#include <sstream>
#include <string>

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
} // namespace

int main() {
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
                std::string s(buf);
                std::string lower = s;
                for (char& ch : lower) ch = static_cast<char>(tolower(static_cast<unsigned char>(ch)));
                if (lower.find("capture") != std::string::npos &&
                    (lower.find("fm") != std::string::npos || lower.find("radio") != std::string::npos)) {
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
