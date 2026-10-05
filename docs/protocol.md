# Fire TV Remote Protocol

Amazon's own local control API, as used by the official Fire TV app
(`com.amazon.storm.lightning.client.aosp`). No ADB, no developer options, no
sideloading, and nothing to enable on the TV.

**Verified against real hardware** — an Amazon Fire TV ("the test TV") announcing
itself as `AFTT`-class hardware on the LAN, and the official app v4.7.0 on
Android 16, captured via `adb logcat` tag `FeniksNetworkModule`.

Everything in "Verified" below was observed directly: either in the app's own
network logs, or in live request/response against the device.

---

## Summary

| Piece | Value |
|---|---|
| Command API | `https://<ip>:8080` — HTTPS, **self-signed certificate** |
| Wake | `http://<ip>:8009/apps/FireTVRemote` — plain HTTP, works while asleep |
| Voice (Alexa) | `wss://<ip>:9090` — raw PCM, not covered here |
| API key | `0987654321` — **constant, identical on every device, not a secret** |
| Real auth | `X-Client-Token` header, obtained once via PIN pairing |
| Content type | `application/json` (required) |
| Response format | `{"description":"..."}` |

The API only answers **while the TV is awake**. A sleeping device still accepts
the TCP connection and completes the TLS handshake, then never replies — so a
hang is the signature of a sleeping TV, not a network fault. Wake it on 8009
first.

---

## 1. Pairing (once per TV)

The client token is durable. Pair once, store the token, reuse it forever.

### Step 1 — ask the TV to display a PIN

```
POST https://<ip>:8080/v1/FireTV/pin/display
X-Api-Key: 0987654321
Content-Type: application/json

{"friendlyName": "<name shown on the TV>"}
```

The TV displays a 4-digit PIN. **The PIN expires after 5 minutes.**

### Step 2 — send the PIN back

```
POST https://<ip>:8080/v1/FireTV/pin/verify
X-Api-Key: 0987654321
Content-Type: application/json

{"pin": "NNNN"}
```

### Reading the response — the important gotcha

**The token is returned inside the `description` field.** There is no field
named `token`.

```json
{"description":"zBMBFhY"}
```

**If `description` is the literal string `"OK"`, that means "not yet"** — the TV
accepted the PIN but has not minted the token. Retry, up to 3 times, 1 second
apart. Treating `"OK"` as failure will report a working pairing as broken.

After pairing, send `X-Client-Token: <token>` on every subsequent request,
alongside the API key.

---

## 2. Commands

```
POST https://<ip>:8080/v1/FireTV?action=<action>
X-Api-Key: 0987654321
X-Client-Token: <token>
Content-Type: application/json
```

Success is `200` with `{"description":"OK"}`, typically in ~0.1s.

### Actions

| Action | Body |
|---|---|
| `home` | *(none)* |
| `back` | *(none)* |
| `menu` | *(none)* |
| `select` | keyDown, then keyUp |
| `dpad_up` / `dpad_down` / `dpad_left` / `dpad_right` | keyDown, then keyUp |
| `volume_up` / `mute` / `power` | *(none)* — observed on the wire, capability varies per device; see below |
| `sleep` | *(none)* — display-off only; the device's control API keeps answering on port 8080 afterward. Distinct from the organic idle deep-sleep in § 3. |

Only the four D-pad directions and `select` are keyed. They are the actions that
need a press *duration*; the rest are discrete presses with no body.

**Re-verified 2026-09-17** by capturing the official app's own requests against a
Fire TV Stick at `192.0.2.22` — `com.amazon.storm.lightning.client.aosp`
v4.7.0 on Android, read from `adb logcat` under the tag `FeniksNetworkModule`:

```
POST /v1/FireTV?action=back      body = null
POST /v1/FireTV?action=home      body = null
POST /v1/FireTV?action=menu      body = null
POST /v1/FireTV?action=select    body = {keyActionType=keyDown}
POST /v1/FireTV?action=select    body = {keyActionType=keyUp}
POST /v1/media?action=play       body = null
```

`menu` appeared in this table as keyDown/keyUp before that capture. The vendor
app sends it body-less, like `home` and `back`. Whether the device also
tolerates a keyDown/keyUp pair on `menu` was **not** tested — the body-less form
is the one with observed use behind it.

### Press-and-hold

A single body-less POST is one discrete press. A directional press is **two**
requests:

```
POST /v1/FireTV?action=dpad_right   {"keyActionType":"keyDown"}
POST /v1/FireTV?action=dpad_right   {"keyActionType":"keyUp"}
```

**The gap between the two calls is load-bearing.** `keyDown` starts a key-repeat
loop on the device; `keyUp` stops it. The gap *is* the press duration.

| Gap | Observed behaviour |
|---|---|
| **~220 ms** (what the official app uses) | moves exactly one tile |
| ~1000 ms | scrolls continuously, overshoots, selection ends up somewhere unexpected |

Measured directly from the app's logs:

```
20:28:11.581  dpad_right  keyDown
20:28:11.801  dpad_right  keyUp      → 220 ms
```

Note the response time of each call does **not** determine the gap — the
requests return in ~90–130 ms, so a naive `await request; await request` gives a
gap far shorter than 220 ms. The implementation must sleep deliberately between
`keyDown` and `keyUp`.

**Hold-to-scroll is not built this way.** Deferring `keyUp` until release would
work on the wire, but the device repeats a held key with **no timeout of its
own**: a lone `keyDown` for `dpad_right` scrolled the test TV for 54 s until a `keyUp`
arrived (2026-09-27). An app that dies, is killed, or loses its network mid-hold
would leave the TV scrolling indefinitely.

Instead, a hold is **repeated body-less presses**. A body-less directional POST
is one complete press. The device synthesizes the down/up pair itself, as it does
for Home and Back, so nothing is left held between requests:

```
POST /v1/FireTV?action=dpad_right   (no body)   → one tile, nothing held
```

Observed on the test TV (Fire OS 6.7.1.1, 2026-09-27) and on `.22` (2026-09-28, two
presses, each moved exactly one tile). This is two devices, one session each.

Measured 2026-09-28 on `.22`, one run: a 3.0 s `keyDown` hold moved the
selection at least 39 tiles round a 14-tile wrapping row. So the device's own
held-key scroll runs at 13 or more tiles a second, including its delay before a
held key starts repeating.

The app does **not** match that rate. It repeats every **220 ms** (about 4.5
tiles a second), the fastest pace this document records from the vendor app:
eleven body-less `volume_up` presses in 2.4 s (§ 2, Volume). Sending faster
than the vendor app has ever been seen to was judged not worth the risk of a
device that locks itself on bursts. The
trade-off is that a hold in this app steps visibly slower than the Amazon
remote's glide.

A repeat that falls due while the previous request is still in flight is
skipped, not queued, so release stops the TV within one interval plus one
round-trip. OK does not repeat, because a held OK is a long press on the
device. Rewind and Forward do not repeat either: § Media, `scan`.

Evidence: a live capture, 2026-09-28. Implementation:
`ui/HoldRepeat.kt`.

### How a press arrives at the TV

Everything above is what the client *sends*. The receiving end splits the
controls in two, measured 2026-09-20 on `.25` from the device's own
`KeyPressReceiver` log:

| Control | `keyed` | On the wire | `eventTime` gap on the device |
|---|---|---|---|
| D-pad, Select | `true` | explicit `keyDown` … `keyUp` | **262–321 ms** — eight samples: 279, 262, 307, 312, 293, 321, 267, 303 (mean ≈ 292) |
| Back, Home, Sleep | `false` | one body-less POST | **2–4 ms** — the device synthesizes the pair |

