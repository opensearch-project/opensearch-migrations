import { z } from "zod";

/**
 * Validate a single field value against a Zod schema
 */
export function validateField<T>(
  schema: z.ZodType<T>,
  value: unknown
): { success: boolean; error?: string } {
  const result = schema.safeParse(value);
  if (result.success) {
    return { success: true };
  }
  
  // Zod 4 uses .issues instead of .errors
  const firstIssue = result.error.issues[0];
  return {
    success: false,
    error: firstIssue?.message || "Invalid value",
  };
}

/**
 * Format Zod errors into a record of field paths to error messages
 */
export function formatZodError(error: z.ZodError): Record<string, string> {
  const errors: Record<string, string> = {};
  
  // Zod 4 uses .issues instead of .errors
  for (const issue of error.issues) {
    const path = issue.path.join(".");
    if (!errors[path]) {
      errors[path] = issue.message;
    }
  }
  
  return errors;
}

/**
 * Extract a specific field error from a ZodSafeParseResult
 */
export function getFieldError<T>(
  result: z.ZodSafeParseResult<T>,
  path: string
): string | undefined {
  if (result.success) {
    return undefined;
  }
  
  const pathParts = path.split(".");
  
  // Zod 4 uses .issues instead of .errors
  for (const issue of result.error.issues) {
    const issuePath = issue.path.join(".");
    if (issuePath === path || issuePath.startsWith(path + ".")) {
      return issue.message;
    }
    
    // Check if the issue path matches any part of the requested path
    if (pathParts.length > 0) {
      const issuePathStr = issue.path.join(".");
      if (path.startsWith(issuePathStr)) {
        return issue.message;
      }
    }
  }
  
  return undefined;
}

/**
 * Validate the entire form and return all errors
 */
export function validateForm<T>(
  schema: z.ZodType<T>,
  data: unknown
): { success: boolean; data?: T; errors: Record<string, string> } {
  const result = schema.safeParse(data);
  
  if (result.success) {
    return {
      success: true,
      data: result.data,
      errors: {},
    };
  }
  
  return {
    success: false,
    errors: formatZodError(result.error),
  };
}

/**
 * Get error text for Cloudscape FormField component
 * Returns empty string if no error (Cloudscape expects string, not undefined)
 */
export function getFormFieldError(
  errors: Record<string, string>,
  fieldPath: string
): string {
  return errors[fieldPath] || "";
}

/**
 * Check if a field has an error
 */
export function hasFieldError(
  errors: Record<string, string>,
  fieldPath: string
): boolean {
  return fieldPath in errors;
}
