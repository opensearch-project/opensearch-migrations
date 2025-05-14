export interface TransformationResult {
  success: boolean;
  document: object;
  error?: string;
  transformationId?: string;
}

// Executes a single transformation on a document
export async function executeTransformation(
  transformation: string,
  document: object,
): Promise<TransformationResult> {
  try {
    const transformFn = evaluateTransformation(transformation);

    if (typeof transformFn !== "function") {
      return {
        success: false,
        document,
        error: "Transformation doesn't provide a valid entrypoint",
      };
    }

    const result = transformFn(document);
    return { success: true, document: result };
  } catch (error) {
    return {
      success: false,
      document,
      error: error instanceof Error ? error.message : "Unknown error",
    };
  }
}

// A DocumentTransformer is a function that takes a document (object or string)
// and returns a transformed document (object or string).
// We need all transformations to conform to this type.
export type DocumentTransformer = (document: object) => object;

// Helper function to extract the last expression from code
function getLastExpression(code: string): string {
  const lines = code.trim().split("\n");
  let lastLine = "";

  // Find the last non-comment, non-empty line
  for (let i = lines.length - 1; i >= 0; i--) {
    const trimmed = lines[i].trim();
    if (trimmed && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
      lastLine = trimmed;
      break;
    }
  }

  // Remove trailing semicolon if present
  return lastLine.endsWith(";") ? lastLine.slice(0, -1) : lastLine;
}

// Evaluates a transformation code string and returns the entrypoint function
function evaluateTransformation(code: string): DocumentTransformer | null {
  try {
    // Use Function constructor to evaluate the code safely
    // This can be replaced with isolated-vm or web workers later for better isolation
    const result = new Function(`
      ${code}

      return (document) => {
        try {
          let entrypoint;

          // First try the traditional pattern for backward compatibility
          try {
            entrypoint = (() => main)();
            if (typeof entrypoint !== 'function') {
              entrypoint = null;
            }
          } catch (e) {
            // main is not defined, continue to next approach
          }

          // If traditional pattern didn't work, try the last expression as the entrypoint
          if (!entrypoint) {
            try {
              const lastExpr = ${JSON.stringify(getLastExpression(code))};
              entrypoint = eval(lastExpr);
            } catch (e) {
              // Last expression evaluation failed
            }
          }

          if (!entrypoint) {
            // Check if we tried both approaches and neither worked
            const lastExpr = ${JSON.stringify(getLastExpression(code))};
            throw new Error("Transformation doesn't provide a valid entrypoint");
          }

          if (typeof entrypoint !== 'function') {
            throw new Error("Transformation entrypoint must be a function");
          }

          const context = {};
          const transformFn = entrypoint(context);

          if (typeof transformFn !== 'function') {
            throw new Error("Transformation doesn't return a valid document transformer function");
          }

          return transformFn(document);
        } catch (e) {
          throw e;
        }
      };
    `)();

    return result;
  } catch (error) {
    console.warn("Error evaluating transformation:", error);
    return null;
  }
}

// Executes a chain of transformations on a document
export async function executeTransformationChain(
  transformations: Array<{ id: string; name: string; content: string }>,
  document: object,
): Promise<TransformationResult> {
  let currentDocument = document;
  let result: TransformationResult = {
    success: true,
    document: currentDocument,
  };

  for (const transformation of transformations) {
    result = await executeTransformation(
      transformation.content,
      currentDocument,
    );

    if (!result.success) {
      result.transformationId = transformation.id;
      break;
    }

    currentDocument = result.document;
  }

  return result;
}
