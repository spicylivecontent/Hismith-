/*
  MegaLubeSlave.ino  –  "dum" Bluetooth-slav för ESP32-WROOM-32 + DRV8871

  Gör bara tre saker:
    1. tar emot textkommandon över Bluetooth Classic (SPP),
    2. styr pumpmotorn mjukt via DRV8871 (framåt/bakåt),
    3. stoppar pumpen direkt om något är fel (länk bort, ingen trafik, för lång gångtid).
  Inga egna program, sekvenser eller menyer finns i kortet. Appen på mobilen bestämmer allt.

  Kommandon (en per rad, avslutas med \n eller \r\n, versaler/gemener spelar ingen roll):
    RUN:<hastighet>:<ms>   hastighet -100..100 (%), ms 1..60000. Negativ = bakåt (töm slangen).
                           Ett nytt RUN förnyar tiden, så appen kan "strömma" RUN.
    SET_SPEED:<hastighet>  samma hastighet, utan egen tid. Pumpen går bara så länge appen
                           fortsätter skicka kommandon (se watchdog).
    STOP                   omedelbart stopp med broms.
    PING                   håller watchdog vid liv, svar: PONG
    STATUS                 svar: STATE:<fas>:<nu%>:<mål%>:<kvar_ms>:<wdt_ms>
    WDT:<ms>               ändra watchdog-tiden 200..10000 (gäller tills anslutningen bryts)
  Svar: OK, OK:<verklig hastighet>, PONG, STATE:..., ERR:SYNTAX / RANGE / UNKNOWN / LEN / LOCKED
  Händelser: EVT:DONE (RUN-tiden slut), EVT:WDT (watchdog stoppade), EVT:MAXTIME (max gångtid).

  FAILSAFE
    - Bluetooth-länken bryts            -> stopp direkt (broms).
    - Inget GILTIGT kommando på 1000 ms -> stopp direkt (broms). Appen ska skicka något
                                           (t.ex. PING eller upprepad SET_SPEED) minst var 300:e ms.
    - Sammanhängande gångtid > 120 s    -> stopp och lås tills STOP skickas.
    - Efter omstart/strömavbrott        -> pumpen står still tills ett nytt kommando kommer.
    - Svaren till appen skickas av en egen uppgift. En trång eller stannad Bluetooth-länk
      kan därför aldrig fördröja ett stopp (full kö = svaret kastas, pumpen påverkas inte).
    - Hänger sig huvudloopen i mer än ca 5 s startar kortets inbyggda vakt (task watchdog)
      om det. Då släpper kortet motorstyrningen och pumpen står still efter omstarten.

  PWM: 20 kHz, 10 bit. Växlar mellan kör och broms ("slow decay"), som DRV8871-databladet
       rekommenderar. Vill du hellre växla mellan kör och fri rullning: bygg med
       -DLUBE_SLOW_DECAY=0 eller ändra raden högst upp i lube_core.h.
  Koppling: GPIO25 -> IN1, GPIO26 -> IN2 på DRV8871. Pumpen sitter på OUT1/OUT2.

  Bygginställningar (Arduino IDE / arduino-cli):
    Kort:      ESP32 Dev Module (klassisk ESP32, WROOM-32)
    Partition: Huge APP (3MB No OTA/1MB SPIFFS)   <- Bluetooth-sketcher är stora
    Fungerar med ESP32 Arduino-kärna 2.0.x och 3.x (testat mot simulerad miljö, se dokumentet).
*/

#include <Arduino.h>
#include <stdio.h>
#include <string.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include "BluetoothSerial.h"
#include "lube_core.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth är inte aktiverat. Välj kortet 'ESP32 Dev Module' (klassisk ESP32)."
#endif
#if !defined(CONFIG_BT_SPP_ENABLED)
#error "SPP (Serial Port Profile) finns bara på klassisk ESP32, inte på S2/S3/C3."
#endif

