/**
 * Utility for executing transformations on documents
 */

export interface TransformationResult {
  success: boolean;
  document: string | object;
  error?: string;
  transformationId?: string;
}

/**
 * Executes a single transformation on a document
 */
export async function executeTransformation(
  transformation: string,
  document: string | object,
  transformationName?: string,
): Promise<TransformationResult> {
  try {
    // Execute the transformation in a controlled environment
    const transformFn = evaluateTransformation(
      transformation,
      transformationName,
    );

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

/**
 * Type definition for a document transformer function
 */
export type DocumentTransformer = (
  document: object | string,
) => object | string;

/**
 * Evaluates a transformation code string and returns the entrypoint function
 */
function evaluateTransformation(
  code: string,
  transformationName?: string,
): DocumentTransformer | null {
  try {
    // Create a custom console that prefixes logs with the transformation name
    // but only if the first argument is not already prefixed
    const customConsoleCode = transformationName
      ? `
        const originalConsole = console;
        const prefix = "[${transformationName}] ";
        console = {
          log: (...args) => {
            if (typeof args[0] === 'string' && args[0].startsWith(prefix)) {
              originalConsole.log(...args);
            } else {
              originalConsole.log(prefix, ...args);
            }
          },
          warn: (...args) => {
            if (typeof args[0] === 'string' && args[0].startsWith(prefix)) {
              originalConsole.warn(...args);
            } else {
              originalConsole.warn(prefix, ...args);
            }
          },
          error: (...args) => {
            if (typeof args[0] === 'string' && args[0].startsWith(prefix)) {
              originalConsole.warn(...args);
            } else {
              originalConsole.warn(prefix + "Error:", ...args);
            }
          },
          info: (...args) => {
            if (typeof args[0] === 'string' && args[0].startsWith(prefix)) {
              originalConsole.info(...args);
            } else {
              originalConsole.info(prefix, ...args);
            }
          },
          debug: (...args) => {
            if (typeof args[0] === 'string' && args[0].startsWith(prefix)) {
              originalConsole.debug(...args);
            } else {
              originalConsole.debug(prefix, ...args);
            }
          },
        };
      `
      : "";

    // Use Function constructor to evaluate the code safely
    // This will be replaced with isolated-vm or web workers later for better isolation
    const result = new Function(`
      ${customConsoleCode}
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
    // Use console.warn instead of console.error
    console.warn("Error evaluating transformation:", error);
    return null;
  }
}

/**
 * Executes a chain of transformations on a document
 */
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
      transformation.name,
    );

    if (!result.success) {
      result.transformationId = transformation.id;
      break;
    }

    currentDocument = result.document;
  }

  return result;
}
