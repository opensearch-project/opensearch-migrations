import { OVERALL_MIGRATION_CONFIG } from "../src";
import * as fs from "fs";
import * as path from "path";

const FIXTURES_DIR = path.join(__dirname, "fixtures/invalid");

const fixtures = fs.readdirSync(FIXTURES_DIR).filter(f => f.endsWith(".json"));

describe("invalid configs fail validation", () => {
    test.each(fixtures)("%s", (file) => {
        const data = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, file), "utf-8"));
        const result = OVERALL_MIGRATION_CONFIG.safeParse(data);
        expect(result.success).toBe(false);
    });
});
