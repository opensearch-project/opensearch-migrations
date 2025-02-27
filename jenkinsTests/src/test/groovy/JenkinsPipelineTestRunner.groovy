import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JenkinsPipelineTestRunner extends BasePipelineTest {
    def script
    File originalDir

    JenkinsPipelineTestRunner() {
        setProjectDir()
    }

    void setProjectDir() {
        // When run from base project, projectDir is set, else navigate up parent
        String dir = System.getProperty('projectDir',
                new File(System.getProperty('user.dir')).getParent())
        // Store current directory
        originalDir = new File(dir)

        // Change to project root directory
        var testRoot = new File(dir, "vars").absolutePath
        System.setProperty("user.dir", testRoot)

        // Set up the helper's base script root
        helper.baseScriptRoot = testRoot
        scriptRoots = helper.scriptRoots = [testRoot]
        println "Changed working directory to: ${new File(System.getProperty("user.dir")).absolutePath}"
    }

    @BeforeEach
    void setUp() {
        super.setUp()

        // Load script with explicit error handling
        println "Attempting to load script: trafficReplayDefaultE2ETest.groovy"
        File scriptFile = new File(helper.baseScriptRoot, "trafficReplayDefaultE2ETest.groovy")
        println "Full script path: ${scriptFile.absolutePath}"
        println "Script file exists: ${scriptFile.exists()}"

        script = loadScript("trafficReplayDefaultE2ETest.groovy")
        println "Script loaded successfully"

        // Register common pipeline steps
        helper.registerAllowedMethod('defaultIntegPipeline', [Map.class], { Map config ->
            println "Pipeline called with config: ${config}"
            Assertions.assertEquals('source-single-node-ec2', config.sourceContextId)
            Assertions.assertEquals('migration-default', config.migrationContextId)
            Assertions.assertEquals('aws-integ', config.defaultStageId)
            Assertions.assertEquals('traffic-replay-default-e2e-test', config.jobName)
            return true
        })
        println "Setup complete"
    }

    @Test
    void testTrafficReplayPipeline() {
        println "Starting pipeline test"
        script.call([:])
        println "Pipeline execution complete"
        printCallStack()
    }

    void cleanup() {
        // Restore original directory if it was changed
        if (originalDir != null) {
            System.setProperty("user.dir", originalDir.absolutePath)
            println "Restored working directory to: ${originalDir.absolutePath}"
        }
    }
}