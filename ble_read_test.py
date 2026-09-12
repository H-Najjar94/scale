import asyncio
from bleak import BleakClient, BleakScanner

TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
received = 0

def notification(_, data):
    global received
    received += 1
    print(data.decode(errors="replace"))

async def main():
    global received
    device = await BleakScanner.find_device_by_name("HookScale-ESP32", timeout=15)
    if device is None:
        print("DEVICE_NOT_FOUND")
        return
    async with BleakClient(device, timeout=15) as client:
        print(f"CONNECTED={client.is_connected}")
        await client.start_notify(TX_UUID, notification)
        await asyncio.sleep(5)
        await client.stop_notify(TX_UUID)
    print(f"NOTIFICATIONS={received}")

asyncio.run(main())
