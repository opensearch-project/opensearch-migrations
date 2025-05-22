import React from "react";
import { render, act } from "@testing-library/react";
import { usePlayground } from "@/context/PlaygroundContext";
import { PlaygroundProvider } from "@/context/PlaygroundProvider";
import {
  createInputDocument,
  createTransformation,
} from "@tests/__utils__/playgroundFactories";

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: jest.fn((key: string) => store[key] || null),
    setItem: jest.fn((key: string, value: string) => {
      store[key] = value;
    }),
    clear: jest.fn(() => {
      store = {};
    }),
    removeItem: jest.fn((key: string) => {
      delete store[key];
    }),
  };
})();

Object.defineProperty(window, "localStorage", { value: localStorageMock });

// Storage key used in PlaygroundContext
const STORAGE_KEY = "transformation-playground-state";

// Test component that uses the context
const TestConsumer: React.FC<{
  onStateReceived: (state: any) => void;
  onDispatchReceived: (dispatch: any) => void;
  onStorageSizeReceived?: (size: number) => void;
  onQuotaExceededReceived?: (isExceeded: boolean) => void;
}> = ({
  onStateReceived,
  onDispatchReceived,
  onStorageSizeReceived,
  onQuotaExceededReceived,
}) => {
  const { state, dispatch, storageSize, isQuotaExceeded } = usePlayground();

  React.useEffect(() => {
    onStateReceived(state);
    onDispatchReceived(dispatch);
    if (onStorageSizeReceived) {
      onStorageSizeReceived(storageSize);
    }
    if (onQuotaExceededReceived) {
      onQuotaExceededReceived(isQuotaExceeded);
    }
  }, [
    state,
    dispatch,
    storageSize,
    isQuotaExceeded,
    onStateReceived,
    onDispatchReceived,
    onStorageSizeReceived,
    onQuotaExceededReceived,
  ]);

  return null;
};

