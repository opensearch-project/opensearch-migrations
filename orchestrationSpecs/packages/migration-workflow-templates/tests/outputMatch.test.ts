import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {AllWorkflowTemplates} from "../src/workflowTemplates/allWorkflowTemplates";

describe('test workflow template renderings', () => {
    const cases = AllWorkflowTemplates.map(input => ({
        name: input.metadata.k8sMetadata.name,
        input,
    }))
        // keep order stable to avoid churn
        .sort((a, b) => a.name.localeCompare(b.name));

    test.each(cases)('$name', ({ input }) => {
        const result = renderWorkflowTemplate(input);
        // let the test name be the snapshot key
        expect(result).toMatchSnapshot();
    });
});
