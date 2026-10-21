import { check } from 'k6';
import { randomBulkBatch } from '../lib/doc-utils.js';
import { generateId } from '../lib/sequences.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const runnerOne = {
    vu: { idInInstance: 1, idInTest: 1 },
    scenario: { iterationInTest: 7 },
  };
  const runnerTwo = {
    vu: { idInInstance: 1, idInTest: 2 },
    scenario: { iterationInTest: 7 },
  };
  const bulk = randomBulkBatch(
    'test-index',
    2,
    () => ({ value: 1 }),
    (item) => generateId(runnerOne, 'bulk', item),
  );
  const actions = bulk.body.trim().split('\n').filter((_, i) => i % 2 === 0).map(JSON.parse);
  check(null, {
    'distributed runner contexts produce different sequence IDs': () =>
      generateId(runnerOne) !== generateId(runnerTwo),
    'bulk items receive distinct replay-stable IDs': () =>
      actions[0].index._id === 'bulk-1-7-0' &&
      actions[1].index._id === 'bulk-1-7-1',
  });

  const { body } = randomBulkBatch(
    'test-index',
    2,
    () => ({ value: 1 }),
    (item) => `run-a-bulk-1-7-${item}`,
  );
  const stableActions = body.trim().split('\n').filter((_, line) => line % 2 === 0).map(JSON.parse);
  check(stableActions, {
    'bulk IDs are stable and distinct within a logical request': (items) =>
      items[0].index._id === 'run-a-bulk-1-7-0' &&
      items[1].index._id === 'run-a-bulk-1-7-1',
  });
}
