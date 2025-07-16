import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import { CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG } from '../schemas/userSchemas'

export type K8sWorkflowTemplate<WORKFLOW_PARAMS> = {
    name: string,
    serviceAccountName: string,
    workflowParameters: WORKFLOW_PARAMS
};