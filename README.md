# Blaze Player

Blaze Player is an Android 12+ (`minSdk 31`) video player (`com.blaze.player`).
The playback service owns one Media3 player and accepts local videos and a
narrow, deliberate network-source policy.

## Source policy

The canonical source policy is implemented by
`app/src/main/java/com/blaze/player/source/SourcePolicy.kt` and exercised by
`SourcePolicyTest`. The accepted network source is **one direct progressive
media URL** using `http://` or `https://`:

- HTTP and HTTPS URLs must have a host, must be syntactically valid, and must
  not contain URL userinfo (`user:password@host`). Query strings and fragments
  are retained for access semantics, but are never safe places to put secrets.
- Cleartext HTTP is allowed only because it is explicitly user-supplied media.
  HTTP is **not confidential**. Prefer HTTPS whenever the source supports it.
- HTTPS redirects may stay on HTTPS. HTTP may redirect to HTTPS. HTTPS-to-HTTP
  downgrades, credential-bearing redirects, redirects without a host, and
  redirect chains longer than five hops are rejected.
- A successful HTTP response must be in the 2xx range. 401/403 responses are
  reported as authentication-required, 3xx responses as rejected redirects,
  other non-2xx responses as HTTP errors, and a requested but unsupported byte
  range as a range/seek error. The player does not silently retry with
  credentials or claim that a failed seek succeeded.

The following are rejected before progressive preparation:

- `file://`, FTP, RTMP, and every scheme other than `content://`, `http://`,
  and `https://`;
- malformed or hostless HTTP(S) URLs and URLs containing credentials;
- HLS (`.m3u8`), DASH (`.mpd`), manifest-style adaptive sources, and DRM
  indicators in URL query/fragment data or supplied source metadata;
- private-media caching, trust-all TLS, authorization headers, cookies, and
  other credential-bearing access mechanisms are not part of this policy.

Local `content://` sources are accepted only when the provider is readable.
Persistable read permission is retained when the originating grant supports it;
an accepted non-persistable grant is valid for the current handoff only.

This policy does not claim compatibility with every codec or container. HLS,
DASH, DRM, Chromecast, and Android Auto are out of scope for v1.

## Build and CI

Use JDK 17 and the Gradle wrapper. The required CI gate is exactly:

```text
./gradlew test lint assembleDebug assembleRelease
```

CI installs Android SDK/API 31+ and produces a debug APK and an **unsigned**
release APK, plus lowercase SHA-256 records, machine-readable metadata, and
reports. These are GitHub Actions artifacts, not GitHub Releases. No signing
keys or release secrets are used.

The local environment for this project does not include the Android SDK,
`adb`, or an emulator. Consequently, local static/unit checks can verify
source-policy wiring, but they cannot verify network behavior on a device.
Real pause/play and seek p95, first-frame and HTTP-seek latency, PiP,
screen-off audio, notification/hardware controls, and codec/decoder behavior
remain **unverified** until a CI/device validation run provides evidence.

## Privacy

Media URLs are user input. Do not log or persist URL query values, fragments,
userinfo, tokens, cookies, authorization headers, or private local paths.
