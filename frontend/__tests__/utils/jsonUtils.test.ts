import {
  readFileAsText,
  validateJsonContent,
  validateNewlineDelimitedJson,
  prettyPrintJson,
} from "@/utils/jsonUtils";

describe("jsonUtils", () => {
  describe("validateJsonContent", () => {
    // Test cases for valid JSON inputs
    test.each([
      ["valid JSON object", JSON.stringify({ key: "value" })],
      ["valid JSON array", JSON.stringify([1, 2, 3])],
      ["valid newline-delimited JSON", '{"id": 1, "name": "Item 1"}\n{"id": 2, "name": "Item 2"}'],
      ["empty string", ""],
      ["whitespace-only string", "  \n  "]
    ])("should return null for %s", (_, input) => {
      expect(validateJsonContent(input)).toBeNull();
    });

    // Test cases for invalid JSON inputs
    test.each([
      ["invalid JSON with extra comma", '{"key": "value",}'],
      ["invalid line in NDJSON", '{"id": 1}\n{"id": 2,}']
    ])("should return error message for %s", (_, input) => {
      expect(validateJsonContent(input)).toContain("Invalid JSON format");
    });
  });

  describe("validateNewlineDelimitedJson", () => {
    // Test cases for valid NDJSON inputs
    test.each([
      ["valid NDJSON", '{"id": 1}\n{"id": 2}'],
      ["single line JSON", '{"id": 1}'],
      ["NDJSON with empty lines", '{"id": 1}\n\n{"id": 2}'],
      ["NDJSON with whitespace lines", '{"id": 1}\n  \n{"id": 2}']
    ])("should return null for %s", (_, input) => {
      expect(validateNewlineDelimitedJson(input)).toBeNull();
    });

    it("should return error message for invalid NDJSON", () => {
      const invalidNdjson = '{"id": 1}\n{"id": 2,}'; // Second line has invalid JSON
      expect(validateNewlineDelimitedJson(invalidNdjson)).toContain("Invalid JSON format");
    });
  });

  describe("prettyPrintJson", () => {
    it("should pretty print valid JSON", () => {
      const compactJson = '{"key":"value","nested":{"inner":"value"}}';
      const prettyJson = prettyPrintJson(compactJson);
      
      // Check that the output has line breaks and indentation
      expect(prettyJson).toContain("\n");
      expect(prettyJson).toContain("  ");
      
      // Check that the content is preserved
      const parsedOriginal = JSON.parse(compactJson);
      const parsedPretty = JSON.parse(prettyJson);
      expect(parsedPretty).toEqual(parsedOriginal);
    });

    it("should pretty print valid NDJSON", () => {
      const compactNdjson = '{"id":1,"name":"Item 1"}\n{"id":2,"name":"Item 2"}';
      const prettyNdjson = prettyPrintJson(compactNdjson);
      
      // Check that we have two formatted JSON objects separated by newlines
      const parts = prettyNdjson.split("\n\n");
      expect(parts.length).toBe(2);
      
      // Check that each part is properly formatted
      parts.forEach((part, index) => {
        expect(part).toContain("\n");
        expect(part).toContain("  ");
        
        // Check content preservation
        const id = index + 1;
        expect(JSON.parse(part)).toEqual({ id, name: `Item ${id}` });
      });
    });

    test.each([
      ["invalid JSON", '{"key": value}', '{"key": value}'], // Returns original for invalid
      ["empty string", "", ""]
    ])("should handle %s", (_, input, expected) => {
      expect(prettyPrintJson(input)).toBe(expected);
    });
  });

  describe("readFileAsText", () => {
    // Helper function to create a mock FileReader
    const createMockFileReader = (content: string, shouldSucceed = true) => {
      if (shouldSucceed) {
        return {
          onload: null as any,
          onerror: null as any,
          result: content,
          readAsText: function() {
            setTimeout(() => {
              if (this.onload) this.onload();
            }, 0);
          }
        };
      } else {
        const mockError = new Error("Failed to read file");
        return {
          onload: null as any,
          onerror: null as any,
          readAsText: function() {
            setTimeout(() => {
              if (this.onerror) this.onerror(mockError);
            }, 0);
          }
        };
      }
    };

    it("should read file content as text", async () => {
      // Create a mock File object
      const fileContent = '{"key": "value"}';
      const file = new File([fileContent], "test.json", { type: "application/json" });
      
      // Replace the global FileReader with our mock
      global.FileReader = jest.fn(() => createMockFileReader(fileContent)) as any;
      
      const result = await readFileAsText(file);
      expect(result).toBe(fileContent);
    });

    it("should reject with error when file reading fails", async () => {
      // Create a mock File object
      const file = new File(["content"], "test.json", { type: "application/json" });
      
      // Replace the global FileReader with our mock that fails
      global.FileReader = jest.fn(() => createMockFileReader("", false)) as any;
      
      await expect(readFileAsText(file)).rejects.toThrow("Failed to read file: test.json");
    });
  });
});
