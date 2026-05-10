# Roadmap

Captures items deliberately deferred during the audit + uplift sessions, plus product directions worth considering. This is **not** a commitment list — pick what's interesting, drop what isn't. The audit's bug list (`B*`) and architecture notes (`A*`) referenced below correspond to entries in earlier `CHANGELOG.md` releases.

## Test infrastructure

These need infrastructure beyond JVM unit tests / Robolectric.

- **Compose UI tests for `MainApp`'s nav graph.** `MainApp` calls `hiltViewModel()` for every nav destination, so a Robolectric screen test crashes on the first composition. Two paths forward: (a) refactor every nav-route Composable to also accept its VM as a parameter (cascading API change), or (b) write the test as instrumented (real device, full Hilt graph). Option (a) makes the tree more testable but is intrusive; option (b) requires CI device-runner infrastructure.
- **End-to-end instrumented test rig.** Currently zero `androidTest/` directories. An on-device smoke test that exercises login → favorites → playback would catch the regressions Robolectric can't (real audio rendering, real Auto session handshake).
- **Compose performance tests.** Baseline profiles already generate, but no `Macrobenchmark` measurements exist. Worth measuring cold-start + first-track-tap latency.

## Product features (deferred)

- **Multi-account.** `HypeSessionStore` is single-tenant. A genuine multi-account model needs a session-id-to-token map.
- **Account creation / password reset.** The login screen had decorative "Create account" and "Forgot password?" links that were [removed in 0.2.0 (B6)](CHANGELOG.md). Wiring them to in-app webview flows would close the loop without leaving the app.
- **Native AAOS sign-in.** AAOS doesn't allow password fields; the only way to sign in today is on the phone first. A QR-code-on-phone-then-pair-on-car flow would unlock first-time AAOS use.
- **Crossfade / gapless playback.** Media3 supports both; not currently wired in `HypePlaybackManager`.
- **Sleep timer.** Ask in `OfflineSettingsRoute` is one-line UI; ExoPlayer has built-in `setMediaItems(...).clearAt(t)` semantics.
- **Cellular-friendly streaming.** `OkHttpClient` has no bandwidth cap. Could add a "data saver" toggle that swaps stream URL parameters or enforces lower-bitrate format.
- **Lyrics surface.** `MediaMetadata` supports `setExtras`; lyrics could feed into the player UI without changing the queue model.

## Localization

- **Beyond Spanish.** `values-es/` exists per module ([0.6.0](CHANGELOG.md)) as a smoke test. Adding French / German / Portuguese is mechanical — no Kotlin changes needed, just `cp -r values-es values-fr` and translate.
- **RTL support.** The codebase declares `android:supportsRtl="true"` but hasn't been visually audited.

## Performance / efficiency

- **Per-screen ExoPlayer recomposition tax.** `publishProgressOnly` ([0.3.0 A4](CHANGELOG.md)) avoids rebuilding the queue list on every tick, but the player UI still re-renders the artwork / title even when only the progress bar changed. Could split `PlaybackQueue` into a "stable" half and a "ticking" half so Compose skips the heavy parts.
- **OkHttp cache freshness.** Currently 60-second `max-age` on all GET responses ([0.3.0 A7](CHANGELOG.md)). Per-endpoint cache windows (e.g., longer for `/blogs/{id}`, shorter for `/me/feed`) would cut bandwidth more.
- **Image cache.** Coil uses its own LRU; haven't tuned the byte-budget. Long sessions on Auto can blow it out.

## Security

- **Certificate pinning.** TLS handshake is currently trust-store-only. Adding `CertificatePinner` for `api.hypem.com` + `hypem.com` would defeat MITM via a compromised CA — at the cost of a hard breakage if Hype Machine rotates.
- **Hide stack traces in error UI.** `LoginViewModel` already wraps the failure in `ApiError` ([0.2.0 B7+B8](CHANGELOG.md)). Other VMs still feed `it.message` directly into `state.error`. Standardize on `toApiError()` everywhere.

## Build / dev experience

- **Detekt static analysis.** Spotless covers formatting; Detekt would catch real code smells (long methods, deeply nested when, etc). Roughly an evening to add.
- **Konsist architectural rules.** Currently a hand-rolled `checkArchitecture` task ([0.18.0](CHANGELOG.md)) with one rule. Konsist provides a richer DSL (e.g., "no ViewModel may import Android Context").
- **Baseline-profile regeneration.** Profiles are generated at build time but not measured against. Macrobenchmark runs in CI would surface regressions.
- **Dependency upgrade hygiene.** Renovate is configured ([0.10.0](CHANGELOG.md)) but the schedule is weekly Monday. Set up branch-protection rules so renovate PRs auto-merge on green CI for patch updates.
- **Module-level coverage floors.** `koverVerify` enforces aggregate ≥ 60% line ([0.18.0](CHANGELOG.md)). Per-module floors would catch a regression in `feature/auth` (currently 97%) even if the aggregate stays above the line because of growth elsewhere.

## Cleanup

- **`HypePlaybackManager` is `@Singleton`** but never released. Acceptable for app-lifetime singletons, but a future change to make it scope-aware should release the underlying ExoPlayer.
- **`MediaNotificationPermissionGate`** is a `private fun` Composable in MainActivity. Robolectric / screen-test-able if widened to `internal` (same trick as `MiniPlayerBar` in [0.15.0](CHANGELOG.md)).
- **`tmp/` and `dist/`** are in the working tree. `tmp/` is now gitignored; `dist/` is intentionally tracked (versioned artifact mirror) but eats ~100 MB of repo size. Long-term, these should move to GitHub Releases.
