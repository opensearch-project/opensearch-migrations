import * as yaml from 'yaml';

export class ClusterYaml {
    endpoint: string = '';
    no_auth?: string;
    basic_auth?: object;
}

export class MetricsSourceYaml {
    type: string = 'cloudwatch';
    region?: string;
}

export class ServicesYaml {
    source_cluster: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();

    stringify(): string {
        return yaml.stringify({
            source_cluster: this.source_cluster,
            target_cluster: this.target_cluster,
            metrics_source: this.metrics_source
        })
    }
}


