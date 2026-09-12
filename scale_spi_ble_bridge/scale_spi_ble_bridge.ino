#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#include "driver/spi_slave.h"

#include "soc/gpio_reg.h"
#include "soc/soc.h"

// Set to 0 for a low-current USB serial wiring test. Restore to 1 for BLE output.
#ifndef ENABLE_BLE
#define ENABLE_BLE 1
#endif

// Passive taps on the scale MCU -> Si4432/RFM22B SPI bus.
// These pins are never configured as outputs. GPIO2/8/9 are avoided on C3
// because they are commonly boot-strapping pins.
#if CONFIG_IDF_TARGET_ESP32C3
static constexpr uint8_t PIN_SCLK = 4;
static constexpr uint8_t PIN_SDI  = 5;   // scale MCU MOSI / radio SDI
static constexpr uint8_t PIN_NSEL = 6;   // active-low chip select
#else
static constexpr uint8_t PIN_SCLK = 25;
static constexpr uint8_t PIN_SDI  = 26;  // scale MCU MOSI / radio SDI
static constexpr uint8_t PIN_NSEL = 27;  // active-low chip select
#endif

// Nordic-UART-compatible BLE service. The TX characteristic sends ASCII lines.
static const char *SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
static const char *TX_UUID      = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

static volatile uint8_t transaction[16];
static volatile uint8_t transactionLength = 0;
static volatile uint8_t bitCount = 0;
static volatile uint8_t shiftByte = 0;

static volatile uint8_t payload[6];
static volatile uint8_t payloadLength = 0;

static constexpr uint8_t QUEUE_SIZE = 16;
static volatile uint32_t rawQueue[QUEUE_SIZE];
static volatile uint8_t queueHead = 0;
static volatile uint8_t queueTail = 0;
static volatile uint32_t droppedPackets = 0;
static volatile uint32_t clockEdgesSeen = 0;
static volatile uint32_t selectionsSeen = 0;
static volatile uint32_t fifoTransactionsSeen = 0;
static volatile uint32_t fifoBytesSeen = 0;
static constexpr uint16_t BYTE_QUEUE_SIZE = 256;
static volatile uint8_t byteQueue[BYTE_QUEUE_SIZE];
static volatile uint16_t byteQueueHead = 0;
static volatile uint16_t byteQueueTail = 0;

static BLECharacteristic *bleTx = nullptr;
static volatile bool bleRestartAdvertising = false;

class ScaleServerCallbacks : public BLEServerCallbacks {
  void onDisconnect(BLEServer *) override {
    bleRestartAdvertising = true;
  }
};

static constexpr uint8_t SPI_QUEUE_SIZE = 12;
static constexpr uint8_t SPI_BUFFER_SIZE = 16;
static spi_slave_transaction_t spiTransactions[SPI_QUEUE_SIZE];
static uint8_t spiRxBuffers[SPI_QUEUE_SIZE][SPI_BUFFER_SIZE]
    __attribute__((aligned(4)));

static inline bool IRAM_ATTR pinIsHigh(uint8_t pin) {
  return (REG_READ(GPIO_IN_REG) & (1UL << pin)) != 0;
}

static void IRAM_ATTR queueRaw(uint32_t raw) {
  const uint8_t next = (queueHead + 1) % QUEUE_SIZE;
  if (next == queueTail) {
    droppedPackets++;
    return;
  }
  rawQueue[queueHead] = raw;
  queueHead = next;
}

static void IRAM_ATTR acceptFifoByte(uint8_t value) {
  // Sliding window automatically recovers when BLE activity makes a C3 miss
  // one of the radio's separate one-byte FIFO write transactions.
  if (payloadLength < 6) {
    payload[payloadLength++] = value;
  } else {
    for (uint8_t index = 0; index < 5; ++index) payload[index] = payload[index + 1];
    payload[5] = value;
  }
  if (payloadLength < 6) return;
  if (payload[0] == 0x02 && payload[5] == 0xAA) {
    const uint32_t raw = static_cast<uint32_t>(payload[1]) |
                         (static_cast<uint32_t>(payload[2]) << 8) |
                         (static_cast<uint32_t>(payload[3]) << 16);
    queueRaw(raw);
  }
}

static void IRAM_ATTR onClockRise() {
  if (pinIsHigh(PIN_NSEL)) return;
  clockEdgesSeen++;

  shiftByte = static_cast<uint8_t>((shiftByte << 1) |
                                   (pinIsHigh(PIN_SDI) ? 1 : 0));
  bitCount++;
  if (bitCount == 8) {
    if (transactionLength < sizeof(transaction)) {
      transaction[transactionLength++] = shiftByte;
    }
    bitCount = 0;
    shiftByte = 0;
  }
}

