import * as yaml from 'yaml';

export class ClusterBasicAuth {
    username: string;
    password?: string;
    password_from_secret_arn?: string;

    constructor({
        username,
        password,
        password_from_secret_arn,
    }: {
        username: string;
        password?: string;
        password_from_secret_arn?: string;
    }) {
        this.username = username;
        this.password = password;
        this.password_from_secret_arn = password_from_secret_arn;

        // Validation: Exactly one of password or password_from_secret_arn must be provided
        if ((password && password_from_secret_arn) || (!password && !password_from_secret_arn)) {
            throw new Error('Exactly one of password or password_from_secret_arn must be provided');
        }
    }
}

export class ClusterYaml {
    endpoint: string = '';
    no_auth?: string | null;
    basic_auth?: ClusterBasicAuth | null;
}

export class MetricsSourceYaml {
    cloudwatch? : object | null = null;
}

export class ECSService {
    cluster_name: string | undefined;
    service_name: string | undefined;
    aws_region: string | undefined;
    constructor() {
        this.cluster_name = undefined;
        this.service_name = undefined;
        this.aws_region = undefined;
    }
}

export class RFSBackfillYaml {
    ecs: ECSService;
    constructor() {
        this.ecs = new ECSService();
    }

    toDict() {
        return {
            reindex_from_snapshot: {ecs: this.ecs}
        };
    }
}

export class OSIBackfillYaml {
    toDict() {
        return {
            opensearch_ingestion: null
        };
    }
}

export class ECSReplayerYaml {
    ecs: ECSService;
    scale: number = 1;

    constructor() {
        this.ecs = new ECSService();
    }

    toDict() {
        return {
            ecs: this.ecs,
            scale: this.scale
        };
    }
}

export class FileSystemSnapshotYaml {
    repo_path: string = '';
}

export class S3SnapshotYaml {
    repo_uri: string = '';
    aws_region: string = '';
}

export class SnapshotYaml {
    snapshot_name: string = '';
    otel_endpoint: string = '';
    s3?: S3SnapshotYaml;
    fs?: FileSystemSnapshotYaml;

    toDict() {
        return {
            snapshot_name: this.snapshot_name,
            otel_endpoint: this.otel_endpoint,
            // This conditinally includes the s3 and fs parameters if they're defined,
            // but does not add the keys otherwise
            ...(this.s3 && { s3: this.s3 }),
            ...(this.fs && { fs: this.fs })
        };
    }
}

// This component can be much more complicated (specified snapshot details, index/component/template allowlists, etc.)
// but for the time being, we are assuming that the snapshot is the one specified in SnapshotYaml.
export class MetadataMigrationYaml {
    from_snapshot: null = null;
    min_replicas: number = 1;
    otel_endpoint: string = '';
}

export class MSKYaml {
}

export class StandardKafkaYaml {
}

export class KafkaYaml {
    broker_endpoints: string = '';
    msk?: string | null;
    standard?: string | null;
}

export class ServicesYaml {
    source_cluster: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();
    backfill: RFSBackfillYaml | OSIBackfillYaml;
    snapshot?: SnapshotYaml;
    metadata_migration?: MetadataMigrationYaml;
    replayer?: ECSReplayerYaml;
    kafka?: KafkaYaml;

    stringify(): string {
        return yaml.stringify({
            source_cluster: this.source_cluster,
            target_cluster: this.target_cluster,
            metrics_source: this.metrics_source,
            backfill: this.backfill?.toDict(),
            snapshot: this.snapshot?.toDict(),
            metadata_migration: this.metadata_migration,
            replay: this.replayer?.toDict(),
            kafka: this.kafka
        },
        {
            'nullStr': ''
        })
    }
}


