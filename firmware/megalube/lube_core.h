// lube_core.h – ren logik för "dum" pump-slav. Inga Arduino-beroenden,
// så den kan enhetstestas på en vanlig dator med g++.
//
// Hastighet: heltal i procent, -100..100. Positiv = framåt, negativ = bakåt (töm slangen).
// Allt som är säkerhetsrelaterat (watchdog, länkbortfall, max-tid, mjuk ramp) bor här.

#ifndef LUBE_CORE_H
#define LUBE_CORE_H

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

namespace lube {

// ---------------------------------------------------------------------------
// Justerbara konstanter
// ---------------------------------------------------------------------------
// 1 = PWM växlar mellan "kör" och "broms" (rekommenderas i DRV8871-databladet, jämnare
//     varvtal vid låga hastigheter). 0 = PWM växlar mellan "kör" och "frilöpning".
#ifndef LUBE_SLOW_DECAY
#define LUBE_SLOW_DECAY 1
#endif

namespace cfg {
static const bool     SLOW_DECAY     = (LUBE_SLOW_DECAY != 0);
static const uint8_t  PWM_BITS       = 10;
static const uint16_t PWM_MAX        = (uint16_t)((1u << PWM_BITS) - 1u);  // 1023
static const int32_t  FWD_MAX_PCT    = 100;    // tak framåt (%)
static const int32_t  REV_MAX_PCT    = 60;     // tak bakåt (%) – begränsar undertryck
static const int32_t  MIN_DRIVE_PM   = 200;    // ‰ av full effekt vid lägsta hastighet (pumpen ska starta)
static const bool     INVERT_DIR     = false;  // true = vänd riktning (eller byt motorkablarna)
static const uint32_t TICK_MS        = 5;      // uppdateringstakt för ramp/PWM
static const int32_t  RAMP_UP_PM_S   = 2500;   // ‰/s  -> 0..100 % på 0,4 s
static const int32_t  RAMP_DOWN_PM_S = 4000;   // ‰/s  -> 100..0 % på 0,25 s
static const uint32_t BRAKE_HARD_MS  = 250;    // broms efter nödstopp (STOP/watchdog/länk bort)
static const uint32_t BRAKE_SOFT_MS  = 120;    // broms efter mjuk stopp / före riktningsbyte
static const uint32_t WDT_DEFAULT_MS = 1000;   // inget giltigt kommando på så här lång tid -> stopp
static const uint32_t WDT_MIN_MS     = 200;
static const uint32_t WDT_MAX_MS     = 10000;
static const uint32_t RUN_MAX_MS     = 60000;  // längsta tid i ett RUN-kommando
static const uint32_t CONT_MAX_MS    = 120000; // längsta sammanhängande gångtid -> stopp + lås
static const size_t   LINE_CAP       = 48;     // längsta tillåtna kommandorad
}  // namespace cfg

enum Evt : uint8_t { EV_NONE = 0, EV_DONE, EV_WDT, EV_MAXTIME, EV_LINKLOST };

inline const char* eventText(Evt e) {
  switch (e) {
    case EV_DONE:     return "EVT:DONE";
    case EV_WDT:      return "EVT:WDT";
    case EV_MAXTIME:  return "EVT:MAXTIME";
    case EV_LINKLOST: return "EVT:LINKLOST";
    default:          return "";
  }
}

// Duty-värden (0..PWM_MAX) för DRV8871 IN1/IN2. PWM_MAX = konstant hög.
//   båda 0 -> frilöpning | båda PWM_MAX -> broms
//   Framåt vid effekt D (0..PWM_MAX):
//     LUBE_SLOW_DECAY 1: IN1 = PWM_MAX, IN2 = PWM_MAX - D   (kör/broms)
//     LUBE_SLOW_DECAY 0: IN1 = D,       IN2 = 0            (kör/frilöpning)
//   Bakåt: samma sak med IN1 och IN2 ombytta.
struct Outputs {
  uint16_t in1;
  uint16_t in2;
};

// ---------------------------------------------------------------------------
// Pump: ramp, broms, watchdog, tidsgränser
// ---------------------------------------------------------------------------
class Pump {
 public:
  Pump() { begin(0); }

  void begin(uint32_t now) {
    cur_ = 0;
    target_ = 0;
    hasLease_ = false;
    leaseEnd_ = 0;
    braking_ = false;
    brakeUntil_ = 0;
    locked_ = false;
    wdtMs_ = cfg::WDT_DEFAULT_MS;
    lastFeed_ = now;
    runStart_ = now;
    lastTick_ = now - cfg::TICK_MS;
    pending_ = EV_NONE;
    out_.in1 = 0;
    out_.in2 = 0;
  }

