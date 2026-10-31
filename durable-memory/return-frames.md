# Invocation return frames

## Contract

For one `--dsl` invocation, `Main` binds a fresh Java SDK `ResultSink` to the
registry before the body. It remains bound through the selected rule entries;
it is not reset between body and entries.

## Output ordering

Existing trace and entry-result lines remain unchanged. The JSON diagnostic
line is followed by the terminal return frame only after all dispatch and the
ledger application check succeed. Failed load/run/entry/application paths emit
no frame, while a program that does not write `$return` emits `null`.

## SDK synchronization and verification

`scripts/sync-sdk.sh` vendors the complete Java SDK source tree and updates
`sdk.lock`; keep it paired with the runtime commit. Canonical programs 360–368
and `sample-suite/run-suite.lms` verify the terminal bytes and no-frame failure
cases.
