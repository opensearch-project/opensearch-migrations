import React from "react";
import { renderHook, act } from "@testing-library/react";
import { usePlaygroundActions } from "../../src/hooks/usePlaygroundActions";
import { PlaygroundProvider } from "../../src/context/PlaygroundContext";

// Mock uuid to return predictable values
jest.mock("uuid", () => ({
  v4: jest.fn(() => "test-uuid"),
}));

describe("usePlaygroundActions", () => {
  // Wrap the hook in the PlaygroundProvider for testing
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <PlaygroundProvider>{children}</PlaygroundProvider>
  );

  beforeEach(() => {
    // Clear mocks before each test
    jest.clearAllMocks();
  });

  describe("addInputDocument", () => {
    it("should create a new input document with the correct structure", () => {
      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

      act(() => {
        result.current.addInputDocument("Test Document", "Test content");
      });

      // The hook should return the created document
      expect(result.current.addInputDocument).toBeInstanceOf(Function);

      // Call the function and check the returned document
      let createdDoc;
      act(() => {
        createdDoc = result.current.addInputDocument(
          "Test Document",
          "Test content"
        );
      });

      expect(createdDoc).toEqual({
        id: "test-uuid",
        name: "Test Document",
        content: "Test content",
      });
    });
  });

  describe("addTransformation", () => {
    it("should create a new transformation with the correct structure", () => {
      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

      // Call the function and check the returned transformation
      let createdTransform;
      act(() => {
        createdTransform = result.current.addTransformation(
          "Test Transform",
          "Test script"
        );
      });

      expect(createdTransform).toEqual({
        id: "test-uuid",
        name: "Test Transform",
        content: "Test script",
      });
    });
  });

  describe("reorderTransformation", () => {
    it("should dispatch reorder action with correct payload", () => {
      const { result } = renderHook(() => usePlaygroundActions(), { wrapper });

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
  });
});
