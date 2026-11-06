import {USER_RFS_PROCESS_OPTIONS} from "../src/userSchemas";
import {collectProjectedFields, specPathKey} from "../src/migrationResourceProjections";

describe("RFS document size options", () => {
    const schema = USER_RFS_PROCESS_OPTIONS;

    test("preserves the unlimited document ceiling without JavaScript number rounding", () => {
        expect(schema.parse({})).toMatchObject({
            documentsSizePerBulkRequest: 10485760,
            maxDocumentSizeBytes: "9223372036854775807",
            splitLargeDocuments: false,
        });
        expect(schema.parse({maxDocumentSizeBytes: "4294967296", splitLargeDocuments: true}))
            .toMatchObject({maxDocumentSizeBytes: "4294967296", splitLargeDocuments: true});
    });

    test.each(["0", "-1", "1.5", "9223372036854775808", "1e6", 9007199254740992])(
        "rejects an invalid or potentially rounded document ceiling: %s", value => {
            expect(schema.safeParse({maxDocumentSizeBytes: value}).success).toBe(false);
        });

    test("projects both settings into gated SnapshotMigration fields", () => {
        const fields = new Map(collectProjectedFields().map(field => [specPathKey(field), field]));
        for (const name of ["MaxDocumentSizeBytes", "SplitLargeDocuments"]) {
            expect(fields.get(`SnapshotMigration:documentBackfill${name}`)?.changeRestriction).toBe("gated");
        }
    });
});
