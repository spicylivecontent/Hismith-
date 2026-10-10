# Hismith Smart Session

Native Android-app (Kotlin, minSdk 26) som styr Hismith-sexmaskiner, Thrusting Pocket
och Lube Launcher via Bluetooth LE med en mänsklig, oförutsägbar rörelsealgoritm.
Inbyggd webbläsare med adblock och nödstopp-overlay. Körs som Foreground Service så
sessionen fortsätter när skärmen är av.

## Ladda ner APK

APK:n byggs automatiskt av GitHub Actions vid varje push.

| Var | Hur |
|-----|-----|
| **Releases** (enklast) | Gå till **Releases** → *Hismith Session – senaste bygget* → ladda ner `HismithSession-release.apk` direkt i telefonen. |
| **Actions** | Öppna senaste körningen av *Build APK* → **Artifacts** → `HismithSession-apk` (zip med både debug- och release-APK). |

Installera: öppna filen i telefonen → tillåt *Installera från okänd källa* för din filhanterare/webbläsare → Installera.
Alla byggen signeras med samma nyckel (`keystore/hismith.p12`), så nya versioner installeras rakt över den gamla.

## Använda appen

1. Starta appen → godkänn **Bluetooth**, **notiser** och **batteriundantag**.
2. **+ Lägg till enhet** → välj roll (Hals, Anal, Extra, Pocket, Lube Launcher) → välj enheten i listan (~5 s sökning). Upprepa för fler.
   Tryck på enhetslistan för att ta bort en enhet.
3. Ställ in **Minsta/Högsta tid** (0 = obegränsat), **Auto-Lube**, ev. **Smart slaglängd**.
4. **STARTA SESSION**.
5. Växla till **Webbläsare**, kör fullscreen. **STOPP** och **SUPER SPRUT** ligger alltid som overlay längst ner och i notisen.
6. Sessionen avslutas automatiskt med final (stort sprut → nedvarvning → stopp), eller manuellt med *Avsluta* / *STOPP*.

### Algoritmen

| Fas | Andel | Beteende |
|-----|-------|----------|
| Rytm | ~60 % | 25–55 %, varierande |
| Max Burst | ~20 % | Ramp till 100 %, håll 10–25 s |
| Deep Hold | ~15 % | 0 %, stilla 10–20 s |
| Tease | ~5 % | Grunda/snabba ↔ djupa/långsamma |
| Final | sista 20 s | 4–7 s sprut, ner till 15 %, stopp vid T=0 |

Alla hastighetsändringar rampas med smoothstep-easing. Med **Smart slaglängd** varieras även
start/stopp på slaget per fas (kortare vid burst, djupt vid hold/final). Auto-Lube ger korta
pumpstötar var 20–40 s.

## Bygga själv

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

Kräver JDK 17 och Android SDK (API 35). Eller: Android Studio → *Open* → den här mappen → *Build APK*.

## Projektstruktur

```
app/src/main/java/com/hismith/session/
├── MainActivity.kt          UI, BLE-sökning, webbläsare, behörigheter
├── SessionService.kt        Foreground service, BLE GATT + skrivkö, notis
├── SessionEngine.kt         Tillståndsmaskin (Rytm/Burst/Hold/Tease/Final)
├── HismithProtocol.kt       AA/FF/CC-kommandon, UUID:er
├── DeviceRole.kt            Roller för anslutna enheter
└── AdBlockWebViewClient.kt  Adblock för WebView
.github/workflows/build-apk.yml   CI som bygger och publicerar APK
keystore/hismith.p12              Signeringsnyckel (privat app, sideload)
```

## Teknik / protokoll

- BLE-service `FFE5`, skriv-karakteristik `FFE9` (write without response).
- Hastighet: `AA 04 <0–100> <sum>` och `FF 03 <0–100> <sum>`.
- Slaglängd (om maskinen stödjer det): `FF 07/06` start/stopp, `FF 08` mjukhet, samt Servok-stil `CC 07/08/09`.
- Launcher: `CC 0B 01 0C` upprepat var 280 ms under spruttiden.
- Alla skrivningar går via en kö per enhet eftersom Android bara tillåter en utestående GATT-skrivning åt gången.
- Automatisk återanslutning var 4:e sekund om en enhet tappas.
