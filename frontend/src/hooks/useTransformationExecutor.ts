import { useCallback, useEffect } from "react";
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
            transformationId: result.transformationId ?? "",
            message: result.error ?? "Unknown error",
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

  // Enable automatic transformation execution when transformations are saved
  useEffect(() => {
    runTransformations();
  }, [state.inputDocuments, state.transformations, runTransformations]);

  return {
    runTransformations,
    isProcessing: state.isProcessingTransformations,
    errors: state.transformationErrors,
  };
}
