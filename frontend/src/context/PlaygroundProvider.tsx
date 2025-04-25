"use client";
import React, { useReducer, useEffect, useMemo } from "react";
import {
  playgroundReducer,
  initialState,
  STORAGE_KEY,
  PlaygroundContext,
} from "./PlaygroundContext";

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
