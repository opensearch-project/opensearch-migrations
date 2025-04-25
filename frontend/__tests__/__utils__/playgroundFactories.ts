import { PlaygroundState } from "@/context/PlaygroundContext";

// Factory functions for test objects
export const createInputDocument = (id = "test-id", overrides = {}) => ({
  id,
  name: `Document ${id}`,
  content: `Content for ${id}`,
  ...overrides,
});

export const createTransformation = (id = "transform-1", overrides = {}) => ({
  id,
  name: `Transformation ${id}`,
  content: `Script for ${id}`,
  ...overrides,
});

export const createOutputDocument = (id = "output-1", overrides = {}) => ({
  id,
  name: `Output ${id}`,
  content: `Output content for ${id}`,
  sourceInputId: "id-1",
  transformationsApplied: ["transform-1"],
  ...overrides,
});

// Helper function to create a test state
export const createTestState = (
  overrides: Partial<PlaygroundState> = {}
): PlaygroundState => ({
  inputDocuments: [],
  transformations: [],
  outputDocuments: [],
  ...overrides,
});
