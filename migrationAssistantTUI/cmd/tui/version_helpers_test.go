package main

// version_helpers_test.go — test-only helper. Lives in _test.go so
// the production binary doesn't link "testing" (which would balloon
// binary size and surface t.* APIs in main).

import "testing"

// setVersionsForTest swaps Version + MAVersion for the duration of
// a single test, restoring them on Cleanup. Use this in any test
// that observes the package vars directly — never mutate them by
// hand: a panicking test would leave the next test running against
// a poisoned global.
func setVersionsForTest(t *testing.T, ver, maVer string) {
	t.Helper()
	origV, origMA := Version, MAVersion
	Version = ver
	MAVersion = maVer
	t.Cleanup(func() {
		Version = origV
		MAVersion = origMA
	})
}
