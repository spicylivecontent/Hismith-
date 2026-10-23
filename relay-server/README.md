# Hismith Relay – miniserver för engångslänken

En liten Node.js-server (ett beroende: `ws`) som kopplar ihop **telefonen** och **mottagarens
webbläsare** över WSS. Den serverar också mottagarens kontrollsida på `/r/`.

```
Mottagarens webbläsare ──WSS──▶ Relay ◀──WSS── Android-telefonen ──BLE/SPP──▶ maskiner + MegaLube
```

Telefonen är alltid auktoriteten. Servern rör aldrig Bluetooth, känner inte till maskiner, läge,
lube-mängder eller historik och kan aldrig gå över telefonens gränser. Den vet bara att en
session finns, vem som är ansluten och hashar av engångstokens.

## Sätt upp den (välj ett sätt)

### A. Render.com – gratis, ett par minuter, helt från mobilen

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/spicylivecontent/Hismith-)

Enklast är att följa guiden i appen (**Länk → Server – sätt upp en gång**):

1. **Skapa och kopiera en hemlighet** i appen.
2. **Öppna Render** (eller knappen ovan) → logga in med GitHub → klistra in hemligheten i
   **HOST_SECRET** → *Apply*. Render hittar `render.yaml` i repots rot.
3. När tjänsten är grön: kopiera adressen (ungefär `https://hismith-relay.onrender.com`).
4. I appen: klistra in adressen → **Spara och testa**. Appen väcker servern och kontrollerar att den
   är en Hismith-relay och att hemligheten krävs.

Gratisplanen somnar efter 15 minuter utan trafik. Första anslutningen väcker den (tar upp till en
minut) – tryck *Skapa länk* igen om det tar för lång tid.

### B. Egen VPS med Docker (automatisk HTTPS via Caddy)

```bash
git clone <repo> && cd <repo>/relay-server/deploy
cp .env.example .env        # fyll i DOMAIN och HOST_SECRET
docker compose up -d
```

Domänen måste peka på serverns IP och portarna 80/443 vara öppna. Caddy hämtar certifikatet själv.

### C. Valfri Node-värd

```bash
cd relay-server && npm ci --omit=dev
HOST_SECRET=... PORT=8080 node src/server.js
```

Lägg den bakom något som ger HTTPS (appen accepterar bara `https://`-adresser).

## Inställningar (miljövariabler)

| Variabel | Standard | Betydelse |
|----------|----------|-----------|
| `PORT` | 8080 | Port att lyssna på |
| `HOST_SECRET` | – | Om satt måste telefonen ange den för att skapa sessioner (rekommenderas) |
| `INVITE_TTL_SEC` | 900 | Hur länge en oanvänd länk gäller |
| `CTL_GRACE_SEC` | 15 | Hur länge mottagaren kan återansluta efter ett avbrott |
| `HOST_GRACE_SEC` | 60 | Hur länge sessionen lever om telefonen tappar nätet |
| `MAX_SESSIONS` | 200 | Tak för samtidiga sessioner |
| `TRUST_PROXY` | – | `1` bakom proxy (läser klientens IP från `X-Forwarded-For` för hastighetsbegränsning) |
| `QUIET` | – | `1` stänger av all loggning |

`GET /api/info` svarar `{"name":"hismith-relay","version","secretRequired","inviteTtlSec"}` – appen
använder den för att testa servern. `GET /healthz` svarar `ok`.

## Säkerhetsmodell

- **Engångslänk:** `https://server/r/#<token>`. Token är 32 slumpade byte och står i URL-fragmentet,
  som webbläsare aldrig skickar till servern – den hamnar alltså inte i serverloggar eller
  `Referer`. Sidan tar bort den ur adressfältet direkt.
- **Bara hashar sparas** (SHA-256). Länken fungerar **en gång**; därefter får mottagaren en
  kortlivad återanslutningstoken som roteras vid varje återanslutning.
- **En mottagare åt gången.** Fler försök får `busy`.
- **Godkännande:** kommandon släpps inte igenom förrän telefonen har godkänt (kan stängas av i appen).
- **Fail-safe i telefonen:** mottagaren skickar en hjärtslag per sekund. Tystnad i 4 s (ställbart)
  → STOPP på allt mottagaren styr; 8 s → mottagaren återkallas. Inget startar om av sig självt.
- **Origin-kontroll** på mottagarens WebSocket, **hastighetsbegränsning** av inlösenförsök per IP,
  storleksgräns på meddelanden, strikt CSP, `no-referrer`, `X-Frame-Options: DENY`.
- Allt ligger i minnet. En omstart av servern avslutar alla sessioner (telefonen stoppar).

## Protokoll

Värd (telefonen) `wss://server/ws/host`:

| Från telefonen | Svar / händelse |
|----------------|-----------------|
| `{"t":"hello","v":1,"secret":"…","resume":"…"?}` | `{"t":"ready","session","hostResume","invite","inviteTtlSec","controller"}` |
| `{"t":"new_invite"}` | `{"t":"invite","invite","inviteTtlSec"}` |
| `{"t":"approve"}` / `{"t":"deny"}` / `{"t":"kick"}` | till mottagaren: `approved` / `denied` / `kicked` |
| `{"t":"state","s":{…}}`, `{"t":"notice","text":"…"}` | vidare till mottagaren |
| `{"t":"end"}` | sessionen raderas, mottagaren får `ended` |
| – | `ctl_joined {resumed}`, `ctl {m}`, `ctl_left`, `error {code}` |

Mottagare `wss://server/ws/ctl`: `{"t":"redeem","token"}` eller `{"t":"resume","token"}`, sedan
`{"t":"hb"}` varje sekund och `{"t":"cmd","c":{"k":…}}`. Kommandon (`k`): `stop`, `manual_start`,
`speed {v}`, `manual_end`, `auto_start {min,max,intensity,style?}`, `auto_end`, `final_now`,
`intensity {v}`, `style {v}`, `action {a: boost|wave|edge|rest}`, `pause`, `resume`,
`lube {kind: extra|super}`. Telefonen validerar alla värden.

## Test

```bash
npm ci && npm test
```
