import { playgroundReducer } from "@/context/PlaygroundContext";
import {
  createInputDocument,
  createTransformation,
  createOutputDocument,
  createTestState,
} from "@tests/__utils__/playgroundFactories";

describe("PlaygroundReducer", () => {
  describe("ADD_INPUT_DOCUMENT", () => {
    it("should add a new input document to the state", () => {
      const initialState = createTestState();
      const newDocument = createInputDocument();

      const action = {
        type: "ADD_INPUT_DOCUMENT" as const,
        payload: newDocument,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(1);
      expect(newState.inputDocuments[0]).toEqual(newDocument);
    });
  });

  describe("UPDATE_INPUT_DOCUMENT", () => {
    it("should update an existing input document", () => {
      const existingDocument = createInputDocument("test-id", {
        name: "Original Name",
        content: "Original content",
      });
      const initialState = createTestState({
        inputDocuments: [existingDocument],
      });

      const updatedDocument = createInputDocument("test-id", {
        name: "Updated Name",
        content: "Updated content",
      });
      const action = {
        type: "UPDATE_INPUT_DOCUMENT" as const,
        payload: updatedDocument,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(1);
      expect(newState.inputDocuments[0]).toEqual(updatedDocument);
    });

    it("should not modify other documents when updating one", () => {
      const document1 = createInputDocument("id-1");
      const document2 = createInputDocument("id-2");
      const initialState = createTestState({
        inputDocuments: [document1, document2],
      });

      const updatedDocument = createInputDocument("id-1", {
        name: "Updated Document 1",
        content: "Updated Content 1",
      });
      const action = {
        type: "UPDATE_INPUT_DOCUMENT" as const,
        payload: updatedDocument,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(2);
      expect(newState.inputDocuments[0]).toEqual(updatedDocument);
      expect(newState.inputDocuments[1]).toEqual(document2);
    });
  });

  describe("REMOVE_INPUT_DOCUMENT", () => {
    it("should remove an input document by id", () => {
      const document1 = createInputDocument("id-1");
      const document2 = createInputDocument("id-2");
      const initialState = createTestState({
        inputDocuments: [document1, document2],
      });

      const action = {
        type: "REMOVE_INPUT_DOCUMENT" as const,
        payload: "id-1",
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(1);
      expect(newState.inputDocuments[0]).toEqual(document2);
    });

    it("should clear output documents when removing an input document", () => {
      const document = createInputDocument("id-1");
      const outputDocument = createOutputDocument();
      const initialState = createTestState({
        inputDocuments: [document],
        outputDocuments: [outputDocument],
      });

      const action = {
        type: "REMOVE_INPUT_DOCUMENT" as const,
        payload: "id-1",
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(0);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("ADD_TRANSFORMATION", () => {
    it("should add a new transformation to the state", () => {
      const initialState = createTestState();
      const newTransformation = createTransformation();

      const action = {
        type: "ADD_TRANSFORMATION" as const,
        payload: newTransformation,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(1);
      expect(newState.transformations[0]).toEqual(newTransformation);
    });

    it("should clear output documents when adding a transformation", () => {
      const outputDocument = createOutputDocument();
      const initialState = createTestState({
        outputDocuments: [outputDocument],
      });

      const newTransformation = createTransformation("transform-2");
      const action = {
        type: "ADD_TRANSFORMATION" as const,
        payload: newTransformation,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(1);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("UPDATE_TRANSFORMATION", () => {
    it("should update an existing transformation", () => {
      const existingTransformation = createTransformation("transform-1", {
        name: "Original Name",
        content: "Original script",
      });
      const initialState = createTestState({
        transformations: [existingTransformation],
      });

      const updatedTransformation = createTransformation("transform-1", {
        name: "Updated Name",
        content: "Updated script",
      });
      const action = {
        type: "UPDATE_TRANSFORMATION" as const,
        payload: updatedTransformation,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(1);
      expect(newState.transformations[0]).toEqual(updatedTransformation);
    });

    it("should clear output documents when updating a transformation", () => {
      const transformation = createTransformation("transform-1", {
        name: "Original Name",
        content: "Original script",
      });
      const outputDocument = createOutputDocument();
      const initialState = createTestState({
        transformations: [transformation],
        outputDocuments: [outputDocument],
      });

      const updatedTransformation = createTransformation("transform-1", {
        name: "Updated Name",
        content: "Updated script",
      });
      const action = {
        type: "UPDATE_TRANSFORMATION" as const,
        payload: updatedTransformation,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations[0]).toEqual(updatedTransformation);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("REMOVE_TRANSFORMATION", () => {
    it("should remove a transformation by id", () => {
      const transformation1 = createTransformation("transform-1");
      const transformation2 = createTransformation("transform-2");
      const initialState = createTestState({
        transformations: [transformation1, transformation2],
      });

      const action = {
        type: "REMOVE_TRANSFORMATION" as const,
        payload: "transform-1",
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(1);
      expect(newState.transformations[0]).toEqual(transformation2);
    });

    it("should clear output documents when removing a transformation", () => {
      const transformation = createTransformation("transform-1");
      const outputDocument = createOutputDocument();
      const initialState = createTestState({
        transformations: [transformation],
        outputDocuments: [outputDocument],
      });

      const action = {
        type: "REMOVE_TRANSFORMATION" as const,
        payload: "transform-1",
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(0);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("REORDER_TRANSFORMATION", () => {
    it("should reorder transformations", () => {
      const transformation1 = createTransformation("transform-1");
      const transformation2 = createTransformation("transform-2");
      const transformation3 = createTransformation("transform-3");
      const initialState = createTestState({
        transformations: [transformation1, transformation2, transformation3],
      });

      const action = {
        type: "REORDER_TRANSFORMATION" as const,
        payload: { fromIndex: 0, toIndex: 2 },
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations).toHaveLength(3);
      expect(newState.transformations[0]).toEqual(transformation2);
      expect(newState.transformations[1]).toEqual(transformation3);
      expect(newState.transformations[2]).toEqual(transformation1);
    });

    it("should clear output documents when reordering transformations", () => {
      const transformation1 = createTransformation("transform-1");
      const transformation2 = createTransformation("transform-2");
      const outputDocument = createOutputDocument();
      const initialState = createTestState({
        transformations: [transformation1, transformation2],
        outputDocuments: [outputDocument],
      });

      const action = {
        type: "REORDER_TRANSFORMATION" as const,
        payload: { fromIndex: 0, toIndex: 1 },
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.transformations[0]).toEqual(transformation2);
      expect(newState.transformations[1]).toEqual(transformation1);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("ADD_OUTPUT_DOCUMENT", () => {
    it("should add a new output document to the state", () => {
      const initialState = createTestState();
      const newOutputDocument = createOutputDocument();

      const action = {
        type: "ADD_OUTPUT_DOCUMENT" as const,
        payload: newOutputDocument,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.outputDocuments).toHaveLength(1);
      expect(newState.outputDocuments[0]).toEqual(newOutputDocument);
    });
  });

  describe("UPDATE_OUTPUT_DOCUMENT", () => {
    it("should update an existing output document", () => {
      const existingOutputDocument = createOutputDocument("output-1", {
        name: "Original Output",
        content: "Original output content",
      });
      const initialState = createTestState({
        outputDocuments: [existingOutputDocument],
      });

      const updatedOutputDocument = createOutputDocument("output-1", {
        name: "Updated Output",
        content: "Updated output content",
        transformationsApplied: ["transform-1", "transform-2"],
      });
      const action = {
        type: "UPDATE_OUTPUT_DOCUMENT" as const,
        payload: updatedOutputDocument,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.outputDocuments).toHaveLength(1);
      expect(newState.outputDocuments[0]).toEqual(updatedOutputDocument);
    });
  });

  describe("REMOVE_OUTPUT_DOCUMENT", () => {
    it("should remove an output document by id", () => {
      const outputDocument1 = createOutputDocument("output-1");
      const outputDocument2 = createOutputDocument("output-2", {
        sourceInputId: "id-2",
        transformationsApplied: ["transform-2"],
      });
      const initialState = createTestState({
        outputDocuments: [outputDocument1, outputDocument2],
      });

      const action = {
        type: "REMOVE_OUTPUT_DOCUMENT" as const,
        payload: "output-1",
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.outputDocuments).toHaveLength(1);
      expect(newState.outputDocuments[0]).toEqual(outputDocument2);
    });
  });

  describe("CLEAR_OUTPUT_DOCUMENTS", () => {
    it("should clear all output documents", () => {
      const outputDocument1 = createOutputDocument("output-1");
      const outputDocument2 = createOutputDocument("output-2", {
        sourceInputId: "id-2",
        transformationsApplied: ["transform-2"],
      });
      const initialState = createTestState({
        outputDocuments: [outputDocument1, outputDocument2],
      });

      const action = {
        type: "CLEAR_OUTPUT_DOCUMENTS" as const,
        payload: undefined,
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("SET_STATE", () => {
    it("should set partial state", () => {
      const initialState = createTestState();
      const inputDocument = createInputDocument("id-1");
      const transformation = createTransformation("transform-1");

      const action = {
        type: "SET_STATE" as const,
        payload: {
          inputDocuments: [inputDocument],
          transformations: [transformation],
        },
      };
      const newState = playgroundReducer(initialState, action);

      expect(newState.inputDocuments).toHaveLength(1);
      expect(newState.inputDocuments[0]).toEqual(inputDocument);
      expect(newState.transformations).toHaveLength(1);
      expect(newState.transformations[0]).toEqual(transformation);
      expect(newState.outputDocuments).toHaveLength(0);
    });
  });

  describe("Unknown action", () => {
    it("should return the current state for unknown action types", () => {
      const initialState = createTestState({
        inputDocuments: [
          { id: "id-1", name: "Document 1", content: "Content 1" },
        ],
      });

      // We need to cast this to any since it's an invalid action type
      const action = { type: "UNKNOWN_ACTION", payload: {} } as any;
      const newState = playgroundReducer(initialState, action);

      expect(newState).toEqual(initialState);
    });
  });
});
