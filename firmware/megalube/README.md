# MegaLube – firmware

"Dum" Bluetooth-slav för ESP32-WROOM-32 + DRV8871. Kortet tar emot textkommandon över
**Bluetooth Classic (SPP)** och styr pumpen mjukt; all logik (ml, flöde, kalibrering,
mikrodoser, final) bor i appen **Hismith Smart Session**.

## Färdig .bin

| Var | Fil |
|-----|-----|
| Release *MegaLube firmware (senaste)* | `MegaLube-merged.bin` – flashas på **0x0** |
| Actions → *Build MegaLube firmware* → Artifacts → `MegaLube-bin` | samma fil + alla delfiler |

`MegaLube-app-0x10000.bin` är bara app-delen (för den som redan har bootloader/partitioner).

## Flytta till ett eget repo

Den här mappen är fristående. Skapa ett nytt repo och lägg **innehållet** i mappen i roten
(`MegaLubeSlave.ino`, `lube_core.h`, `test/`, `.github/workflows/build.yml`, `README.md`).
Workflowen `Bygg MegaLube` bygger då .bin vid varje push och publicerar releasen
`firmware-latest` från `main`.

## Flasha från Android

1. Dra ur 12 V. Koppla telefon → USB-OTG → datakabel → ESP32.
2. ESPFlash (Play) eller WebSerial ESPTool i Chrome: välj `MegaLube-merged.bin`, adress `0x0`.
3. Går kortet inte i flashläge: håll BOOT, tryck kort på EN, släpp BOOT.

## Parkoppla med appen

1. Starta kortet (LED blinkar långsamt).
2. Android → Bluetooth → parkoppla **MegaLube**, PIN **2580** (ändra `BT_PIN` i `.ino`).
3. Hismith Smart Session → **Lube**-fliken → *Anslut MegaLube* → välj enheten.

## Protokoll (en rad per kommando, `\n` eller `\r\n`)

| Kommando | Betydelse | Svar |
|----------|-----------|------|
| `RUN:<v>:<ms>` | kör med `v` = −100…100 % i högst `ms` (1–60000); nytt RUN förnyar tiden | `OK:<v>` |
| `SET_SPEED:<v>` | kör tills watchdog slår till | `OK:<v>` |
| `STOP` | stopp med broms, kvitterar MAXTIME-lås | `OK` |
| `PING` | håller watchdog vid liv | `PONG` |
| `STATUS` | `STATE:<fas>:<nu%>:<mål%>:<kvar_ms>:<wdt_ms>` | |
| `WDT:<ms>` | watchdog 200–10000 ms | `OK` |

Händelser: `EVT:DONE`, `EVT:WDT`, `EVT:MAXTIME`. Fel: `ERR:SYNTAX/RANGE/UNKNOWN/LEN/LOCKED`.

Appen strömmar `RUN:<v>:700` var 250:e ms under en dos och skickar till sist exakt kvarvarande
tid. Tappar appen kontakten stannar pumpen inom 0,7 s (lease) eller 1 s (watchdog).

## Failsafe i kortet

- Länken bryts → broms direkt.
- Inget giltigt kommando på 1000 ms → broms.
- Sammanhängande körning > 120 s → stopp och lås tills `STOP`.
- Omstart → står still tills nytt kommando.
- Riktningsbyte alltid via noll + broms. Bakåt max 60 %.

## Test på datorn

```bash
g++ -std=c++11 -Wall -Wextra -Werror -I. test/lube_core_test.cpp -o lube_core_test && ./lube_core_test
```