static void IRAM_ATTR onChipSelectChange() {
  if (!pinIsHigh(PIN_NSEL)) {
    selectionsSeen++;
    transactionLength = 0;
    bitCount = 0;
    shiftByte = 0;
    return;
  }

  // Si4432 write address 0xFF means register 0x7F (TX FIFO).
  if (bitCount == 0 && transactionLength >= 2 && transaction[0] == 0xFF) {
    fifoTransactionsSeen++;
    for (uint8_t index = 1; index < transactionLength; ++index) {
      fifoBytesSeen++;
      const uint16_t nextByte = (byteQueueHead + 1) % BYTE_QUEUE_SIZE;
      if (nextByte != byteQueueTail) {
        byteQueue[byteQueueHead] = transaction[index];
        byteQueueHead = nextByte;
      }
      acceptFifoByte(transaction[index]);
    }
  }
}

static void startHardwareSpiListener() {
  spi_bus_config_t bus = {};
  bus.mosi_io_num = PIN_SDI;
  bus.miso_io_num = -1;  // Never drive the scale's SDO line.
  bus.sclk_io_num = PIN_SCLK;
  bus.quadwp_io_num = -1;
  bus.quadhd_io_num = -1;
  bus.max_transfer_sz = SPI_BUFFER_SIZE;

  spi_slave_interface_config_t slave = {};
  slave.spics_io_num = PIN_NSEL;
  slave.flags = 0;
  slave.queue_size = SPI_QUEUE_SIZE;
  slave.mode = 0;

  ESP_ERROR_CHECK(spi_slave_initialize(SPI2_HOST, &bus, &slave, SPI_DMA_DISABLED));
  for (uint8_t i = 0; i < SPI_QUEUE_SIZE; ++i) {
    memset(&spiTransactions[i], 0, sizeof(spiTransactions[i]));
    spiTransactions[i].length = SPI_BUFFER_SIZE * 8;
    spiTransactions[i].rx_buffer = spiRxBuffers[i];
    ESP_ERROR_CHECK(spi_slave_queue_trans(SPI2_HOST, &spiTransactions[i], portMAX_DELAY));
  }
}

static void serviceHardwareSpi() {
  spi_slave_transaction_t *completed = nullptr;
  uint8_t processed = 0;
  while (processed++ < 24 &&
         spi_slave_get_trans_result(SPI2_HOST, &completed, 0) == ESP_OK) {
    selectionsSeen++;
    clockEdgesSeen += completed->trans_len;
    const size_t byteCount = (completed->trans_len + 7) / 8;
    const uint8_t *bytes = static_cast<const uint8_t *>(completed->rx_buffer);

    if (completed->trans_len == 14 && byteCount >= 2 && (bytes[0] & 0xFC) == 0xFC) {
      // ESP32-C3 sees the final six address bits plus all eight data bits.
      // FIFO address 0xFF makes those six known bits all ones. IDF stores the
      // 14 received bits left-aligned, so recover the original data byte.
      const uint8_t value = static_cast<uint8_t>(((bytes[0] & 0x03) << 6) |
                                                  (bytes[1] >> 2));
      fifoTransactionsSeen++;
      fifoBytesSeen++;
      acceptFifoByte(value);
    } else if (completed->trans_len >= 16 && byteCount >= 2 && bytes[0] == 0xFF) {
      fifoTransactionsSeen++;
      fifoBytesSeen++;
      acceptFifoByte(bytes[1]);
    }

    completed->trans_len = 0;
    ESP_ERROR_CHECK(spi_slave_queue_trans(SPI2_HOST, completed, portMAX_DELAY));
  }
}

static void acceptTransaction(const uint8_t *bytes, uint8_t byteCount) {
  selectionsSeen++;
  if (byteCount < 2 || bytes[0] != 0xFF) return;
  fifoTransactionsSeen++;
  for (uint8_t index = 1; index < byteCount; ++index) {
    fifoBytesSeen++;
    const uint16_t nextByte = (byteQueueHead + 1) % BYTE_QUEUE_SIZE;
    if (nextByte != byteQueueTail) {
      byteQueue[byteQueueHead] = bytes[index];
      byteQueueHead = nextByte;
    }
    acceptFifoByte(bytes[index]);
  }
}

// The scale gives too little NSEL setup/hold time for ESP32's SPI-slave block.
// A tight polling loop samples the measured ~0.37 MHz clock reliably.
static void pollScaleSpi(uint32_t windowUs) {
  static bool active = false;
  static bool previousClock = false;
  static uint8_t bytes[SPI_BUFFER_SIZE];
  static uint8_t byteLength = 0;
  static uint8_t currentByte = 0;
  static uint8_t currentBits = 0;

  const uint32_t started = micros();
  uint16_t iterations = 0;
  do {
    const uint32_t levels = REG_READ(GPIO_IN_REG);
    const bool selected = (levels & (1UL << PIN_NSEL)) == 0;
    const bool clock = (levels & (1UL << PIN_SCLK)) != 0;

    if (selected) {
      if (!active) {
        active = true;
        previousClock = clock;
        byteLength = 0;
        currentByte = 0;
        currentBits = 0;
      } else if (!previousClock && clock) {
        clockEdgesSeen++;
        currentByte = static_cast<uint8_t>((currentByte << 1) |
            ((levels & (1UL << PIN_SDI)) ? 1 : 0));
        if (++currentBits == 8) {
          if (byteLength < sizeof(bytes)) bytes[byteLength++] = currentByte;
          currentByte = 0;
          currentBits = 0;
        }
      }
      previousClock = clock;
    } else if (active) {
      active = false;
      if (currentBits == 0) acceptTransaction(bytes, byteLength);
    }
  } while (++iterations != 0 || static_cast<uint32_t>(micros() - started) < windowUs);
}

