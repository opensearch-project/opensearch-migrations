// Package workdir owns path conventions and atomic on-disk operations
// for the migration-assistant TUI's workdir tree.
//
// The workdir is a directory created adjacent to the operator's CWD,
// named deterministically from the AWS account + region pair (UX §0.4):
//
//	./opensearch-migration-<account>-<region>/
//
// Two accounts or regions never share a workdir — that is the load-bearing
// safety property. If an operator runs the TUI in two terminals against
// two accounts, the workdirs are siblings and the resume logic loads
// each session's state independently.
//
// This package owns:
//
//   - Path computation: Path(parent, account, region) -> string.
//
//   - Workdir detection: Detect(parent, account, region) reports whether
//     the conventional workdir already exists, and whether its
//     .ma-state.json (if present) claims the same account/region. A
//     collision (existing workdir whose state belongs to a different
//     account/region) is hard-rejected — never silently overwritten.
//
//   - Atomic writes: WriteFileAtomic(path, data, perm) writes through
//     a temp file in the same directory, fsyncs, then renames. If the
//     operator Ctrl+C's mid-write, the destination is either fully
//     updated or untouched — never half-written.
//
//   - State file marshaling: ReadState / WriteState handle the JSON
//     .ma-state.json file with the atomic-write contract above.
//
// What this package does NOT own:
//
//   - The artifact CAS (.cache/<sha256>/<name>) — that's marelease (f7),
//     which uses workdir's path helpers but enforces SHA verification
//     itself.
//
//   - Workdir lifecycle decisions (resume vs. fresh) — that's launch (f5),
//     which composes Detect with versioncheck.
package workdir
