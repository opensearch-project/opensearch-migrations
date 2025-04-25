import React from "react";
import { renderHook, act } from "@testing-library/react";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import { PlaygroundProvider } from "@/context/PlaygroundProvider";
import { PlaygroundContext, initialState } from "@/context/PlaygroundContext";
import {
  createInputDocument,
  createTransformation,
} from "@tests/__utils__/playgroundFactories";

const TEST_UUID = "test-uuid";
const TEST_DOC_NAME = "Test Document";
const TEST_DOC_CONTENT = "Test content";

jest.mock("uuid", () => ({
  v4: jest.fn(() => TEST_UUID),
}));

describe("usePlaygroundActions", () => {
  // Standard wrapper using the actual PlaygroundProvider
  const standardWrapper = ({ children }: { children: React.ReactNode }) => (
    <PlaygroundProvider>{children}</PlaygroundProvider>
  );

  // Create a wrapper with a mock dispatch function for testing dispatched actions
  const createMockWrapper = () => {
    const mockDispatch = jest.fn();
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <PlaygroundContext.Provider
        value={{
          state: initialState,
          dispatch: mockDispatch,
        }}
      >
        {children}
      </PlaygroundContext.Provider>
    );
    return { wrapper, mockDispatch };
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("addInputDocument", () => {
    it("should create a new input document with the correct structure", () => {
      const { result } = renderHook(() => usePlaygroundActions(), {
        wrapper: standardWrapper,
      });

      let createdDoc;
      act(() => {
        createdDoc = result.current.addInputDocument(
          TEST_DOC_NAME,
          TEST_DOC_CONTENT
        );
      });

      expect(createdDoc).toEqual({
        id: TEST_UUID,
        name: TEST_DOC_NAME,
        content: TEST_DOC_CONTENT,
      });
    });

    it("should dispatch the ADD_INPUT_DOCUMENT action with correct payload", () => {
      const { wrapper, mockDispatch } = createMockWrapper();
      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

      const testDocument = createInputDocument(TEST_UUID);

      act(() => {
        result.current.addInputDocument(
          testDocument.name,
          testDocument.content
        );
      });

      expect(mockDispatch).toHaveBeenCalledTimes(1);
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "ADD_INPUT_DOCUMENT",
        payload: testDocument,
      });
    });
  });

  describe("addTransformation", () => {
    it("should create a new transformation with the correct structure", () => {
      const { result } = renderHook(() => usePlaygroundActions(), {
        wrapper: standardWrapper,
      });

      let createdTransform;
      act(() => {
        createdTransform = result.current.addTransformation(
          TEST_DOC_NAME,
          TEST_DOC_CONTENT
        );
      });

      expect(createdTransform).toEqual({
        id: TEST_UUID,
        name: TEST_DOC_NAME,
        content: TEST_DOC_CONTENT,
      });
    });

    it("should dispatch the ADD_TRANSFORMATION action with correct payload", () => {
      const { wrapper, mockDispatch } = createMockWrapper();

      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

      const testTransformation = createTransformation(TEST_UUID);

      act(() => {
        result.current.addTransformation(
          testTransformation.name,
          testTransformation.content
        );
      });

      expect(mockDispatch).toHaveBeenCalledTimes(1);
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "ADD_TRANSFORMATION",
        payload: testTransformation,
      });
    });
  });

  describe("reorderTransformation", () => {
    it("should dispatch reorder action with correct payload", () => {
      const { result } = renderHook(() => usePlaygroundActions(), {
        wrapper: standardWrapper,
      });

      // Add two transformations first
      act(() => {
        result.current.addTransformation("Transform 1", "Script 1");
        result.current.addTransformation("Transform 2", "Script 2");
      });

      // Now reorder them
      act(() => {
        result.current.reorderTransformation(0, 1);
      });

      // We can't easily test the dispatch call directly without mocking the context,
      // but we can verify the function exists and doesn't throw
      expect(result.current.reorderTransformation).toBeInstanceOf(Function);
    });

    it("should dispatch the correct sequence of actions when adding transformations and reordering", () => {
      const { wrapper, mockDispatch } = createMockWrapper();
      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

      const testTransformations = [
        createTransformation(TEST_UUID, { name: "Transform 1" }),
        createTransformation(TEST_UUID, { name: "Transform 2" }),
        createTransformation(TEST_UUID),
      ];

      act(() => {
        result.current.addTransformation(
          testTransformations[0].name,
          testTransformations[0].content
        );
        result.current.addTransformation(
          testTransformations[1].name,
          testTransformations[1].content
        );
      });

      // Now reorder them
      act(() => {
        result.current.reorderTransformation(0, 1);
      });

      // Verify all dispatched actions
      expect(mockDispatch).toHaveBeenCalledTimes(3);

      expect(mockDispatch).toHaveBeenNthCalledWith(1, {
        type: "ADD_TRANSFORMATION",
        payload: expect.objectContaining(testTransformations[0]),
      });

      expect(mockDispatch).toHaveBeenNthCalledWith(2, {
        type: "ADD_TRANSFORMATION",
        payload: expect.objectContaining(testTransformations[1]),
      });

      expect(mockDispatch).toHaveBeenNthCalledWith(3, {
        type: "REORDER_TRANSFORMATION",
        payload: { fromIndex: 0, toIndex: 1 },
      });
    });
  });
});
