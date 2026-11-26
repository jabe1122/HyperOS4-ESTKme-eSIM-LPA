# AGENTS.md

Notes for coding agents working in this repository. They collect the conventions and
decisions from earlier work sessions. The user-facing documentation is [README.md](README.md)
(Chinese first, then English).

## Project

A KernelSU / SukiSU / Magisk + LSPosed module that bridges removable physical eUICC cards
(ESTKme, Xiaobai, 9eSIM, Xesim, 5ber and other EasyEUICC-compatible cards) in SIM slot 2 to the
stock Xiaomi LPA (`prebuilt/MIUIEsimLPA.apk`, unmodified) on HyperOS 4 (Android 15-17).

- `probe/app`: LSPosed module and control app. Source package `io.github.local.esimh4probe`,
  applicationId `io.github.jabe1122.esimh4probe` (matches the LSPosed Repo entry; do not change).
  - `CompatModule.java`: LibXposed hooks, APDU bridge, control IPC.
  - `CardTypeGuard.java`: fail-closed ISD-R detection and switch-state cache.
  - `MainActivity.java` (Material 3) and `MainScreen.kt` (Miuix Compose) render the same pages;
    `DashboardModel.java` / `MainUiState.kt` hold the state both layouts share.
  - `TesterPage.java`: the "Test" tab (simulated carrier provisioning).
  - Secondary pages come in pairs: `*Activity.java` plus a Compose `*Screen.kt` for Miuix.
- `probe/ui`: shared UI module (`io.github.local.esimh4ui`): theme config, glass and background
  effects, bottom bar, pager, press feedback, language setting, `MiuixKit.kt`.
- `build_module.py`: packages the flashable module (ships the arm64-v8a APK of the ABI split).
- `finalize_release.py`: checks the outputs and writes SHA-256 sums.
- `.github/workflows/nightly.yml`: nightly CI build.

## Building and verification

- **Do not build or verify locally.** The machine has no Android SDK or Gradle. Do not search for
  them or try to install them. After code changes, stop at the edits and say clearly that the
  changes have not been compiled yet. The user builds through CI.
- CI command: `gradle -p probe clean assembleRelease` (or `assembleDebug`), then
  `python build_module.py [--debug]` and `python finalize_release.py [--debug]`.
- Release builds are R8-minified and signed; signing comes from `probe/local.properties`
  (`signing.*`) or `SIGNING_*` environment variables. Never commit signing material.
- APKs are split by ABI: universal, `arm64-v8a`, `armeabi-v7a` only.

## Dependencies and toolchain

- Versions live in [probe/gradle/libs.versions.toml](probe/gradle/libs.versions.toml). AGP and
  Kotlin follow the `compose-miuix-ui/miuix` main branch; Miuix is on a `0.9.5` beta. Keep code
  easy to move to later Miuix releases.
- AGP 9 compiles Kotlin itself. Modules must not apply `org.jetbrains.kotlin.android`; it is only
  declared at the root to pin the Kotlin version.
- Compose comes in through Miuix. Do not add a Compose BOM.
- The open source licenses list is generated at build time by the AboutLibraries Gradle plugin.
  Do not maintain it by hand.

## CI (nightly)

- Publish builds as workflow artifacts, not as GitHub Releases.
- The default build is the R8 release; `workflow_dispatch` has an option to build the debug APK
  instead. Upload the R8 mapping file as an artifact.
- The job summary lists each artifact with its size and SHA-256.

## UI conventions

The app has two UI styles that the user can switch between: Material 3 and Miuix.

- **Miuix means Compose everywhere.** Every page shown in the Miuix style must be written in
  Compose with Miuix components, not with Views.
- Reference implementations for the Miuix look: SukiSU Ultra's Miuix theme and the
  `compose-miuix-ui/miuix` example app. Match them for progressive background blur, glass and
  floating-glass top bars, buttons whose background fades in on scroll, the floating liquid-glass
  bottom bar, and predictive back.
- Secondary pages use the OS4 background effect and the glass back button. When scrolled, their
  top bar must look like the other Compose pages. Keep top bar title sizes moderate.
- Status uses SukiSU-style colors: green for working, red for not working. The top of the home
  page has a SukiSU-style status banner.
- Use pull-to-refresh (Miuix's own component where one exists) instead of refresh buttons.
- Use system `Toast` for all messages, not custom snackbars.
- Tappable cards use the Miuix press-feedback tilt with haptic feedback.
- Settings sliders show a value indicator and the theme's colors.
- Page state is shared between the Material and Miuix layouts. Add new state to the shared model,
  not to one layout.
- When the mock app is disabled, hide the "mock install" button on the home page.
- Launcher icon: themed monochrome foreground only (no legacy icons), kept inside the adaptive
  icon safe zone so that launchers with strong masks do not crop it.
- The English app name is `eSIM Probe`. Localized strings exist for `values`, `values-zh` and
  `values-b+zh+Hant`; update all of them when adding strings.
- About and licenses pages: no commit counts, and no "adapted from the Miuix example" credit
  lines. Tapping a third-party library opens its web page.

## Safety rules (do not weaken)

- The test flow (`test.esim.local`) must never send write or reset APDUs to the physical card.
- Only sync the `/data/app` update layer after the `/product/priv-app` privileged mount
  (`FLAG_SYSTEM`) is confirmed.
- Disabling or removing the module must restore everything (`pm uninstall-system-updates`,
  backed-up settings and properties).
- Fail safe when the removable card is pulled while the bridge is still active, and restore
  physical SIM selection when the eUICC is removed.

## Git

- Never commit, amend, rebase or push without the user's explicit approval for that action.
  Propose the commit message first.
- Do not add `Co-authored-by` or AI attribution trailers.
- Commit messages: Conventional Commits in English (`feat:`, `fix:`, `build:`, `chore(style):`).
- Commits are GPG-signed (`commit.gpgsign` is on). When rewriting history, keep the original
  author dates.
- Branches: `main` and `feat/miuix` (the Miuix work). `upstream` is the original repository.

## Writing style

- Reply to the user in the language they use (usually Chinese).
- Code comments and commit messages are in English. Keep README changes bilingual.
- No emoji in code, comments, docs or commit messages.
