package org.opensearch.migrations.trafficcapture;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.Summariser;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;

public class JMeterLoadTest {

    static class Parameters {
        @Parameter(required = true, names = { "-p", "--port" }, description = "Port number")
        int backsidePort;
        @Parameter(required = true, names = { "-s", "--server-name" }, description = "Server name")
        String domainName;
        @Parameter(required = false, names = {
            "-r",
            "--protocol" }, description = "Protocol used. HTTP or HTTPS. Default = HTTPS")
        String protocol = "HTTPS";
    }

    public static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", args));
            jCommander.usage();
            return null;
        }
    }

    public static void main(String[] args) {
        var params = parseArgs(args);
        StandardJMeterEngine jmeter = new StandardJMeterEngine();
        File home = new File(".");
        JMeterUtils.setJMeterHome(home.getPath());
        File jmeterProperties = new File(home, "jmeter.properties");
        if (!jmeterProperties.exists()) {
            try {
                System.out.println("Couldn't find a provided jmeter.properties file, creating a new one.");
                jmeterProperties.createNewFile();
            } catch (IOException e) {
                System.out.println("Error creating jmeter.properties file: " + e.getMessage());
            }
        }
        JMeterUtils.loadJMeterProperties(jmeterProperties.getPath());

        String outputFileName = null;
        // outputFileName = "results.jtl";
        ListedHashTree hashTree = createTestPlan(
            params.protocol,
            params.domainName,
            params.backsidePort,
            1000,
            8,
            1,
            false,
            outputFileName
        );

        jmeter.configure(hashTree);
        jmeter.run();
    }

    @NotNull
    private static ListedHashTree createTestPlan(
        String protocol,
        String domain,
        int port,
        int loopCount,
        int workerThreadCount,
        int summaryUpdateFrequencySeconds,
        boolean verifyResponse,
        String logOutputFileName
    ) {
        ListedHashTree hashTree = new ListedHashTree();

        TestPlan testPlan = new TestPlan("Test Plan");
        testPlan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());

        FileReference[] files = new FileReference[] {
            new FileReference("100.txt", 't', 100, true),
            new FileReference("1K.txt", 's', 1000, true),
            new FileReference("10K.txt", 'm', 1000 * 10, true),
            new FileReference("100K.txt", 'L', 1000 * 100, false),
            new FileReference("1M.txt", 'X', 1000 * 1000, false),
            // new FileReference("10M.txt", 'H', 1000 * 1000 * 10, false)
        };

        hashTree.add(testPlan);
        hashTree.add(testPlan, createTimer());
        {
            var threadGroupHashTree = hashTree.add(
                testPlan,
                createThreadGroup("FireAndForgetGroup", loopCount, workerThreadCount, 1)
            );
            Arrays.stream(files)
                .forEach(
                    fr -> threadGroupHashTree.add(createHttpSampler(protocol, domain, port, fr, "GET", verifyResponse))
                );
        }
        {
            var threadGroupHashTree = hashTree.add(
                testPlan,
                createThreadGroup("TransactionGroup", loopCount, workerThreadCount, 1)
            );
            Arrays.stream(files)
                .forEach(
                    fr -> threadGroupHashTree.add(createHttpSampler(protocol, domain, port, fr, "POST", verifyResponse))
                );
        }
        hashTree.add(testPlan, createResultCollector(logOutputFileName, summaryUpdateFrequencySeconds));
        return hashTree;
    }

    @NotNull
    private static ResultCollector createResultCollector(String resultsOutputFile, int summaryUpdateFrequencySeconds) {
        Summariser summarizer = null;
        String summariserName = JMeterUtils.getPropDefault("summarizer.name", "So far...");
        JMeterUtils.setProperty("summariser.interval", "1");
        if (summariserName.length() > 0) {
            summarizer = new Summariser(summariserName);
        }

        ResultCollector resultCollector = new ResultCollector(summarizer);
        resultCollector.setProperty(TestElement.TEST_CLASS, ResultCollector.class.getName());
        Optional.ofNullable(resultsOutputFile).ifPresent(f -> resultCollector.setFilename(f));
        return resultCollector;
    }

    @NotNull
    private static RegexExtractor createExtractor() {
        RegexExtractor regexExtractor = new RegexExtractor();
        regexExtractor.setProperty(TestElement.TEST_CLASS, RegexExtractor.class.getName());
        regexExtractor.setName("Regex Extractor");
        regexExtractor.setUseField(RegexExtractor.USE_BODY);
        regexExtractor.setRefName("data");
        regexExtractor.setRegex("(.*)");
        regexExtractor.setTemplate("$1$");
        regexExtractor.setMatchNumber(1);
        return regexExtractor;
    }

    @NotNull
    private static ResponseAssertion createResponseAssertion(char c, int count) {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        assertion.setName("ResponseAssertion");
        assertion.setEnabled(true);
        assertion.setTestFieldResponseData();
        assertion.addTestString("^" + c + "{" + count + "}$");
        assertion.setToMatchType();
        return assertion;
    }

    @NotNull
    private static HashTree createHttpSampler(
        String protocol,
        String domain,
        int port,
        FileReference fr,
        String method,
        boolean verifyResponse
    ) {
        HTTPSampler httpSampler = new HTTPSampler();
        httpSampler.setProperty(TestElement.TEST_CLASS, HTTPSampler.class.getName());
        httpSampler.setName(method + " Sampler");
        httpSampler.setMethod(method);
        httpSampler.setDomain(domain);
        httpSampler.setPort(port);
        httpSampler.setProtocol(protocol);
        httpSampler.setPath(fr.filename);
        var hashTree = new ListedHashTree();
        hashTree.add(httpSampler)
            .add(
                Arrays.stream(
                    new Object[] {
                        fr.verifyResponse && verifyResponse ? createResponseAssertion(fr.testChar, fr.count) : null,
                        createExtractor() }
                ).filter(o -> o != null).toArray()
            );
        return hashTree;
    }

    @NotNull
    private static ThreadGroup createThreadGroup(String name, int loopCount, int numThreads, int rampUp) {
        LoopController loopController = new LoopController();
        loopController.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
        loopController.setLoops(loopCount);
        loopController.setFirst(true);
        loopController.initialize();

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(TestElement.TEST_CLASS, ThreadGroup.class.getName());
        threadGroup.setName(name);
        threadGroup.setNumThreads(numThreads);
        threadGroup.setRampUp(rampUp);
        threadGroup.setScheduler(false);
        threadGroup.setSamplerController(loopController);
        return threadGroup;
    }

    @NotNull
    private static ConstantTimer createTimer() {
        ConstantTimer constantTimer = new ConstantTimer();
        constantTimer.setProperty(TestElement.TEST_CLASS, ConstantTimer.class.getName());
        constantTimer.setName("Constant Timer");
        constantTimer.setEnabled(true);
        constantTimer.setDelay("10");
        return constantTimer;
    }

    @AllArgsConstructor
    public static class FileReference {
        public final String filename;
        public final char testChar;
        public final int count;
        public final boolean verifyResponse;
    }
}
