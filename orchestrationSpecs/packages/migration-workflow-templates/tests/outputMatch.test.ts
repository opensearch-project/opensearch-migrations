import * as fs from 'fs';
import * as path from 'path';
import {diff} from 'jest-diff';
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {AllWorkflowTemplates} from "../src/workflowTemplates/allWorkflowTemplates";

const snapshotDir = path.join(__dirname, '__snapshots__');

expect.extend({
    toMatchFileSnapshot(received: unknown, filename: string) {
        const filePath = path.join(snapshotDir, filename);
        const content = JSON.stringify(received, null, 2) + '\n';
        const {snapshotState} = expect.getState() as any;
        const updating = snapshotState._updateSnapshot === 'all';

        if (updating || !fs.existsSync(filePath)) {
            fs.mkdirSync(snapshotDir, {recursive: true});
            fs.writeFileSync(filePath, content);
            snapshotState.updated++;
            return {pass: true, message: () => ''};
        }

        const existing = fs.readFileSync(filePath, 'utf-8');
        if (content === existing) {
            snapshotState.matched++;
            return {pass: true, message: () => ''};
        }
        snapshotState.unmatched++;
        return {
            pass: false,
            message: () => `Snapshot mismatch: ${filename}\n\n${diff(existing, content) ?? ''}\n\nRun with --updateSnapshot to update.`
        };
    }
});

describe('workflow template renderings', () => {
    const cases = AllWorkflowTemplates
        .map(t => ({name: t.metadata.k8sMetadata.name, template: t}))
        .sort((a, b) => a.name.localeCompare(b.name));

    test.each(cases)('$name', ({name, template}) => {
        const result = renderWorkflowTemplate(template);
        const camel = name.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase());
        (expect(result) as any).toMatchFileSnapshot(`${camel}.snap.json`);
    });
});
