import { useCallback } from "react";
import { v4 as uuidv4 } from "uuid";
import {
  usePlayground,
  InputDocument,
  Transformation,
} from "../context/PlaygroundContext";

export const usePlaygroundActions = () => {
  const { dispatch } = usePlayground();

  const addInputDocument = useCallback(
    (name: string, content: string) => {
      const newDoc: InputDocument = {
        id: uuidv4(),
        name,
        content,
      };
      dispatch({ type: "ADD_INPUT_DOCUMENT", payload: newDoc });
      return newDoc;
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
    addTransformation,
    reorderTransformation,
  };
};
