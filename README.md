# X3Mira

Your phone's screen, live on RayNeo X3 Pro glasses over Wi-Fi — with sound, with
the temple pad as the input, and with a page agent you can talk to.

Two apps:

| | package | what it does |
|---|---|---|
| **Glasses** | `com.x3mira.app` | decodes and renders the mirror in the binocular viewport, draws the HUD, runs the page agent |
| **Phone** | `com.x3mira.phone` | captures the screen (MediaProjection), streams H.264 + audio over TCP, injects the taps and scrolls that come back |

Miracast-style, not DeX: no OEM cooperation, no root, no adb at run time. The
phone needs one screen-capture consent and it works.

### Install

Both APKs are in this repo — sideload each onto its own device:

```bash
adb -s <glasses> install -r X3Mira-glasses.apk    # com.x3mira.app
adb -s <phone>   install -r X3Mira-phone.apk      # com.x3mira.phone
```

The phone app's source lives in its own repository,
[X3MiraPhone](https://github.com/tropicalstream/X3MiraPhone); the two ship
together and their wire format has to match, so update them as a pair.

### A note on the Wi-Fi Direct passphrase

When the pair falls back to Wi-Fi Direct, the group is created with a fixed
network name and passphrase compiled into both apps — that is what lets the
glasses join without a human accepting a dialog every session. It is in this
source, so treat it as public: anyone within radio range running this code
could join the group. It carries only the mirror, and the phone keeps
cellular as its default route, but change both constants if that matters to
you.

---

## Which browsers does this work with?

**All of them — and every other app on the phone too.** That is a property of
how it is built, not a list that has to be maintained:

* The glasses show the **phone's screen**, captured with `MediaProjection`.
  Whatever is in front on the phone is what you see. X3Mira never knows or
  cares which app that is.
* The page agent **looks at pixels**. It sends the decoded frame to a vision
  model — there is no DOM access, no injected JavaScript, no browser
  extension, and nothing to integrate per site.
* The agent **acts through Android's accessibility gestures**, which land in
  whatever app is in front. Again, nothing browser-specific.

This is the main way X3Mira differs from a WebView agent (like the one in
`x3hub`, which injects a JS bundle and therefore only works inside its own
WebView). The trade is real: X3Mira cannot target an element by CSS selector,
so it locates things visually and is only as precise as the model's spatial
grounding. What it buys is that a map, a receipt, a photo, a PDF, a game and a
web page are all the same thing to it.

### Measured on the reference device

Samsung Galaxy S23 Ultra (Android 16) → RayNeo X3 Pro (Android 12):

| Browser | Mirrors | Agent can read + tap | Rotates to landscape |
|---|---|---|---|
| Chrome | yes | yes | yes |
| Brave | yes | yes | yes |
| Samsung Internet | yes | yes | yes |
| Comet (Perplexity) | yes | yes | **no — portrait-locked app** |

"Rotates to landscape" is an app behaviour, not a compatibility tier. X3Mira
follows the phone's real orientation either way; a portrait-locked app simply
stays portrait, and the mirror is then a centred portrait strip rather than
filling the view. Nothing about the agent changes.

### The genuine limits

These are worth knowing before you file a bug:

* **DRM / protected surfaces** (Netflix, some banking apps) come through
  `MediaProjection` as **black**. That is an OS restriction and no app-side
  setting changes it. The agent detects a uniform frame and says so rather
  than confidently describing a black rectangle.
* **Tapping and scrolling need the accessibility service enabled** on the
  phone (Settings → Accessibility → X3Mira input). Without it the mirror is
  view-only — everything still displays, nothing can be pressed. Reinstalling
  the phone app unbinds it and it must be re-enabled. Opening a site is the
  exception: that is an intent, not a gesture, so it works either way.
* **Text legibility** depends on capture resolution. The default is half-native
  (720×1536 for a 1440×3088 phone), which keeps small UI labels readable after
  the downscale to the 640×480-per-eye panel; the glasses can request other
  sizes from the settings page.
* **Small or repeated targets** are where the agent's visual grounding is
  weakest. Double-tap the pad stops an errand at any point.
* **Sound has two possible paths, and running both is an echo.** X3Mira
  streams the phone's audio over the same socket as the picture, but the
  glasses can also be paired to the phone over Bluetooth — and A2DP is
  already feeding the same speakers, a couple of hundred milliseconds later.
  The result does not sound like a duplicate, it sounds like slight reverb,
  which is why it gets blamed on the codec. The phone's **Sound to glasses**
  setting defaults to Auto and simply does not send a second copy while
  Bluetooth is carrying the first. Bluetooth is the path that wins, because
  it also carries what `AudioPlaybackCapture` is forbidden to touch: a live
  voice conversation runs as `USAGE_VOICE_COMMUNICATION` and cannot be
  mirrored at all.

---

## The pad

| gesture | does |
|---|---|
| tap | ask the page agent a question, or give it a task |
| double tap | stop the agent / dismiss what it said |
| triple tap | glasses settings panel |
| swipe up / down | scroll the phone |
| press and hold | click at the aim point |
| swipe sideways | move the aim point |

## The page agent

Tap, speak, and it answers aloud and on the HUD. Ask it to *do* something and
it does it: it locates the element visually and presses it through the same
return channel the pad uses.

It has four ways to act — press, scroll down, scroll up, and open a named site.
That last one exists because the other three cannot reach one: the agent has no
way to type, so "open youtube.com" used to end with it tapping the address bar
and meeting a keyboard. It hands the phone the address instead, which also means
it lands in whatever app owns that link — `youtube.com` opens the YouTube app,
not a browser tab.

An errand is a loop, not one action — "scroll down, find Spider-Man and press
the next showtime" scrolls, looks, scrolls, looks, and presses, taking a fresh
frame every step so a scroll that did nothing self-corrects. It stops when it
reports done, when it would press the same place twice, when it would open a
site it has already opened, after 10 steps, or the moment you double-tap.

Those two repeat rules are the load-bearing ones. A model asked to look again
mid-page-load cannot see that it already succeeded and will cheerfully answer
"open it" a second time, forever; the same goes for a tap when two rows look
alike. Neither is caught by asking the model to be careful — only by making the
repeat structurally impossible.

Speech in is Groq Whisper (falling back to Gemini); the eye and the reasoning
are Gemini (`2.5-flash`, falling back to the lite models, which is worth having
because full Flash returns 503 under load); the answer is spoken by the
device's own TTS.

### Keys

Two doors, both over adb — there is no login screen:

```bash
adb push gemini_api_key.txt /sdcard/Android/data/com.x3mira.app/files/gemini_api_key.txt
```

```bash
adb shell am broadcast -a com.x3mira.app.SET_API_KEY --es key "AIza..."
```

A file may hold several labelled keys (`gemini api key = …`, `groq api key = …`)
and each provider finds its own. The phone app reads the same two doors under
`com.x3mira.phone` and shows the resulting state on its settings screen.

### What gets sent

One still image of your own phone screen, per question you ask, only when you
tap. Nothing is captured while the agent is idle, and the microphone opens only
between your tap and the send.

There is an opt-in on the phone — **Read screen text**, default **off** — that
would additionally let the agent read the screen's text through the
accessibility service. It is off by default deliberately: that service is
declared in `res/xml/dex_input_service.xml` as one that can touch the screen
and must not be able to read it, and reversing that should be the wearer's
explicit choice. With it off the agent works from the picture alone, which is
the path everything above describes.

---

## Building

```bash
./gradlew :app:assembleDebug
```

`sdk.dir` in `local.properties`; minSdk 29, compileSdk 34. The phone app lives
in the sibling `X3MiraPhone` repo and must be installed too.

---

## License

[MIT](LICENSE), with a project notice covering what this software actually
does, because none of it is obvious from a feature list: it mirrors **every**
pixel of the phone including notifications and passwords as they are typed;
the link is **not encrypted by this software** and the Wi-Fi Direct
credentials are public constants in this source; the companion app can
**operate the phone** through an accessibility service; and the page agent
**uploads a picture of your screen** to a model provider when an errand runs.

Read it before pointing this at a phone you care about.
