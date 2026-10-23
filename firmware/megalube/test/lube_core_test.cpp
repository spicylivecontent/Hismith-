// Värdtest för lube_core.h – körs på en vanlig dator, ingen ESP32 behövs.
//   g++ -std=c++11 -Wall -Wextra -Werror -I.. lube_core_test.cpp -o lube_core_test && ./lube_core_test
//
// Testar det appen (Hismith Smart Session) förlitar sig på: RUN-strömning med förnyad tid,
// watchdog, länkbortfall, MAXTIME-lås, riktningsbyte via noll och kommandotolken.

#include <cstdio>
#include <cstring>
#include <string>

#include "../lube_core.h"

static int g_fail = 0;
static int g_checks = 0;

#define CHECK(cond)                                                        \
  do {                                                                     \
    ++g_checks;                                                            \
    if (!(cond)) {                                                         \
      ++g_fail;                                                            \
      std::printf("FEL %s:%d: %s\n", __FILE__, __LINE__, #cond);           \
    }                                                                      \
  } while (0)

static std::string cmd(lube::Device& d, const char* text, uint32_t now) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%s", text);
  return d.handle(buf, now);
}

// Kör tick() i 5 ms-steg från 'from' till 'to' och returnerar sista händelsen (om någon).
static lube::Evt run(lube::Device& d, uint32_t from, uint32_t to) {
  lube::Evt last = lube::EV_NONE;
  for (uint32_t t = from; t <= to; t += 5) {
    d.pump.tick(t);
    lube::Evt e = d.pump.takeEvent();
    if (e != lube::EV_NONE) last = e;
  }
  return last;
}

static void testParser() {
  lube::Device d;
  d.pump.begin(0);
  CHECK(cmd(d, "PING", 0) == "PONG");
  CHECK(cmd(d, "ping", 0) == "PONG");
  CHECK(cmd(d, "  STATUS  ", 0) == "STATE:IDLE:0:0:0:1000");
  CHECK(cmd(d, "RUN:50", 0) == "ERR:SYNTAX");
  CHECK(cmd(d, "RUN:101:100", 0) == "ERR:RANGE");
  CHECK(cmd(d, "RUN:50:0", 0) == "ERR:RANGE");
  CHECK(cmd(d, "RUN:50:60001", 0) == "ERR:RANGE");
  CHECK(cmd(d, "RUN:5x:100", 0) == "ERR:SYNTAX");
  CHECK(cmd(d, "WDT:100", 0) == "ERR:RANGE");
  CHECK(cmd(d, "WDT:6000", 0) == "OK");
  CHECK(cmd(d, "FOO", 0) == "ERR:UNKNOWN");
  CHECK(cmd(d, "A:B:C:D", 0) == "ERR:SYNTAX");
  CHECK(cmd(d, "STOP", 0) == "OK");
  CHECK(cmd(d, "SET_SPEED:-80", 0) == "OK:-60");  // bakåt begränsas till 60 %
  CHECK(cmd(d, "STOP", 0) == "OK");
}

static void testRunStreamingAndDone() {
  // Appen strömmar RUN:<v>:<lease> var 250:e ms och avslutar med exakt kvarvarande tid.
  lube::Device d;
  d.pump.begin(0);
  uint32_t t = 0;
  CHECK(cmd(d, "RUN:60:700", t) == "OK:60");
  for (int i = 0; i < 8; ++i) {
    CHECK(run(d, t, t + 245) == lube::EV_NONE);
    t += 250;
    CHECK(cmd(d, "RUN:60:700", t) == "OK:60");
  }
  CHECK(d.pump.curPct() == 60);
  CHECK(cmd(d, "RUN:60:300", t) == "OK:60");
  CHECK(run(d, t, t + 290) == lube::EV_NONE);
  CHECK(run(d, t + 295, t + 320) == lube::EV_DONE);
  run(d, t + 325, t + 1000);
  CHECK(std::strcmp(d.pump.phase(), "IDLE") == 0);
}

