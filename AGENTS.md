# Repository instructions

- This project is developed from Termux, where Android SDK 36 is not installed.
- Do not treat a local `SDK location not found` error as a project build failure.
- After changing buildable source, verify formatting locally, then commit and push the change to trigger `.github/workflows/build.yml`.
- Use the GitHub Actions `Build` workflow as the authoritative APK compilation check, inspect the failed step and logs, and keep fixing until it succeeds.
- Device/LSPosed diagnostic captures such as `gms-debug.log` and `lspd-log/` are local artifacts and must not be committed.