// ---------------------------------------------------------------------------
// Inställningar du kan behöva ändra
// ---------------------------------------------------------------------------
#define BT_NAME     "MegaLube"   // namnet som syns i Bluetooth-listan
#define BT_USE_PIN  1            // 1 = kräv PIN vid parkoppling, 0 = ingen PIN
#define BT_PIN      "2580"       // ÄNDRA DENNA. Fyra siffror.

static const uint8_t  PIN_IN1     = 25;     // -> DRV8871 IN1
static const uint8_t  PIN_IN2     = 26;     // -> DRV8871 IN2
static const uint8_t  PIN_LED     = 2;      // inbyggd LED på de flesta kort (255 = ingen LED)
static const uint32_t PWM_FREQ_HZ = 20000;  // över hörbart

// ---------------------------------------------------------------------------
static BluetoothSerial  SerialBT;
static lube::Device     g_dev;
static lube::LineReader g_reader;
static volatile bool    g_evClose = false;  // sätts av Bluetooth-återanrop
static bool             g_wasLinked = false;

static void btEvent(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  (void)param;
  if (event == ESP_SPP_CLOSE_EVT) g_evClose = true;  // bara en flagga; allt annat sker i loop()
}

// ---------------------------------------------------------------------------
// Svar till mobilen
//   SerialBT.println() kan vänta över en sekund när Bluetooths sändkö är full, till exempel
//   om appen inte läser det kortet skickar. Därför skriver loop() aldrig själv till Bluetooth.
//   Den lägger svaret i en kö (väntar 0 ms) och uppgiften "bt_out" skickar det. Är kön full
//   kastas svaret. Det är bättre än att ett stopp ska behöva vänta.
// ---------------------------------------------------------------------------
static const size_t OUT_LEN   = 64;  // längsta svar, inklusive nolltecken
static const size_t OUT_DEPTH = 16;  // så många svar får vänta på att skickas

struct OutMsg {
  char text[OUT_LEN];
};
static QueueHandle_t g_outQ = nullptr;

static void say(const char* s) {
  if (g_outQ == nullptr || s == nullptr || s[0] == 0) return;
  OutMsg m;
  snprintf(m.text, sizeof(m.text), "%s", s);
  xQueueSend(g_outQ, &m, 0);  // 0 = vänta aldrig. Full kö: svaret kastas.
}

static void sayClear() {  // nya anslutningar ska aldrig få gamla svar
  if (g_outQ != nullptr) xQueueReset(g_outQ);
}

// Uppgiften "bt_out": tar svar ur kön och skickar dem, ett i taget.
static void sendTask(void*) {
  for (;;) {
    OutMsg m;
    if (xQueueReceive(g_outQ, &m, portMAX_DELAY) != pdTRUE) continue;
    if (!SerialBT.hasClient()) continue;  // ingen lyssnar längre, släng svaret
    char line[OUT_LEN + 2];
    const size_t n = strlen(m.text);
    memcpy(line, m.text, n);
    line[n]     = '\r';
    line[n + 1] = '\n';
    SerialBT.write(reinterpret_cast<const uint8_t*>(line), n + 2);  // får vänta, det gör inget här
  }
}

static void pwmBegin() {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(PIN_IN1, PWM_FREQ_HZ, lube::cfg::PWM_BITS);
  ledcAttach(PIN_IN2, PWM_FREQ_HZ, lube::cfg::PWM_BITS);
#else
  ledcSetup(0, PWM_FREQ_HZ, lube::cfg::PWM_BITS);
  ledcAttachPin(PIN_IN1, 0);
  ledcSetup(1, PWM_FREQ_HZ, lube::cfg::PWM_BITS);
  ledcAttachPin(PIN_IN2, 1);
#endif
}

static void pwmWrite(uint16_t d1, uint16_t d2) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(PIN_IN1, d1);
  ledcWrite(PIN_IN2, d2);
#else
  ledcWrite(0, d1);
  ledcWrite(1, d2);
#endif
}

static void updateLed(uint32_t now, bool linked, bool running) {
  if (PIN_LED == 255) return;
  bool on;
  if (!linked)      on = ((now / 500) % 2) == 0;  // långsam blink: väntar på anslutning
  else if (running) on = ((now / 100) % 2) == 0;  // snabb blink: pumpen går
  else              on = true;                    // fast sken: ansluten, pumpen står
  digitalWrite(PIN_LED, on ? HIGH : LOW);
}