Every press arrives as `deviceId=6`, `amzkeyboard`. The physical remotes are
device 1 (`MStar Smart TV Keypad`) and device 2 (`MStar Smart TV IR Receiver`),
which is a usable filter: a log line whose `deviceId` is not 6 is not this app's
traffic.

Two things follow that the send side cannot show. A `keyed = false` control has
no device-measured duration at all — the pair is synthesized from one request —
so hold-to-repeat cannot lengthen it, whatever the client does. And the
device-measured gap for a keyed control is wider than the 220 ms sent, so a
device log is not a place to read the client's own constant back from.

**Read `eventTime`, not the log line's own timestamp**, for anything measured on
a device waking from sleep — one press logged at 45 ms measured 303 ms by
`eventTime`. Same class of trap as the 220 ms row above, at the other end.

### Media

```
POST https://<ip>:8080/v1/media?action=play
```

**Confirmed in use 2026-09-17.** The official app sent exactly this — `POST`
with an empty body — against the Fire TV Stick at `192.0.2.22`, captured from
`adb logcat` (`FeniksNetworkModule`, app v4.7.0). That is vendor-app evidence on
real hardware, which settles the earlier doubt about whether this endpoint does
anything: it is not a guess from a third-party write-up.

**It toggles, and it is one action, not two.** Confirmed the same day by
pressing play/pause repeatedly during playback while capturing: seven presses
produced seven byte-identical requests — same URL, same empty body — and the TV
paused and resumed. The device infers play-versus-pause from its own state; the
caller never chooses. There is no `pause` counterpart to send.

**One case remains untested:** what it does when nothing is playing. The capture
covers toggling during playback only.

`POST /v1/media?action=scan` was also observed twice in the 2026-09-17 capture,
20 s apart, and it is **not** body-less:

```
POST /v1/media?action=scan
{"direction":"back","durationInSeconds":"10","speed":"1"}
```

`scan` is the media-player term for shuttle, and the body is a shuttle command:
which way, for how long, at what rate. That makes this the **rewind and
fast-forward** control — a transport action this document did not previously
have at all. Both captured presses were `direction: "back"`, so the forward
form is inferred from the field rather than observed; and `durationInSeconds`
being supplied per request suggests the app passes its own step size rather than
the device choosing one. **The pair ships together** — see § 2, Control coverage
(Rewind + Fast-forward) — because shipping only the backward half made an
overshoot uncorrectable, which is the UX trap the Step 3 hand-test surfaced.

**That last reading is not confirmed, and one live press counts against it.**
Pressed from this app against `.22` on 2026-09-18 with media playing, the body
above moved the media backward by roughly ten minutes rather than ten seconds
(recorded in § 2, Control coverage; the open question is in § 6). So "for how
long" may not be what the field controls, or its unit may not be seconds. Until
that is settled, treat the body as *the shape the vendor app sends* — which is
what makes the request legitimate — and not as a step size anyone has verified.

**Settled for `.22` on 2026-09-28: `scan` is a shuttle *mode*, not a step.**
The app sent the body above three times and then `play`, while the user watched:

- The first `scan` started a continuous rewind at "1x".
- Each further `scan` raised the speed: "2x", then "3x".
- The rewind ran until `play` stopped it. About 16 s of rewinding took the video
  from 1:20:00 to about 1:05.

The 2026-09-18 "about ten minutes per tap" reading was a shuttle left running,
not a jump.

The forward body (`"direction":"forward"`) was sent the same day: it started a
fast-forward at "1x", and `play` stopped it. The forward form is therefore
observed, no longer only inferred.

Two consequences:
- A tap already behaves like the vendor remote's: pressing again is what speeds
  the shuttle up. So Rewind and Forward do **not** repeat while held. Repeating
  would race to top speed within a second, and would leave the TV shuttling if
  the app died mid-hold.
- Nothing yet shows whether `durationInSeconds` or `speed` change anything, or
  whether the test TV's "about ten seconds" (2026-09-18) was a jump or a shuttle
  stopped early.

This is one run, on one device.

An earlier revision of this paragraph said these were body-less. That came from
reading `body = null` and not the `bodyString` beside it — the same mistake this
document warns about for `/properties` below.

### Volume, mute and power — sent, but capability-gated

```
POST /v1/FireTV?action=volume_up   body = null
POST /v1/FireTV?action=mute        body = null
POST /v1/FireTV?action=power       body = null
```

**Observed in use 2026-09-17**, in the same capture as § 2 Media: the official
app sent these body-less, in the `?action=` family, shaped exactly like `home`
and `back`. Four `volume_up` and four `mute` presses went out over about nine
seconds, then one `power`.

That settles half of § 6's volume question at the wire: volume **is** a key
event on the control endpoint, not a separate CEC/IR path the app reaches around
this API.

**Capability varies per device.** On the Fire TV Stick captured here
(`192.0.2.22`) `volume_up` and `mute` produced no effect the user could see
or hear. **Re-tested 2026-09-17 evening against a different Fire TV Stick
(`192.0.2.25`, mDNS friendly name `the test TV`)**: tapping `volume_up` audibly
raised the TV's volume to 100, and `mute` muted it. So the earlier "no effect"
observation was device-specific, not universal — the wire shape is right; some
Fire TV Sticks act on these actions and some do not. See below for the app's
own capability signal.

`volume_up` observed in two shapes in the second capture, on the same
`/v1/FireTV?action=volume_up` URL:

- **Discrete tap** — body-less POST. Fired eleven times over 2.4 s as the user
  rapid-tapped the volume rocker.
- **Press-and-hold** — the keyed pair `body = {keyActionType=keyDown}` followed
  ~5.2 s later by `body = {keyActionType=keyUp}`. The 5.2 s gap was the
  physical hold duration.

Same dual shape as the D-pad and `select`, applied to a non-directional action.

**The app's capability signal, and what it predicts — settled 2026-09-18.** That
`NotSupported` triple is not the only place the verdict appears: this device
reports it natively on `GET /v1/FireTV`, read with a valid token against the same
stick (`.22`), as `"isVolumeControlsSupported":false` together with
`"volumeCapabilityLevelOfSupport":"NotSupported"` (full object in § 2, Device info
and capabilities). The device's own advertisement and its observed behaviour
therefore **agree, on the same device in the same session** — the pairing earlier
revisions of this section lacked, which is why they could only say the triple
"reads like" a capability report, with no second source to check it against. The
`.25` result above is the other half of the same picture: a stick that acts on
these actions is a stick whose read would say so.

Whether the app's `bodyString` on `/properties` is a request payload or a
response echo is still unresolved, and no longer matters here — the gate described
below reads `GET /v1/FireTV`.

`volume_down` did **not** appear on `.22`, and only `volume_up` was logged
there. That was ambiguous rather than negative: nothing in that capture
distinguished "not pressed" from "suppressed by the app". **It is settled now** —
against `the test TV`, which reports volume support, the rocker's lower half sends
`POST /v1/FireTV?action=volume_down`, so the earlier silence was the device
gating the rocker rather than the rocker having no down direction. See § 2,
Control coverage (Volume down).

