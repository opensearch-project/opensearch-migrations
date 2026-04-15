import {CONTAINER_NAMES} from "../src/containerNames";
import * as fs from "fs";
import * as path from "path";

const DASHBOARD_DIR = path.resolve(__dirname, "../../../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/cloudwatch-dashboards");

/**
 * Ensures container names used in workflow templates stay in sync with CloudWatch dashboard widgets.
 * If a container name changes, the corresponding dashboard JSON must be updated too.
 */
describe("Container names match CloudWatch dashboards", () => {
    const rfsDashboard = fs.readFileSync(path.join(DASHBOARD_DIR, "reindex-from-snapshot-dashboard.json"), "utf-8");
    const cdcDashboard = fs.readFileSync(path.join(DASHBOARD_DIR, "capture-replay-dashboard.json"), "utf-8");

    it("bulk-loader is referenced in RFS dashboard", () => {
        expect(rfsDashboard).toContain(`"${CONTAINER_NAMES.BULK_LOADER}"`);
    });

    it("replayer is referenced in CDC dashboard", () => {
        expect(cdcDashboard).toContain(`"${CONTAINER_NAMES.REPLAYER}"`);
    });
});
