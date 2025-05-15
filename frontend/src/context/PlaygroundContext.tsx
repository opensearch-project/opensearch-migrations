"use client";

import React, { createContext, useContext } from "react";

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

export interface TransformationError {
  documentId: string;
  transformationId: string;
  message: string;
}

export interface PlaygroundState {
  inputDocuments: InputDocument[];
  transformations: Transformation[];
  outputDocuments: OutputDocument[];
  isProcessingTransformations: boolean;
  transformationErrors: TransformationError[];
}

export const initialState: PlaygroundState = {
  inputDocuments: [],
  transformations: [],
  outputDocuments: [],
  isProcessingTransformations: false,
  transformationErrors: [],
};

export type ActionType =
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
  | { type: "CLEAR_OUTPUT_DOCUMENTS"; payload: undefined }
  | { type: "SET_STATE"; payload: Partial<PlaygroundState> }
  | { type: "START_TRANSFORMATIONS"; payload: undefined }
  | {
      type: "COMPLETE_TRANSFORMATIONS";
      payload: {
        outputDocuments: OutputDocument[];
        errors: TransformationError[];
      };
    }
  | { type: "CLEAR_TRANSFORMATION_ERRORS"; payload: undefined };

export const playgroundReducer = (
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
    case "START_TRANSFORMATIONS":
      return {
        ...state,
        isProcessingTransformations: true,
        transformationErrors: [],
      };
    case "COMPLETE_TRANSFORMATIONS":
      return {
        ...state,
        isProcessingTransformations: false,
        outputDocuments: action.payload.outputDocuments,
        transformationErrors: action.payload.errors,
      };
    case "CLEAR_TRANSFORMATION_ERRORS":
      return {
        ...state,
        transformationErrors: [],
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
  storageSize: number;
  isQuotaExceeded: boolean;
};

export const PlaygroundContext = createContext<
  PlaygroundContextType | undefined
>(undefined);

// This is used to identify this app's state in local storage
export const STORAGE_KEY = "transformation-playground-state";

// Mock dispatch function that does nothing - used when context is accessed outside of provider
const mockDispatch: React.Dispatch<ActionType> = () => {
  console.warn("PlaygroundContext used outside of PlaygroundProvider");
};

export const usePlayground = () => {
  const context = useContext(PlaygroundContext);

  // If context is undefined (outside of provider), return a mock context
  // This allows components to be built individually without errors
  if (context === undefined) {
    console.warn(
      "usePlayground used outside of PlaygroundProvider - using mock context",
    );
    // Return a mock context that won't throw errors during build
    return {
      state: initialState,
      dispatch: mockDispatch,
      storageSize: 0,
      isQuotaExceeded: false,
    };
  }

  return context;
};
