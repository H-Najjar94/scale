# 15-ton wireless hook scale — radio investigation handoff

Date captured: 2026-09-07 (Asia/Amman)

## Purpose

The original handheld receiver/remote is broken and a matching replacement has not been found in Jordan. The goal is to build a replacement receiver, probably using an ESP32 plus a compatible sub-GHz radio, while preserving the scale's original electronics and calibration.

The scale is a large metal hook unit rated by the owner for approximately 15 tonnes and weighing roughly 50 kg. It is not practical or safe to calibrate it by hand. Tomorrow's test should use normal workplace lifting equipment and a stable reference load.

## Hardware identified

- The scale contains a HopeRF RFM22B-style module built around the Silicon Labs Si4432 sub-GHz transceiver.
- The module crystal is marked `30.000` MHz.
- The radio is proprietary sub-GHz radio. It is not Wi-Fi or Bluetooth.
- Scale PCB marking visible in photographs: `H-meter connecting plate`, `Version 2.1`, `2011.9.27`.
- Logic analyzer: Cypress FX2/Saleae-compatible, 24 MHz, 8 channels.
- USB identity: `VID 0925`, `PID 3881`; after fx2lafw loads, serial identity is `SALEAE_LOGIC`.
- Windows driver: WinUSB from libwdi, version `6.1.7600.16385`.

## Logic-analyzer connections

| Analyzer | Scale radio pad | Meaning |
|---|---|---|
| GND | GND | Common reference |
| CH1 / D0 | SCLK | SPI clock |
| CH2 / D1 | SDI | MCU to radio (MOSI) |
| CH3 / D2 | SDO | Radio to MCU (MISO) |
| CH4 / D3 | NSEL | SPI chip select, active low |
| CH5 / D4 | NIRQ | Radio interrupt; captured but not required for decoding |

Do not connect the analyzer `POW` or `CLK` pins to the scale. The analyzer is powered by USB. Connect or move probes only while the scale is off.

## Installed software

- PulseView: `C:\Program Files\sigrok\PulseView\pulseview.exe`
- sigrok-cli: `C:\Program Files\sigrok\sigrok-cli\sigrok-cli.exe`
- Zadig: `C:\Program Files\sigrok\PulseView\zadig.exe`
- Reusable decoder: `C:\Users\user\Documents\balance\decode_scale_capture.py`
- Official reference datasheet saved as `C:\Users\user\Documents\balance\Si4430-31-32_datasheet.pdf`

The analyzer is detected by sigrok as:

```text
fx2lafw - Saleae Logic with 8 channels: D0 D1 D2 D3 D4 D5 D6 D7
```

If Windows later reports `USB device not recognized` or Code 43, unplug the analyzer USB for 10 seconds and reconnect it directly to the PC. This was tested successfully. The probe wiring did not cause the fault.

## Confirmed radio configuration

These values were captured directly from the scale MCU writing the Si4432 registers during a cold start.

| Property | Confirmed value | Evidence/calculation |
|---|---:|---|
| Carrier frequency | **457.500 MHz** | Registers `75/76/77 = 55 BB 80` |
| Modulation | **GFSK** | Register `71 = 23`, `modtyp=3` |
| Data source | **FIFO packet mode** | Register `71 = 23`, `dtmod=2` |
| Data rate | **9600.16 bit/s** (nominal 9600) | `6E/6F = 4E A5`, low-rate scale bit in `70 = 2C` |
| Frequency deviation | **48.125 kHz** | `72 = 4D`; 77 × 625 Hz |
| Manchester encoding | **Off** | `70 = 2C`, `enmanch=0` |
| Data whitening | **Off** | `70 = 2C`, `enwhite=0` |
| Preamble | **10 bytes** | `33/34 = 02 0A` |
| Preamble detection threshold | **20 bits** | `35 = 2A`; threshold field is 5 × 4 bits |
| Sync word | **2D D4** (two bytes) | `33 = 02`, `36/37 = 2D D4` |
| Header length | **0 bytes** | `33 = 02`, `hdlen=0` |
| Payload length | **6 bytes** | Register `3E = 06` |
| Length mode | **Variable-length packet mode** | `33 = 02`, `fixpklen=0`; an on-air length byte precedes the payload |
| Packet handling | **Enabled for TX and RX** | `30 = 8D` |
| CRC | **Enabled, CRC-16 IBM** | `30 = 8D`, polynomial selector `01` |
| Bit order | **MSB first on air** | `30 = 8D`, `lsbfrst=0` |
| Packet repetition | **about 50.5 packets/s** | Median interval approximately 19.79–19.80 ms |
| SPI mode | **Mode 0, MSB first, NSEL active low** | Decoded register writes and valid recurring packet structure |
| Observed SPI clock | **about 0.36–0.37 MHz** | Measured from captured SCLK edges; the 8 MHz capture rate is sufficient |

