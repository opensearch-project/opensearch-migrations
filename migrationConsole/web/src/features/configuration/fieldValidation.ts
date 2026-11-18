export function fieldValidationProblem(
  value: string,
  pattern?: string,
  message?: string,
): string {
  if (!value || !pattern) return "";
  try {
    return new RegExp(`^(?:${pattern})$`).test(value)
      ? ""
      : message || `Use a value matching ${pattern}.`;
  } catch {
    return "";
  }
}
