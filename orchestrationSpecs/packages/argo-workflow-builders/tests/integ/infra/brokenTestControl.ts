const includeBroken = process.env.PARITY_INCLUDE_BROKEN === "1";

/**
 * Marks tests as known-broken by default while allowing opt-in execution.
 * Run with PARITY_INCLUDE_BROKEN=1 to execute these suites.
 */
export function describeBroken(name: string, suite: () => void) {
  const title = `[broken] ${name}`;
  if (includeBroken) {
    return describe(title, suite);
  }

  // Register tests as pending (instead of skipping the whole suite) so the parity
  // reporter can classify them as known-broken skipped coverage.
  return describe(title, () => {
    const originalTest: any = (globalThis as any).test;
    const originalIt: any = (globalThis as any).it;

    (globalThis as any).test = originalTest.skip.bind(originalTest);
    (globalThis as any).it = originalIt.skip.bind(originalIt);
    try {
      suite();
    } finally {
      (globalThis as any).test = originalTest;
      (globalThis as any).it = originalIt;
    }
  });
}
