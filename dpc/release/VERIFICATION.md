# v1.54.0 verification

Validated on Windows with Windows PowerShell 5.1, Android Emulator 37.2.12 and an Android 7.0 / API 24 AOSP x86_64 image. These results establish reference Android 7 behavior; they do not certify every manufacturer's ROM or USB driver.

- The extracted Windows package completed first-time Device Owner provisioning and a same-signature update. Its own bundled ADB was used, from a directory containing spaces. The installer verified ownership, app-control/factory-reset/safe-boot restrictions, automatic time, Doze exemption, the guard wake lock and scheduled alarms.
- `test/run-freeze-device.ps1`: 10 real DevicePolicyManager checks passed, including actual suspension/release, uninstall blocking, restrictions, automatic time and the pinned schedule timezone.
- `test/run-sync-config-device.ps1`: 15 checks passed with isolated preferences/files and no network request. They cover default-disabled sync, transaction destination pinning, separate cache/baseline namespaces, rejecting stale UI callbacks, preserved rules and rejecting configuration changes during freeze.
- `test/run-api-smoke-device.ps1`: 11 main screens opened successfully on API 24. The initial cutout-field and package-suspension-query incompatibilities were fixed before this pass.
- The maintenance APK also passed the 10 freeze checks before receiving any recovery command. It does not turn off normal enforcement merely by being installed.
- `test/run-recovery-device.ps1` before/after phases passed. A real suspended probe was released, Device Owner and the restrictions were removed, and the marker plus study data survived. The packaged Windows recovery tool verified asynchronous administrator cleanup. Uninstall and fresh provisioning subsequently succeeded without a factory reset.
- `test/deployment-parser.ps1`: 13 parser/preflight checks passed under Windows PowerShell 5.1. `test/deploy-safety.sh`: 8 mocked failure paths passed without using a device.
- The offline regression runner passed its 207 core assertions and all additional suites for rules, merges, learning, cards, quizzes, notes and tools, including the new sync target tests.
- Both APKs were verified against the fixed release certificate. The production manifest has no recovery receiver; the separate maintenance receiver requires `android.permission.DUMP`. Neither APK declares `testOnly` or `debuggable`.
- Packaging checks inspect the APK identity, minimum SDK, signing certificate, receiver isolation and embedded data for credentials / the former personal sync target. The source ZIP uses an explicit file allowlist and excludes private signing material, credentials and build products. An extracted source archive rebuilt using JDK 17, SDK build-tools 34.0.0 / platform 34 and Python, with a separate development signature.

The GitHub synchronization settings verify account/Gist ownership before enabling real network access. Live user-data synchronization was not exercised against a personal GitHub account during release tests. No physical phone was modified by this release verification.
