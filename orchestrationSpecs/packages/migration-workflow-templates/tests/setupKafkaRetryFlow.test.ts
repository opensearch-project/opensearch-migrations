import { renderWorkflowTemplate } from "@opensearch-migrations/argo-workflow-builders";
import { SetupKafka } from "../src/workflowTemplates/setupKafka";

describe("setup-kafka retry flow", () => {
    it("renders the Kafka topic reconciliation loop with suspend-and-retry recursion", () => {
        const rendered = renderWorkflowTemplate(SetupKafka);
        const template = rendered.spec.templates.find(entry => entry.name === "createkafkatopicwithretry");

        expect(template).toBeDefined();
        expect(template?.steps?.[0]?.[0]?.name).toBe("tryApply");
        expect(template?.steps?.[0]?.[0]?.continueOn).toEqual({ failed: true });
        expect(template?.steps?.[1]?.[0]?.name).toBe("waitForFix");
        expect(template?.steps?.[1]?.[0]?.template).toBe("suspendforretry");
        expect(template?.steps?.[1]?.[0]?.when).toContain("steps.tryApply.status");
        expect(template?.steps?.[2]?.[0]?.name).toBe("retryLoop");
        expect(template?.steps?.[2]?.[0]?.template).toBe("createkafkatopicwithretry");
        expect(template?.steps?.[2]?.[0]?.when).toContain("steps.waitForFix.status");
    });

    it("renders KafkaUser reconciliation for managed SCRAM auth", () => {
        const rendered = renderWorkflowTemplate(SetupKafka);
        const retryTemplate = rendered.spec.templates.find(entry => entry.name === "createkafkauserwithretry");
        const manifestTemplate = rendered.spec.templates.find(entry => entry.name === "createkafkauser");
        const clusterTemplate = rendered.spec.templates.find(entry => entry.name === "deploykafkaclusterwithretry");

        expect(retryTemplate).toBeDefined();
        expect(retryTemplate?.steps?.[0]?.[0]?.template).toBe("createkafkauser");
        expect(retryTemplate?.steps?.[1]?.[0]?.template).toBe("suspendforretry");
        expect(clusterTemplate?.steps?.[2]?.[0]?.template).toBe("createkafkauserwithretry");
        expect(clusterTemplate?.steps?.[2]?.[0]?.when).toContain("scram-sha-512");
        expect(JSON.stringify(manifestTemplate)).toContain("KafkaUser");
        expect(JSON.stringify(manifestTemplate)).toContain("migration-app");
        expect(JSON.stringify(manifestTemplate)).toContain("{{inputs.parameters.userSpec}}");
        expect(JSON.stringify(retryTemplate)).toContain("authentication");
    });
});
