import React from "react";
import { render, act } from "@testing-library/react";
import {
  PlaygroundProvider,
  usePlayground,
  InputDocument,
  Transformation,
} from "../../src/context/PlaygroundContext";

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
}> = ({ onStateReceived, onDispatchReceived }) => {
  const { state, dispatch } = usePlayground();

  React.useEffect(() => {
    onStateReceived(state);
    onDispatchReceived(dispatch);
  }, [state, dispatch, onStateReceived, onDispatchReceived]);

  return null;
};

describe("PlaygroundProvider", () => {
  beforeEach(() => {
    // Clear localStorage before each test
    localStorageMock.clear();
    jest.clearAllMocks();
  });

  it("should provide initial state when localStorage is empty", () => {
    const onStateReceived = jest.fn();
    const onDispatchReceived = jest.fn();

    render(
      <PlaygroundProvider>
        <TestConsumer
          onStateReceived={onStateReceived}
          onDispatchReceived={onDispatchReceived}
        />
      </PlaygroundProvider>
    );

    expect(onStateReceived).toHaveBeenCalledWith({
      inputDocuments: [],
      transformations: [],
      outputDocuments: [],
    });
    expect(onDispatchReceived).toHaveBeenCalled();
    expect(localStorageMock.getItem).toHaveBeenCalledWith(STORAGE_KEY);
  });

  it("should load state from localStorage on mount", () => {
    // Setup localStorage with some initial state
    const savedState = {
      inputDocuments: [
        {
          id: "doc-1",
          name: "Document 1",
          content: "Content 1",
        },
      ],
      transformations: [
        {
          id: "transform-1",
          name: "Transformation 1",
          content: "Script 1",
        },
      ],
    };
    localStorageMock.setItem(STORAGE_KEY, JSON.stringify(savedState));

    const onStateReceived = jest.fn();
    const onDispatchReceived = jest.fn();

    render(
      <PlaygroundProvider>
        <TestConsumer
          onStateReceived={onStateReceived}
          onDispatchReceived={onDispatchReceived}
        />
      </PlaygroundProvider>
    );

    // First call is with initial state, second call is after loading from localStorage
    expect(onStateReceived).toHaveBeenCalledTimes(2);
    expect(onStateReceived).toHaveBeenLastCalledWith({
      inputDocuments: savedState.inputDocuments,
      transformations: savedState.transformations,
      outputDocuments: [],
    });
  });

  it("should save state to localStorage when it changes", () => {
    const onStateReceived = jest.fn();
    const onDispatchReceived = jest.fn();
    let dispatchFn: any;

    render(
      <PlaygroundProvider>
        <TestConsumer
          onStateReceived={onStateReceived}
          onDispatchReceived={(dispatch) => {
            onDispatchReceived(dispatch);
            dispatchFn = dispatch;
          }}
        />
      </PlaygroundProvider>
    );

    // Clear the initial localStorage calls
    jest.clearAllMocks();

    // Add an input document
    const newDocument: InputDocument = {
      id: "doc-1",
      name: "Document 1",
      content: "Content 1",
    };

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
    const newTransformation: Transformation = {
      id: "transform-1",
      name: "Transformation 1",
      content: "Script 1",
    };

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
});
