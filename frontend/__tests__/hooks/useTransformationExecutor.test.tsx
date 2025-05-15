import React from "react";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useTransformationExecutor } from "@/hooks/useTransformationExecutor";
import {
  PlaygroundContext,
  PlaygroundState,
} from "@/context/PlaygroundContext";
import * as transformationExecutor from "@/utils/transformationExecutor";

// Mock the transformation executor
jest.mock("@/utils/transformationExecutor");

describe("useTransformationExecutor", () => {
  const mockDispatch = jest.fn();

  const createWrapper = (initialState: Partial<PlaygroundState> = {}) => {
    const state: PlaygroundState = {
      inputDocuments: [],
      transformations: [],
      outputDocuments: [],
      isProcessingTransformations: false,
      transformationErrors: [],
      ...initialState,
    };

    return ({ children }: { children: React.ReactNode }) => (
      <PlaygroundContext.Provider
        value={{
          state,
          dispatch: mockDispatch,
          storageSize: 0,
          isQuotaExceeded: false,
        }}
      >
        {children}
      </PlaygroundContext.Provider>
    );
  };

  beforeEach(() => {
    jest.clearAllMocks();

    // Mock the executeTransformationChain function
    (
      transformationExecutor.executeTransformationChain as jest.Mock
    ).mockResolvedValue({
      success: true,
      document: { transformed: true },
    });
  });

  it("should not run transformations when there are no input documents", async () => {
    const wrapper = createWrapper({
      inputDocuments: [],
      transformations: [{ id: "1", name: "Test", content: "// code" }],
    });

    renderHook(() => useTransformationExecutor(), { wrapper });

    expect(mockDispatch).not.toHaveBeenCalled();
    expect(
      transformationExecutor.executeTransformationChain
    ).not.toHaveBeenCalled();
  });

  it("should not run transformations when there are no transformations", async () => {
    const wrapper = createWrapper({
      inputDocuments: [{ id: "1", name: "Test", content: "{}" }],
      transformations: [],
    });

    renderHook(() => useTransformationExecutor(), { wrapper });

    expect(mockDispatch).not.toHaveBeenCalled();
    expect(
      transformationExecutor.executeTransformationChain
    ).not.toHaveBeenCalled();
  });

  it("should run transformations when there are input documents and transformations", async () => {
    const inputDoc = { id: "1", name: "Test", content: '{"value": 42}' };
    const transformation = { id: "1", name: "Test", content: "// code" };

    const wrapper = createWrapper({
      inputDocuments: [inputDoc],
      transformations: [transformation],
    });

    renderHook(() => useTransformationExecutor(), { wrapper });

    // Wait for the async operations to complete
    await waitFor(() => {
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "START_TRANSFORMATIONS",
        payload: undefined,
      });
    });

    expect(
      transformationExecutor.executeTransformationChain
    ).toHaveBeenCalledWith([transformation], { value: 42 });

    await waitFor(() => {
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "COMPLETE_TRANSFORMATIONS",
        payload: {
          outputDocuments: expect.arrayContaining([
            expect.objectContaining({
              name: inputDoc.name,
              sourceInputId: inputDoc.id,
              transformationsApplied: [transformation.id],
            }),
          ]),
          errors: [],
        },
      });
    });
  });

  it("should handle transformation errors", async () => {
    const inputDoc = { id: "1", name: "Test", content: '{"value": 42}' };
    const transformation = { id: "1", name: "Test", content: "// code" };

    // Mock the transformation to fail
    (
      transformationExecutor.executeTransformationChain as jest.Mock
    ).mockResolvedValue({
      success: false,
      document: { value: 42 },
      error: "Test error",
      transformationId: "1",
    });

    const wrapper = createWrapper({
      inputDocuments: [inputDoc],
      transformations: [transformation],
    });

    renderHook(() => useTransformationExecutor(), { wrapper });

    await waitFor(() => {
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "COMPLETE_TRANSFORMATIONS",
        payload: {
          outputDocuments: [],
          errors: [
            {
              documentId: inputDoc.id,
              transformationId: transformation.id,
              message: "Test error",
            },
          ],
        },
      });
    });
  });

  it("should manually run transformations when runTransformations is called", async () => {
    const inputDoc = { id: "1", name: "Test", content: '{"value": 42}' };
    const transformation = { id: "1", name: "Test", content: "// code" };

    const wrapper = createWrapper({
      inputDocuments: [inputDoc],
      transformations: [transformation],
    });

    const { result } = renderHook(() => useTransformationExecutor(), {
      wrapper,
    });

    // Clear previous calls from the automatic run
    mockDispatch.mockClear();
    (
      transformationExecutor.executeTransformationChain as jest.Mock
    ).mockClear();

    // Manually run transformations
    act(() => {
      result.current.runTransformations();
    });

    await waitFor(() => {
      expect(mockDispatch).toHaveBeenCalledWith({
        type: "START_TRANSFORMATIONS",
        payload: undefined,
      });
    });

    expect(
      transformationExecutor.executeTransformationChain
    ).toHaveBeenCalledWith([transformation], { value: 42 });
  });
});
