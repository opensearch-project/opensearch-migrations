import { useCallback } from "react";
import { randomUUID } from "crypto";
import {
  usePlayground,
  InputDocument,
  Transformation,
} from "@/context/PlaygroundContext";
import { getJsonSizeInBytes, validateJsonContent } from "@/utils/jsonUtils";
import { MAX_TOTAL_STORAGE_BYTES, formatBytes } from "@/utils/sizeLimits";

export const usePlaygroundActions = () => {
  const { dispatch, storageSize, isQuotaExceeded } = usePlayground();

  const addInputDocument = useCallback(
    (name: string, content: string) => {
      // Check if quota is already exceeded
      if (isQuotaExceeded) {
        throw new Error(
          `Cannot add document: Local storage quota would be exceeded. Please remove some documents first.`,
        );
      }

      // Calculate size of new document
      const newDocSize = getJsonSizeInBytes(content);

      // Check if adding this document would exceed the storage limit
      if (storageSize + newDocSize > MAX_TOTAL_STORAGE_BYTES) {
        throw new Error(
          `Adding this document would exceed the maximum storage limit of ${formatBytes(MAX_TOTAL_STORAGE_BYTES)}`,
        );
      }

      const newDoc: InputDocument = {
        id: randomUUID(),
        name,
        content,
      };
      dispatch({ type: "ADD_INPUT_DOCUMENT", payload: newDoc });
      return newDoc;
    },
    [dispatch, storageSize, isQuotaExceeded],
  );

  const removeInputDocument = useCallback(
    (id: string) => {
      dispatch({ type: "REMOVE_INPUT_DOCUMENT", payload: id });
    },
    [dispatch],
  );

  const addTransformation = useCallback(
    (name: string, script: string) => {
      const newTransform: Transformation = {
        id: randomUUID(),
        name,
        content: script,
      };
      dispatch({ type: "ADD_TRANSFORMATION", payload: newTransform });
      return newTransform;
    },
    [dispatch],
  );

  const reorderTransformation = useCallback(
    (fromIndex: number, toIndex: number) => {
      dispatch({
        type: "REORDER_TRANSFORMATION",
        payload: { fromIndex, toIndex },
      });
    },
    [dispatch],
  );

  const removeTransformation = useCallback(
    (id: string) => {
      dispatch({ type: "REMOVE_TRANSFORMATION", payload: id });
    },
    [dispatch],
  );

  const updateTransformation = useCallback(
    (id: string, name: string, content: string) => {
      const updatedTransform: Transformation = {
        id,
        name,
        content,
      };
      dispatch({ type: "UPDATE_TRANSFORMATION", payload: updatedTransform });
      return updatedTransform;
    },
    [dispatch],
  );

  const updateInputDocument = useCallback(
    (id: string, name: string, content: string) => {
      // Check if quota is already exceeded
      if (isQuotaExceeded) {
        throw new Error(
          `Cannot update document: Local storage quota would be exceeded. Please remove some documents first.`,
        );
      }

      // Validate JSON content
      const validationError = validateJsonContent(content);
      if (validationError) {
        throw new Error(`Invalid JSON: ${validationError}`);
      }

      const updatedDoc: InputDocument = {
        id,
        name,
        content,
      };
      dispatch({ type: "UPDATE_INPUT_DOCUMENT", payload: updatedDoc });
      return updatedDoc;
    },
    [dispatch, isQuotaExceeded],
  );

  return {
    addInputDocument,
    removeInputDocument,
    updateInputDocument,
    addTransformation,
    reorderTransformation,
    removeTransformation,
    updateTransformation,
  };
};
