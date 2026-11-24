# Integrasi Hardware FM Radio di Galaxy A21s (Exynos 850 / LineageOS 22.2)

Agar aplikasi `A21sFMRadio` dapat membuka `/dev/radio0` dan mengalirkan audio lewat `AudioTrack` tanpa penolakan SELinux pada ROM `lineage-22.2`, tambahkan konfigurasi berikut ke device tree `android_device_samsung_a21s`:

## 1. ueventd.exynos850.rc (Izin Device Node Kernel)
```rc
/dev/radio0               0660   system     audio
/dev/fmradio              0660   system     audio
```

## 2. sepolicy/vendor/fm_radio.te
```te
# Izinkan system/priv-app mengakses V4L2 FM tuner node
type fm_radio_device, dev_type;
allow system_app fm_radio_device:chr_file rw_file_perms;
allow priv_app fm_radio_device:chr_file rw_file_perms;
allow audioserver fm_radio_device:chr_file r_file_perms;
```

## 3. audio_policy_configuration.xml (AudioSource RADIO_TUNER)
Pastikan `mixPort` input untuk FM Tuner terdaftar pada primary module:
```xml
<mixPort name="fm_tuner" role="sink">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="48000" channelMasks="AUDIO_CHANNEL_IN_STEREO"/>
</mixPort>
<devicePort tagName="FM Tuner" type="AUDIO_DEVICE_IN_FM_TUNER" role="source">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="48000" channelMasks="AUDIO_CHANNEL_IN_STEREO"/>
</devicePort>
```

## 4. mixer_paths_0.xml (Exynos 850 Codec Path)
Tambahkan rute analog/digital FM ke `gain-media-headset` dan `gain-media-speaker` sesuai codec audio Galaxy A21s (`s5e3830`) sehingga `AudioRecord(RADIO_TUNER)` menerima sinyal PCM bersih saat kabel headset 3.5mm terpasang sebagai antena.
