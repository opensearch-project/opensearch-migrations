/**
 * Shared document generation utilities used by all schema modules in lib/data/.
 */

export function randomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function randomFloat(min, max) {
  return Math.random() * (max - min) + min;
}

/**
 * Build a newline-delimited _bulk request body.
 * Returns { body: string, docCount: number }.
 */
export function randomBulkBatch(index, batchSize, randomDocFn) {
  const lines = [];
  for (let i = 0; i < batchSize; i++) {
    lines.push(JSON.stringify({ index: { _index: index } }));
    lines.push(JSON.stringify(randomDocFn()));
  }
  lines.push(''); // _bulk requires a trailing newline
  return { body: lines.join('\n'), docCount: batchSize };
}
