import asyncio
from bleak import BleakScanner

async def main():
    devices = await BleakScanner.discover(timeout=10, return_adv=True)
    print(f"DEVICES={len(devices)}")
    found = False
    for address, (device, advertisement) in devices.items():
        name = device.name or advertisement.local_name or ""
        if "hookscale" in name.lower() or any("6e400001" in uuid.lower() for uuid in advertisement.service_uuids):
            found = True
            print(f"FOUND address={address} name={name} rssi={advertisement.rssi} services={advertisement.service_uuids}")
    if not found:
        print("HOOKSCALE_NOT_FOUND")

asyncio.run(main())
