# Validation - 1.0.0

Built October 22, 2026 against Prism 4.4 and FAWE 2.16.1 with JDK 25.

`gradlew clean build` passed. All **15 tests passed** (7 batch/queue, 6 action/recording, 2 listener). The JAR was inspected for its plugin descriptor, config and bridge classes, and contains no bundled Prism or FAWE implementation classes.

Independent Java and general code reviews identified issues in the initial approach. The final design uses one reversible record per position, public Prism action deserialization, pre-commit snapshots plus post-commit publication, complete tile-data replacement, and explicit skips for unsafe native multi-block reversal. The known limitations are listed in README.md.

These are source/API and automated checks. They do not prove live Paper startup, client visibility, every FAWE command, in-game NBT behavior, or database round-trip/rollback/restore acceptance. Test the small acceptance cases in README.md on a disposable world before production use.

`downloads/SHA256SUMS.txt` identifies the packaged JAR. Accepted recording counters mean queued into Prism, not durable database writes.
