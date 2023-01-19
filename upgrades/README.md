# Upgrade Testing Framework

This package is built on the `cluster_migration_core` library and provides runnable steps to test ElasticSearch and OpenSearch upgrades.  It's purpose is to facilitate the creation of a centralized store of the Elasticsearch/OpenSearch community's understanding of the "happy-path" and "sad-path" behavior expected when performing a cluster version upgrade by providing a mechanism to assert that the knowledge store is accurate.  As an example, it should generally be true that the number of documents stored in a cluster should remain the same before and after a software upgrade; this framework makes it easy to assert that is the case for a specific cluster, starting/ending versions, and upgrade type.  It tests that expectation by spinning up clusters locally in Docker and performing an actual upgrade of the software while interrogating the source/target clusters along the way.

Having a way to test these expectations about upgrade behavior means we have confidence in their accuracy.  That confidence opens up future possibilities, such as being able to provide a report on the issues we expect a user to encounter when perform an cluster software upgrade between two version, WITHOUT them having to actually perform that upgrade themselves.  As more expectations are added to our central store, and more tests added to the UTF, the community will gain greater and greater confidence in the cost and risks of proposed software upgrades and prevent many users from inpendendently/blindly encountering the same problem.  Once the issue is added to the central store and a test for it added to the UTF, its status should be promoted from "unknown unknown" to "known".

## How This Tool Is Structured