Carrier calculation using the Si4432 formula and the captured values:

```text
hbsel = 0
fb = 0x15 = 21
fc = 0xBB80 = 48000

F = 10 MHz × (hbsel + 1) × (fb + 24 + fc/64000)
  = 10 MHz × (21 + 24 + 48000/64000)
  = 457.500 MHz
```

## Cold-start register writes

The first complete initialization sequence captured the following writes. Addresses and values are hexadecimal.

| Register | Value | Function |
|---:|---:|---|
| 07 | 80, then 05/09/01 during operation | Software reset / operating-mode control |
| 75 | 55 | Frequency-band select |
| 76 | BB | Nominal carrier frequency high byte |
| 77 | 80 | Nominal carrier frequency low byte |
| 6E | 4E | TX data-rate high byte |
| 6F | A5 | TX data-rate low byte |
| 70 | 2C | Data-rate scaling and encoding options |
| 58 | 80 | Device-specific/reserved setting in this datasheet revision |
| 72 | 4D | Frequency deviation |
| 1C | AB | IF filter bandwidth configuration |
| 20 | 39 | Clock-recovery oversampling ratio |
| 21 | 20 | Clock-recovery offset 2 |
| 22 | 68 | Clock-recovery offset 1 |
| 23 | DC | Clock-recovery offset 0 |
| 24 | 00 | Clock-recovery timing-loop gain 1 |
| 25 | 2C | Clock-recovery timing-loop gain 0 |
| 1D | 3C | AFC loop gearshift override |
| 1E | 02 | AFC timing control |
| 2A | FF | AFC limiter |
| 1F | 03 | Clock-recovery gearshift override |
| 34 | 0A | Preamble length |
| 35 | 2A | Preamble detection control |
| 33 | 02 | Header/sync/packet-length configuration |
| 36 | 2D | Sync word byte 3 |
| 37 | D4 | Sync word byte 2 |
| 30 | 8D | Packet handling, CRC and bit order |
| 32 | 00 | Header control 1 |
| 71 | 23 | FIFO GFSK mode |
| 0C | 15 | GPIO1 configuration |
| 0B | 12 | GPIO0 configuration |
| 09 | D7 | Crystal oscillator load capacitance |
| 69 | 60 | AGC override |
| 6D | 1E | TX power configuration |
| 05 | 03/04 during operation | Interrupt Enable 1 |
| 06 | 00 | Interrupt Enable 2 |
| 0D | F4 | GPIO2 configuration |
| 3E | 06 | TX payload length |

The MCU repeatedly clears/reads interrupt status, fills FIFO register `7F`, enters TX mode, and returns to the ready/standby state for every packet.

## Packet format

The six bytes written into the Si4432 TX FIFO are consistently:

```text
02 LL HH UU 80 AA
```

Current interpretation:

- Byte 0: constant `02`, probably message type or scale address.
- Bytes 1–3: changing 24-bit measurement value, little-endian candidate:
  `raw = LL | (HH << 8) | (UU << 16)`.
- Byte 4: constant `80` during all captures; likely status flags.
- Byte 5: constant `AA`; likely protocol marker.
- Hardware CRC is added by the Si4432 and is not among these six FIFO bytes.

