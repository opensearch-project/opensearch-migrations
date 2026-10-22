const path = require("node:path");
const {build} = require("esbuild");

build({
    entryPoints: [path.resolve(__dirname, "src/index.ts")],
    bundle: true,
    platform: "browser",
    format: "esm",
    write: false,
    logLevel: "error",
}).catch(error => {
    console.error(error);
    process.exit(1);
});
