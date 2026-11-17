/**
 * Assert that the chart's workload NodePools carry the overrides passed to
 * bootstrapMA(workloadsNodePool:), and that every node they launched satisfies them.
 *
 * In the chart's default isolated mode, Kafka, capture proxies and replayers run on dedicated pools
 * (kafka-work-pool, proxy-work-pool, replayer-work-pool) that inherit each base setting unless the
 * chart's values.yaml overrides it for that pool; general-work-pool keeps only the remaining
 * workloads and may launch nothing in a CDC run. So:
 *
 * Checked on each NodePool object: every override the pool inherits (requirements and disruption),
 * plus limits on general-work-pool, the only pool the base limits apply to.
 * Checked on each node's labels: architectures, capacityTypes, instanceCategories, instanceSizes
 * and minInstanceGeneration -- the fields that decide which instances Karpenter may launch -- for
 * every field its pool inherits.
 *
 * Run it after the tests, not right after bootstrap: the chart's first pods can land on nodes the
 * bootstrap pool already started, so the workload pools may not have launched anything yet. By the
 * end of a test run the migration workloads have needed their own capacity, and WhenEmpty
 * consolidation keeps those nodes around long enough to inspect.
 *
 * Usage:
 *   verifyWorkloadNodePool(
 *       kubectlContext: env.eksKubeContext,
 *       workloadsNodePool: workloadsNodePool   // same map passed to bootstrapMA
 *   )
 */
def call(Map config = [:]) {
    def kubectlContext = config.kubectlContext
    def expected = config.workloadsNodePool
    if (!kubectlContext) { error("verifyWorkloadNodePool: 'kubectlContext' is required") }
    if (!expected) { error("verifyWorkloadNodePool: 'workloadsNodePool' is required") }
    def chartValuesFile = config.chartValuesFile ?:
        "${env.WORKSPACE}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml"
    def chartPools = readYaml(file: chartValuesFile).workloadsNodePool
    def mode = (expected.mode ?: chartPools.mode ?: 'isolated').toString()

    // The pool name and, for a dedicated pool, the chart defaults that replace inherited settings.
    def pools = [[name: (expected.name ?: chartPools.name ?: 'general-work-pool').toString(), overrides: [:], shared: true]]
    if (mode == 'isolated') {
        for (key in ['kafka', 'proxy', 'replayer']) {
            def pool = chartPools.dedicatedPools[key]
            pools << [name: pool.name.toString(), overrides: pool, shared: false]
        }
    }

    def failures = []
    def totalNodes = 0
    def summaries = []
    for (pool in pools) {
        def inherited = { field -> expected.containsKey(field) && !pool.overrides.containsKey(field) }
        def nodePool = readJSON text: sh(
            script: "kubectl --context='${kubectlContext}' get nodepool ${pool.name} -o json",
            returnStdout: true)
        def nodes = readJSON(text: sh(
            script: "kubectl --context='${kubectlContext}' get nodes -l karpenter.sh/nodepool=${pool.name} -o json",
            returnStdout: true)).items
        totalNodes += nodes.size()
        failures.addAll(checkPool(pool.name, nodePool, nodes, expected, inherited, pool.shared))
        summaries << "${pool.name} nodes:\n" + nodes.collect { n ->
            def l = n.metadata.labels
            "  ${n.metadata.name}: ${l['node.kubernetes.io/instance-type']} " +
                "arch=${l['kubernetes.io/arch']} capacity=${l['karpenter.sh/capacity-type']}"
        }.join("\n")
    }
    if (totalNodes == 0) {
        failures << "no workload NodePool (${pools.collect { it.name }.join(', ')}) launched nodes, " +
            "so the overrides were never exercised"
    }
    echo summaries.join("\n")

    if (failures) {
        error("Workload NodePools do not match the workloadsNodePool overrides:\n  - " + failures.join("\n  - "))
    }
    echo "Workload NodePools (${mode}) match the workloadsNodePool overrides: ${expected}"
}

private List checkPool(String poolName, nodePool, nodes, Map expected, Closure inherited, boolean shared) {
    def requirements = [:]
    for (r in nodePool.spec.template.spec.requirements) {
        requirements[r.key.toString()] = r
    }
    def failures = []

    // "In" requirements: the NodePool must list exactly the expected values (or omit the
    // requirement for an empty list), and every node's label must be one of them.
    def listFields = [
        architectures     : 'kubernetes.io/arch',
        capacityTypes     : 'karpenter.sh/capacity-type',
        instanceCategories: 'eks.amazonaws.com/instance-category',
        instanceSizes     : 'eks.amazonaws.com/instance-size',
    ]
    for (field in listFields.keySet()) {
        if (!inherited(field)) { continue }
        def label = listFields[field]
        def want = (expected[field] ?: []).collect { it.toString() } as Set
        def req = requirements[label]
        if (want.isEmpty()) {
            if (req) { failures << "${poolName} ${field}: expected no ${label} requirement, found ${req.values}" }
            continue
        }
        def got = req ? (req.values.collect { it.toString() } as Set) : null
        if (req?.operator?.toString() != 'In' || got != want) {
            failures << "${poolName} ${field}: expected ${label} In ${want}, found ${req ? "${req.operator} ${got}" : 'no requirement'}"
        }
        for (node in nodes) {
            def value = node.metadata.labels[label]?.toString()
            if (!(value in want)) {
                failures << "${poolName} ${field}: node ${node.metadata.name} has ${label}=${value}, expected one of ${want}"
            }
        }
    }

    if (inherited('minInstanceGeneration')) {
        def label = 'eks.amazonaws.com/instance-generation'
        def min = expected.minInstanceGeneration
        def req = requirements[label]
        if (!min) {
            if (req) { failures << "${poolName} minInstanceGeneration: expected no ${label} requirement, found ${req.operator} ${req.values}" }
        } else {
            def got = req ? req.values.collect { it.toString() } : null
            if (req?.operator?.toString() != 'Gte' || got != [min.toString()]) {
                failures << "${poolName} minInstanceGeneration: expected ${label} Gte [${min}], found ${req ? "${req.operator} ${got}" : 'no requirement'}"
            }
            for (node in nodes) {
                def value = node.metadata.labels[label]?.toString()
                if (!value?.isInteger() || value.toInteger() < (min as Integer)) {
                    failures << "${poolName} minInstanceGeneration: node ${node.metadata.name} has ${label}=${value}, expected >= ${min}"
                }
            }
        }
    }

    // Plain spec fields, compared as the strings Helm rendered. The base limits cap only the shared
    // pool; dedicated pools set their own.
    def specFields = [disruption: nodePool.spec.disruption]
    if (shared) {
        specFields.limits = nodePool.spec.limits
    }
    for (section in specFields.keySet()) {
        if (!inherited(section)) { continue }
        for (key in expected[section].keySet()) {
            def want = expected[section][key]?.toString()
            def got = specFields[section]?.get(key)?.toString()
            if (got != want) {
                failures << "${poolName} ${section}.${key}: expected ${want}, found ${got}"
            }
        }
    }
    return failures
}
