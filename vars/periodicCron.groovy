// Returns the periodic cron schedule for a given Jenkins job name.
//
// Single source of truth for periodic cadences. To change a cadence
// or add one for a new pipeline, edit the switch below.
//
// Jenkins' 'H' token auto-staggers jobs by hashing the job name,
// so jobs sharing the same cadence still fire at unique minutes/hours.
//
// pr-* jobs and any job not listed here return '' (no cadence).
def call(String jobName) {
    if (jobName == null) return ''

    // Release canaries: every 6h, staggered across the full day
    if (jobName.startsWith('release-')) return 'H H(0-23)/6 * * *'

    // Main-branch periodic jobs (per-job cadence)
    switch (jobName) {
        case 'main-deploy-eks-cfn-create-vpc':        return 'H H/12 * * *'
        case 'main-deploy-eks-cfn-import-vpc':        return 'H H/12 * * *'
        case 'main-eks-integ-test':                   return 'H */2 * * *'
        case 'main-elasticsearch-5x-k8s-local-test':  return '@hourly'
        case 'main-elasticsearch-8x-k8s-local-test':  return '@hourly'
        case 'main-full-es68source-e2e-test':         return '@hourly'
        case 'main-k8s-matrix-test':                  return 'H 22 * * *'
        case 'main-rfs-default-e2e-test':             return '@hourly'
        case 'main-solr-8x-k8s-local-test':           return 'H H(0-23)/6 * * *'
        case 'main-solutions-cfn-create-vpc-test':    return '@hourly'
        case 'main-eks-byos-integ-test':              return 'H H(0-23)/6 * * *'
        case 'main-eks-aoss-search-integ-test':       return 'H H(0-23)/6 * * *'
        case 'main-eks-aoss-timeseries-integ-test':   return 'H H(0-23)/6 * * *'
        case 'main-eks-aoss-vector-integ-test':       return 'H H(0-23)/6 * * *'
        case 'main-eks-cdc-full-e2e-test':            return 'H H(0-23)/6 * * *'
        case 'main-eks-cdc-aoss-e2e-test':            return 'H H(0-23)/6 * * *'
    }
    return ''
}
