import { useCallback, useEffect } from "react";
import { randomUUID } from "crypto";
import {
  usePlayground,
  OutputDocument,
  TransformationError,
} from "@/context/PlaygroundContext";
import { executeTransformationChain } from "@/utils/transformationExecutor";

export function useTransformationExecutor() {
  const { state, dispatch } = usePlayground();

  const runTransformations = useCallback(async () => {
    if (
      state.inputDocuments.length === 0 ||
      state.transformations.length === 0
    ) {
      return;
    }

    dispatch({ type: "START_TRANSFORMATIONS", payload: undefined });

    const outputDocuments: OutputDocument[] = [];
    const errors: TransformationError[] = [];

    // Process each input document through the transformation chain
    for (const inputDoc of state.inputDocuments) {
      try {
        const inputContent = JSON.parse(inputDoc.content);

        const result = await executeTransformationChain(
          state.transformations,
          inputContent,
        );

        if (result.success) {
          // Create output document
          outputDocuments.push({
            id: randomUUID(),
            name: inputDoc.name,
            content: JSON.stringify(result.document),
            sourceInputId: inputDoc.id,
            // For now, this just lists all the transformations
            // Later, we can track which transformations had an effect
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
        console.warn(`Error processing document ${inputDoc.id}:`, error);
        errors.push({
          documentId: inputDoc.id,
          transformationId: "",
          message: error instanceof Error ? error.message : "Unknown error",
        });
      }
    }
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
