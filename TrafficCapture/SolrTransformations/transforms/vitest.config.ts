import { defineConfig } from "vitest/config";
import { readFileSync } from "node:fs";
import { Plugin } from "vite";

/** Vite plugin that loads solr.pegjs as a raw text string for the parser module. */
function pegJsRawPlugin(): Plugin {
  return {
    name: "pegjs-raw",
    transform(_code, id) {
      if (id.endsWith("query-engine/parser/solr.pegjs")) {
        const text = readFileSync(id, "utf-8");
        return { code: `export default ${JSON.stringify(text)};` };
      }
    },
  };
}

export default defineConfig({
  plugins: [pegJsRawPlugin()],
  test: {
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"],
      reportsDirectory: "./coverage",
    },
  },
});
