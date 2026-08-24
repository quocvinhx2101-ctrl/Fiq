# Delta Spark 4.0.1 compatibility boundary

FIQ confines non-public Delta Spark dependencies to this directory.

- `DeltaLog` resolves the versioned snapshot and transaction-log segment.
- `Snapshot.tombstones` supplies real `RemoveFile` facts without reading or mutating `_delta_log`
  actions directly.
- `Snapshot.protocol` supplies the effective reader/writer protocol and table features.
- `LogSegment.checkpointProvider` supplies checkpoint and commits-since-checkpoint evidence.

These APIs are qualified only against Delta Lake 4.0.1. A later Delta version must pass the
compatibility suite before FIQ enables mutation on it.
