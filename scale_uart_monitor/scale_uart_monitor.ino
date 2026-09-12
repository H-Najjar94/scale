#include <Arduino.h>

HardwareSerial ScaleSerial(2);
static const int SCALE_RX_PIN = 16;
static const int SCALE_TX_PIN = 17;

void setup() {
  Serial.begin(115200);
  delay(500);
  ScaleSerial.begin(38400, SERIAL_8N1, SCALE_RX_PIN, SCALE_TX_PIN);
  Serial.println();
  Serial.println("Scale monitor: GPIO16, 38400 baud, 8N1");
}

void loop() {
  static uint32_t counts[256] = {};
  static uint32_t total = 0;
  static uint32_t lastReport = 0;
  while (ScaleSerial.available()) {
    uint8_t value = ScaleSerial.read();
    counts[value]++;
    total++;
  }
  if (millis() - lastReport >= 2000) {
    lastReport = millis();
    Serial.printf("total=%lu values:", (unsigned long)total);
    for (int i = 0; i < 256; i++) {
      if (counts[i]) Serial.printf(" %02X=%lu", i, (unsigned long)counts[i]);
    }
    Serial.println();
  }
}
