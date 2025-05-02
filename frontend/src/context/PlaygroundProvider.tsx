"use client";
import React, { useReducer, useEffect, useMemo, useState } from "react";
import { MAX_TOTAL_STORAGE_BYTES, formatBytes } from "@/utils/sizeLimits";
import {
  playgroundReducer,
  initialState,
  STORAGE_KEY,
  PlaygroundContext,
  InputDocument,
  Transformation,
} from "./PlaygroundContext";

// Provider to persist/load from localStorage, for input documents and transformations

export const PlaygroundProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [state, dispatch] = useReducer(playgroundReducer, initialState);

  // Track total storage size and quota exceeded state
  const [totalStorageSize, setTotalStorageSize] = useState<number>(0);
  const [isQuotaExceeded, setIsQuotaExceeded] = useState<boolean>(false);

  // Load state from localStorage on initial render (only inputDocuments and transformations)
  useEffect(() => {
    try {
      const savedState = localStorage.getItem(STORAGE_KEY);
      if (savedState) {
        const sizeInBytes = new Blob([savedState]).size;
        setTotalStorageSize(sizeInBytes);

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

  // Keep track of the previous valid state that was successfully saved
  const [lastValidState, setLastValidState] = useState<{
    inputDocuments: InputDocument[];
    transformations: Transformation[];
  }>({ inputDocuments: [], transformations: [] });

  // Save state to localStorage whenever it changes (excluding outputDocuments)
  useEffect(() => {
    // Skip the effect if we're in the process of reverting to the last valid state
    const stateToSave = {
      inputDocuments: state.inputDocuments,
      transformations: state.transformations,
    };

    const stateJson = JSON.stringify(stateToSave);

    // Update the total storage size
    const sizeInBytes = new Blob([stateJson]).size;
    setTotalStorageSize(sizeInBytes);

    // Only try to save to localStorage if we're not in a quota exceeded state
    if (!isQuotaExceeded) {
      try {
        localStorage.setItem(STORAGE_KEY, stateJson);

        // Update the last valid state after successful save
        setLastValidState(stateToSave);
        setIsQuotaExceeded(false); // Reset the quota exceeded flag
      } catch (storageError) {
        // Check if it's a quota exceeded error
        if (
          storageError instanceof DOMException &&
          (storageError.name === "QuotaExceededError" ||
            storageError.name === "NS_ERROR_DOM_QUOTA_REACHED" ||
            storageError.message.includes("quota"))
        ) {
          const errorMsg = `Storage quota exceeded. Maximum storage limit is approximately ${formatBytes(MAX_TOTAL_STORAGE_BYTES)}.`;
          console.error(errorMsg);

          // Set the quota exceeded flag
          setIsQuotaExceeded(true);
          return;
        } else {
          // Log other errors
          console.error("Failed to save state to localStorage:", storageError);
        }
      }
    }
  }, [state.inputDocuments, state.transformations, isQuotaExceeded]);

  // Effect to revert to last valid state when quota is exceeded
  useEffect(() => {
    if (isQuotaExceeded && lastValidState.inputDocuments.length > 0) {
      dispatch({
        type: "SET_STATE",
        payload: lastValidState,
      });
    }
  }, [isQuotaExceeded, lastValidState, dispatch]);

  const contextValue = useMemo(
    () => ({
      state,
      dispatch,
      storageSize: totalStorageSize,
      isQuotaExceeded,
    }),
    [state, dispatch, totalStorageSize, isQuotaExceeded],
  );

  return (
    <PlaygroundContext.Provider value={contextValue}>
      {children}
    </PlaygroundContext.Provider>
  );
};