static void testWatchdog() {
  lube::Device d;
  d.pump.begin(0);
  CHECK(cmd(d, "SET_SPEED:30", 0) == "OK:30");
  CHECK(run(d, 0, 995) == lube::EV_NONE);
  CHECK(run(d, 1000, 1010) == lube::EV_WDT);
  CHECK(d.pump.curPct() == 0);
  // PING håller den vid liv.
  CHECK(cmd(d, "SET_SPEED:30", 2000) == "OK:30");
  uint32_t t = 2000;
  for (int i = 0; i < 10; ++i) {
    run(d, t, t + 295);
    t += 300;
    CHECK(cmd(d, "PING", t) == "PONG");
  }
  CHECK(d.pump.curPct() == 30);
  CHECK(cmd(d, "STOP", t) == "OK");
}

static void testLinkLost() {
  lube::Device d;
  d.pump.begin(0);
  CHECK(cmd(d, "SET_SPEED:50", 0) == "OK:50");
  run(d, 0, 400);
  d.onLinkLost(400);
  CHECK(d.pump.curPct() == 0);
  lube::Outputs o = d.pump.tick(405);
  CHECK(o.in1 == lube::cfg::PWM_MAX && o.in2 == lube::cfg::PWM_MAX);  // broms
  CHECK(d.pump.takeEvent() == lube::EV_LINKLOST);
}

static void testMaxTimeLock() {
  lube::Device d;
  d.pump.begin(0);
  CHECK(cmd(d, "WDT:10000", 0) == "OK");
  uint32_t t = 0;
  CHECK(cmd(d, "SET_SPEED:40", t) == "OK:40");
  lube::Evt ev = lube::EV_NONE;
  while (t < 125000 && ev == lube::EV_NONE) {
    ev = run(d, t, t + 995);
    t += 1000;
    if (ev == lube::EV_NONE) cmd(d, "PING", t);
  }
  CHECK(ev == lube::EV_MAXTIME);
  CHECK(t > 119000);
  CHECK(cmd(d, "SET_SPEED:40", t) == "ERR:LOCKED");
  CHECK(cmd(d, "STOP", t) == "OK");
  CHECK(cmd(d, "SET_SPEED:40", t) == "OK:40");
  CHECK(cmd(d, "STOP", t) == "OK");
}

static void testDirectionChangeGoesThroughZero() {
  lube::Device d;
  d.pump.begin(0);
  CHECK(cmd(d, "SET_SPEED:50", 0) == "OK:50");
  uint32_t t = 0;
  for (; t < 600; t += 5) d.pump.tick(t);
  CHECK(cmd(d, "SET_SPEED:-50", t) == "OK:-50");
  bool sawZero = false;
  for (int i = 0; i < 200; ++i, t += 5) {
    d.pump.tick(t);
    if (d.pump.curPct() == 0) sawZero = true;
    if (i % 50 == 0) cmd(d, "PING", t);
  }
  CHECK(sawZero);
  CHECK(d.pump.curPct() < 0);
  CHECK(cmd(d, "STOP", t) == "OK");
}

static void testLineReader() {
  lube::LineReader r;
  const char* in = "PING\r\nSTATUS\n";
  int lines = 0;
  for (const char* p = in; *p; ++p) {
    if (r.feed(*p) == lube::LineReader::LR_LINE) ++lines;
  }
  CHECK(lines == 2);
  for (int i = 0; i < 60; ++i) r.feed('A');
  CHECK(r.feed('\n') == lube::LineReader::LR_TOOLONG);
}

int main() {
  testParser();
  testRunStreamingAndDone();
  testWatchdog();
  testLinkLost();
  testMaxTimeLock();
  testDirectionChangeGoesThroughZero();
  testLineReader();
  std::printf("%d kontroller, %d fel\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}