**Design consequence.** A client should ask the device before drawing these
controls, because the device answers: `GET /v1/FireTV` reports which of them it
supports, and on the hardware measured here the answer is *none* —
`isVolumeControlsSupported:false`, with `volumeCapabilityLevelOfSupport` and
`powerCapabilityLevelOfSupport` both `NotSupported`. The vendor app reads the
same signal: against this stick it greys its volume, mute and power buttons out
at connect (observed 2026-09-20 — an earlier revision of this paragraph said it
fires them blind, which the 2026-09-17 capture could not tell apart from a
greyed button never being pressed). Its own volume-setup flow can un-grey them
without a new read, after which the presses go out and the stick ignores them
(captured 2026-09-20).
The gate is the read; a **failed** read is not a verdict, and must not be allowed
to hide a control — see § 2, Control coverage.

### Device info and capabilities

```
GET /v1/FireTV/status      device build info — not one shape, see below
GET /v1/FireTV             capability object — see below
GET /v1/FireTV2            byte-identical to /v1/FireTV on the device measured 2026-09-18
GET /v1/FireTV/properties  405 on GET; POST unreconciled — see below; what it returns is still unread
```

`/v1/FireTV/status` answers while the TV is awake and is the cheapest awake-check
available — it needs no key press, which is why the wake path polls it. All of
these require both auth headers. `X-Api-Key` alone returns `403
{"description":"Request unauthorized, missing api key"}`; a request carrying the
key but no client token returns `403 {"description":"Request unauthorized,
missing client token"}`.

`status` carries **no friendly name** — see § 7.

**The `status` body is not one shape across devices.** Measured 2026-09-18
against `192.0.2.22`, awake, with a valid token, twice, byte-identical:

```json
{"osVersion":"0036005356164","platformType":"android","turnstileVersion":"0.1.147068"}
```

Two things differ from the shape this section recorded on 2026-09-16 — and the
second is the answer to a question the earlier revision could only leave open:

- `osVersion` here is a numeric build id, not a Fire OS marketing string, and the
  object carries a `platformType` field the recorded shape lacks.