Because variable-length mode is active, the likely complete on-air structure is:

```text
10-byte alternating preamble
2D D4                  sync
06                     payload length
02 LL HH UU 80 AA      payload
CRC-16 IBM             added by Si4432 hardware
```

The meaning and scale factor of the 24-bit measurement are not yet proven. The behavior strongly supports a load-cell/measurement value, but conversion to kg must be established with stable reference points.

## ESP32 Bluetooth bridge prepared

A passive SPI-to-BLE bridge is saved at:

```text
C:\Users\user\Documents\balance\scale_spi_ble_bridge\scale_spi_ble_bridge.ino
```

It leaves the original Si4432/RFM22B radio installed and taps `SCLK`, `SDI`, and
`NSEL` as ESP32 inputs. It recognizes FIFO payloads matching
`02 LL HH UU 80 AA`, extracts the little-endian 24-bit raw value, and sends it as
ASCII notifications from BLE device `HookScale-ESP32`. Wiring and phone steps are
in `scale_spi_ble_bridge\README.md`. The scale conversion remains disabled until
the workplace known-load test provides `ZERO_RAW` and `COUNTS_PER_KG`.

The four-wire bridge and BLE were tested live on 2026-09-07. Direct GPIO polling at
160 MHz decoded valid packets while BLE was active and printed raw readings around
3102–3151. ESP32 interrupt sampling and its SPI-slave peripheral were not reliable
for this bus timing; keep the direct-polling implementation. The existing USB supply
caused one or more brownout resets during BLE startup before stabilizing, so the final
installation needs a solid 5 V supply and short cable.

An ESP32-C3 Super Mini was subsequently installed with `SCLK=GPIO4`, `SDI=GPIO5`,
`NSEL=GPIO6`, and common ground. Its SPI-slave peripheral sees 14 bits for some
16-bit scale transactions because NSEL has very short setup/hold timing. The C3
firmware reconstructs FIFO data from the known `0xFF` address prefix. A later packet
sample was `02 A1 0B 00 72 AA`, proving byte 4 is a changing status byte rather than
the previously assumed constant `80`; validation now uses byte 0 `02` and final byte
`AA`. The final PC BLE test received 25 notifications in five seconds with live raw
values around 2952 and increasing sequence numbers. The C3 firmware is flashed and
working end to end.

## Captured measurements

### Stationary home capture

File: `scale_stationary_10s.sr`

- Duration: 10.0 seconds
- Valid packets: 505
- Median raw value: **3153**
- Mean raw value: **3151.48**
- Range: **3126–3178**
- Standard deviation: **18.72 counts**
- State: scale stationary at home; this must not yet be treated as a certified zero reference.

### Hand/movement capture

File: `scale_load_test.sr`

- Duration: 52.4288 seconds
- Valid packets: 2646
- Median raw value: **3216**
- Range: **2798–3777**
- Standard deviation: **247.24 counts**
- The changing value followed physical movement/force, proving that the captured payload responds to the load cell.
- There was no stable known load plateau, so this capture cannot provide a kg conversion factor.

### Cold-start capture

File: `scale_cold_start.sr`

- Duration: 2.0 seconds at 24 MHz
- Contains the complete useful radio initialization beginning about 0.182 seconds after the trigger.
- Contains 88 recognizable measurement packets after initialization.
- Early measurement bytes move through large transient values while the electronics start and settle; do not use those values for calibration.

### Powered-off baseline

File: `scale_off_baseline.sr`

- Duration: 0.010 seconds at 24 MHz.
- Connected radio lines were low while the scale was unpowered; unused analyzer inputs floated high.

## Files and integrity hashes

| File | SHA-256 |
|---|---|
| `scale_cold_start.sr` | `49333e639630130044c301533bbdf4a0678b6d9f7d25775cc812f1d28d1be8ec` |
| `scale_load_test.sr` | `8f91de4d9616cdea18a9aa31eaf46dab9fa3dc897d795383dbdd39e4d68affd5` |
| `scale_stationary_10s.sr` | `e59b3ef0d2bdb00c8c981362ba401f08741e90fc187af67b15acf99dd3d9d821` |

