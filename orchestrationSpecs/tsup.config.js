"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var tsup_1 = require("tsup");
exports.default = (0, tsup_1.defineConfig)({
    entry: ["src/index.ts"],
    format: ["cjs"],
    dts: true,
    sourcemap: true,
    clean: true,
    loader: {
        ".sh": "text"
    }
});
