import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {AllWorkflowTemplates} from "@/workflowTemplates/allWorkflowTemplates";

describe('test workflow template renderings', () => {
    test.each(AllWorkflowTemplates)('should produce correct output for %s',
        (input) => {
            const name = input.metadata.k8sMetadata.name;
            const result = renderWorkflowTemplate(input);
            expect(result).toMatchSnapshot(name);
        });
});
