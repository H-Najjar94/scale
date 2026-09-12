# FNC-SCALE Android app

Native Android BLE display for the `HookScale-ESP32` bridge. The ESP remains a
passive measurement reader; calibration, zero, tare, units, stability, and
the 15,000 kg capacity warning are calculated on the phone.

## Calibration

1. Power the scale and ESP, open the app, grant Nearby Devices permission, and wait
   for `Connected`.
2. Open the **Calibration** tab. With the unloaded hook stable, tap
   **Capture empty zero**.
3. Apply an independently known load, enter its value in kilograms, and tap
   **Capture known load**.
4. Repeat with a larger certified load if greater accuracy across the full 15-tonne
   range is required. The latest two-point calibration is saved on the phone.

**Tare** stores the current gross weight as the temporary tare and displays net
weight. It does not send a command to the scale electronics.

The installable debug APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`.
