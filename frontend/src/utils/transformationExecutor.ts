export interface TransformationResult {
  success: boolean;
  document: string | object;
  error?: string;
  transformationId?: string;
}

// Executes a single transformation on a document
export async function executeTransformation(
  transformation: string,
  document: string | object,
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
export type DocumentTransformer = (
  document: object | string,
) => object | string;

// Evaluates a transformation code string and returns the entrypoint function
function evaluateTransformation(code: string): DocumentTransformer | null {
  try {
    // Use Function constructor to evaluate the code safely
    // This can be replaced with isolated-vm or web workers later for better isolation
    // The way of extracting the entrypoint function is a bit specific to our default transformation format,
    // this could be broadened.
    const result = new Function(`
      ${code}
      return (document) => {
        try {
          const entrypoint = ${code.includes("(() => main)()") ? "(() => main)()" : "null"};
          if (typeof entrypoint !== 'function') {
            throw new Error("Transformation doesn't provide a valid entrypoint");
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
  document: object | string,
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