static void startBle() {
  BLEDevice::init("HookScale-ESP32");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ScaleServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);
  bleTx = service->createCharacteristic(
      TX_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  bleTx->addDescriptor(new BLE2902());
  bleTx->setValue("WAITING");
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->start();
}

void setup() {
  Serial.begin(115200);
  delay(400);

  // Lower CPU current leaves more supply margin for the BLE radio burst.
  setCpuFrequencyMhz(80);

  pinMode(PIN_SCLK, INPUT);
  pinMode(PIN_SDI, INPUT);
  pinMode(PIN_NSEL, INPUT);

#if CONFIG_IDF_TARGET_ESP32C3
  startHardwareSpiListener();
#endif

  if (ENABLE_BLE) {
    startBle();
    setCpuFrequencyMhz(160);
  }
  Serial.println("HookScale passive SPI -> BLE bridge");
  Serial.printf("SCLK=GPIO%u SDI=GPIO%u NSEL=GPIO%u; all inputs\n",
                PIN_SCLK, PIN_SDI, PIN_NSEL);
  if (ENABLE_BLE) Serial.println("BLE device: HookScale-ESP32");
  else Serial.println("BLE disabled for wiring test");
}

void loop() {
#if CONFIG_IDF_TARGET_ESP32C3
  serviceHardwareSpi();
#else
  pollScaleSpi(2000);
#endif

  if (ENABLE_BLE && bleRestartAdvertising) {
    bleRestartAdvertising = false;
    BLEDevice::startAdvertising();
  }
  // Discard the optional byte-level diagnostic stream in normal operation.
  byteQueueTail = byteQueueHead;

  // Average every valid radio packet received during the BLE interval. The scale
  // transmits about 50.5 packets/s and its raw value has a slow cyclic ripple.
  // Sending only the latest packet at 5 Hz aliases that ripple and can make the
  // same physical load appear different after it is lowered and raised again.
  static uint64_t intervalSum = 0;
  static uint16_t intervalSamples = 0;
  while (queueTail != queueHead) {
    noInterrupts();
    const uint32_t queuedRaw = rawQueue[queueTail];
    queueTail = (queueTail + 1) % QUEUE_SIZE;
    interrupts();
    intervalSum += queuedRaw;
    intervalSamples++;
  }

  static uint32_t lastSend = 0;
  if (intervalSamples > 0 && millis() - lastSend >= 200) {
    lastSend = millis();
    const uint16_t samplesSent = intervalSamples;
    const uint32_t averagedRaw = static_cast<uint32_t>(
        (intervalSum + intervalSamples / 2) / intervalSamples);
    intervalSum = 0;
    intervalSamples = 0;
    // This remains below the default 20-byte BLE payload even at the maximum
    // 24-bit reading. Android owns calibration and all weight calculations.
    char message[20];
    snprintf(message, sizeof(message), "RAW=%lu,N=%u",
             static_cast<unsigned long>(averagedRaw), samplesSent);

    Serial.println(message);
    if (ENABLE_BLE && bleTx != nullptr) {
      bleTx->setValue(reinterpret_cast<uint8_t *>(message), strlen(message));
      bleTx->notify();
    }
  }

  static uint32_t lastDropReport = 0;
  if (droppedPackets != lastDropReport) {
    lastDropReport = droppedPackets;
    Serial.printf("Dropped packets: %lu\n", static_cast<unsigned long>(lastDropReport));
  }

  static uint32_t lastDiagnostic = 0;
  if (millis() - lastDiagnostic >= 5000) {
    lastDiagnostic = millis();
    Serial.printf("DIAG SCLK=%lu NSEL=%lu FIFO_TX=%lu FIFO_BYTES=%lu levels(C,D,S)=%u%u%u\n",
                  static_cast<unsigned long>(clockEdgesSeen),
                  static_cast<unsigned long>(selectionsSeen),
                  static_cast<unsigned long>(fifoTransactionsSeen),
                  static_cast<unsigned long>(fifoBytesSeen),
                  pinIsHigh(PIN_SCLK), pinIsHigh(PIN_SDI), pinIsHigh(PIN_NSEL));
  }
#if CONFIG_IDF_TARGET_ESP32C3
  // The C3 is single-core; yield so its BLE host can transmit queued updates.
  delay(1);
#endif
}
