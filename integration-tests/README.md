# Disposable Paper server checks

This is a separate **test plugin**, not part of the bridge JAR. Do not install it on a production server: running `bridgecheck` clears blocks at Y=80 within X/Z -40..-16, then changes fixed test positions there.

1. With JDK 25, run `gradlew clean build integrationHarnessJar` from the repository root.
2. Prepare a disposable Paper 26.3 server with stock Prism 4.4, FAWE 2.16.1, NBTAPI 2.16.1 and `PrismFAWEBridge-1.0.1.jar`. Keep Prism's own `worldedit-break` and `worldedit-place` actions disabled. Configure Prism to use SQLite normally.
3. Set `server-ip=127.0.0.1`, `server-port=25577`, `level-name=bridge-test-world`, and `pause-when-empty-seconds=-1` in that test server's `server.properties`.
4. Copy `build/libs/PrismFAWEBridge-1.0.1-integration-harness.jar` into its `plugins/` directory and start the server.
5. Run `bridgecheck` from the server console. It refuses to run unless the exact test-world name and localhost binding are present.
6. Read `plugins/PrismFAWEIntegrationTests/results.txt` and the server log. Success is `LIVE CHECKS COMPLETE: 17 PASS`, with no FAIL lines. Run `prismfawe` to check for pending changes or dropped/failed recordings.

FAWE commands are dispatched from the console with a one-block WorldEdit selection and a placement position explicitly set to position 1. Additional same-type tile tests use FAWE's queued edit-session API. World inspection and Bukkit fixture creation run on the server thread; FAWE queued API edits and polling run asynchronously. Prism's public API handles its own database and modification scheduling.

Each lookup is constrained to the exact position, world, `fawe-change` action, and current run's epoch-second time window. Rollback and restore select the returned persisted activity ID. A second run resets the test positions and ignores older records.

These checks do not connect a Minecraft player client. See [../VALIDATION.md](../VALIDATION.md) for coverage and limits.
