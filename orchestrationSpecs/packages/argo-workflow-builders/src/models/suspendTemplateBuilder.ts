import {InputParametersRecord, OutputParametersRecord} from "./parameterSchemas";
import {GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {TemplateBodyBuilder, TemplateRebinder} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";

/** Duration in milliseconds - switch this to Temporal.Duration once that becomes widely adopted */
export type DurationInSeconds = number;

/** Convert Duration (ms) to seconds string for Argo */
export function durationToSeconds(duration: DurationInSeconds): string {
    return String(Math.floor(duration / 1000));
}

export class SuspendTemplateBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    { duration?: DurationInSeconds },
    OutputParamsScope,
    SuspendTemplateBuilder<ParentWorkflowScope, InputParamsScope, any>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: { duration?: DurationInSeconds },
        outputsScope: OutputParamsScope,
        synchronization: SynchronizationConfig | undefined
    ) {
        const templateRebind: TemplateRebinder<ParentWorkflowScope, InputParamsScope, GenericScope> = (
            ctx, inputs, body, outputs, sync
        ) => new SuspendTemplateBuilder(ctx, inputs, body as { duration?: DurationInSeconds }, outputs, sync) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, synchronization, templateRebind);
    }

    setDuration(duration: DurationInSeconds): SuspendTemplateBuilder<ParentWorkflowScope, InputParamsScope, OutputParamsScope> {
        return new SuspendTemplateBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            { duration },
            this.outputsScope,
            this.synchronization
        );
    }

    protected getBody(): { suspend: { duration?: DurationInSeconds } } {
        return this.bodyScope.duration !== undefined
            ? { suspend: { duration: this.bodyScope.duration } }
            : { suspend: {} };
    }
}
