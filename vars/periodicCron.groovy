// Returns the periodic cron schedule for a given Jenkins job name.
//
// Every job returns '' (no schedule): the tests run in GitHub Actions instead. Jenkins drops a
// job's cron trigger the next time that job runs with this library.
def call(String jobName) {
    return ''
}
