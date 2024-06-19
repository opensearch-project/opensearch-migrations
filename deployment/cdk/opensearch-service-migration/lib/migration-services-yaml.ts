import * as yaml from 'yaml';

export class ClusterYaml {
    endpoint: string = '';
    no_auth?: string | null;
    basic_auth?: object | null;
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

export class ServicesYaml {
    source_cluster: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();
    backfill: RFSBackfillYaml | OSIBackfillYaml;

    stringify(): string {
        return yaml.stringify({
            source_cluster: this.source_cluster,
            target_cluster: this.target_cluster,
            metrics_source: this.metrics_source,
            backfill: this.backfill?.toDict()
        },
        {
            'nullStr': ''
        })
    }
}


