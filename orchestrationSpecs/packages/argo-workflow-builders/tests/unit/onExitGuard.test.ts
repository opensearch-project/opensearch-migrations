import {INTERNAL, WorkflowBuilder} from '../../src';

describe("addOnExit guard", () => {
    it("throws when addOnExit is called more than once", () => {
        expect(() =>
            WorkflowBuilder.create({k8sResourceName: "double-exit-test"})
                .addTemplate("cleanup1", t => t.addSteps(b => b))
                .addTemplate("cleanup2", t => t.addSteps(b => b))
                .addTemplate("main", t => t
                    .addSteps(b => b)
                    .addOnExit(INTERNAL, "cleanup1")
                    .addOnExit(INTERNAL, "cleanup2")
                )
        ).toThrow(/onExit handler already set/);
    });
});
