import {
  executeTransformation,
  executeTransformationChain,
} from "@/utils/transformationExecutor";

describe("transformationExecutor", () => {
  describe("executeTransformation", () => {
    it("should execute a valid transformation", async () => {
      const transformation = `
        function main(context) {
          return (document) => {
            document.transformed = true;
            return document;
          };
        }
        // Entrypoint function
        (() => main)();
      `;

      const document = { value: 42 };
      const result = await executeTransformation(transformation, document);

      expect(result.success).toBe(true);
      expect(result.document).toEqual({ value: 42, transformed: true });
    });

    it("should handle transformation without valid entrypoint", async () => {
      const transformation = `
        function notMain(context) {
          return (document) => {
            document.transformed = true;
            return document;
          };
        }
      `;

      const document = { value: 42 };
      const result = await executeTransformation(transformation, document);

      expect(result.success).toBe(false);
      expect(result.error).toBe("Transformation doesn't provide a valid entrypoint");
      expect(result.document).toEqual(document); // Document should be unchanged
    });

    it("should execute a transformation using the last expression convention", async () => {
      const transformation = `
        function newMain(context) {
          return (document) => {
            document.transformed = true;
            return document;
          };
        }
        newMain
      `;

      const document = { value: 42 };
      const result = await executeTransformation(transformation, document);

      expect(result.success).toBe(true);
      expect(result.document).toEqual({ value: 42, transformed: true });
    });

    it("should handle transformation that throws an error", async () => {
      const transformation = `
        function main(context) {
          return (document) => {
            throw new Error("Test error");
          };
        }
        // Entrypoint function
        (() => main)();
      `;

      const document = { value: 42 };
      const result = await executeTransformation(transformation, document);

      expect(result.success).toBe(false);
      expect(result.error).toBe("Test error");
      expect(result.document).toEqual(document); // Document should be unchanged
    });
  });

  describe("executeTransformationChain", () => {
    it("should execute a chain of transformations", async () => {
      const transformations = [
        {
          id: "1",
          name: "Transform 1",
          content: `
            function main(context) {
              return (document) => {
                document.step1 = true;
                return document;
              };
            }
            (() => main)();
          `,
        },
        {
          id: "2",
          name: "Transform 2",
          content: `
            function main(context) {
              return (document) => {
                document.step2 = true;
                return document;
              };
            }
            (() => main)();
          `,
        },
      ];

      const document = { value: 42 };
      const result = await executeTransformationChain(transformations, document);

      expect(result.success).toBe(true);
      expect(result.document).toEqual({ value: 42, step1: true, step2: true });
      expect(Object.hasOwn(result.document, 'step3')).toBe(false);
    });

    it("should stop execution when a transformation fails", async () => {
      const transformations = [
        {
          id: "1",
          name: "Transform 1",
          content: `
            function main(context) {
              return (document) => {
                document.step1 = true;
                return document;
              };
            }
            (() => main)();
          `,
        },
        {
          id: "2",
          name: "Transform 2",
          content: `
            function main(context) {
              return (document) => {
                throw new Error("Test error");
              };
            }
            (() => main)();
          `,
        },
        {
          id: "3",
          name: "Transform 3",
          content: `
            function main(context) {
              return (document) => {
                document.step3 = true;
                return document;
              };
            }
            (() => main)();
          `,
        },
      ];

      const document = { value: 42 };
      const result = await executeTransformationChain(transformations, document);

      expect(result.success).toBe(false);
      expect(result.error).toBe("Test error");
      expect(result.transformationId).toBe("2");
      expect(result.document).toEqual({ value: 42, step1: true }); // Only first transformation applied
    });
  });
});
