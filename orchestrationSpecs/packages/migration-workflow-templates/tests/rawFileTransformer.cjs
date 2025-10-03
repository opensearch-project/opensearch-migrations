// tests/rawFileTransformer.cjs
const path = require("path");

module.exports = {
    process(src, filename) {
        const code = `exports.__esModule = true; exports.default = ${JSON.stringify(
            String(src)
        )};`;
        return { code, map: null };
    },
    getCacheKey(src, filename, configString, options) {
        return [
            "raw-transform@1",
            path.extname(filename),
            filename,
            String(options && options.instrument),
            configString,
        ].join(":");
    },
};
