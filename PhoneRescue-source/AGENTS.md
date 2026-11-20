# PhoneRescue — instructions for coding agents

## Purpose and boundaries

PhoneRescue is a local Windows execution/file bridge with an Android client. The Android automation uses the official ChatGPT UI through AccessibilityService; it does not use model API keys or extract account credentials. Keep this design unless the user explicitly requests a different integration.

Read `README.md` first. For operating a running bridge, read `docs/AGENT_USAGE.md` and `docs/API.md`. For modifying it, read `docs/ARCHITECTURE.md` and `docs/BUILD.md`.

## Repository map

- `Program.cs`: ASP.NET Core HTTP server, PowerShell jobs, file endpoints.
- `android/src/local/phonerescue/app/MainActivity.java`: native Android UI, pairing, manual commands, file streaming.
- `RepairService.java` in the same directory: accessibility state machine, per-command approval, result feedback.
- `RepairProtocol.java`: fixed diagnostics, prompts, nonce framing, response validation.
- `wwwroot/`: manual browser client (including iPhone LAN use).
- `scripts/`: build support, SDK bootstrap, CLI client, source export.
- `tests/integration.py`: isolated Windows API integration tests.
- `tests/android/`: opt-in physical-device instrumentation.

## Working conventions

1. Use UTF-8. Preserve Chinese user-facing text. Do not pipe Chinese source text through a legacy Windows console encoding; prefer direct UTF-8 file edits.
2. Never commit local signing keys, `session.json`, tokens, UI dumps, personal logs, uploads, downloaded SDKs or device-specific registry backups. See `.gitignore`.
3. Do not hardcode a developer's username, absolute home path, ADB serial or network address. Derive workspace from `/api/status`; accept device identifiers as arguments.
4. Preserve loopback-only USB mode and four-digit USB pairing. LAN mode retains a long random token. Do not expose the service publicly or silently enable LAN.
5. Arbitrary PowerShell is not sandboxed. The HTTP API has no interactive approval gate; an agent must have user authorization for the intended task. The Android loop must continue to confirm custom `shell` commands individually. Fixed diagnostic commands and file inventory may run automatically.
6. Do not automatically toggle Android accessibility settings or bypass Android's installation/USB authorization dialogs.
7. Do not kill a user's bridge, restart ADB, install test APKs or modify device drivers merely to run a unit test. Use isolated integration tests first; physical-device tests require the device to be available for testing and no active task.
8. Avoid private ChatGPT endpoints, credential extraction and network interception. Never include bridge credentials in model prompts or logs.
9. Keep changes focused. Do not add Gradle/third-party runtime dependencies unless needed for the requested capability.

## Important invariants

- One command at a time. Same request UUID + same payload returns the existing job; same UUID + different payload is rejected. Never blindly retry an uncertain submission under a new UUID.
- Commands time out in 1–300 seconds; outputs are capped; cancellation attempts to kill the process tree but cannot undo edits.
- Downloads stay within `inbox`/`outbox`. Reject traversal, alternate data streams and reparse-point paths. Uploads get separate directories; never overwrite a user's same-named file.
- Accessibility processing is active only for an explicitly started session, and only the selected app's root is read. Each round uses a new nonce. The prompt itself must not contain a complete response marker.
- Compose UI trees can be more than 45 levels deep. Bound total visited nodes instead of using a shallow depth limit. Do not retry a send action blindly.
- A model summary is not proof of a successful repair or correct conversion. Report actual tested results and remaining uncertainty separately.

## Build and verification

Windows x64, .NET 10 SDK, Python 3.10+, JDK 17, Android platform 35/build-tools 35.0.0. Tool discovery supports `JAVA_HOME`, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and the local `toolchain/` fallback.

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -o release/staging-windows
python tests/integration.py
python android/build.py
python tests/android/build.py
python scripts/export_source.py --check
```

Use API integration tests for server/job/file changes. Build the APK for Java/resource changes. Physical-device UI claims require an actual device test; compilation is not UI validation. Documentation-only changes need links, consistency and export checks, not an unnecessary full device test.

`tests/integration.py` uses port 18766 and a temporary data directory. It must not overwrite the user's normal session. `tests/usb_device.py` uses an existing USB bridge and changes the phone; do not run it during active automation. UIAutomator/instrumentation may interrupt accessibility services on some phones; do not combine those tools with a live-loop test without accounting for that interruption.

## Releases and completion reports

Use `docs/RELEASING.md`. Generated binaries belong in GitHub Releases, not the source tree. Development keys are generated locally and excluded; stable public APK updates require the maintainer's private release key.

Before finishing, state what changed, what was actually tested, and any device or provider behavior not validated. Do not claim a full ChatGPT file-processing loop passed when only the Windows HTTP test passed. Keep `VALIDATION.md` accurate and free of personal device IDs and raw private outputs.
