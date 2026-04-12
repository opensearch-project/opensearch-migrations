import type { ChecksumDependency } from '@opensearch-migrations/schemas';

export type ChangeClass = 'safe' | 'gated' | 'impossible';

export type DependencyPattern =
  | 'focus-change'
  | 'immediate-dependent-change'
  | 'transitive-dependent-change'
  | 'focus-gated-change'
  | 'immediate-dependent-gated-change'
  | 'immediate-dependent-impossible-change';

export type ChecksumReportEntry = {
  kind: string;
  resourceName: string;
  configChecksum: string;
  downstreamChecksums: Partial<Record<ChecksumDependency, string>>;
  dependsOn: string[];
};

export type ChecksumReport = {
  components: Record<string, ChecksumReportEntry>;
};

export type DerivedSubgraph = {
  focus: string;
  immediateDependents: string[];
  transitiveDependents: string[];
  upstreamPrerequisites: string[];
  independent: string[];
};

export type ApprovedMutator<C = Record<string, unknown>> = {
  id: string;
  path: string;
  changeClass: ChangeClass;
  patterns: DependencyPattern[];
  apply: (baseConfig: C) => C;
  rationale: string;
};

export type MatrixSelector = {
  changeClass: ChangeClass;
  patterns: DependencyPattern[];
  requireFullCoverage?: boolean;
};

export type MatrixSpec = {
  focus: string;
  select: MatrixSelector[];
};

export type ScenarioSpec = {
  name: string;
  base: string;
  matrix?: MatrixSpec[];
};

export type ExpandedTestCase = {
  name: string;
  focus: string;
  pattern: DependencyPattern;
  changeClass: ChangeClass;
  mutatorId: string;
  baselineChecksumReport: ChecksumReport;
  mutatedConfig: Record<string, unknown>;
  mutatedChecksumReport: ChecksumReport;
  expect: ExpandedExpectation;
};

export type ExpandedExpectation = {
  reran: string[];
  unchanged: string[];
  blockedOn?: Record<string, string[]>;
};

/**
 * Runtime expectation for a single run within a test.
 * - allCompleted: baseline run — every component should reach Ready/Completed
 * - allSkipped: noop run — every component checksum unchanged from prior
 * - reran/unchanged: mutation run — specific components changed, others didn't
 */
export type RunExpectation =
  | { mode: 'allCompleted' }
  | { mode: 'allSkipped' }
  | { mode: 'selective'; reran: string[]; unchanged: string[]; blockedOn?: Record<string, string[]> };

export type ComponentObservation = {
  kind: string;
  resourceName: string;
  phase: string;
  configChecksum: string;
};

export type ScenarioObservation = {
  scenario: string;
  run: number;
  runName: string;
  observedAt: string;
  resources: Record<string, ComponentObservation>;
};

export type TestCaseResult = {
  name: string;
  focus: string;
  pattern: DependencyPattern;
  changeClass: ChangeClass;
  mutatorId: string;
  status: 'passed' | 'failed';
  expect: ExpandedExpectation;
  observed: Record<string, { phase: string; changed: boolean }>;
  failures?: string[];
};

export type ScenarioReport = {
  scenario: string;
  status: 'passed' | 'failed';
  summary: { generated: number; passed: number; failed: number };
  expandedTests: TestCaseResult[];
  coverage: {
    selectedCases: string[];
    expandedCases: string[];
    uncoveredCases: string[];
  };
};
