/**
 * Expand a version string like "ES_7.10" or "OS_1.3" into a
 * hyphenated form like "elasticsearch-7-10" or "opensearch-1-3".
 *
 * Used to build Kubernetes configmap names for cluster configs.
 *
 * Usage:
 *   def expanded = expandVersionString('ES_7.10')  // → "elasticsearch-7-10"
 */
def call(String input) {
    def trimmed = input.trim()
    def pattern = ~/^(ES|OS)_(\d+)\.(\d+)$/
    def matcher = trimmed =~ pattern
    if (!matcher.matches()) {
        error("Invalid version string format: '${input}'. Expected something like ES_7.10 or OS_1.3")
    }
    def prefix = matcher[0][1]
    def major  = matcher[0][2]
    def minor  = matcher[0][3]
    def name   = (prefix == 'ES') ? 'elasticsearch' : 'opensearch'
    return "${name}-${major}-${minor}"
}
