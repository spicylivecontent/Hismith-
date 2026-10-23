# Hotmodell – Hismith Smart Session

Kravspecifikationen kräver en hotmodell för fjärrstyrningen och hårdvaran. Det här dokumentet
beskriver vad som skyddas, mot vem och hur – och vad som medvetet lämnas utanför.

## Vad som skyddas

| Tillgång | Varför det spelar roll |
|----------|------------------------|
| **Kroppslig säkerhet** | Motorer och pump får aldrig gå okontrollerat, över gränserna eller starta om av sig själva. |
| **Kontrollen över maskinerna** | Bara telefonen och den som fått en godkänd engångslänk får styra – och bara inom telefonens gränser. |
| **Integritet** | Läge, lube-mängder, enheter, historik och statistik är privata och lämnar inte telefonen. |
| **Värdhemligheten** (`HOST_SECRET`) | Den som har den kan skapa sessioner på din relay-server. |
| **Appens signeringsnyckel** | Avgör vem som kan släppa uppdateringar som installeras över din app. |

## Aktörer

- **Ägaren** – använder telefonen. Har alltid sista ordet.
- **Mottagaren** – har fått engångslänken och styr via webbläsaren.
- **Någon som får tag i länken** – vidarebefordrad, skärmdump, delad chatt, axelsurfning.
- **Relay-servern / dess värd** – vidarebefordrar meddelanden. Ska ses som *ärlig men nyfiken* och
  i värsta fall komprometterad.
- **Nätverket** – öppet wifi, mobiloperatör, proxy.
- **Någon i Bluetooth-räckvidd** – inom ett tiotal meter.
- **Webbsidor** i den inbyggda webbläsaren.

## Hot och skydd

### Engångslänken

| Hot | Skydd |
|-----|-------|
| Länken vidarebefordras eller läcker | Länken fungerar **en gång**. Därefter används en kortlivad återanslutningstoken som **roteras** vid varje återanslutning. Bara **en** mottagare åt gången – fler får `busy`. Oanvänd länk dör efter 15 min (`INVITE_TTL_SEC`). |
| Fel person löser in länken först | **Godkännande på telefonen** krävs som standard innan något kommando släpps igenom. Ägaren kan neka, koppla bort och avsluta när som helst. |
| Token hamnar i loggar eller `Referer` | Token står i **URL-fragmentet** (`#…`), som webbläsare aldrig skickar till servern. Sidan tar bort det ur adressfältet direkt. `Referrer-Policy: no-referrer`. |
| Servern läcker sin databas | Servern sparar bara **SHA-256-hashar** av tokens, allt i minnet. Inget skrivs till disk. |
| Gissning / brute force | 32 slumpade byte per token. **Hastighetsbegränsning** av inlösenförsök per IP. |
| Replay av gamla meddelanden | Inlösta tokens raderas direkt. Återanslutningstoken byts vid varje användning. Kommandon gäller bara inom en levande WebSocket. |
| Mottagaren försöker gå över gränserna | **Telefonen validerar allt.** Kommandon går genom `CommandArbiter`: maxhastighet per läge och länkens eget tak, mjuk ramp, Pocket nekar lube, EXTRA/SUPER via länken är **avstängt som standard**. Servern kan inte skicka något som telefonen inte redan tillåter. |
| Mottagaren tappar nätet eller stänger sidan | **Fail-safe i telefonen** (`RemoteWatchdog`): hjärtslag varje sekund. 4 s tystnad → STOPP på allt mottagaren styr. 8 s → mottagaren återkallas. **Inget startar om av sig självt.** |
| Telefonen tappar nätet | Allt lokalt fortsätter som ägaren startat det – men allt som **mottagaren** styrde stoppas. Servern håller sessionen i 60 s (`HOST_GRACE_SEC`) och raderar den sedan. |

### Relay-servern

