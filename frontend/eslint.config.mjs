import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { FlatCompat } from "@eslint/eslintrc";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  ...compat.extends("next/core-web-vitals", "next/typescript", "plugin:prettier/recommended"),
  {
    rules: {
    "no-restricted-imports": [
      "error",
      {
        "patterns": [
          {
            "group": ["../"],
            "message": "Relative imports are not allowed."
          }
        ]
      }
    ]
  },
},
];

export default eslintConfig;
