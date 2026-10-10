# Hismith Smart Session

Native Android-app (Kotlin, minSdk 26) som styr Hismith-sexmaskiner, Thrusting Pocket
och Lube Launcher via Bluetooth LE med en mänsklig, oförutsägbar rörelsealgoritm.
Inbyggd webbläsare med adblock och nödstopp-overlay. Körs som Foreground Service så
sessionen fortsätter när skärmen är av.

## Ladda ner APK

APK:n byggs automatiskt av GitHub Actions vid varje push.

| Var | Hur |
|-----|-----|
| **Releases** (enklast) | Gå till **Releases** → *Hismith Session v1.2.0* → ladda ner `HismithSession-release.apk` direkt i telefonen. |
| **Actions** | Öppna senaste körningen av *Build APK* → **Artifacts** → `HismithSession-apk` (zip med både debug- och release-APK). |

Installera: öppna filen i telefonen → tillåt *Installera från okänd källa* för din filhanterare/webbläsare → Installera.
Alla byggen signeras med samma nyckel (`keystore/hismith.p12`), så nya versioner installeras rakt över den gamla.

### Nytt i v1.2.0
- **Kontrollpanel-knappen borttagen:** Startvyn är kontrollpanelen och *Webbläsare*-knappen är ensam i sitt fält. Knappen *⚙ Kontroll* finns kvar i webbläsarens verktygsfält för att gå tillbaka.
- **Snabblänkar i webbläsaren:** Pornhub, XVideos, XNXX, xHamster, RedTube, YouPorn och SpankBang – en rad under adressfältet. Listan finns i `MainActivity.QUICK_LINKS`.
- **Bättre adblock (`AdBlockWebViewClient`):**
  - Path-regler matchar nu bara riktiga annonssegment (`/ads/`, `/adserver/`, `/popunder…`) i sökvägen – inte query-strängen och inte `/track/`, `/collect/`, `/analytics/` längre, som kunde bryta spellistor och uppspelning.
  - Ogiltiga poster i värdlistan (t.ex. `facebook.com/tr`, `chaturbate.com/in`) och vanliga sajter (`stripchat.com`, `jasmin.com`) är borttagna. Fler kända adult-annonsnät är tillagda (ExoDynamic, ero-advertising, TrafficStars, Adcash m.fl.).
  - Det kosmetiska filtret använde `[class*="ad-"]`, som också gömde `upload-`/`download-`-element. Det är nu smalare och körs redan vid sidstart.
  - **Navigeringsskydd:** En sidladdning till en annan sajt som inte orsakats av ett tryck (popunder, falsk "uppdatera"-sida) avbryts. `intent:`/`market:`-länkar följs aldrig.
  - Popup-fönster (`window.open`) är avstängda och nedladdningar blockeras, så inga APK/"spelare"-filer sparas.
  - Tredjeparts-cookies är avstängda och blandat http-innehåll på https-sidor tillåts inte.
- **Buggfixar:**
  - Ny session kunde starta med hastigheten från föregående session, eftersom rampen inte nollställdes vid stopp.
  - Auto-Lube (0,4 s) avbröt en pågående *SUPER SPRUT* eller slutklimax-sprut. En kortare spray ersätter nu inte längre en längre.
  - Enhetslistan under BLE-sökning skrevs från en annan tråd utan synkronisering.
  - Cleartext-HTTP är avstängt i manifestet (sajterna använder HTTPS).
- Release-noteringarna i CI genereras nu automatiskt i stället för att vara hårdkodade till en version.

### Nytt i v1.1.1
- **Immersive Mode i hela appen:** `setImmersiveMode(true)` körs i `onCreate`, `onResume`, kontrollpanel och webbläsare, med både `FLAG_FULLSCREEN` och `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`. Klocka och batteristatus är därmed alltid dolda – även i kontrollpanelen.
- **Helskärmsvideo som faktiskt fungerar:** Vid `onShowCustomView` döljs inte WebView och skärmorienteringen tvingas inte längre, och overlay-knapparna anropar inte `bringToFront()` vid tryck. Helskärmsknappen på videor i webbläsaren fungerar utan att WebView avbryter helskärmsläget.
- **Stoppknappar endast vid körning:** `STOPP` (nödstopp) och overlay-knapparna `⋮⋮ STOPP` / `⋮⋮ SUPER SPRUT` visas bara när en maskin är ansluten **och** en session är igång (`isSessionRunning == true`); annars döljs de.
- **Robusthet:** BLE-sökning som alltid avslutas (inkl. `onScanFailed`), WebView som tas ur vyn innan `destroy()`, inga dialoger efter att aktiviteten avslutats och en unik tom adblock-respons per blockering.

### Nytt i v1.1.0
- **Luxury Dark/Gold-tema:** Mörkt obsidian- och guldtema med tydliga kortsektioner.
- **Flyttbara overlay-knappar:** `⋮⋮ STOPP`, `⋮⋮ SUPER SPRUT` och `⋮⋮ Meny` kan dras fritt över skärmen och sparas automatiskt i position, även ovanpå helskärmsvideo.
- **Immersive Mode:** Systemfälten döljs i webbläsar- och videoläge, och adressfältet kan fällas in med *Dölj fält ▲* för maximal skärmyta.
- **Landscape Video:** Helskärmsvideo i webbläsaren roterar automatiskt till liggande läge (sensor-landscape) utan att ladda om sidan. *(I v1.1.1 tas den tvingade rotationen bort eftersom den avbröt helskärmsläget – videon styr i stället orientationen själv.)*

## Använda appen

1. Starta appen → godkänn **Bluetooth**, **notiser** och **batteriundantag**.
2. **+ Lägg till enhet** → välj roll (Hals, Anal, Extra, Pocket, Lube Launcher) → välj enheten i listan (~5 s sökning). Upprepa för fler.
   Tryck på enhetslistan för att ta bort en enhet.
3. Ställ in **Minsta/Högsta tid** (0 = obegränsat), **Auto-Lube**, ev. **Smart slaglängd**.
4. **STARTA SESSION**.
5. Växla till **Webbläsare** (snabblänkarna under adressfältet), kör fullscreen. Tillbaka till kontrollpanelen med **⚙ Kontroll**. **STOPP** och **SUPER SPRUT** ligger som flyttbara overlay-knappar (och i notisen) så länge en session körs – de döljs automatiskt när ingen session är igång.
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
