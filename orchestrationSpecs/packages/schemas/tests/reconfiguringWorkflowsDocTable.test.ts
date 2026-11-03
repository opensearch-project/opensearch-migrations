import * as fs from "node:fs";
import * as path from "node:path";
import {z} from "zod";
import {FieldMeta, USER_RFS_OPTIONS} from "../src/userSchemas";

/** docs/reconfiguringWorkflows.md lists every documentBackfillConfig field; keep it in step with the schema. */
describe("reconfiguringWorkflows.md document backfill field table", () => {
    const doc = fs.readFileSync(path.join(__dirname, "../../../../docs/reconfiguringWorkflows.md"), "utf8");
    const section = doc.slice(doc.indexOf("**Document backfill (RFS) fields:**"));
    const rows = new Map<string, {category: string, replayerChecksum: boolean}>();
    for (const match of section.slice(0, section.indexOf("\n---\n")).matchAll(
        /^\| `spec\.documentBackfillConfig\.(\w+)` \| ([^|]+?) \|[^|]*\| (✅|❌) \|$/gm)) {
        rows.set(match[1], {category: match[2].trim(), replayerChecksum: match[3] === "✅"});
    }

    const shape = (USER_RFS_OPTIONS.in as unknown as z.ZodObject<any>).shape;
    const expected = Object.entries(shape).map(([field, schema]) => {
        const meta = ((schema as z.ZodTypeAny).meta() ?? {}) as FieldMeta;
        const category = meta.changeRestriction === "impossible" ? "Impossible"
            : meta.changeRestriction === "gated" ? (meta.gatedOnIncreaseOnly ? "Gated on increase" : "Gated")
            : "Safe";
        return {field, category, replayerChecksum: (meta.checksumFor ?? []).includes("replayer")};
    });

    test.each(expected.map(row => [row.field, row]))("%s", (field, row) => {
        expect(rows.get(field as string)).toEqual({category: row.category, replayerChecksum: row.replayerChecksum});
    });

    test("lists no fields the schema doesn't have", () => {
        expect([...rows.keys()].filter(field => !(field in shape))).toEqual([]);
    });
});
