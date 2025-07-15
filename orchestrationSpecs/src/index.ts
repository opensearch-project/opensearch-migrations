import { z } from 'zod';
import {NormalizedParamDef, defineParam, paramsToCallerSchema, defineRequiredParam} from './schemas/workflowSchemas'
import { CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG } from './schemas/userSchemas'

const WorkflowParameters = {
    etcdEndpoints:       defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
    etcdUser:            defineRequiredParam({ type: z.string(), description: "" }),
    etcdPassword:        defineParam({ defaultValue: "password" }),
    // etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
    // s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" })
} as const;

const WorkflowParamsSignature = paramsToCallerSchema(WorkflowParameters);
type WorkflowInputs = z.infer<typeof WorkflowParamsSignature>;

const workflowInputs : WorkflowInputs = {
    etcdEndpoints: "",
    etcdUser: "",
    etcdPassword: ""
}


const FullMigrationInputs = {
    sourceMigrationConfigs: defineParam({ type: SNAPSHOT_MIGRATION_CONFIG,
        description: "List of server configurations to direct migrated traffic toward",
    //defaultValue = {}
    }),
    targets: defineParam({ type: CLUSTER_CONFIG,
        description: "List of server configurations to direct migrated traffic toward" } ),
//    s3Params: defineParam({type: }),
    imageParams: defineParam({
        type: z.object(
            Object.fromEntries([ "captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils" ]
                .flatMap((k) => [
                    [`${k}Image`, z.string()],
                    [`${k}ImagePullPolicy`, z.string()]
                ])
            )
        ),
        description: "OCI image locations and pull policies for required images"
    })
} as const satisfies Record<string, NormalizedParamDef<any>>;

const FullMigrationSignature = paramsToCallerSchema(FullMigrationInputs);
type FullMigrationMainInputs = z.infer<typeof FullMigrationSignature>;