  // Sätt mål-hastighet. Returnerar false om pumpen är låst (efter MAXTIME tills STOP kommer).
  bool setSpeed(int32_t pct, bool useLease, uint32_t leaseMs, uint32_t now, int32_t* applied) {
    if (locked_) return false;
    if (pct > cfg::FWD_MAX_PCT) pct = cfg::FWD_MAX_PCT;
    if (pct < -cfg::REV_MAX_PCT) pct = -cfg::REV_MAX_PCT;
    if (cur_ == 0 && target_ == 0) runStart_ = now;
    target_ = pct * 10;
    hasLease_ = useLease && pct != 0;
    if (hasLease_) leaseEnd_ = now + leaseMs;
    lastFeed_ = now;
    if (applied) *applied = pct;
    return true;
  }

  // Omedelbart stopp med broms. Kvitterar även MAXTIME-låset.
  void stop(uint32_t now) {
    locked_ = false;
    if (cur_ != 0 || target_ != 0) hardStop(now, EV_NONE);
    hasLease_ = false;
  }

  void feed(uint32_t now) { lastFeed_ = now; }

  void linkUp(uint32_t now) {
    wdtMs_ = cfg::WDT_DEFAULT_MS;
    lastFeed_ = now;
    locked_ = false;
  }

  void linkLost(uint32_t now) {
    if (cur_ != 0 || target_ != 0) hardStop(now, EV_LINKLOST);
    hasLease_ = false;
    wdtMs_ = cfg::WDT_DEFAULT_MS;
    locked_ = false;
  }

  bool setWdt(uint32_t ms) {
    if (ms < cfg::WDT_MIN_MS || ms > cfg::WDT_MAX_MS) return false;
    wdtMs_ = ms;
    return true;
  }

  // Anropas ofta (varje varv i loop()). Returnerar aktuella utsignaler.
  Outputs tick(uint32_t now) {
    uint32_t dt = now - lastTick_;
    if (dt < cfg::TICK_MS) return out_;
    lastTick_ = now;
    if (dt > 100) dt = 100;  // efter ett långt uppehåll: ingen stor ramp-sats

    const bool active = (cur_ != 0) || (target_ != 0);
    if (active && !braking_) {
      if ((uint32_t)(now - lastFeed_) > wdtMs_) {
        hardStop(now, EV_WDT);
      } else if (hasLease_ && (int32_t)(now - leaseEnd_) >= 0) {
        target_ = 0;
        hasLease_ = false;
        pending_ = EV_DONE;
      } else if ((uint32_t)(now - runStart_) > cfg::CONT_MAX_MS) {
        hardStop(now, EV_MAXTIME);
        locked_ = true;
      }
    }

    if (braking_) {
      if ((int32_t)(now - brakeUntil_) >= 0) {
        braking_ = false;
      } else {
        out_.in1 = cfg::PWM_MAX;
        out_.in2 = cfg::PWM_MAX;
        return out_;
      }
    }

    // Aldrig direkt riktningsbyte: gå via noll (och broms).
    int32_t eff = target_;
    if ((cur_ > 0 && eff < 0) || (cur_ < 0 && eff > 0)) eff = 0;

    const int32_t prev = cur_;
    if (eff != cur_) {
      const int32_t ac = cur_ < 0 ? -cur_ : cur_;
      const int32_t ae = eff < 0 ? -eff : eff;
      const bool accel = (ae > ac) && (cur_ == 0 || ((cur_ > 0) == (eff > 0)));
      const int32_t rate = accel ? cfg::RAMP_UP_PM_S : cfg::RAMP_DOWN_PM_S;
      int32_t step = (int32_t)(((int64_t)rate * (int64_t)dt + 999) / 1000);
      if (step < 1) step = 1;
      if (cur_ < eff) {
        cur_ += step;
        if (cur_ > eff) cur_ = eff;
      } else {
        cur_ -= step;
        if (cur_ < eff) cur_ = eff;
      }
    }

    if (prev != 0 && cur_ == 0) {  // just stannat -> kort broms så den inte glider
      braking_ = true;
      brakeUntil_ = now + cfg::BRAKE_SOFT_MS;
      out_.in1 = cfg::PWM_MAX;
      out_.in2 = cfg::PWM_MAX;
      return out_;
    }

    if (cur_ == 0) {
      out_.in1 = 0;
      out_.in2 = 0;
    } else {
      const int32_t a = cur_ < 0 ? -cur_ : cur_;  // 1..1000
      const int32_t dpm = cfg::MIN_DRIVE_PM + (1000 - cfg::MIN_DRIVE_PM) * a / 1000;
      const uint16_t duty = (uint16_t)(((int64_t)dpm * cfg::PWM_MAX) / 1000);
      const bool fwd = (cur_ > 0) != cfg::INVERT_DIR;
      const uint16_t drive = cfg::SLOW_DECAY ? cfg::PWM_MAX : duty;                    // den "kör"-pinne
      const uint16_t other = cfg::SLOW_DECAY ? (uint16_t)(cfg::PWM_MAX - duty) : (uint16_t)0;  // den andra
      out_.in1 = fwd ? drive : other;
      out_.in2 = fwd ? other : drive;
    }
    return out_;
  }

