#include <Arduino.h>
#include "soc/gpio_reg.h"
#include "soc/soc.h"

static const uint8_t watchedPins[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 21};

void setup() {
  Serial.begin(115200);
  delay(1000);
  for (uint8_t pin : watchedPins) pinMode(pin, INPUT);
  Serial.println("ESP32-C3 all-pin activity scan; each result covers 5 seconds");
}

void loop() {
  uint32_t counts[22] = {};
  uint32_t previous = REG_READ(GPIO_IN_REG);
  const uint32_t started = millis();
  uint16_t checkTimer = 0;

  while (true) {
    const uint32_t current = REG_READ(GPIO_IN_REG);
    uint32_t changed = current ^ previous;
    previous = current;
    for (uint8_t pin : watchedPins) {
      if (changed & (1UL << pin)) counts[pin]++;
    }
    if (++checkTimer == 0 && millis() - started >= 5000) break;
  }

  const uint32_t levels = REG_READ(GPIO_IN_REG);
  for (uint8_t pin : watchedPins) {
    Serial.printf("GPIO%u transitions=%lu level=%u\n", pin,
                  static_cast<unsigned long>(counts[pin]),
                  (levels & (1UL << pin)) ? 1 : 0);
  }
  Serial.println("---");
}
