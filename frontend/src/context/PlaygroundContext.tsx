"use client";

import React, {
  createContext,
  useContext,
  useReducer,
  useEffect,
  useMemo,
} from "react";

export interface InputDocument {
  id: string;
  name: string;
  content: string;
}

export interface Transformation {
  id: string;
  name: string;
  content: string;
}

export interface OutputDocument {
  id: string;
  name: string;
  content: string;
  sourceInputId: string;
  transformationsApplied: string[]; // ids of all transformations that changed the document
}

export interface PlaygroundState {
  inputDocuments: InputDocument[];
  transformations: Transformation[];
  outputDocuments: OutputDocument[];
}

const initialState: PlaygroundState = {
  inputDocuments: [],
  transformations: [],
  outputDocuments: [],
};

type ActionType =
  | { type: "ADD_INPUT_DOCUMENT"; payload: InputDocument }
  | { type: "UPDATE_INPUT_DOCUMENT"; payload: InputDocument }
  | { type: "REMOVE_INPUT_DOCUMENT"; payload: string }
  | { type: "ADD_TRANSFORMATION"; payload: Transformation }
  | { type: "UPDATE_TRANSFORMATION"; payload: Transformation }
  | { type: "REMOVE_TRANSFORMATION"; payload: string }
  | {
      type: "REORDER_TRANSFORMATION";
      payload: { fromIndex: number; toIndex: number };
    }
  | { type: "ADD_OUTPUT_DOCUMENT"; payload: OutputDocument }
  | { type: "UPDATE_OUTPUT_DOCUMENT"; payload: OutputDocument }
  | { type: "REMOVE_OUTPUT_DOCUMENT"; payload: string }
  | { type: "CLEAR_OUTPUT_DOCUMENTS"; payload?: undefined }
  | { type: "SET_STATE"; payload: Partial<PlaygroundState> };

const playgroundReducer = (
  state: PlaygroundState,
  action: ActionType,
): PlaygroundState => {
  switch (action.type) {
    case "ADD_INPUT_DOCUMENT":
      return {
        ...state,
        inputDocuments: [...state.inputDocuments, action.payload],
      };
    case "UPDATE_INPUT_DOCUMENT":
      return {
        ...state,
        inputDocuments: state.inputDocuments.map((doc) =>
          doc.id === action.payload.id ? action.payload : doc,
        ),
      };
    case "REMOVE_INPUT_DOCUMENT":
      return {
        ...state,
        inputDocuments: state.inputDocuments.filter(
          (doc) => doc.id !== action.payload,
        ),
        outputDocuments: [],
      };
    case "ADD_TRANSFORMATION":
      return {
        ...state,
        transformations: [...state.transformations, action.payload],
        outputDocuments: [],
      };
    case "UPDATE_TRANSFORMATION":
      return {
        ...state,
        transformations: state.transformations.map((transform) =>
          transform.id === action.payload.id ? action.payload : transform,
        ),
        outputDocuments: [],
      };
    case "REMOVE_TRANSFORMATION":
      return {
        ...state,
        transformations: state.transformations.filter(
          (transform) => transform.id !== action.payload,
        ),
        outputDocuments: [],
      };
    case "REORDER_TRANSFORMATION": {
      const { fromIndex, toIndex } = action.payload;
      const newTransformations = [...state.transformations];
      const [movedItem] = newTransformations.splice(fromIndex, 1);
      newTransformations.splice(toIndex, 0, movedItem);
      return {
        ...state,
        transformations: newTransformations,
        outputDocuments: [],
      };
    }
    case "ADD_OUTPUT_DOCUMENT":
      return {
        ...state,
        outputDocuments: [...state.outputDocuments, action.payload],
      };
    case "UPDATE_OUTPUT_DOCUMENT":
      return {
        ...state,
        outputDocuments: state.outputDocuments.map((doc) =>
          doc.id === action.payload.id ? action.payload : doc,
        ),
      };
    case "REMOVE_OUTPUT_DOCUMENT":
      return {
        ...state,
        outputDocuments: state.outputDocuments.filter(
          (doc) => doc.id !== action.payload,
        ),
      };
    case "CLEAR_OUTPUT_DOCUMENTS":
      return {
        ...state,
        outputDocuments: [],
      };
    case "SET_STATE":
      return {
        ...state,
        ...action.payload,
      };
    default:
      return state;
  }
};

// Create context
type PlaygroundContextType = {
  state: PlaygroundState;
  dispatch: React.Dispatch<ActionType>;
};

const PlaygroundContext = createContext<PlaygroundContextType | undefined>(
  undefined,
);

// This is used to identify this app's state in local storage
const STORAGE_KEY = "transformation-playground-state";

// Provider to persist/load from localStorage, for input documents and transformations
export const PlaygroundProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [state, dispatch] = useReducer(playgroundReducer, initialState);

  // Load state from localStorage on initial render (only inputDocuments and transformations)
  useEffect(() => {
    try {
      const savedState = localStorage.getItem(STORAGE_KEY);
      if (savedState) {
        const parsedState = JSON.parse(savedState);
        dispatch({
          type: "SET_STATE",
          payload: {
            inputDocuments: parsedState.inputDocuments ?? [],
            transformations: parsedState.transformations ?? [],
          },
        });
      }
    } catch (error) {
      console.error("Failed to load state from localStorage:", error);
    }
  }, []);

  // Save state to localStorage whenever it changes (excluding outputDocuments)
  useEffect(() => {
    try {
      const stateToSave = {
        inputDocuments: state.inputDocuments,
        transformations: state.transformations,
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(stateToSave));
    } catch (error) {
      console.error("Failed to save state to localStorage:", error);
    }
  }, [state.inputDocuments, state.transformations]);

  const contextValue = useMemo(() => ({ state, dispatch }), [state, dispatch]);

  return (
    <PlaygroundContext.Provider value={contextValue}>
      {children}
    </PlaygroundContext.Provider>
  );
};

// Custom hook for using the context
export const usePlayground = () => {
  const context = useContext(PlaygroundContext);
  if (context === undefined) {
    throw new Error("usePlayground must be used within a PlaygroundProvider");
  }
  return context;
};