describe("PlaygroundProvider", () => {
  // Helper function to render the provider and return necessary objects
  function renderProvider() {
    const onStateReceived = jest.fn();
    const onDispatchReceived = jest.fn();
    const onStorageSizeReceived = jest.fn();
    const onQuotaExceededReceived = jest.fn();
    let dispatchFn: any;

    const renderResult = render(
      <PlaygroundProvider>
        <TestConsumer
          onStateReceived={onStateReceived}
          onDispatchReceived={(dispatch) => {
            onDispatchReceived(dispatch);
            dispatchFn = dispatch;
          }}
          onStorageSizeReceived={onStorageSizeReceived}
          onQuotaExceededReceived={onQuotaExceededReceived}
        />
      </PlaygroundProvider>
    );

    return {
      onStateReceived,
      onDispatchReceived,
      onStorageSizeReceived,
      onQuotaExceededReceived,
      dispatchFn,
      ...renderResult,
    };
  }

  beforeEach(() => {
    // Clear localStorage before each test
    localStorageMock.clear();
    jest.clearAllMocks();
  });

  it("should provide initial state when localStorage is empty", () => {
    const { onStateReceived, onDispatchReceived } = renderProvider();

    expect(onStateReceived).toHaveBeenCalledWith({
      inputDocuments: [],
      transformations: [],
      outputDocuments: [],
      isProcessingTransformations: false,
      transformationErrors: [],
    });
    expect(onDispatchReceived).toHaveBeenCalled();
    expect(localStorageMock.getItem).toHaveBeenCalledWith(STORAGE_KEY);
  });

  it("should load state from localStorage on mount", () => {
    // Setup localStorage with some initial state
    const inputDoc = createInputDocument("doc-1", {
      name: "Document 1",
      content: "Content 1",
    });
    const transformation = createTransformation("transform-1", {
      name: "Transformation 1",
      content: "Script 1",
    });
    const savedState = {
      inputDocuments: [inputDoc],
      transformations: [transformation],
    };
    localStorageMock.setItem(STORAGE_KEY, JSON.stringify(savedState));

    const { onStateReceived } = renderProvider();

    expect(onStateReceived).toHaveBeenLastCalledWith({
      inputDocuments: savedState.inputDocuments,
      transformations: savedState.transformations,
      outputDocuments: [],
      isProcessingTransformations: false,
      transformationErrors: [],
    });
  });

  it("should save state to localStorage when it changes", () => {
    const { dispatchFn } = renderProvider();

    // Add an input document
    const newDocument = createInputDocument("doc-1", {
      name: "Document 1",
      content: "Content 1",
    });

    act(() => {
      dispatchFn({
        type: "ADD_INPUT_DOCUMENT" as const,
        payload: newDocument,
      });
    });

    // Verify localStorage was updated
    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      STORAGE_KEY,
      JSON.stringify({
        inputDocuments: [newDocument],
        transformations: [],
      })
    );

    // Add a transformation
    const newTransformation = createTransformation("transform-1", {
      name: "Transformation 1",
      content: "Script 1",
    });

    act(() => {
      dispatchFn({
        type: "ADD_TRANSFORMATION" as const,
        payload: newTransformation,
      });
    });

    // Verify localStorage was updated again
    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      STORAGE_KEY,
      JSON.stringify({
        inputDocuments: [newDocument],
        transformations: [newTransformation],
      })
    );
  });

  it("should track storage size when state changes", () => {
    const { dispatchFn, onStorageSizeReceived } = renderProvider();

    // Initial storage size should be 0
    expect(onStorageSizeReceived).toHaveBeenCalledWith(0);

    // Add an input document
    const newDocument = createInputDocument("doc-1", {
      name: "Document 1",
      content: "Content 1",
    });

    act(() => {
      dispatchFn({
        type: "ADD_INPUT_DOCUMENT" as const,
        payload: newDocument,
      });
    });

    // Storage size should be updated
    // The exact number of calls may vary, so we just check the last call
    expect(onStorageSizeReceived).toHaveBeenLastCalledWith(expect.any(Number));

    // The size should be greater than 0 after adding a document
    const sizeAfterAddingDocument =
      onStorageSizeReceived.mock.calls[
        onStorageSizeReceived.mock.calls.length - 1
      ][0];
    expect(sizeAfterAddingDocument).toBeGreaterThan(0);

    // Add a transformation
    const newTransformation = createTransformation("transform-1", {
      name: "Transformation 1",
      content: "Script 1",
    });

    act(() => {
      dispatchFn({
        type: "ADD_TRANSFORMATION" as const,
        payload: newTransformation,
      });
    });

    // Storage size should be updated again
    expect(onStorageSizeReceived).toHaveBeenLastCalledWith(expect.any(Number));

    // The size should be greater after adding a transformation
    const sizeAfterAddingTransformation =
      onStorageSizeReceived.mock.calls[
        onStorageSizeReceived.mock.calls.length - 1
      ][0];
    expect(sizeAfterAddingTransformation).toBeGreaterThan(
      sizeAfterAddingDocument
    );
  });

  it("should handle quota exceeded errors", () => {
    const { dispatchFn, onQuotaExceededReceived } = renderProvider();

    const originalConsoleError = console.error;
    console.error = jest.fn();

    // Initial quota exceeded state should be false
    expect(onQuotaExceededReceived).toHaveBeenCalledWith(false);

    // Mock localStorage.setItem to throw a quota exceeded error
    const originalSetItem = localStorageMock.setItem;
    localStorageMock.setItem = jest.fn().mockImplementation(() => {
      const error = new DOMException("Quota exceeded", "QuotaExceededError");
      throw error;
    });

    // Add a document to trigger the quota exceeded error
    const newDocument = createInputDocument("doc-1", {
      name: "Document 1",
      content: "Content 1",
    });

    act(() => {
      dispatchFn({
        type: "ADD_INPUT_DOCUMENT" as const,
        payload: newDocument,
      });
    });

    // Quota exceeded flag should be set to true
    expect(onQuotaExceededReceived).toHaveBeenLastCalledWith(true);

    // Verify console.error was called with the expected message
    expect(console.error).toHaveBeenCalledWith(
      expect.stringContaining("Storage quota exceeded")
    );

    // Restore the original implementations
    localStorageMock.setItem = originalSetItem;
    console.error = originalConsoleError;
  });
});