void setup() {
  // Motorpinnarna FÖRST och låga, så pumpen aldrig kan starta av misstag vid uppstart.
  pinMode(PIN_IN1, OUTPUT);
  digitalWrite(PIN_IN1, LOW);
  pinMode(PIN_IN2, OUTPUT);
  digitalWrite(PIN_IN2, LOW);
  if (PIN_LED != 255) {
    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LOW);
  }
  pwmBegin();
  pwmWrite(0, 0);

  Serial.begin(115200);
  Serial.println();
  Serial.println("MegaLubeSlave v1 startar");

  if (!SerialBT.begin(BT_NAME)) {
    Serial.println("Bluetooth kunde inte startas, startar om...");
    delay(1000);
    ESP.restart();
    return;  // kommer aldrig hit på riktigt kort
  }
  SerialBT.register_callback(btEvent);
#if BT_USE_PIN
  lube::setPinCompat(SerialBT, BT_PIN);
#endif

  // Uppgiften som skickar svar. Startar den inte fungerar styrningen ändå, men utan svar.
  g_outQ = xQueueCreate(OUT_DEPTH, sizeof(OutMsg));
  if (g_outQ != nullptr &&
      xTaskCreatePinnedToCore(sendTask, "bt_out", 4096, nullptr, 1, nullptr, 0) != pdPASS) {
    vQueueDelete(g_outQ);
    g_outQ = nullptr;
  }
  if (g_outQ == nullptr) Serial.println("Varning: svarsuppgiften startade inte, inga svar skickas");

  g_dev.pump.begin(millis());
#if defined(CONFIG_AUTOSTART_ARDUINO) && CONFIG_AUTOSTART_ARDUINO
  enableLoopWDT();  // sista reserv: hänger loop() sig i ca 5 s startar kortet om
#endif
  Serial.println("Redo. Parkoppla mobilen med namnet " BT_NAME);
}

void loop() {
  uint32_t now = millis();

  // 1) Anslutningsstatus. Länk bort = stopp direkt.
  bool linked = SerialBT.hasClient();
  if (g_evClose) {
    g_evClose = false;
    linked = false;
  }
  if (linked && !g_wasLinked) {
    g_wasLinked = true;
    g_reader.reset();
    sayClear();
    g_dev.onLinkUp(now);
    Serial.println("LINK:UP");
  } else if (!linked && g_wasLinked) {
    g_wasLinked = false;
    g_reader.reset();
    sayClear();
    g_dev.onLinkLost(now);
    Serial.println("LINK:DOWN");
  }

  // 2) Kommandon in (högst ett begränsat antal tecken per varv, så loop() aldrig fastnar)
  if (linked) {
    int budget = 96;
    while (budget-- > 0 && SerialBT.available()) {
      const int ch = SerialBT.read();
      if (ch < 0) break;
      const lube::LineReader::Result r = g_reader.feed((char)ch);
      if (r == lube::LineReader::LR_LINE) {
        say(g_dev.handle(g_reader.line(), millis()));
      } else if (r == lube::LineReader::LR_TOOLONG) {
        say("ERR:LEN");
      }
    }
  }

  // 3) Ramp, watchdog och motorutgångar
  now = millis();
  const lube::Outputs o = g_dev.pump.tick(now);
  static uint16_t last1 = 0xFFFF, last2 = 0xFFFF;
  if (o.in1 != last1 || o.in2 != last2) {
    pwmWrite(o.in1, o.in2);
    last1 = o.in1;
    last2 = o.in2;
  }

  // 4) Händelser ut
  const lube::Evt ev = g_dev.pump.takeEvent();
  if (ev != lube::EV_NONE) {
    const char* txt = lube::eventText(ev);
    Serial.println(txt);
    if (ev != lube::EV_LINKLOST && linked) say(txt);
  }

  updateLed(now, linked, g_dev.pump.active());
  delay(1);
}
