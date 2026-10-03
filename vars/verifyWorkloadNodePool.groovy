/**
 * Assert that the chart's general-work-pool NodePool carries the overrides passed to
 * bootstrapMA(workloadsNodePool:), and that every node it launched satisfies them.
 *
 * Checked on the NodePool object: every overridden field (requirements, limits, disruption).
 * Checked on each node's labels: architectures, capacityTypes, instanceCategories, instanceSizes
 * and minInstanceGeneration -- the fields that decide which instances Karpenter may launch.
 *
 * Run it after the tests, not right after bootstrap: the chart's first pods can land on nodes the
 * bootstrap pool already started, so general-work-pool may not have launched anything yet. By the
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

    def nodePool = readJSON text: sh(
        script: "kubectl --context='${kubectlContext}' get nodepool general-work-pool -o json",
        returnStdout: true)
    def nodes = readJSON(text: sh(
        script: "kubectl --context='${kubectlContext}' get nodes -l karpenter.sh/nodepool=general-work-pool -o json",
        returnStdout: true)).items

    def requirements = [:]
    for (r in nodePool.spec.template.spec.requirements) {
        requirements[r.key.toString()] = r
    }

    def failures = []
    if (nodes.size() == 0) {
        failures << "general-work-pool launched no nodes, so the overrides were never exercised"
    }

    // "In" requirements: the NodePool must list exactly the expected values (or omit the
    // requirement for an empty list), and every node's label must be one of them.
    def listFields = [
        architectures     : 'kubernetes.io/arch',
        capacityTypes     : 'karpenter.sh/capacity-type',
        instanceCategories: 'eks.amazonaws.com/instance-category',
        instanceSizes     : 'eks.amazonaws.com/instance-size',
    ]
    for (field in listFields.keySet()) {
        if (!expected.containsKey(field)) { continue }
        def label = listFields[field]
        def want = (expected[field] ?: []).collect { it.toString() } as Set
        def req = requirements[label]
        if (want.isEmpty()) {
            if (req) { failures << "${field}: expected no ${label} requirement, found ${req.values}" }
            continue
        }
        def got = req ? (req.values.collect { it.toString() } as Set) : null
        if (req?.operator?.toString() != 'In' || got != want) {
            failures << "${field}: expected ${label} In ${want}, found ${req ? "${req.operator} ${got}" : 'no requirement'}"
        }
        for (node in nodes) {
            def value = node.metadata.labels[label]?.toString()
            if (!(value in want)) {
                failures << "${field}: node ${node.metadata.name} has ${label}=${value}, expected one of ${want}"
            }
        }
    }

    if (expected.containsKey('minInstanceGeneration')) {
        def label = 'eks.amazonaws.com/instance-generation'
        def min = expected.minInstanceGeneration
        def req = requirements[label]
        if (!min) {
            if (req) { failures << "minInstanceGeneration: expected no ${label} requirement, found ${req.operator} ${req.values}" }
        } else {
            def got = req ? req.values.collect { it.toString() } : null
            if (req?.operator?.toString() != 'Gte' || got != [min.toString()]) {
                failures << "minInstanceGeneration: expected ${label} Gte [${min}], found ${req ? "${req.operator} ${got}" : 'no requirement'}"
            }
            for (node in nodes) {
                def value = node.metadata.labels[label]?.toString()
                if (!value?.isInteger() || value.toInteger() < (min as Integer)) {
                    failures << "minInstanceGeneration: node ${node.metadata.name} has ${label}=${value}, expected >= ${min}"
                }
            }
        }
    }

    // Plain spec fields, compared as the strings Helm rendered.
    def specFields = [limits: nodePool.spec.limits, disruption: nodePool.spec.disruption]
    for (section in specFields.keySet()) {
        if (!expected.containsKey(section)) { continue }
        for (key in expected[section].keySet()) {
            def want = expected[section][key]?.toString()
            def got = specFields[section]?.get(key)?.toString()
            if (got != want) {
                failures << "${section}.${key}: expected ${want}, found ${got}"
            }
        }
    }

    echo "general-work-pool nodes:\n" + nodes.collect { n ->
        def l = n.metadata.labels
        "  ${n.metadata.name}: ${l['node.kubernetes.io/instance-type']} " +
            "arch=${l['kubernetes.io/arch']} capacity=${l['karpenter.sh/capacity-type']}"
    }.join("\n")

    if (failures) {
        error("general-work-pool does not match the workloadsNodePool overrides:\n  - " + failures.join("\n  - "))
    }
    echo "general-work-pool matches the workloadsNodePool overrides: ${expected}"
}
