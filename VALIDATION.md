# Validation - 1.0.1

Tested October 22, 2026 using Java 25.0.4.1, Paper **26.3 build 214** (`e65c076`), stock **Prism 4.4**, **FAWE 2.16.1+9e5e49e**, and **NBTAPI 2.16.1**. The server binds only to `127.0.0.1:25577` and uses the disposable `bridge-test-world`.

`gradlew clean build integrationHarnessJar` passed. All **16 unit tests passed**: 7 batch/queue, 6 action/recording, 2 listener, and 1 typed binary NBT transfer test. Java and general code reviews found no remaining blockers in this change.

The live harness passed **17 checks**, recorded in [integration-tests/results/paper-26.3.txt](integration-tests/results/paper-26.3.txt). It exercised real FAWE console commands `//set`, `//replace`, `//undo`, `//redo`, `//cut`, and `//paste -o`. It checked actual world blocks and queried Prism's SQLite-backed API for persisted `fawe-change` rows, then rolled back and restored the selected database activity IDs through stock Prism.

Verified cases: negative coordinates, no duplicate record for a no-op set, stone/diamond/gold replacements, cut and pasted chest contents, same-type chest NBT changes including exact custom-name removal/restoration, and same-state sign text with numeric, boolean-like and empty string lines. Final bridge status: pending **0**, accepted activities **8**, not accepted **0**, overflow **0**, errors **0**, non-queued sessions **0**. A separate ordinary Prism console lookup also displayed the saved records.

The first live run exposed a real defect: LinStringIO wrote empty sign lines as unquoted empty values, which Minecraft rejected during reversal. Version 1.0.1 transfers detached tile tags through typed binary NBT to the existing NBTAPI/native writer before persisting their SNBT. The repeated live checks passed both sign rollback and restore, including numeric text staying text. No Prism or FAWE patches, database schema changes, or new runtime dependencies were needed.

The unit NBT test verifies lossless binary transfer into a mocked native reader; the live sign checks are the evidence for actual native serialization. The harness invokes Prism's public modification API, not its in-game rollback/restore command UI. The real-player path, player UUID attribution at runtime, client packets/visibility without relogging, large edits/throughput, and every material/command option remain untested. Existing coverage limits, including unsafe multi-block snapshot reversal skips, remain in [README.md](README.md).

The Windows host emitted unrelated OS performance-counter warnings at startup. These did not prevent plugin startup or completion of the checks.

`downloads/SHA256SUMS.txt` identifies the packaged JAR. Accepted activity counters mean queued into Prism; the live tests separately checked database readback.
