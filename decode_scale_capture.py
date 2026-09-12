#!/usr/bin/env python3
"""Decode Si4432 FIFO packets from this project's sigrok .sr captures.

Expected logic-analyzer mapping:
  D0=SCLK, D1=SDI/MOSI, D2=SDO/MISO, D3=NSEL, D4=NIRQ
"""

from __future__ import annotations

import argparse
import csv
import pathlib
import re
import statistics
import zipfile


def read_capture(path: pathlib.Path) -> tuple[bytes, float]:
    with zipfile.ZipFile(path) as archive:
        metadata = archive.read("metadata").decode(errors="replace")
        match = re.search(r"^samplerate=([0-9.]+)\s*(Hz|kHz|MHz)$", metadata, re.MULTILINE)
        if not match:
            raise ValueError("Samplerate is missing from sigrok metadata")
        multiplier = {"Hz": 1.0, "kHz": 1_000.0, "MHz": 1_000_000.0}[match.group(2)]
        samplerate = float(match.group(1)) * multiplier
        names = sorted(
            (name for name in archive.namelist() if name.startswith("logic-1-")),
            key=lambda name: int(name.rsplit("-", 1)[1]),
        )
        return b"".join(archive.read(name) for name in names), samplerate


def decode_transactions(raw: bytes, samplerate: float) -> list[tuple[float, list[int]]]:
    # SPI mode 0: capture MOSI on rising SCLK edges while NSEL is low.
    rising_edges: list[int] = []
    previous = raw[0]
    for index, current in enumerate(raw[1:], start=1):
        if not (previous & 0x01) and (current & 0x01) and not (current & 0x08):
            rising_edges.append(index)
        previous = current

    groups: list[list[int]] = []
    current_group: list[int] = []
    # A 4 us gap is safely longer than gaps within the observed SPI transfer.
    boundary = samplerate * 4e-6
    previous_edge: int | None = None
    for edge in rising_edges:
        if previous_edge is not None and edge - previous_edge > boundary:
            groups.append(current_group)
            current_group = []
        current_group.append(edge)
        previous_edge = edge
    if current_group:
        groups.append(current_group)

    transactions: list[tuple[float, list[int]]] = []
    for group in groups:
        if len(group) < 16:
            continue
        bits = [1 if raw[index] & 0x02 else 0 for index in group]
        values: list[int] = []
        for offset in range(0, len(bits) - 7, 8):
            value = 0
            for bit in bits[offset : offset + 8]:
                value = (value << 1) | bit
            values.append(value)
        transactions.append((group[0] / samplerate, values))
    return transactions


def extract_packets(transactions: list[tuple[float, list[int]]]) -> list[tuple[float, list[int], int]]:
    packets: list[tuple[float, list[int], int]] = []
    fifo: list[int] = []
    start_time: float | None = None
    for timestamp, mosi in transactions:
        if len(mosi) < 2 or mosi[0] != 0xFF:  # write to FIFO register 0x7F
            continue
        if start_time is None:
            start_time = timestamp
        fifo.extend(mosi[1:])
        while len(fifo) >= 6:
            packet, fifo = fifo[:6], fifo[6:]
            if packet[0] == 0x02 and packet[4:] == [0x80, 0xAA]:
                raw_le24 = packet[1] | (packet[2] << 8) | (packet[3] << 16)
                packets.append((start_time, packet, raw_le24))
            start_time = timestamp if fifo else None
    return packets


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=pathlib.Path)
    parser.add_argument("--csv", type=pathlib.Path)
    args = parser.parse_args()

    raw, samplerate = read_capture(args.capture)
    transactions = decode_transactions(raw, samplerate)
    packets = extract_packets(transactions)
    values = [packet[2] for packet in packets]
    print(f"Capture: {args.capture}")
    print(f"Duration: {len(raw) / samplerate:.6f} s")
    print(f"SPI transactions: {len(transactions)}")
    print(f"Valid six-byte packets: {len(packets)}")
    if values:
        print(f"Raw range: {min(values)}..{max(values)}")
        print(f"Raw median: {statistics.median(values)}")
        print(f"Raw mean: {statistics.mean(values):.3f}")
        if len(packets) > 1:
            intervals = [packets[i][0] - packets[i - 1][0] for i in range(1, len(packets))]
            period = statistics.median(intervals)
            print(f"Median packet period: {period * 1000:.6f} ms ({1 / period:.3f} Hz)")

    output = args.csv or args.capture.with_name(args.capture.stem + "_packets.csv")
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["time_s", "packet_hex", "raw_le24"])
        for timestamp, packet, value in packets:
            writer.writerow([f"{timestamp:.6f}", " ".join(f"{byte:02X}" for byte in packet), value])
    print(f"CSV: {output}")


if __name__ == "__main__":
    main()