| Hot | Skydd |
|-----|-------|
| Servern (eller dess värd) är nyfiken | **Dataminimering:** servern ser bara det mottagaren behöver för att styra: hastighet, tid, fas, stil, om lube finns tillgängligt och om ett sprut pågår. **Aldrig** läge (Oral/Anal/Pocket), ml-mängder, enhetsnamn, historik, statistik eller utmaningar. Avvisade lube-kommandon får ett generiskt svar så att orsaken inte avslöjar läget. |
| Servern är komprometterad | Den kan bara skicka kommandon som en mottagare hade kunnat skicka, och de går genom samma validering och samma gränser i telefonen. Den kan inte röra Bluetooth och inte höja några tak. I värsta fall kan den stoppa sessionen, och då **stannar** allt. |
| Någon annan skapar sessioner på din server | `HOST_SECRET` krävs för att skapa sessioner. I appen krypteras den med en AES-nyckel i **Android Keystore**. |
| Avlyssning / MITM | Appen accepterar bara `https://`/`wss://`. TLS sköts av Render eller Caddy (Let's Encrypt). |
| Elak webbsida bäddar in eller styr kontrollsidan | Origin-kontroll på mottagarens WebSocket, strikt **CSP**, `X-Frame-Options: DENY`, storleksgräns på meddelanden. |
| Överbelastning | `MAX_SESSIONS`, hastighetsbegränsning, små meddelanden. En överbelastad server betyder i värsta fall att länken slutar fungera, och då stoppar telefonen. |

### Bluetooth och hårdvara

| Hot | Skydd |
|-----|-------|
| Okänd enhet utger sig för att vara en maskin eller MegaLube | Ägaren väljer själv varje enhet, och appen sparar den på adress. MegaLube kräver **parkoppling med PIN** (`BT_PIN` i firmware – byt standardkoden). |
| Någon annan ansluter till MegaLube | Klassisk Bluetooth SPP med PIN och bara en anslutning åt gången. |
| Länken till MegaLube bryts mitt i ett sprut | Pumpen körs bara med **lease**: varje `RUN` gäller i max 700 ms och förnyas var 250:e ms. Tystnar telefonen stannar pumpen av sig själv (**watchdog i firmware**) och bromsar. Appen märker en död länk efter 8 s och slutar räkna med den. |
| Appen kraschar eller dödas | Inga fler leases → MegaLube stannar inom en sekund. Hismith-maskinerna får en STOPP när tjänsten stängs. När en maskin återansluter hålls den (`held`) tills ägaren själv startar igen – **ingen automatisk omstart**. |
| Pumpen trycker för länge eller för mycket | I appen: max pumphastighet, tak för varje dostyp, max per session och segment på högst 90 s. I firmware: ett `RUN` gäller högst 60 s, och efter 120 s sammanhängande gång stoppar och låser kortet. |
| STOPP skickas flera gånger eller i fel läge | STOPP är **idempotent** och går alltid före allt annat. |

### Telefonen

| Hot | Skydd |
|-----|-------|
| Någon tittar på skärmen eller i appväxlaren | **Diskret läge:** appen döljs i *senaste appar* och skärmdumpar och skärminspelning blockeras (`FLAG_SECURE`). |
| Historik och statistik läcker | Sparas bara lokalt i appens privata lagring. Historiken är begränsad (max 200 pass) och kan raderas i appen. |
| Webbsidor spårar eller läcker | Adblock och **privat läge**, som rensar kakor, historik och cache när appen stängs. Webbläsaren har ingen åtkomst till filer eller till appens data. |
| Webbsidor försöker styra maskinerna | En webbsida kan aldrig starta något. Med **videosynk** på kan videons uppspelning bara pausa ett pass som telefonen själv kör, och bara återuppta en paus som videon själv orsakade. Med *final när videon tar slut* på kan den även tidigarelägga finalen. Allt sker inom samma gränser. |

### Uppdateringar och signering

| Hot | Skydd |
|-----|-------|
| Någon släpper en falsk uppdatering | Android installerar bara uppdateringar som är signerade med **samma nyckel**. |
| **Kvarstående risk:** standardnyckeln i `keystore/` är offentlig | Bygget kan använda en egen nyckel via GitHub-hemligheterna `HISMITH_KEYSTORE_BASE64`, `HISMITH_KEYSTORE_PASSWORD`, `HISMITH_KEY_ALIAS` och `HISMITH_KEY_PASSWORD` (se README, *Signering*). Byter man nyckel måste appen avinstalleras en gång. Tills dess kan vem som helst bygga en APK som installeras över appen – installera bara APK:er från repots egna Releases. |

## Medvetet utanför

- **En komprometterad telefon** (root, skadlig app med tillgänglighetsbehörighet). Telefonen är
  auktoriteten, och den som äger den äger allt.
- **Fysisk åtkomst** till MegaLube-kortet. Den som kan flasha om kortet kan ändra allt i firmware.
- **Störsändning av Bluetooth.** Den kan hindra kommandon men aldrig starta något: tystnad leder
  alltid till stopp.
- **Mottagarens egen enhet.** Är den komprometterad kan angriparen göra det mottagaren kan, inom
  samma gränser och med samma fail-safe.

## Testat

- **Enhetstester:**
  - `CommandArbiterTest`: STOPP utan omstart, Pocket nekar lube och länkens tak och tillstånd.
  - `RemoteProtocolTest`: validering av kommandon och watchdog 4/8 s.
  - `DoseControllerTest`: prioriteter, tak och kö.
- **Firmware:** `lube_core_test` testar parsern, RUN-streaming, watchdog, länkbortfall, tidslåset och riktningsbyte via noll.
- **Relay-servern** (`relay-server/test`): engångsinlösen, en mottagare, rotation av
  återanslutningstoken, godkännande, kick, origin-kontroll och hastighetsbegränsning.
- **Ej testat än:** riktig hårdvara och riktiga telefoner.