  // --- status ---
  int32_t curPct() const { return cur_ / 10; }
  int32_t targetPct() const { return target_ / 10; }
  uint32_t wdtMs() const { return wdtMs_; }
  bool locked() const { return locked_; }
  bool active() const { return cur_ != 0 || target_ != 0 || braking_; }
  uint32_t leaseLeftMs(uint32_t now) const {
    if (!hasLease_) return 0;
    const int32_t d = (int32_t)(leaseEnd_ - now);
    return d > 0 ? (uint32_t)d : 0;
  }
  const char* phase() const {
    if (braking_) return "BRAKE";
    return (cur_ != 0 || target_ != 0) ? "RUN" : "IDLE";
  }
  Evt takeEvent() {
    const Evt e = pending_;
    pending_ = EV_NONE;
    return e;
  }

 private:
  void hardStop(uint32_t now, Evt ev) {
    cur_ = 0;
    target_ = 0;
    hasLease_ = false;
    braking_ = true;
    brakeUntil_ = now + cfg::BRAKE_HARD_MS;
    if (ev != EV_NONE) pending_ = ev;
    out_.in1 = cfg::PWM_MAX;  // gäller direkt, även innan nästa tick
    out_.in2 = cfg::PWM_MAX;
  }

  int32_t cur_, target_;  // ‰ (-1000..1000)
  bool hasLease_;
  uint32_t leaseEnd_;
  bool braking_;
  uint32_t brakeUntil_;
  bool locked_;
  uint32_t wdtMs_, lastFeed_, runStart_, lastTick_;
  Evt pending_;
  Outputs out_;
};

// ---------------------------------------------------------------------------
// Radläsare: bygger rader av tecken (\n, \r eller \r\n avslutar)
// ---------------------------------------------------------------------------
class LineReader {
 public:
  enum Result : uint8_t { LR_NONE = 0, LR_LINE, LR_TOOLONG };

  LineReader() : len_(0), over_(false) { buf_[0] = 0; }

  Result feed(char c) {
    if (c == '\n' || c == '\r') {
      if (over_) {
        over_ = false;
        len_ = 0;
        return LR_TOOLONG;
      }
      if (len_ == 0) return LR_NONE;  // tom rad (t.ex. andra halvan av \r\n)
      buf_[len_] = 0;
      len_ = 0;
      return LR_LINE;
    }
    if (len_ < cfg::LINE_CAP) {
      buf_[len_++] = c;
    } else {
      over_ = true;
    }
    return LR_NONE;
  }

  char* line() { return buf_; }
  void reset() {
    len_ = 0;
    over_ = false;
  }

 private:
  char buf_[cfg::LINE_CAP + 1];
  size_t len_;
  bool over_;
};

// ---------------------------------------------------------------------------
// Kommandotolk
//   RUN:<hastighet>:<ms>   hastighet -100..100 %, ms 1..60000 (förnyas av varje nytt RUN)
//   SET_SPEED:<hastighet>  fortsätter så länge kommandon kommer (watchdog)
//   STOP                   omedelbart stopp + broms
//   PING                   håller watchdog vid liv -> PONG
//   STATUS                 -> STATE:<fas>:<nu%>:<mål%>:<kvar_ms>:<wdt_ms>
//   WDT:<ms>               ändrar watchdog-tid (200..10000), återställs vid ny anslutning
// ---------------------------------------------------------------------------
class Device {
 public:
  Pump pump;

