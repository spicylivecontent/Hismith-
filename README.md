# Hismith Smart Session 2.1

Android-app (Kotlin, minSdk 26) som styr Hismith-maskiner och Thrusting Pocket över Bluetooth LE
och den egenbyggda **MegaLube**-launchern (ESP32 + DRV8871) över klassisk Bluetooth.
Oförutsägbar sessionsmotor, lube i **milliliter** med kalibrerat flöde, 30-dagarsutmaningar,
fjärrstyrning via **engångslänk**, recept och inbyggd webbläsare med adblock.

```
Automatik ─┐
Telefonen ─┼─▶ CommandArbiter ─▶ Säkerhet ─▶ Hismith-maskiner (BLE)
Länken    ─┤                              └▶ MegaLube (Bluetooth SPP)
Utmaning  ─┘
```

Ingen del av appen pratar direkt med motorerna förbi den centrala grinden. Samma gränser,
STOPP-regler, kommunikationsförlust och lägesregler gäller oavsett om kommandot kommer från
automatiken, telefonen, en utmaning eller någon med engångslänken.

## Ladda ner

| Vad | Var |
|-----|-----|
| **App (APK)** | Releases → *Hismith Session v2.0.0* → `HismithSession-release.apk` (eller Actions → *Build APK* → Artifacts) |
| **MegaLube-firmware (.bin)** | Repot [spicylivecontent/MegaLube](https://github.com/spicylivecontent/MegaLube) → Releases → *MegaLube firmware (senaste)* → `MegaLube-merged.bin`, flashas på **0x0** |
| **Relay-servern** (för engångslänkar) | [![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/spicylivecontent/Hismith-) – eller se [relay-server/README.md](relay-server/README.md) |

Installera APK:n: öppna filen i telefonen → tillåt *Installera från okänd källa* → Installera.
Uppdateringar installeras rakt över den gamla versionen.

## Flikarna

### ◉ Session
- **Live:** tid, kvar, hastighet, fas och en graf över de senaste två minuterna. Snabbknapparna
  **⚡ Boost**, **〰 Våg**, **🎯 Edge** och **💤 Vila** byter beteende direkt. *Pausa/Fortsätt*,
  *Final nu*, *Avsluta*, samt **EXTRA** och **SUPER SPRUT**.
- **Enheter:** *+ Maskin / Pocket* söker via BLE. *+ Simulator* lägger till en låtsasmaskin så att du
  kan prova allt utan hårdvara. Enheter sparas och återansluts vid start (kan stängas av).
- **Läge:** **Oral**, **Anal** eller **Pocket**. Läget styr mikrodosprofil och maxhastighet;
  **Pocket nekar all lube**, även från länken.
- **Automatisk session:** min/max-tid, intensitet, mikrodoser, final med sprut, smart slaglängd,
  nonstop.
- **Stil** (går att byta mitt i passet): *Balanserad*, *Sensuell* (långa vågor, mjukt), *Intensiv*
  (mer burst), *Edging* (upp mot kanten och stopp, om och om igen), *Tease* och *Rytmisk*.
- **Överraskningar** (*av / ibland / ofta*): en plötslig burst tillsammans med ett EXTRA SPRUT.
- **Mikrodos när en burst drar igång** – lubet följer rörelsen i stället för bara en timer.
- **Statistik:** antal pass, total tid och lube, längsta pass, dagar i rad (🔥), favoritstil och de
  senaste passen. Sparas bara på telefonen.
- **Diskret läge:** appen syns inte i *senaste appar* och skärmdumpar blockeras.
- **Manuell styrning:** hastighetsreglage (glider mjukt, max 60 %/s) och slaglängd.
- **Maxhastighet per läge:** automatiken skalas in under taket, manuell styrning och länken kan
  aldrig gå över.

### 💧 Lube
- **Anslut MegaLube:** välj det parkopplade kortet (namn *MegaLube*, PIN *2580*) eller sök nya.
  *Annan…* ger simulator eller originalets Hismith-launcher (BLE).
- **Dosering:** manuell dos 1–30 ml, EXTRA och SUPER, pågående dos med levererad mängd, kö,
  total för sessionen och hur mycket som är kvar i dunken.
- **Volymer och flöde:** EXTRA (start 15 ml), SUPER (start 50 ml), final min–max (15–50 ml),
  mikrodos Oral/Anal (ml + intervall), önskat flöde per typ, max pumphastighet och max per session.
- **Sprutet:** finalen (och valfritt SUPER) kan komma som **pulserande stötar** – 2–12 stötar som
  blir mindre och mindre med lite längre pauser emellan, som ett riktigt sprut. Samma totala volym.
- **Kalibrering:** kör pumpen X % i Y sekunder, mät i måttglas, spara. Flera punkter (t.ex. 20, 50,
  100 %) ger en flödeskurva. Utan kalibrering är doserna uppskattningar (5 ml/s vid 100 %).
- **Underhåll:** fyll slangen, töm slangen bakåt (max 60 %).

### ▦ Utmaning
Skapa en utmaning på t.ex. 30 dagar: startnivå (minuter, intensitet, final-ml), slutmål,
progression (*linjär*, *mjuk start*, *mjuk avslutning* eller *manuellt schema* där varje dag kan
ändras), sista dagen som nonstop-mål, vad som händer med missade dagar (*markera missad*, *flytta
resten av schemat*, *lämna kalendern oförändrad*), paus och valfri daglig påminnelse. Varje dag blir
ett vanligt automatiskt pass – utmaningen pratar aldrig själv med Bluetooth, och STOPP fungerar alltid.

### ⇄ Länk
Engångslänk så att någon annan kan styra från sin webbläsare. Kräver en egen liten relay-server –
gratis på Render och uppsatt på ett par minuter, helt från mobilen. Fliken guidar dig:

1. **Skapa och kopiera en hemlighet** (sparas krypterad i telefonen).
2. **Öppna Render** → logga in med GitHub → klistra in hemligheten i `HOST_SECRET` → *Apply*.
3. Kopiera serverns adress (`https://…onrender.com`) och klistra in den i appen.
4. **Spara och testa** – appen väcker servern och kontrollerar att allt stämmer.

Sedan:

1. *Skapa länk* → *Dela länken*. Länken fungerar **en gång** och går ut efter 15 minuter.
3. När mottagaren öppnar den frågar telefonen om du godkänner (kan stängas av).
4. Mottagaren kan styra manuellt eller starta automatik (med stil), trycka ⚡ Boost / 〰 Våg /
   🎯 Edge / 💤 Vila, pausa, trycka STOPP och – om du tillåter det – EXTRA/SUPER. Allt inom dina gränser.
5. Tystnar mottagaren stoppas allt den styr efter 4 s och länken återkallas efter 8 s (ställbart).
   Inget startar om av sig självt.

### ☰ Recept
Mega-Lube-receptet med kalkylator: välj satsstorlek, grundrecept eller XLube-bas, vattenfritt
citrat, konservering och justeringarna (längre trådar, tunnare, vitare, mindre salt). Gram per
ingrediens och beräknad osmolalitet räknas om direkt. Gör så här, säkerhet och hållbarhet finns
under. **Inte för vaginal användning.**

### Webbläsare
Knappen *Webbläsare* i sidhuvudet. Snabblänkar, adblock med navigeringsskydd, helskärmsvideo och
flyttbara overlay-knappar (**STOPP**, **SUPER SPRUT**, **Meny**) som syns så länge något kan röra sig.

- **Minipanel** (flyttbar i ⋮⋮): hastighet, −/+ (intensitet eller manuell hastighet), ⚡ 🎯 💤,
  💧 EXTRA och ⏸ – styr passet utan att lämna videon.
- **☆ Favoriter:** spara sidan som snabblänk. Långtryck på en snabblänk för att ta bort/dölja den.
- **⋯-menyn:**
  - **Videosynk** – pausar du videon pausas passet, spelar du igen fortsätter det.
  - **Final när videon tar slut.**
  - **Privat läge** – historik, cookies och webbdata rensas när appen stängs.
  - Rensa webbdata nu, visa dolda snabblänkar.

## Sessionsmotorn

Variation på tre nivåer:

| Nivå | Vad som varieras |
|------|------------------|
| **Makro** | Nästa beteende (rytm, vågor, burst, hold, tease, kanten). Nyss använda beteenden straffas, sådana som inte förekommit på länge får bonus, och beteendesekvenser som varit vanliga i tidigare sessioner (sparat minne) får lägre vikt. |
| **Meso** | Varje beteende slumpas på nytt: mål, längd, ramp, easing, overshoot, avbrott, dippar. Burst är inte alltid 100 %, hold inte alltid helt stilla. |
| **Mikro** | Långsamt korrelerat brus (Ornstein–Uhlenbeck) runt grundhastigheten. |

Mikrodoser schemaläggs explicit (`nextMicroDoseAt`) efter varje dos. **Finalen räknas baklänges:**
uppbyggnad + pumptiden för vald volym vid kalibrerat flöde + nedvarvning + marginal, så att
sprutet hinner klart innan tiden är slut. Med en fast seed blir en session exakt reproducerbar
(används i testerna).

## Lube-modellen

```
önskad volym (ml) + önskat flöde (ml/s) → kalibrerad hastighet → körtid → faktisk dos
```

Appen strömmar `RUN:<hastighet>:<lease>` till MegaLube var 250:e ms och skickar till sist exakt
kvarvarande tid, så kortet avslutar dosen själv även om appen hänger sig. Långa doser delas upp under
kortets 120 s-spärr. Doskö med prioritet:

**STOPP → kommunikationsförlust → final/SUPER → EXTRA → vanlig dos → mikrodos**

En mikrodos avbryter eller staplas aldrig ovanpå en större dos. Totalvolymen per session har ett tak.

## Säkerhet

- STOPP är idempotent, har högsta prioritet och finns i sidhuvudet, i webbläsaren, i notisen och på
  mottagarens sida. Efter STOPP står allt still tills någon uttryckligen startar igen.
- Tappar en maskin kontakten pausas passet. En maskin som återansluter står still tills du trycker
  *Pausa → Fortsätt* – inget startar om bara för att Bluetooth kom tillbaka.
- MegaLube-firmwaren stoppar själv vid länkbortfall, utebliven trafik (1 s), utgången lease och efter
  120 s sammanhängande körning.
- Pocket-läge nekar all lube. Länken kan aldrig gå över telefonens gränser.
- Relay-servern sparar bara hashar av engångstokens och vet ingenting om maskiner eller lube.

## MegaLube

Firmware, värdtest, bygget av `.bin` och Mega-Lube-dokumentet (inköp, kopplingsschema, recept)
ligger i ett eget repo: [spicylivecontent/MegaLube](https://github.com/spicylivecontent/MegaLube).
Firmwaren är oförändrad från MegaLube-paketet.

## Bygga själv

```bash
./gradlew testDebugUnitTest            # 71 enhetstester för motor, doser, säkerhet, länk, utmaning, recept, statistik
./gradlew assembleDebug assembleRelease
cd relay-server && npm ci && npm test  # 13 integrationstester för relay-servern
```

Kräver JDK 17 och Android SDK 35. Appen har bara ett externt beroende (OkHttp för länken).

## Signering

Byggen signeras med `keystore/hismith.p12` så att uppdateringar installeras över tidigare versioner.
**Den nyckeln och lösenordet ligger publikt i repot och ska ses som komprometterad.** För en egen
nyckel: lägg in GitHub Secrets `HISMITH_KEYSTORE_BASE64` (base64 av en .p12),
`HISMITH_KEYSTORE_PASSWORD`, `HISMITH_KEY_ALIAS` och ev. `HISMITH_KEY_PASSWORD`. Byte av nyckel kräver
att appen avinstalleras en gång.

## Projektstruktur

```
app/src/main/java/com/hismith/session/
├── core/                  Ren Kotlin, enhetstestad
│   ├── AutoEngine.kt      Sessionsmotorn + anti-repeat-minne
│   ├── CommandArbiter.kt  Enda vägen till motorer och pump
│   ├── Doses.kt           Doskö, prioriteter, RUN-strömning
│   ├── FlowModel.kt       Kalibrering: ml + flöde → hastighet + tid
│   ├── Challenge.kt       Utmaningar
│   ├── RemoteProtocol.kt  Länkens kommandon + fail-safe
│   └── Recipe.kt          Receptkalkylator
├── hw/                    BLE (Hismith), MegaLube SPP, simulator
├── remote/                WSS-klient mot relay-servern, Keystore
├── ui/                    Flikarna och egna vyer
├── SessionService.kt      Foreground service med styrloop
└── MainActivity.kt        Flikar, webbläsare, overlay-knappar
relay-server/              Miniservern för engångslänken
```

## Protokoll

- Hismith BLE: service `FFE5`, skriv `FFE9`. Hastighet `AA 04 <0–100> <sum>` och `FF 03 …`,
  slaglängd `FF 06/07/08` och `CC 07/08/09`. Original-launchern: `CC 0B 01 0C` var 280 ms.
- MegaLube SPP: se [MegaLube-repots README](https://github.com/spicylivecontent/MegaLube#protokoll).
- Länken: se [relay-server/README.md](relay-server/README.md).

## Öppna frågor (TBD)

Slutligt tillåtet maxflöde, minsta pålitliga mikrodos och total maxvolym fastställs efter fysisk
test och kalibrering – värdena går att ändra i Lube-fliken. Fail-safe-tiderna för länken (4 s / 8 s)
är testvärden och går att ändra i Länk-fliken.
