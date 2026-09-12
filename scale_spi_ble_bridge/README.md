# Scale SPI-to-Bluetooth bridge

This firmware uses the existing ESP32 as a passive listener. It reads the six-byte
measurement packets that the scale MCU writes to the original Si4432/RFM22B radio,
then publishes the raw measurement through Bluetooth Low Energy (BLE).

Keep the original radio installed. The scale expects status and interrupt responses
from that radio. Removing it may cause the scale firmware to stop transmitting after
the first attempted packet.

## Wiring

Make every connection while the scale is switched off.

| Scale radio pad | ESP32 pin | Purpose |
|---|---:|---|
| `GND` | `GND` | Common signal reference |
| `SCLK` | `GPIO25` | SPI clock input |
| `SDI` | `GPIO26` | Data from scale MCU to radio |
| `NSEL` | `GPIO27` | Active-low SPI selection input |

For an **ESP32-C3**, use this mapping instead:

| Scale radio pad | ESP32-C3 pin | Purpose |
|---|---:|---|
| `GND` | `GND` | Common signal reference |
| `SCLK` | `GPIO4` | SPI clock input |
| `SDI` | `GPIO5` | Data from scale MCU to radio |
| `NSEL` | `GPIO6` | Active-low SPI selection input |

The C3 build was flashed and its BLE service was connected successfully from the PC
on 2026-09-07. The final C3 hardware-SPI decoder compensates for the scale's short
chip-select setup time, accepts the changing status byte, and reports a sequence
number with every update. The final wireless test received 25 notifications in five
seconds, including `RAW=2952,SEQ=...`.

Only these four wires are required. `SDO`, `NIRQ`, `VDD`, and `SDN` are not needed.

- Power the ESP32 separately from USB or a USB power bank.
- Do not connect ESP32 `3V3`, `5V`, or `VIN` to the scale.
- The three ESP32 signal pins remain inputs. Do not change them to outputs.
- The captured scale radio bus is 3.3 V logic. Never connect a 5 V signal to an
  ESP32 GPIO.
- Use short wires and secure them so they cannot enter the hook or load path.

The logic analyzer is not required after these connections are verified. It may stay
connected in parallel during initial testing if desired.

The four-wire connection was tested successfully on 2026-09-07. The ESP32 decoded
valid packets and reported stable raw readings around `3129` to `3131`.

BLE and SPI decoding were then tested together successfully at a 160 MHz CPU clock.
The flashed ESP32 sends the latest raw reading five times per second. The test board
showed one or more brownout resets while BLE started on the existing USB connection,
then ran normally. If this repeats, use a short good-quality USB cable and a supply
rated for at least 500 mA. A 470 uF electrolytic capacitor across the ESP32 board's
`5V/VIN` and `GND` can also provide startup margin; observe capacitor polarity.

## Phone connection

1. Flash `scale_spi_ble_bridge.ino` to the ESP32.
2. Power the ESP32 and scale.
3. On Android, scan for the BLE device `HookScale-ESP32`.
4. Connect using a BLE UART/scanner application.
5. Open notifications on Nordic UART TX characteristic
   `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`.

The phone initially receives lines such as:

```text
RAW=3153
```

The raw-to-kilogram factor is still unknown. After collecting stable no-load and
known-load readings at work, set `ZERO_RAW` and `COUNTS_PER_KG` in the sketch. It will
then send:

```text
RAW=123456,KG=3000.0
```

## Confirmed packet recognition

The firmware listens for writes to Si4432 FIFO register `0x7F` and accepts packets
matching:

```text
02 LL HH UU SS AA
```

It calculates the raw measurement as:

```text
raw = LL | (HH << 8) | (UU << 16)
```

`SS` is a status byte. It was initially `80` and later changed to `72`, so it must
not be treated as a constant packet marker.
