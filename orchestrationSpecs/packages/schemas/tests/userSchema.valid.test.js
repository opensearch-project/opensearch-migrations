"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src");
var fs = require("fs");
var path = require("path");
var FIXTURES_DIR = path.join(__dirname, "fixtures/valid");
var fixtures = fs.readdirSync(FIXTURES_DIR).filter(function (f) { return f.endsWith(".json"); });
describe("valid configs parse successfully", function () {
    test.each(fixtures)("%s", function (file) {
        var data = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, file), "utf-8"));
        var result = src_1.OVERALL_MIGRATION_CONFIG.safeParse(data);
        if (!result.success) {
            throw new Error(result.error.issues.map(function (i) { return "".concat(i.path.join("."), ": ").concat(i.message); }).join("\n"));
        }
        expect(result.success).toBe(true);
    });
});
