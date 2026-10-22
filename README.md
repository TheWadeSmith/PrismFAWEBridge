# Prism FAWE Bridge

An extra Paper plugin that records FastAsyncWorldEdit block changes in **stock Prism**. No patched Prism or FAWE JAR is needed.

Built against **Prism 4.4**, **FAWE 2.16.1**, and Paper's 1.21.11 API. **Java 25 is required by current FAWE.** Uses Prism's published Paper API service and FAWE's public batch processor API, with no reflection or imports from Prism's implementation. This reduces update coupling, but future breaking API changes can still require a bridge update.

## Install

1. Install Prism 4.4+, FAWE, and NBT-API normally on a Paper server.
2. Put `PrismFAWEBridge-1.0.1.jar` in `plugins/` and restart the server. Remove any older bridge JAR first.
3. Keep Prism's own `actions.worldedit-break` and `actions.worldedit-place` **false** (their defaults). This bridge records its own `fawe-change` action; leaving the old hook enabled can cause warnings or duplicate recording.
4. Run `/prismfawe` as an operator to check the bridge counters.

The JAR is available in this repository's `downloads/` folder. GitHub Actions also builds it under the **Actions** tab.

The bridge does not change Prism's files, database schema, FAWE settings, permissions, history, or lighting/packet processors. FAWE stays on its native queued edit path.

## Using Prism

Use the normal Prism inspection tools, or search the bridge action type:

```text
/pr lookup a:fawe-change p:YourName since:5m r:16
```

From the server console, supply a reference location explicitly, for example `pr lookup a:fawe-change world:YourWorld at:0,80,0 r:16 since:5m`.

Each changed position is **one record**, containing complete before/after snapshots. The bridge's public modification handler restores the selected snapshot through Prism's native block actions, including available chest contents and sign text. This avoids relying on the order of a separate break/place pair.

```text
/pr rollback a:fawe-change p:YourName since:5m r:16
/pr restore a:fawe-change p:YourName since:5m r:16
```

Always narrow the player/time/radius to the intended edit. Review Prism's results before using rollback or restore in a real world. The bridge cannot recover edits made before it was installed.

## Coverage and limits

- Captures committed **queued block changes** from FAWE. Live console-command checks passed for `//set`, `//replace`, `//paste`, `//cut`, `//undo`, and `//redo` on Paper 26.3 with stock Prism 4.4 and FAWE 2.16.1. This is a small disposable-world test, not coverage of every command option or large edits.
- Preserves coordinates, block properties, player UUID/name or non-player cause, capture time, and available block-entity NBT. NBT-only tile writes are included.
- **Does not record entities, biome-only changes, direct NMS writes, regeneration outside FAWE's queue, or WNA/direct-world mode.** Non-queued edit sessions produce a warning and a status counter, rather than changing how FAWE edits the world.
- Prism's filters still apply. Its default `ignore-creative=false` is suitable for builders; enabling it can suppress creative-player records.
- Prism owns database ordering, modification selection, queue limits, and persistence. Narrow rollback/restore to the intended edit; selecting multiple overlapping edits also depends on Prism's ordering. Tile snapshots bypass Prism's properties-only "already set" shortcut so NBT-only changes can be restored; all other modification rules are preserved.
- **Beds, doors, and other double-height blocks are logged, but reversal that places those block snapshots is explicitly skipped.** Stock Prism synthesizes neighboring halves, which is unsafe for FAWE's per-position records. This initial bridge does not claim complete rollback coverage for those materials; inspect the Prism modification report. Ordinary stairs and trapdoors are supported.
- Before-image snapshots are captured at FAWE's final read-only processing stage and published after its commit callback. The bounded committed-change queue hands snapshots to the Paper main thread. Defaults: 100,000 queued changes, at most 1,000 changes converted per tick. In-flight snapshots also use memory while FAWE completes its native chunk writes. Edit completion may precede logging completion; wait for `/prismfawe` pending to reach zero and for Prism's own queue to drain before querying/rolling back.
- The bridge never blocks a FAWE worker waiting on the main thread. If the bridge queue fills, the lost-change count is logged at **SEVERE** and shown by `/prismfawe`. Reduce edit size or tune queue/drain settings for your server. A full Prism queue can also reject records; Prism reports its own dropped records.
- `accepted activities` means Prism accepted the activity into its queue, **not** that it is safely on disk. `not accepted` includes Prism filtering as well as Prism queue rejection.
- An abrupt crash can lose snapshots not yet handed to Prism. Normal shutdown closes queue admission, unregisters the hook and drains pending snapshots for up to five seconds; any remainder or late callback is reported. Avoid disabling/reloading plugins during an edit. Folia is not supported by this bridge.

Keep this plugin installed if you need Prism to recognize and reverse its custom action types.

## Build and verification

Install JDK 25, then run:

```text
./gradlew clean build
```

Output: `build/libs/PrismFAWEBridge-1.0.1.jar`. Dependencies are compile-only and are not bundled; the server supplies Prism, FAWE, Paper, and NBT-API.

All 16 unit tests and 17 live server checks passed. Live checks verify database-backed Prism lookup, rollback and restore, chest contents and names, and sign text including blank and numeric lines. Version 1.0.1 fixes a sign rollback failure found by those checks by using Minecraft's native NBT writer for tile snapshots. See [VALIDATION.md](VALIDATION.md) and [the test harness](integration-tests/README.md) for exact coverage and reproduction steps. Player UUID attribution is covered by unit tests; actual player sessions, client visibility without relogging, and large-edit performance remain untested.