- **The 2026-09-16 readings came from a different device.** § 7's same-day probe
  (the one that established no endpoint returns the friendly name) names the
  record it read as `n=the test TV`, and `the test TV` is `192.0.2.25` (§ 2, "Volume,
  mute and power"). So the 2026-09-16 shapes in this subsection are `.25`'s, not
  `.22`'s — which is why neither the status shape nor the capability shape below
  reproduces here. The earlier revision guessed "a different device or a
  truncated transcription"; the log behind it is still not in the repo, but the
  device attribution is now documented rather than a coin flip.

Nothing in this document depends on the `status` shape: the awake-check needs
only that something answers, and a `4xx` counts as an answer.

**The capability object, measured 2026-09-18.** Read with a valid client token
from the Fire TV Stick at `192.0.2.22` (`Living Room Fire TV`), awake, after one
DIAL wake on `:8009`:

```json
{"isEpgSupported":true,"isInternalIntentSupported":true,"isPropertiesApiSupported":true,
 "isVolumeControlsSupported":false,"powerCapabilityLevelOfSupport":"NotSupported",
 "ringableRemoteCount":0,"volumeCapabilityLevelOfSupport":"NotSupported"}
```

`GET /v1/FireTV` and `GET /v1/FireTV2` returned **byte-identical** bodies here,
confirmed on two separate reads (calibration and live verification). An earlier
revision of this section recorded `/v1/FireTV` as
`{"isVolumeControlsSupported":true}` — a one-field object — and `/FireTV2` as a
three-field object with different keys. Neither reproduces on `.22`, and by the
attribution above they are `.25`'s shapes. **The first field is the interesting
one:** `.25` advertises `isVolumeControlsSupported:true`, and `.25` is the device
whose `volume_up` and `mute` were audibly observed working (see § 2, "Volume,
mute and power"). So on the evidence available the flag tracks behaviour in
**both** directions — `false` on the device that ignored the actions, `true` on
the device that acted on them. That is a two-device correlation, inferred from a
read recorded on one device and a behaviour observed on the same one, and it is
stronger than the single-device pairing this document had before. **That read was
taken from `.25` on 2026-09-20**, from a client holding a token paired with that
device: `GET /v1/FireTV` returned `{"isVolumeControlsSupported":true}`, the value
the 2026-09-16 record carried. The positive direction is a measurement now rather
than an inherited record — which confirms the recorded value but does not widen
what the flag proves, since a device that reports yes and then ignores the action
would still not be caught by the read at all.

**`/properties`, updated 2026-09-18.** The vendor app was observed `POST`ing to
it against `192.0.2.22` (see § 2, "Volume, mute and power", and the
2026-09-17 capture), so `POST` is
the app's method — which contradicts a `405` recorded on 2026-09-16. A repeat
probe that day carrying `Content-Type: application/json; charset=utf-8` returned
`403 {"description":"Request unauthorized, missing api key"}`: route and method
resolved, only auth was refused. The two observations are still not reconciled.
What the endpoint *returns* remains unread — it needs a valid token, and no probe
here has had one.

**`/properties`, updated 2026-09-20.** A third observation, and the first taken
from a client holding a valid token for `.25`. `POST /v1/FireTV/properties` with
`X-Api-Key: 0987654321`, a valid `X-Client-Token` for `.25`, `Content-Type:
application/json` and an **empty body** returned `405 {"description":"Method not
allowed"}` — the same status as the 2026-09-16 record. This is consistent with
auth being evaluated *before* routing: the 2026-09-18 `403` was a missing-api-key
refusal, so that request never reached the method check, which is why it appeared
to "resolve". It does **not** reconcile the contradiction, because the vendor
app's observed `POST` carries a `bodyString` and this probe sent no body — a
body-bearing `POST` may behave differently. What the endpoint returns remains
unread.

This endpoint no longer gates anything, which is worth saying plainly because an
earlier revision of this section treated it as load-bearing. The gate this
document relies on reads `GET /v1/FireTV`, not `/properties`; that read is taken,
quoted above, and its verdict (`NotSupported` for volume and power) is the
device's own statement about itself. The `bodyString` the app logged on its
`/properties` POST carries the same verdict, and whether that field is a request
payload or a response echo is still unresolved — for `/v1/media?action=scan` the
same field demonstrably carries a *request*, so it is not reliably an echo. The
question is now moot for a client following this document.

### App launch and app list

```
GET  /v1/FireTV/apps            app list (legacy shape)
GET  /v1/FireTV/appsV2          installed apps, display names, icon art
POST /v1/FireTV/app/<package>   launch
```

`apps` and `appsV2` are **verified** (2026-09-16): both return a JSON array of
`{appId, name, tvIconArt, isInstalled, …}`. Auth headers required.

`POST /v1/FireTV/app/<package>` remains **unverified** — reported by the
reference implementation only.

### Keyboard and text input

```
GET  /v1/FireTV/keyboard          → queries text-input focus / cursor state
POST /v1/FireTV/text              body: {"text": "<one character>"}
```

**Observed 2026-09-17** against Fire TV Stick `the test TV` at `192.0.2.25`, from
the same Feniks capture. Two independent typing sessions produced the identical
shape:

```
GET  /v1/FireTV/keyboard
POST /v1/FireTV/text   body = {text=A}
POST /v1/FireTV/text   body = {text=a}
POST /v1/FireTV/text   body = {text=a}
GET  /v1/FireTV/keyboard
```

Four things about this shape are load-bearing:

- **One POST per keystroke.** Text is not batched — `abc` is three separate
  POSTs, one per character. Expect burst-y wire traffic during text entry.
- **`GET /keyboard` brackets a session.** Fired before the first character and
  again after typing stops. Probably queries whether a text-input field on the
  TV has focus, and reports state changes. The response body was not observed
  here — the capture logs request-side only.
- **Auto-capitalisation is client-side.** The first character shows as `A`
  because the phone's IME auto-capped it, not because the TV requested case.
  Whatever the IME sends is what reaches the wire.
- **The IME "checkmark" / Enter tap is client-side only** — it produces zero
  additional POSTs. It merely closes the phone's keyboard sheet; the TV's
  submit still needs a `dpad_center` (or the app's own OK affordance) pressed
  separately. Do not equate "user tapped the checkmark" with "form submitted
  on the TV" in this API.

### Control coverage — what is wired, what is absent, and on what evidence

The controls this document commits to, one row each: the actions above are the
ones with observed use; this table is the *verdict* on the wider set, including
the controls the vendor remote displays and this API has not been shown to
support. A control that is not in the "wired" state below is not drawn.

**Read the source column before trusting a row.** Evidence comes in two grades
here. `requests.txt` is the 2026-09-17 capture of the vendor app's requests —
thirteen request lines, the whole of it, kept with the maintainer rather than
published here, and it carries six distinct actions:
`scan`, `home`, `volume_up`, `mute`, `power`, `properties`. The raw `adb logcat`
buffers behind the wider vocabulary were deliberately kept off-repo (Amazon
telemetry, hashed device id), so a row citing "prose" rests on a log a reader
cannot open. Rows say which, so that the weaker grade is visible rather than
implied.

| Control | Action string | Wire shape | Observed effect (device + date) | Verdict | Source |
|---|---|---|---|---|---|
| Volume up | `volume_up` | body-less `POST /v1/FireTV?action=volume_up` | `.22`: no visible or audible effect (2026-09-17). `.25` (`the test TV`): audibly raised the volume to 100 (2026-09-17 evening), and sent again from the same device 2026-09-18 | **Capability-varies.** Wired only where the device read reports support; on a device reporting `false` it is absent | `requests.txt` lines 4–7 of the 2026-09-17 capture for the wire shape; `.25` again in the 2026-09-18 volume channel capture for the send; the audible result is prose, log off-repo |
| Volume down | `volume_down` | body-less `POST /v1/FireTV?action=volume_down` | `.25` (`the test TV`) 2026-09-18: sent on both presses, each bracketed by a `volume_up` that logged before and after it, so the pipeline was provably alive on both sides. Never sent on `.22`, which advertises `isVolumeControlsSupported:false` and returned only `volume_up` | **Observed on the wire; wired** (`ui/RemoteScreen.kt`, `ROCKER_CONTROLS`). Capability-gated, as volume up — the rocker follows the device's own report, so the earlier "absent" verdict was the device, not the protocol | `requests.txt` in the 2026-09-18 volume channel capture, lines 8 and 15 for the wire shape |
| Mute | `mute` | body-less `POST /v1/FireTV?action=mute` | `.22`: no effect. `.25`: muted the TV | **Capability-varies**, as volume up | `requests.txt` lines 8–11; `.25` prose |
| Power | `power` | body-less `POST /v1/FireTV?action=power` | Sent once on `.22` at 13:11:32; **the effect was not recorded in any capture** | **Not established.** Gate applies: `.22` advertises `NotSupported` | `requests.txt` line 12 for the send; no effect evidence |
| Channel up / down | *(none)* — the rocker sends `dpad_up` / `dpad_down` | — | Pressed on `.25` (`the test TV`) 2026-09-18: the upper half sent `dpad_up`, the lower half `dpad_down`. No `channel*` string appears in any capture | **Not a control of its own.** The vendor remote's channel rocker is a D-pad passthrough — it does exactly what the D-pad does, so there is nothing to wire for it | `requests.txt` in the 2026-09-18 volume channel capture, lines 10–11 and 18–19 |
| TV / source | `epg` | body-less `POST /v1/FireTV?action=epg` | `.25` (`the test TV`) 2026-09-18: sent once per press of the button the remote displays as TV/source. **The effect was not recorded** — the TV was already on the TV input, so nothing visibly changed | **Not established.** The send is observed; the effect is not, at the same grade as `power`. The name reads as a guide rather than an input switch, which pressing the TV/source button does not settle | `requests.txt` in the 2026-09-18 volume channel capture, lines 13 and 16 |
| Rewind | `scan` | `POST /v1/media?action=scan`, body `{"direction":"back","durationInSeconds":"10","speed":"1"}` | Two presses on `.22`, 20 s apart, both `direction:"back"`, effect unrecorded in the capture. **Pressed from this app 2026-09-18 with media playing on two devices, two different outcomes.** On `.22`: the media shuttled backward roughly ten minutes on a single tap. On `.25` (`the test TV`): the media shuttled backward roughly ten seconds on a single tap, matching the field name (observer's visual estimate in both cases, not a measurement). A press-and-hold does not scale the effect on either device — one tap is one shuttle. **2026-09-28 on `.22`: a tap starts a continuous shuttle that runs until `play`, and each further tap speeds it up (1x → 2x → 3x); the "ten minutes" was that shuttle left running (§ 2, Media)** | **Ships — the press and its effect are both observed on two devices.** The step size varies by device on the same wire body, so any UI that claims a fixed jump is misleading — see § 6 | `requests.txt` lines 1 and 3 for the wire shape; live verification 2026-09-18 on `.22` and `.25` for the effect |
| Fast-forward | `scan` | same shape with `"direction":"forward"` | Never captured from the vendor app; the forward direction is inferred from the `direction` field. **Pressed from this app on `.25` 2026-09-18 with media playing: accepted, and the media shuttled forward roughly ten seconds on a single tap** — same step behaviour as rewind's on the same device, and a press-and-hold does not scale it either. **2026-09-28 on `.22`: sent from this app, it started a fast-forward at "1x" that `play` stopped** | **Ships as a matched pair with rewind** — direction inferred, effect observed. Shipped as a pair rather than solo because shipping only the backward half made an overshoot on rewind uncorrectable, which is the UX trap the Step 3 hand-test surfaced on `.22` | § 2, Media (prose, log off-repo); live verification 2026-09-18 on `.25` for the effect |
| Sleep | `sleep` | body-less `POST /v1/FireTV?action=sleep` | **Pressed twice from this app on `.22` 2026-09-18** — once against an idle device, once with media actively playing. Both accepted, the display went dark (observed), and `GET /v1/FireTV/status` answered `200` afterward in both cases. So § 3's "display-off, the API keeps answering" holds under this app's own press rather than on prose alone. Not in the 2026-09-17 capture | **Ships** — both halves observed live, matching § 3 | § 3 prose, plus live verification 2026-09-18 |
| Keyboard (open) | *(undetermined)* | `GET /v1/FireTV/keyboard` brackets a text-entry session; `POST /v1/FireTV/text` carries one character per request | `/keyboard` seen before and after typing on `.25`; **no open-keyboard action string was observed**, and `/keyboard`'s response body never was | **Not established as a control.** No text-entry UI ships on this | § 2, Keyboard and text input (prose, log off-repo) |

**Two consequences a reader should take from the table.** First, the set that
survives is smaller than the vendor remote's — that is the intent, not a
shortfall: a control present and broken is worse than one absent, and a guessed
action is worse than a missing button. Second, **a failed capability read is not
a verdict.** Reading `GET /v1/FireTV` is how the volume, mute and power rows are
decided on a given device; if that read fails, the controls are drawn rather than
hidden, because a network hiccup must not be mistaken for the device saying no.
Only an explicit `false` / `NotSupported` suppresses a control.

---

## 3. Waking a sleeping TV

```
POST http://<ip>:8009/apps/FireTVRemote
```

Plain HTTP, DIAL. Works while the device is asleep. Returns `200`/`201`.

**This must be wired into the app.** Recommended shape: on a button press, send
the command; if it does not answer within ~1 second, fire the wake, wait for the
device to come up, then resend. That is what makes the official app feel
instant when the TV has been idle — one press both wakes it and lands the action.
A **deeply** asleep TV does not answer this request at all until a Wake-on-LAN
magic packet wakes it (§ 3, "Waking a deeply asleep TV — the magic packet").

Note: `GET http://<ip>:8009/apps/FireTVRemote` returns DIAL XML including
`<state>`. `stopped` is **normal** and does not indicate a fault.

**A timeout does not prove the device is asleep.** The signature described above
— accepts the TCP connection, completes the TLS handshake, never replies — is
produced just as well by a control API that has stopped servicing requests while
the device is awake and playing video. Measured 2026-09-16 against Fire OS
6.7.1.1: DIAL on 8009 answered `200` throughout, `ping` was clean at ~7 ms, TCP
8080 was open and TLS negotiated the expected Turnstile chain — yet HTTP
returned zero bytes on every attempt, at both 8 s and 15 s read timeouts. It
reproduced identically from a second client on the same network at the same
moment, so it is a property of the device, not of the client.

### A refused port is the same condition — and it is the common one

Measured 2026-09-17 against a Fire TV Stick. While the device was in use,
`:8080` accepted connections. Once it idled into its screen saver, the port was
**refused** — not hung, not filtered. `ping` stayed clean and DIAL on `:8009`
kept answering throughout, so the device was plainly on and on the network.
Powering it off with its own remote did **not** close the port; going idle did.

This is a third signature, distinct from both of the above, and it is the one a
user meets most often:

| What the client sees | What it means |
|---|---|
| Accepts TCP, negotiates TLS, returns no bytes | The TV is up but its control API has stopped servicing |
| Refuses the connection | The TV is up and **idle**; the API is not listening yet |
| No route, or a refusal on a host that never answered ping | Wrong address, or off the network |

### What the official app does about it

Captured from its own requests against `192.0.2.22` (`adb logcat`, tag
`FeniksNetworkModule`, app v4.7.0). One press that failed produced:

```
POST https://<ip>:8080/v1/FireTV?action=home   → fails, the port is refused
POST http://<ip>:8009/apps/FireTVRemote        → the wake, 89 ms later
GET  https://<ip>:8080/v1/FireTV/status        → poll
GET  https://<ip>:8080/v1/FireTV/status        → poll
POST https://<ip>:8080/v1/FireTV?action=home   → lands
```

Two things about that shape matter, and neither is obvious:

- **The wake fires on any failure, not just a timeout.** A refusal is treated
  as "not listening", not as "wrong address". A client that wakes only on a
  timeout leaves the user with a dead remote against a TV that is visibly on.
- **It polls the status endpoint rather than sleeping a fixed interval.** Any
  answer counts as up — including a `403`, which means the port is open and the
  process is serving. In this capture the API was answering again within
  ~900 ms of the wake.

Consequence for the recovery shape recommended above: **waking cannot fix this
state.** A DIAL wake returns `201` and the resend still gets nothing. Treat the
timeout as *"not answering"* — asleep or wedged — and do not report "asleep" as
the cause. The wake-on-`SocketTimeoutException` rule still stands; only the
inference drawn from it was wrong.

### `action=sleep` is display-off, not deep-sleep

**Two distinct sleep-shaped states exist and they are not the same thing on the
wire.** Measured 2026-09-17 evening against Fire TV Stick `the test TV` at
`192.0.2.25`:

- **`POST /v1/FireTV?action=sleep`** — the *display-off* state. The screen goes
  dark, but port 8080 keeps answering. The very next command lands with no wake
  burst required. Reproduced twice: `action=sleep` was followed within ~3.5 s by
  `GET /v1/FireTV/keyboard` and three `POST /v1/FireTV/text` calls, all
  succeeding on port 8080 with zero DIAL POSTs in between.
- **Organic idle deep-sleep** — the state described in § 3 above, produced by
  leaving the TV alone until the screensaver takes port 8080 offline. Port 8080
  refuses / times out; port 8009 wake POST is the only path back. On the same
  the test TV earlier the same evening this state took **~35 seconds of continuous
  DIAL wake POSTs** (fired every ~2.4 s by the official app) before status
  answered again.

Same visual outcome — dark screen — completely different network state.
`action=sleep` does not need to be recovered from; organic deep-sleep does.
Clients that treat "TV is dark" as a single condition and always fire the wake
sequence will spend ~35 s waking a TV that was one HTTP call away from
responding.

**What causes organic deep-sleep is still not characterised.** The Fire TV
Stick captured earlier (`.22`, § 3 head paragraph) held port 8080 open through
its own idle screensaver, then refused it once idle enough. the test TV's deeper
state — no ICMP, no TCP on any port, no SSDP response for a stretch of minutes
— was deeper still and is not reproducible from `action=sleep` alone. Amazon's
own app appears to have a cloud-mediated wake path for this state, over a
persistent websocket to Amazon's backend; that path is not available to a
LAN-only client.

**It is not Android's Doze.** Read 2026-09-25 from `the test TV` itself over ADB
(Fire OS 6.7.1.1, Android 7.1.2): `dumpsys deviceidle` reports
`mLightEnabled=false mDeepEnabled=false` — both idle modes are switched off on
this stick — so forcing Doze from a shell would test a state the device never
enters by itself. Its own sleep key (`KEYCODE_SLEEP`, 223) took it to
`mWakefulness=Asleep`, display `OFF`, within 4 s.

**Narrowed 2026-09-18, and the markers above do not separate the two states.**
`the test TV` was found silent to **ICMP** and to TCP on `:8080` — the signature this
subsection attaches to the *deeper* state — and was then brought back **over the
LAN** by this app's own direct-connect wake path, answering ICMP afterwards. So
"no ICMP" cannot be read as "only the cloud path can reach it": the recoverable
state and the deeper one overlap on that marker. Not controlled: `:8009` was not
probed while the device was dark, deliberately, so whether the wake radio was
still answering is unmeasured — and the lockout risk of probing a Fire TV from a
shell is why that probe was not made to find out.

### How long the command API takes to answer after a wake

Measured 2026-09-18 against a Fire TV Stick, over **ten** wake cycles. The figure
this produces lives in `FireTvClient.WAKE_SETTLE_MS`; what follows is the method
behind it. A bare number in a spec is a guess wearing a measurement's clothes.

**The state under test.** TV powered on and left idle into its screen saver, with
the control port **refused** before each press. The refusal is the point: a
genuinely powered-off TV cannot be woken from a LAN client at all, so testing
against one produces a false failure rather than a slow wake. The two states are
easy to confuse, and the screen saver spans both — the Stick held `:8080` open
*through* its screen saver and closed it only once idle enough (above), so
"screen saver showing" is not by itself the state this measures. Idle time to
refusal was not characterised: 8 minutes of idleness still answered, 22 did not.

**How the samples were taken.** By driving the app — `adb shell input`, or a
press on the phone — never by probing the TV from a shell. A burst of shell
probes is what security-locks a Fire TV until power-cycle, so
the measurement uses the app's own traffic, which is indistinguishable from real
use. Instrumentation logged `nowMillis()` and HTTP status only, never the token.
Each sample is **self-verifying**: the first poll answering *refused* is what
proves the TV was asleep at press time, so the state check and the measurement
are one observation rather than two.

**Observed spread**, ten samples in ms:

| | |
|---|---|
| samples | 530, 533, 536, 809, 823, 1660, 1818, 1821, 1822, 1835 |
| min / max | 530 / 1835 |
| spread | 3.5x |

The figure taken is the observed **maximum**, not the mean, because this is a
deadline the loop waits out and a mean-shaped deadline would fail roughly half
the time. Maximum + 50%, rounded up to a whole poll interval: 2752.5 → **3000 ms**.

The tail is not the poll interval. A poll that lands on an accepted-but-silent
connection costs the full ~1000 ms read timeout,
and each of the four slowest samples carries exactly one. A sample with none
tracks the poll arithmetic instead — 3 polls is 2 sleeps, ~530 ms. Two timeouts
would land near 2835 ms, inside the ceiling.

**What this figure does not cover.** It is not a claim about a TV in any other
state: not one powered off (unwakeable from the LAN), not one mid-boot, and not
one whose control API is wedged while awake — that last produces a byte-identical
signature to sleep and may well settle differently. It is also not a cold-boot
measurement; every sample starts from the screen-saver-idle state above. Ten
samples bound the tail loosely at best: nothing above 1835 ms was observed, and
nothing above it is excluded.

### Waking a deeply asleep TV — the magic packet

**A deeply asleep Fire TV Stick does not answer the DIAL wake. A Wake-on-LAN
magic packet is what wakes it.** Established 2026-09-25 against `the test TV` (`.25`,
Fire OS 6.7.1.1) over four runs, from the vendor app's own log (v4.8.0 — its
`DeviceConnection` and `UdpSockets` tags record what the HTTP-only
`FeniksNetworkModule` does not) and from this app's own request log, captured
2026-09-25.

**Run 1 — the vendor app connecting.** One wake request with
a 10 s limit, answered `201` after **7.4 s**; `UdpSockets` closed two sockets 66 ms
after the request went out and a third 3.5 s later. The command API answered on
the first poll after the wake. `.22`, which announces no `WAKEUP` MAC and was idle
rather than deeply asleep, answered its wake in 0.2 s with no UDP at all.

**Run 2 — a Home press, after `KEYCODE_SLEEP` and eleven minutes untouched.**

```
this app     19:48:17.9    Home, 2 s    -> no connection
             19:48:19.9    wake, 10 s   -> no connection                          (TV stays dark)
vendor app   19:49:28.9    Home, 3 s    -> failed to connect after 3000 ms
             19:49:31.965  wake #1, 2 s -> failed to connect   UDP socket at .971
             19:49:34.497  wake #2, 2 s -> failed to connect   UDP socket at .504
             19:49:37.078  wake #3, 2 s -> 201 at 19:49:38.5   UDP socket at .084 (TV wakes)
             19:49:38.7    status, 2 s  -> 200
```

Every vendor wake attempt opened and closed a UDP socket within 7 ms of its HTTP
request. The TV came back through its maker's logo and then Amazon's — the TV set
powering on with the stick — and the vendor app did **not** resend the Home press.

**Run 3 — this app's retry loop, DIAL only.** Same sleep recipe, seventeen minutes
untouched: the press, then sixteen wake attempts shaped exactly like the vendor
app's — 2 s each, started 2.5 s apart — across 40 s. Not one connected.

**Run 4 — one magic packet, nothing else.** With `the test TV` still dark after run 3,
one standard 102-byte magic packet for its MAC went from a laptop on the same
network to the subnet broadcast, port 9: no HTTP request, no app. The TV woke by
itself, through the maker's logo to Home.

So what separated the two apps was never the shape of the retries; it was the UDP.
The timings fit: both vendor wakes answered 6.5–7.4 s after the first UDP send,
which is how long the stick takes to resume.

**Retracted** — written earlier the same day, on runs 1 and 2 alone: that many
short wake attempts beat one long one because a waiting connection re-sends at
widening gaps. Run 3 falsified it: sixteen short attempts failed exactly as the
one long attempt had.

**Where the MAC comes from.** The TV announces it: `the test TV`'s SSDP answer carries
`WAKEUP: MAC=00:00:5e:00:53:01;Timeout=20` (§ 5 lists every reply on this
network). This app keeps the MAC with the pairing, and a scan fills it in when the
pairing has none. A scan never replaces a stored MAC: an SSDP answer is an
unauthenticated UDP datagram whose source address the sender writes, so a replace
would let anyone on the LAN point the wake at other hardware.
A stored TV with no MAC also gets one SSDP search of its own after the first
press that lands on it, once per app screen (`PairingFlow.learnWakeupMac`). The
search fills a MAC in but never replaces one, and stores it only when every
answer from that address names the same MAC.
It has to be heard while the TV is awake, because a TV asleep deeply enough to
need it answers nothing. Measured 2026-09-26: a phone whose `the test TV` pairing
predated the MAC and had not scanned since sent DIAL alone to the deeply asleep
TV — sixteen attempts, no wake — while the vendor app woke it.

**What this app does.** Each wake attempt sends the magic packet — to the
network's own broadcast address and to `255.255.255.255`, port 9 — and then the
DIAL request; attempts of 2 s start 2.5 s apart for up to 40 s
(`FireTvClient.WAKE_ATTEMPT_TIMEOUT_MS`, `WAKE_ATTEMPT_INTERVAL_MS`,
`WAKE_BUDGET_MS`). It then polls the command API and resends the press, which the
vendor app does not. A TV that announces no MAC gets the DIAL request alone.

**Verified live 2026-09-25 (run 5).** `the test TV` asleep eighteen minutes by the same
recipe, one Home press: the third attempt was answered `201` 6.65 s after the
first packet, the TV came up through both logos, and the resent Home landed 9 s
after the tap.

Not settled: what the vendor's UDP packets hold and where they go — its log names
neither, and seeing them takes a root capture; what the `Timeout` value in
`WAKEUP` promises — not read here yet; and whether the cloud path described above
ever matters, since the LAN packet alone accounts for every wake observed.

### DIAL keepalive cadence during active use

The official app fires `POST http://<ip>:8009/apps/FireTVRemote` about every
**2.4 seconds** while a remote-screen session is active, not just at wake.
Twenty-eight DIAL POSTs went out over 66 seconds during the test TV's wake-and-drive
sequence 2026-09-17 evening, interleaved with normal command traffic. When the
app is backgrounded or the remote screen is closed, the cadence stops — not a
persistent global keepalive, only session-scoped.

This is not required for correctness in a third-party client. A simpler
implementation that fires DIAL wake only on a real timeout / refusal (the § 3
recovery shape above) will work; the vendor's ~2.4 s cadence is a UX
optimisation that keeps the TV out of screensaver during interactive use so no
subsequent press ever pays the wake latency.

The 2026-09-25 capture (v4.8.0, see above) shows no DIAL POST after either
connect — including the 8.5 s between `.22` connecting and the switch to
`the test TV`. It cannot say how long a remote screen was open, so it neither confirms
this cadence nor retires it.

---

## 4. Auth model, and what it is worth

- `X-Api-Key: 0987654321` is a hardcoded constant shared by every Fire TV. It is
  not a secret and provides no security.
- **`X-Client-Token` is the only real gate.** Anyone on the LAN holding both the
  constant and a valid token can drive the TV.
- TLS uses a **self-signed certificate** (issued by Amazon Lab 126 / "Turnstile
  Server"). Clients must disable certificate verification. This means the client
  cannot authenticate the server either — the connection is encrypted but not
  mutually authenticated.
- Treat the token as a credential: do not commit it, do not log it.

---

## 5. Discovery

Fire TV advertises `_amzn-wplay._tcp.local.` over mDNS. The TXT record contains
the device's friendly name and dynamic service ports:

```
s=0 at=... tr=tcp n=the test TV sp=53315 pv=1 mv=2 v=2 u=<uuid> a=0 ad=... dpv=1 t=2 f=0
```

Observed on the wire 2026-09-17 (tcpdump on the LAN, four Fire TVs): the `n=`
key carries each device's own name — `n=Living Room Fire TV`,
`n=Home Gym`, `n=<model> (Amazon Fire TV Stick)`. Newer records also
carry `c=<MAC>` (`c=00:00:5e:00:53:04`), the device's hardware address. The Fire
TVs query this record of each other — a Fire TV at `.23` polled
`_amzn-wplay._tcp.local.` and `amzn.dmgr:<uuid>._amzn-wplay._tcp.local.`
repeatedly in the same capture — but no phone-side client was seen doing so.

Notable: the command API port is **not always 8080** on the Whisperlink side —
the `sp`/SRV ports are per-service and rotate. For the REST API on this class of
device, 8080 was correct and stable.

**Discovery is implemented, by SSDP — see the subsection below.** This paragraph
previously read "for v1, do not implement discovery", which the shipped scanner
superseded. What the app does now: it searches for the DIAL service and lists
**every** host that answers, labelled with the name that host gives about
itself. It deliberately does **not** filter to Fire TVs. A Samsung TV answers
the same DIAL search — observed 2026-09-17, `192.0.2.40`, `Samsung UPnP
SDK/1.0` — and is listed alongside the Fire TVs, the way a Wi-Fi list shows
networks that are not yours. The user picks. This is a deliberate reversal of
the "a host is a Fire TV only if it answers the DIAL search" framing this
project previously carried in its phase plan.

### SSDP M-SEARCH — the vendor's own discovery path

Observed 2026-09-17 from the official app (`com.amazon.storm.lightning.client.aosp`,
v4.7.0) via `adb logcat -s FeniksNetworkModule`, driving it against four Fire TVs
on a `192.0.2.0/22` network. This is the app's **default** discovery path, not a
fallback: its own config carries `isIPScanningFeatureEnabled: true`, labelled a
*manual* mechanism for edge cases.

What the capture recorded, per "Look Again" tap:

- **Six identical datagrams**, sent to multicast **`239.255.255.250:1900`**.
- **`ST: urn:dial-multiscreen-org:service:dial:1`** — the DIAL service, not `ssdp:all`.
- **`MX: 1`** — the maximum delay, in seconds, a responder may wait before answering.

Those are the fields the capture establishes. The client sends the complete
SSDP-standard search around them:

```
M-SEARCH * HTTP/1.1
HOST: 239.255.255.250:1900
MAN: "ssdp:discover"
MX: 1
ST: urn:dial-multiscreen-org:service:dial:1
```

`HOST` and `MAN` are required by the SSDP specification rather than observed
here; the four bullets above are the observed part.

Each responder's reply carries a **`LOCATION`** header pointing at that device's
descriptor document. Two findings from the same capture correct what earlier
notes assumed:

- **The friendly name is at the `LOCATION` descriptor, not the DIAL app-status
  endpoint.** `GET :8009/apps/FireTVRemote` answers with
  `<name>FireTVRemote</name>` — the *application's* name, identical on every
  device. Reading a device name from there would label an entire picker the same
  string.
- **The `LOCATION` path varies by Fire OS generation** — `:60000/dd.xml` on
  older builds, `:60000/upnp/dev/<uuid>/desc` on newer. A client should follow
  whatever URL the reply carries rather than reconstruct a path, which also
  leaves this variation unencoded.

Some devices additionally send a **`WAKEUP`** header carrying the device MAC and
a timeout (`MAC=<addr>;Timeout=<seconds>`) — the only Wake-on-LAN handle this
protocol exposes, and the address of the magic packet that wakes a deeply asleep
stick (§ 3). Others omit it, so it is optional; discovery must not depend on it.

Every answer to one search on 2026-09-25, from a laptop on the same network:

| Answered from | `WAKEUP` | `SERVER` |
|---|---|---|
| `.22` | *(none)* | `Linux/2.6 UPnP/1.1 quick_ssdp/1.1` |
| `.23` | `MAC=00:00:5e:00:53:02;Timeout=35` | `Linux/3.10.54 UPnP/1.0 Cling/2.0` |
| `.25` (`the test TV`, Fire OS 6.7.1.1) | `MAC=00:00:5e:00:53:01;Timeout=20` | `Linux/2.6 UPnP/1.1 quick_ssdp/1.1` |
| `.35` | `MAC=00:00:5e:00:53:03;Timeout=35` | `Linux/3.10.54 UPnP/1.0 Cling/2.0` |
| `.66` | *(none)* | `Linux/2.6 UPnP/1.1 quick_ssdp/1.1` |
| `192.0.2.1` | `MAC=00:00:5e:00:53:02;Timeout=35` — `.23`'s MAC, from a second interface | `Linux/3.10.54 UPnP/1.0 Cling/2.0` |

This paragraph used to say newer builds send `WAKEUP` and older ones omit it. The
table does not bear that out: `the test TV`, on Fire OS 6, sends it, while `.22` and
`.66`, with the same `SERVER` string, do not.

**`USN` identifies the device; the source address does not.** A USN is
`uuid:<id>::<service>`, and a TV that answers from more than one address — two
interfaces, or both Wi-Fi bands — repeats the same uuid from each. A client that
de-duplicates on the reply's source address therefore lists one TV twice; this
was observed 2026-09-17, where such a scan returned *Home Gym* twice
while the vendor app, holding the same information, listed it once. Key on the
uuid.

**Verified:** the search's service type, multicast group and port, `MX`, and the
six-repeat cadence, against four devices on one network in one Fire OS family.
**Not verified:** whether `LOCATION` forms beyond the two above occur, what
decides whether a device sends `WAKEUP` (the table above rules out a simple split
by generation), and whether the search survives an access point that
rate-limits multicast or drops it between wireless clients.

### A TV that is unreachable is invisible to the scan

Measured 2026-09-18, against a paired Fire TV Stick with its power pulled. The
scan returned the other four devices on the network and gave no sign that a
fifth had ever existed: with the device gone, the DIAL search hears nothing and
the `/24` sweep has nothing to probe, so it never becomes a candidate. It is not
a device the scan reports with a caveat — it is not reported at all.

The app still lists it, because it no longer depends on the scan for it. A TV
the app has already paired with is held in `SharedPreferences` with its name,
address and token, and `PairingFlow` appends it to the scan result marked *not
answering* when the scan did not hear from it. Selecting that entry opens the
remote on the stored token rather than asking for a PIN again — the PIN would be
on a screen nobody can read while the TV is off.

The same run settled a second question, and it corrects an easy assumption.
Powering the stick off with **its own remote** did *not* remove it from the
scan: it still answered the DIAL search and still served its command API, so it
was listed first and unlabelled, as an ordinary device. Only pulling the power
made it unreachable. "Off" on this hardware is a standby, not an absence — read
this together with the § 3 note that powering a device off does not close `:8080`.

**What the scan still cannot do:** find a TV that is off or asleep. That remains
open. What the app
now guarantees is the narrower claim: **a TV it has already paired with is not
lost from the list when the network loses it.**

---

## 6. Known-unknowns / not verified here

- App launch (`POST /v1/FireTV/app/<package>`). The apps *list* is now verified —
  see § 2, App launch and app list. Launch itself is not.
- Whether the wake endpoint differs across Fire OS generations.
- **Whether a capability read that says yes can be trusted.** Narrowed
  2026-09-18, not closed. The gate is established in the *negative* direction by
  measurement: `.22` reports `NotSupported` and was observed ignoring the same
  actions, on the same stick in the same session. The positive direction now has
  a record rather than a measurement: the capability object recorded on
  2026-09-16 says `isVolumeControlsSupported:true`, and by the attribution in
  § 2 that reading is `.25`'s — the device whose `volume_up` and `mute` audibly
  worked. So the flag correlates with behaviour on both devices, which is
  stronger than the single-device pairing this document had before. **Narrowed
  again 2026-09-20:** the read has now been *taken* from `.25` and returned the
  recorded value, so that half is a measurement rather than an inherited record.
  What is still untested: a device that reports yes and then ignores the action
  would not be caught by the read at all — the read cannot settle its own
  trustworthiness — and whether a Fire TV Cube behaves like either Stick.
- **Whether `/v1/FireTV/properties` returns anything.** A probe on 2026-09-20
  *did* carry a valid token (for `.25`) and still could not read it: `POST` with
  valid auth and an empty body returns `405 Method not allowed`. So "no probe has
  carried a valid token" no longer holds, and the route/method question is open
  again — see § 2, Device info and capabilities. Nothing in this document depends
  on it — the capability gate reads `GET /v1/FireTV` — so this remains idle
  curiosity rather than a gap a client needs closed.
- **Whether an ad break is observable from the LAN.** Observed 2026-09-20 against
  `.25`, and so far the answer is no. Four break boundaries were captured and the
  device log is the only surface that named them; nothing on the wire did. Break
  durations are **not uniform** — two at 96.75 s and 96.81 s, one at 32.4 s
  (≈ a single clip), one unmeasured — and the segments are server-stitched over
  the same CDN, so there is no local seam to watch. The `MediaSession` is not a
  usable trigger either: the one refresh caught at 5 s resolution arrived 5 s
  *after* a break had begun, and whether `actions=38` marks a break is
  unresolved. Recorded so the approach is not re-tried on the assumption that a
  break leaves some wire signature.
- **Whether a channel action exists in the protocol at all — answered
  2026-09-18: it does not.** The rocker was pressed on `.25` (`the test TV`) and each
  half sent a D-pad action — `dpad_up` from the top half, `dpad_down` from the
  bottom — so the control is a passthrough with no string of its own, and no
  `channel*` string appears in either committed capture. `GET /v1/FireTV2`'s
  `isEpgSupported` therefore governs something else: it is `true` on `.22`, and
  the button the remote labels TV/source sends `epg`. **Whether that flag gates
  `epg`, rather than a rocker that turns out not to exist, is the question that
  replaces this one.**
- **What `epg` does.** Observed 2026-09-18 as a body-less
  `POST /v1/FireTV?action=epg` from the TV/source button, with no effect recorded
  because the TV was already on that input. The send is established; the effect
  is not.
- **Keyboard submit, and the open-keyboard action.** `/v1/FireTV/keyboard` and
  `/v1/FireTV/text` were characterised 2026-09-17 evening (see § 2, Keyboard and
  text input), but only the state query that brackets a typing session was seen —
  no open-keyboard *action string* was observed, and `/keyboard`'s response body
  never was. The submit step was not captured either: the phone's IME checkmark
  is client-side only, and no wire signal for form-submit was observed. On the TV
  side, `dpad_center` on the text field probably submits, but this was not
  exercised. Both are recorded not-established in § 2, Control coverage.
- **What `durationInSeconds` actually means in a `scan` body.** Same body,
  same duration field (`"10"`), two devices, two outcomes on 2026-09-18. On
  `.22` a single tap shuttled the media roughly **ten minutes**; on `.25` the
  same tap shuttled it roughly **ten seconds** — matching the field name. The
  observer's estimates in both cases are visual, so no ratio is fixed, but the
  fact of the disparity is: the field does not carry a device-independent step
  size. Untested: whether `speed` scales it, whether `durationInSeconds:"1"`
  reduces `.22`'s jump to something usable, whether the vendor app's own
  presses vary the same way. A press-and-hold does not scale the effect on
  either device — one tap is one shuttle, and the app currently emits one
  request per tap accordingly. This matters to any client that offers a rewind
  button: the step size is what the user feels, and it varies by device.
  **Partly answered 2026-09-28 on `.22`:** there is no step. A `scan` starts a
  shuttle that runs until `play`, and each further `scan` speeds it up (§ 2,
  Media). Still open: whether `durationInSeconds` or `speed` change anything, and
  whether the test TV's "about ten seconds" was a jump or a shuttle stopped early.
- Behaviour on **Vega OS** (2025+ Fire TV Sticks). Vega is Linux-based and
  dropped ADB entirely; no source confirms or denies that this REST API exists
  there. **This protocol is confirmed on Android-based Fire OS only.**

## 7. Dead ends — so nobody re-chases them

- **ADB over TCP (5555)** works, but requires unlocking Developer Options
  (7 D-pad clicks), enabling ADB Debugging, and approving an on-screen prompt.
  It is also gone on Vega OS. Strictly worse than this API for our purposes.
- **Port 8080 does not speak Whisperlink.** The Whisperlink/Thrift stack lives on
  the dynamic ports advertised by mDNS. `amzn.lightning` there requires pairing
  and returns `401` without it.
- **Ports 55442/55443** are Alexa Whole Home Audio (speaker grouping), unrelated
  to remote control.
- **Amazon Fling SDK** reached end of support 2026-03-05 and was removed from
  Fire OS 6+. It never carried key events anyway.
- **DIAL is launch-only** — it cannot send key presses. Its one use here is the
  wake call on 8009.
- **No REST endpoint returns the TV's friendly name.** Probed 2026-09-16 against
  Fire OS 6.7.1.1 with a valid client token: `GET /v1/FireTV/status` returns no
  name field — on that device only `osVersion` and `turnstileVersion`, and on
  `.22` three build fields including `platformType`, so the field set is not
  fixed either (both shapes are in § 2, Device info and capabilities).
  `GET /v1/FireTV/{name,deviceInfo,
  description,friendlyName,deviceName}` returned nothing at all (connection
  closed, no response); the DIAL root, `/ssdp/dd.xml` and `/description.xml` on
  8009 return `404`; `GET /` on 8080 returns `405` with no body. The name is
  carried in two places. The primary one is the `LOCATION` descriptor of the
  DIAL answer — its `<friendlyName>`, recorded in § 5's SSDP subsection. The
  other is the mDNS TXT record for `_amzn-wplay._tcp.local.` (`n=the test TV` on
  this device). This sentence read "**only** in the mDNS TXT record" until
  2026-09-17, when the SSDP capture falsified it. Caveat: this is a finite probe
  of plausible paths, not a proof of absence across the whole API.

---

## 8. Source of this document

Primary evidence, in order of authority:

1. **Live request/response against the device** — pairing and command calls
   performed and observed directly.
2. **The official app's own logs** — `adb logcat | grep FeniksNetworkModule`
   prints method, URL and body of every request the app makes. This is the
   cheapest and most reliable way to extend or re-verify any of the above, and
   it is how the endpoint list was confirmed.
3. **`hms-homelab/hms-firetv`** — a working C++ implementation. Its pairing flow,
   the `description`-field token quirk, the `"OK"` retry, and the 5-minute expiry
   were read directly from its source.
4. Community work describing the same API: `SLC-Josh/FireTVRest`,
   `mase1981/uc-intg-firetv`. Secondhand, not read directly.