  // Tar en komplett rad (utan radslut) och returnerar svar ("" = inget svar).
  // OBS: ändrar innehållet i 'line'.
  const char* handle(char* line, uint32_t now) {
    char* s = trim(line);
    size_t n = strlen(s);
    if (n == 0) return "";
    for (size_t i = 0; i < n; i++) {
      if (s[i] >= 'a' && s[i] <= 'z') s[i] = (char)(s[i] - 32);
    }

    char* tok[3];
    size_t nt = 0;
    tok[nt++] = s;
    for (size_t i = 0; i < n; i++) {
      if (s[i] == ':') {
        s[i] = 0;
        if (nt >= 3) return "ERR:SYNTAX";
        tok[nt++] = s + i + 1;
      }
    }
    for (size_t i = 0; i < nt; i++) tok[i] = trim(tok[i]);

    const char* c = tok[0];
    int32_t a = 0, b = 0;

    if (!strcmp(c, "RUN")) {
      if (nt != 3) return "ERR:SYNTAX";
      if (!parseInt(tok[1], a) || !parseInt(tok[2], b)) return "ERR:SYNTAX";
      if (a < -100 || a > 100 || b < 1 || b > (int32_t)cfg::RUN_MAX_MS) return "ERR:RANGE";
      return doSpeed(a, true, (uint32_t)b, now);
    }
    if (!strcmp(c, "SET_SPEED")) {
      if (nt != 2) return "ERR:SYNTAX";
      if (!parseInt(tok[1], a)) return "ERR:SYNTAX";
      if (a < -100 || a > 100) return "ERR:RANGE";
      return doSpeed(a, false, 0, now);
    }
    if (!strcmp(c, "STOP")) {
      if (nt != 1) return "ERR:SYNTAX";
      pump.feed(now);
      pump.stop(now);
      return "OK";
    }
    if (!strcmp(c, "PING")) {
      if (nt != 1) return "ERR:SYNTAX";
      pump.feed(now);
      return "PONG";
    }
    if (!strcmp(c, "STATUS")) {
      if (nt != 1) return "ERR:SYNTAX";
      pump.feed(now);
      snprintf(reply_, sizeof(reply_), "STATE:%s:%ld:%ld:%lu:%lu", pump.phase(),
               (long)pump.curPct(), (long)pump.targetPct(),
               (unsigned long)pump.leaseLeftMs(now), (unsigned long)pump.wdtMs());
      return reply_;
    }
    if (!strcmp(c, "WDT")) {
      if (nt != 2) return "ERR:SYNTAX";
      if (!parseInt(tok[1], a)) return "ERR:SYNTAX";
      if (a < (int32_t)cfg::WDT_MIN_MS || a > (int32_t)cfg::WDT_MAX_MS) return "ERR:RANGE";
      pump.setWdt((uint32_t)a);
      pump.feed(now);
      return "OK";
    }
    return "ERR:UNKNOWN";
  }

  void onLinkUp(uint32_t now) { pump.linkUp(now); }
  void onLinkLost(uint32_t now) { pump.linkLost(now); }

 private:
  const char* doSpeed(int32_t pct, bool lease, uint32_t ms, uint32_t now) {
    int32_t applied = 0;
    if (!pump.setSpeed(pct, lease, ms, now, &applied)) return "ERR:LOCKED";
    snprintf(reply_, sizeof(reply_), "OK:%ld", (long)applied);
    return reply_;
  }

  static char* trim(char* t) {
    while (*t == ' ' || *t == '\t' || *t == '\r' || *t == '\n') t++;
    size_t m = strlen(t);
    while (m > 0 && (t[m - 1] == ' ' || t[m - 1] == '\t' || t[m - 1] == '\r' || t[m - 1] == '\n')) {
      t[--m] = 0;
    }
    return t;
  }

  // Strikt heltal: valfritt +/-, sedan 1..8 siffror, inget annat.
  static bool parseInt(const char* s, int32_t& out) {
    size_t n = strlen(s);
    size_t i = 0;
    bool neg = false;
    if (n > 0 && (s[0] == '-' || s[0] == '+')) {
      neg = (s[0] == '-');
      i = 1;
    }
    if (n - i < 1 || n - i > 8) return false;
    int32_t v = 0;
    for (; i < n; i++) {
      if (s[i] < '0' || s[i] > '9') return false;
      v = v * 10 + (s[i] - '0');
    }
    out = neg ? -v : v;
    return true;
  }

  char reply_[64];
};

// ---------------------------------------------------------------------------
// BluetoothSerial::setPin() har haft olika signaturer mellan ESP32-kärnor
// (setPin(pin) respektive setPin(pin, längd)). Den här hjälparen väljer den som finns.
// ---------------------------------------------------------------------------
namespace detail {
template <typename T>
auto setPinImpl(T& bt, const char* pin, int) -> decltype(bt.setPin(pin, (uint8_t)strlen(pin)), void()) {
  bt.setPin(pin, (uint8_t)strlen(pin));
}
template <typename T>
auto setPinImpl(T& bt, const char* pin, long) -> decltype(bt.setPin(pin), void()) {
  bt.setPin(pin);
}
}  // namespace detail

template <typename T>
inline void setPinCompat(T& bt, const char* pin) {
  detail::setPinImpl(bt, pin, 0);
}

}  // namespace lube

#endif  // LUBE_CORE_H