Decoded CSV files:

- `scale_cold_start_packets.csv`
- `scale_load_test_packets.csv`
- `scale_stationary_10s_packets.csv`

## Earlier UART investigation

The scale PCB has a four-pin `J4` connector marked `GND`, `RXD`, `TXD`, `VCC`. An ESP32 was detected on `COM7` and used with:

```text
C:\Users\user\Documents\balance\scale_uart_monitor\scale_uart_monitor.ino
```

The observed line produced repeated `FF` bytes at a likely 38,400 baud, around 50 bytes/s, and did not clearly respond to load. This may be an idle, inverted, service, or synchronization line. It is not currently the preferred path because the radio SPI capture provides clear structured measurement packets.

## Unknowns still requiring a workplace test

1. The raw-count-to-kilogram scale factor and correct sign.
2. The true installed no-load offset while the hook scale is hanging normally and stable.
3. Whether byte 4 changes for stability, tare, overload, battery, or other status conditions.
4. The exact command packets sent from the handheld receiver to the scale for zero, tare, and configuration. The scale radio is configured for both TX and RX, so bidirectional control is possible, but no working remote command was available to capture.
5. Whether the original handheld stores the calibration factor or merely displays a value already scaled by the scale MCU.

## Procedure for tomorrow at work

Do not attempt to create a heavy test load manually. Use the normal crane/rigging arrangement and workplace lifting procedure.

1. Keep the analyzer probes connected as listed above. Do not connect `POW` or analyzer `CLK`.
2. With the scale off, connect the analyzer USB directly to the PC.
3. Confirm that sigrok detects `fx2lafw`.
4. Hang/install the scale in its normal orientation with no applied working load. Allow it to stop moving.
5. Record at least 10 seconds of stable installed no-load data.
6. Apply a stable reference load whose weight is independently known. For proper calibration of a 15-ton scale, a load around 20% of capacity (approximately 3 tonnes) or higher is much more useful than 50 kg. Do not alter the scale's existing calibration settings.
7. Hold the reference load stable for at least 10 seconds and record its exact reference weight.
8. If practical, record a second larger known load. Two loaded points help verify linearity.
9. Record an unload return-to-zero segment to measure hysteresis and drift.
10. If the broken handheld displays any number at all, photograph or write down that number at every plateau. This can reveal whether the radio payload is already scaled.

Suggested capture command for each stable state:

```powershell
& 'C:\Program Files\sigrok\sigrok-cli\sigrok-cli.exe' `
  --driver fx2lafw `
  --config samplerate=8MHz `
  --channels D0=SCLK,D1=SDI,D2=SDO,D3=NSEL,D4=NIRQ `
  --time 15000 `
  --output-file 'C:\Users\user\Documents\balance\work_test_NAME.sr'
```

Replace `NAME` with a clear label such as `zero`, `3000kg`, `6000kg`, or `unloaded_after_test`.

Decode a saved capture with:

```powershell
python 'C:\Users\user\Documents\balance\decode_scale_capture.py' `
  'C:\Users\user\Documents\balance\work_test_NAME.sr'
```

## Receiver hardware direction

The closest replacement-radio option is another RFM22B/Si4432 module in the 433 MHz hardware band, because it can be configured exactly like the original at 457.500 MHz and will handle the packet CRC in hardware. An ESP32 can control that module over SPI and display or forward the decoded measurement.

An SX1278 433 MHz module may also cover 457.500 MHz and supports FSK/GFSK, but it will require more protocol adaptation. A generic fixed 433 MHz ASK/OOK receiver is not suitable. An RTL-SDR can observe the signal but cannot transmit zero/tare commands.

Do not desolder or replace the scale's working radio module. Build the new receiver as a separate device first.

## Reference

Silicon Labs, *Si4430/31/32-B1 ISM Transceiver Data Sheet*, revision 1.2:

https://www.silabs.com/documents/public/data-sheets/Si4430-31-32.pdf
