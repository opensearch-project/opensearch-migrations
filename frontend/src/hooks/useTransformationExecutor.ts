import { useCallback, useEffect, useState } from "react";
import { v4 as uuidv4 } from "uuid";
import {
  usePlayground,
  OutputDocument,
  TransformationError,
} from "@/context/PlaygroundContext";
import { executeTransformationChain } from "@/utils/transformationExecutor";

/**
 * Hook for executing transformations on input documents
 */
export function useTransformationExecutor() {
  const { state, dispatch } = usePlayground();
  // Track validation errors for transformations
  const [validationErrors, setValidationErrors] = useState<
    Record<string, boolean>
  >({});

  // Method to update validation errors
  const updateValidationError = useCallback((id: string, hasError: boolean) => {
    setValidationErrors((prev) => ({
      ...prev,
      [id]: hasError,
    }));
  }, []);

  // Check if any transformation has validation errors
  const hasValidationErrors = useCallback(() => {
    return Object.values(validationErrors).some((hasError) => hasError);
  }, [validationErrors]);

  /**
   * Run all transformations on all input documents
   */
  const runTransformations = useCallback(async () => {
    if (
      state.inputDocuments.length === 0 ||
      state.transformations.length === 0
    ) {
      // Nothing to do
      return;
    }

    dispatch({ type: "START_TRANSFORMATIONS", payload: undefined });

    const outputDocuments: OutputDocument[] = [];
    const errors: TransformationError[] = [];

    // Process each input document through the transformation chain
    for (const inputDoc of state.inputDocuments) {
      try {
        // Parse the input document
        const inputContent = JSON.parse(inputDoc.content);

        // Execute all transformations in sequence
        const result = await executeTransformationChain(
          state.transformations,
          inputContent,
        );

        if (result.success) {
          // Create output document
          outputDocuments.push({
            id: uuidv4(),
            name: inputDoc.name,
            content: JSON.stringify(result.document, null, 2),
            sourceInputId: inputDoc.id,
            transformationsApplied: state.transformations.map((t) => t.id),
          });
        } else {
          // Record error
          errors.push({
            documentId: inputDoc.id,
            transformationId: result.transformationId || "",
            message: result.error || "Unknown error",
          });
        }
      } catch (error) {
        // Use console.warn instead of console.error
        console.warn(`Error processing document ${inputDoc.id}:`, error);
        errors.push({
          documentId: inputDoc.id,
          transformationId: "",
          message: error instanceof Error ? error.message : "Unknown error",
        });
      }
    }

    // Update state with results
    dispatch({
      type: "COMPLETE_TRANSFORMATIONS",
      payload: { outputDocuments, errors },
    });
  }, [state.inputDocuments, state.transformations, dispatch]);

  // Disable automatic transformation execution to prevent UI "snapping"
  // The user can run transformations manually using the "Run transformations" button
  // or by saving a transformation with Ctrl+S

  // Uncomment this to re-enable automatic execution
  /*
  useEffect(() => {
    if (!hasValidationErrors()) {
      runTransformations();
    }
  }, [state.inputDocuments, state.transformations, runTransformations, hasValidationErrors]);
  */

  return {
    runTransformations,
    isProcessing: state.isProcessingTransformations,
    errors: state.transformationErrors,
    updateValidationError,
    hasValidationErrors: hasValidationErrors(),
  };
}
