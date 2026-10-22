# Prism 4.4 overlapping rollback fix

Stock Prism 4.4 can process overlapping history in the wrong order. For example: FAWE places stone, the player breaks that stone, and a rollback selects both actions. The expected final state is air. Stock Prism can remove the FAWE block first and then restore the later break, leaving stone behind.

The fault is in Prism's streamed SQL queries. Key selection and batch refetch use material/coordinate priorities without a consistent time order. The fix orders all three ungrouped fetch paths by timestamp and activity ID: newest first for rollback, oldest first for restore. Grouped lookup behavior is unchanged. This also prevents a limit or batch boundary from selecting/replaying a different history order.

## Install

1. Stop the server and back up the Prism JAR, database, and world.
2. Replace the existing Prism JAR with [`prism-paper-v4.4-chronology-fix.jar`](../../downloads/prism-paper-v4.4-chronology-fix.jar). Keep only one Prism JAR in `plugins/`.
3. Keep `PrismFAWEBridge-1.0.1.jar`, FAWE, and NBTAPI installed, and start the server.
4. Test a fresh WorldEdit followed by a normal break, selecting both with `/pr rb p:YourName since:5m r:16`.

This is an optional **custom Prism 4.4 build**. It still reports version 4.4. The bridge is unchanged and continues to use public APIs. No database migration is included. The patch corrects ordering of saved records; it cannot recreate missing records or edits made before logging was enabled. Do not blindly replay an old rollback against a world that has since changed.

## Source and rebuild

[`chronological-modifications.patch`](chronological-modifications.patch) contains the complete production change and eight database regression tests. It applies to Prism v4.4 at commit `9a3407a1d650e6ae9fca6d770a1109951beea977`. Prism and this patch are GPL-3.0 licensed; the repository includes that license. Original source: <https://github.com/prism/prism>.

With Git, JDK 21, Node.js and pnpm available, use a fresh checkout:

```text
git clone https://github.com/prism/prism.git prism-chronology
cd prism-chronology
git checkout --detach 9a3407a1d650e6ae9fca6d770a1109951beea977
git apply /path/to/chronological-modifications.patch
./gradlew test :prism-core:checkstyleMain :prism-core:checkstyleTest :prism-paper-loader:shadowJar --no-daemon
```

On Windows use `gradlew.bat` and set `JAVA_HOME` to JDK 21. The full build includes Prism's web interface. Output: `prism-paper-loader/build/libs/prism-paper-v4.4.jar`. The Minecraft test runtime used JDK 25, as required by current FAWE.

The patch is pinned to 4.4. For a future Prism update, first check whether upstream fixed the ordering issue; do not assume this JAR or patch automatically follows new releases.

## Verification

All eight SQLite regression tests failed against the original production code and passed after the change. Both modified module Checkstyle tasks passed. The complete `test :prism-paper-loader:shadowJar` build passed, including the web build.

Live checks used localhost-only Paper 26.3 build 209, Ignite 1.2.1, FAWE 2.16.1, NBTAPI 2.16.1, bridge 1.0.1 and a disposable `global-check-world`. Real Prism console rollback/restore commands reproduced leftover stone on stock Prism, then correctly processed both overlapping actions with the patch. The FAWE edit was real; the later native `block-break` row was a controlled database fixture paired with a real world-block removal. This does not prove an actual player's event recording, permissions, or client view on another server.

A 16,384-block FAWE edit was persisted, restored and rolled back through Prism's streaming path; the final rollback applied all 16,384 changes with zero skips, and the entire region matched an untouched reference copy. A support block and two weeping-vine blocks also passed rollback and restore. These are bounded checks, not proof for every material or database backend. See [command evidence](../../integration-tests/results/prism-4.4-chronology.txt).
