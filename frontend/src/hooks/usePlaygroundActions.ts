import { useCallback } from "react";
import { v4 as uuidv4 } from "uuid";
import {
  usePlayground,
  InputDocument,
  Transformation,
} from "@/context/PlaygroundContext";
import { getJsonSizeInBytes } from "@/utils/jsonUtils";
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
        id: uuidv4(),
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
        id: uuidv4(),
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

  return {
    addInputDocument,
    removeInputDocument,
    addTransformation,
    reorderTransformation,
  };
};
