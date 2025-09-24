export function withTimeLimit<T>(
  promise: Promise<T>,
  timeoutMs: number,
): Promise<T> {
  return Promise.race([
    promise,
    new Promise<never>((_, reject) =>
      setTimeout(
        () => reject(new Error(`Request timed out in ${timeoutMs}ms`)),
        timeoutMs,
      ),
    ),
  ]);
}