The UTF is based around the following concepts:
* A sequence of "Steps" that are executed in a set order as part of a "Workflow"
* A "Runner" that takes a Workflow and executes the steps in it sequentially, while managing things like error handling, logging, and state
* A shared "State" that exists in-memory of the process and enables the results of one step to be re-used by a later step
* A "Test Config" JSON file that encapsulates the specific upgrade to be tested (e.g. snapshot/restore from ES 7.10.2 to OS 1.3.6).  This package will contain a collection of pre-canned Test Configs (`./test_configs/`) that should represent the limits of the UTF's knowledge about what is true about how Upgrades work.  In other words - if there isn't a Test Config file in the included collection that covers the specific cluster setup and upgrade type you're interested in, the UTF is not testing that setup/upgrade type.
* A set of "Test Actions" that are performed at various points in a Workflow via an existing library called the [Robot Framework](https://robotframework.org/?tab=2#getting-started).  These currently at `./upgrade_testing_framework/robot_test_defs/`
* A set of "Expectations" that represent things we expect to be true about the upgrade specified in the Test Config file (number of documents should remain the same before/after).  Each Expectation has an Expectation ID that is used to track which Test Actions are associated with a given Expectation, and determine if the Expectation was true or not.  These Expectation IDs are associated with each Test Action as a tag that the UTF search for and enable selective invocation.
* A "Knowledge Base" that represents all the Expectations we're currently tracking and (hopefully) testing, currently located at `../knowledge_base/`.

## Running the Upgrade Testing Framework

To run the UTF, perform the following steps.

### PRE-REQUISITES

* Python3 and venv
* Currently in the same directory as this README, the setup.py, etc

### Step 1 - Activate your Python virtual environment

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

You can exit the Python virtual environment and remove its resources like so:
```
deactivate
rm -rf .venv
```

Learn more about venv [here](https://docs.python.org/3/library/venv.html).

### Step 2 - Run the UTF

Run the UTF framework with the `run_utf.py` script and a test config file. For the default snapshot/restore upgrade from Elasticsearch 7.10.2 to OpenSearch 1.3.6, it can be invoked with:
```
./run_utf.py --test_config test_configs/snapshot_restore_es_7_10_2_to_os_1_3_6.json
```

### Step 3 - Interpreting The Results

At the end of a UTF run, the final results of the test should be printed to STDOUT.  Something like:

```
[FrameworkRunner] ============ Running Step: ReportResults ============
[ReportResults] ========================================== FINAL RESULTS ==========================================
{
    "failing_expectations": [],
    "passing_expectations": [
        "consistent-document-count"
    ],
    "untested_expectations": [
        "doy-format-date-range-query-bug"
    ]
}
[ReportResults] For more information about how to interpret these results, please consult the Upgrade Testing Framework's README file: https://github.com/opensearch-project/opensearch-migrations/blob/main/upgrades/README.md
[FrameworkRunner] Step Succeeded: ReportResults
```

As explained above, the UTF is focused on checking whether our Expectations about the upgrade-under-test are true or not.  In this example above, we had two Expectations in our Knowledge Base that were relevant to this upgrade - `consistent-document-count` and `doy-format-date-range-query-bug`.  These strings are both Expectation IDs, and can be used to find more details about the specific expectation is/what versions it applies to (check `../knowledge_base/`) and how they are being tested (`./upgrade_testing_framework/robot_test_defs/`).  The ID can be searched for in both place to find the relevant bits.

We split our results into three categories:
* Passing Expectations: These Expectations had Test Actions associated with them, and the Test Actions' results matched what we thought they should.  In the example above, we checked that the document count was the same before after an upgrade (a happy-path test).  However, if we were aware of a bug that should present itself as part of the upgrade, and that bug did present itself in the way we expected, then that would be considered a "passing" Expectation as well.  In other words - passing Expectations indicate that the upgrade went how we thought it should, for good or ill.
* Failing Expectations: These Expectations had Test Actions associated with them, but they did not perform like we thought the should.  For example, if the number of documents wasn't the same before and after the upgrade, or a bug we knew about failed to be detected.
* Untested Expectations: These Expectations didn't have Test Actions associated with them.  Mostly likely, this means that there's a new Expectation in our Knowledge Base that no one has had time to actually check for with Test Actions.  Think of this as an indication of where our maintainers/contributors should be investing them time next! :-)

## Understanding The UTF's Feedback

The UTF provides substantial quantities of feedback via a combination of messages to STDOUT, log files, a state file, and HTML reports.  Let's go through each of them in turn.

#### STDOUT/Your Terminal

The UTF provides high-level feedback about the overall flow of a Workflow directly to STDOUT.  In most cases, this should be the only place a user needs to look - except when things go wrong.  Think of this as your INFO+ level log channel for the UTF as a whole.

Pro-tip: each log line to STDOUT is prefixed by the specific Step in the Workflow that is currently being performed.  This can help you track down where exactly things went wrong.  These step names correspond to the names of classes in the UTF's codebase.

#### Log File - UTF Run Logs

Every UTF run should (unless things went \*really\* wrong) end with a printout like this to STDOUT indicating where you can find DEBUG level logs:

```
[FrameworkRunner] Saving application state to file...
[FrameworkRunner] Application state saved
[FrameworkRunner] Application state saved to: /tmp/utf/state-file
[FrameworkRunner] Full run details logged to: /tmp/utf/logs/run.log.2023-01-05_15_44_42
```

The file `/tmp/utf/logs/run.log.2023-01-05_15_44_42` contains a much higher level of detail about what the UTF is doing and can help you troubleshoot issues that you couldn't from just STDOUT alone.

#### UTF State File

The UTF has an application State that is shared between all Steps in a Workflow to enable information passing between them.  This State is dumped as a JSON blob to a file on-disk whenever the UTF process exits.  It can be helpful to look at this in the case there's a really wacky bug that even the DEBUG level logs in the UTF Run Log can't shed light on, or if the UTF isn't logging something that it should.  Think of the State File as something akin to a dump of the application memory of UTF at the point at which the application ended.

You can find the path to the file in the ending printout to STDOUT:

```
[FrameworkRunner] Saving application state to file...
[FrameworkRunner] Application state saved
[FrameworkRunner] Application state saved to: /tmp/utf/state-file
[FrameworkRunner] Full run details logged to: /tmp/utf/logs/run.log.2023-01-05_15_44_42
```

#### HTML Reports

The Robot Framework used to execute our Test Actions produces logs and reports as well, and the paths to them are sent to STDOUT as part of a Workflow:

```
[FrameworkRunner] Step Succeeded: StartSourceCluster
[FrameworkRunner] ============ Running Step: PerformPreUpgradeTest ============
====================================================================================================
Robot Test Defs
====================================================================================================
Robot Test Defs.Common Upgrade Test
====================================================================================================
Perform pre-upgrade setup of "consistent-document-count" expectation                        | PASS |
----------------------------------------------------------------------------------------------------                                                                                  Robot Test Defs.Common Upgrade Test                                                         | PASS |
1 test, 1 passed, 0 failed
====================================================================================================
Robot Test Defs                                                                             | PASS |
1 test, 1 passed, 0 failed
====================================================================================================
Output:  /tmp/utf/test-results/pre-upgrade/output.xml
Log:     /tmp/utf/test-results/pre-upgrade/log.html
Report:  /tmp/utf/test-results/pre-upgrade/report.html
[FrameworkRunner] Step Succeeded: PerformPreUpgradeTest
```

These files would be useful when a specific Test Action failed and the reason why isn't apparent from either the messaging to STDOUT or the UTF Run Log.  More information about what these files are can be found in [the Robot Framework's documentation](https://robotframework.org/robotframework/latest/RobotFrameworkUserGuide.html#different-output-files).

#### Current State & Limitations

The UTF is currently functional and runnable, but of limited utility. The following are areas where we expect to need to invest more time or investigation:
- Adding more expectations to the knowledge base and corresponding test actions. There is a discussion in [PR #68](https://github.com/opensearch-project/opensearch-migrations/pull/68) regarding the format of expectations. Generally, the value of this project is directly correlated to the number and breadth of the expectations and tests, so this is a very important area of investment.
- Expanding the functionality of the OpenSearchRESTActions library in `./upgrade_testing_framework/robot_lib`. This library contains code that makes various actions available to the robot framework (e.g. running specific api calls against the cluster).
- These libraries are currently not distibuted in any way beyond this git repo. They could be packaged for PyPI and distributed as standalone tools.
- Supporting alternate upgrade mechanisms in the `cluster_migration_core` library. Snapshot & restore is the only mechanism currently supported and it has limitations including being unable to do upgrades beyond version N+1. To simulate and test upgrades between more disparate versions, support needs to be added for either multi-step upgrades or different mechanisms.
- Supporting additional version of Elasticsearch/OpenSearch. We currently have targeted ES 7.10.2 -> OS 1.3.6 and support that through the code. To add additional versions, some minor changes will likely be needed in `cluster_migration_core` and `upgrade_testing_framework` (more conditional branches, maybe changes in some configuration syntax). Additionally, a new Test Config will need to be created and added to `./test_configs` to encapsulate the new test type.
