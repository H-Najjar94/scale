# FNC-SCALE

Bluetooth interface and Android display for a 15-ton industrial hook scale whose
original handheld receiver is unavailable.

An ESP32-C3 passively reads the scale MCU's SPI writes to its Si4432/RFM22B radio
and publishes the measurement over Bluetooth Low Energy. The Android app performs
calibration, tare, zero, gross/net selection, unit conversion, stability display,
and a 15,000 kg overload warning.

## Install the Android app

Download [`FNC-SCALE.apk`](./FNC-SCALE.apk), allow installation from the browser or
file manager when Android asks, and grant the app Nearby Devices permission.

## ESP32-C3 wiring

| Scale radio signal | ESP32-C3 Super Mini |
|---|---:|
| `GND` | `GND` |
| `SCLK` | `GPIO4` |
| `SDI` | `GPIO5` |
| `NSEL` | `GPIO6` |

Power the ESP32-C3 separately through USB. Do not connect the scale VCC to the ESP.
The bridge is passive; all three signal pins are inputs.

Firmware and detailed notes are in [`scale_spi_ble_bridge`](./scale_spi_ble_bridge)
and [`SCALE_RADIO_HANDOFF.md`](./SCALE_RADIO_HANDOFF.md). Android source is in
[`android_hook_scale`](./android_hook_scale).

## Calibration

1. With the hook empty and stable, open **Calibrate** and tap **Set Zero**.
2. Apply an independently known load.
3. Enter its weight in kilograms and tap **Calibrate**.

Calibration is stored locally on each Android device.

Version 1.5 collects a stable five-second window for both calibration points.
Firmware also averages all roughly 50 radio measurements per second before sending
five BLE updates per second. Recalibrate after installing version 1.5 because older
calibrations were based on individual samples and are deliberately not reused.

Version 1.6 permits a stable light-load calibration when its measured change is
clearly above the observed noise. Treat this as a home test and recalibrate with a
heavier certified load before using the scale near its industrial working range.

Version 1.7 verifies repeatability before saving calibration. It captures empty and
known-load readings twice, rejects excessive zero-return or repeated-load error, and
averages the two accepted points. Existing calibrations are cleared by this update.
