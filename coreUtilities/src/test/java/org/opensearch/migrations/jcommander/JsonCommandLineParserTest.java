package org.opensearch.migrations.jcommander;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCommandLineParserTest {

    static class SimpleArgs {
        @Parameter(names = {"--name", "-n"})
        public String name;

        @Parameter(names = {"--count"})
        public int count;

        @Parameter(names = {"--enabled"})
        public boolean enabled;

        @Parameter(names = {"--tags"})
        public List<String> tags;
    }

    static class GlobalArgs {
        @Parameter(names = {"--verbose", "-v"})
        public boolean verbose;

        @Parameter(names = {"--config-file"})
        public String configFile;
    }

    static class ConnectionArgs {
        @Parameter(names = {"--host"})
        public String host = "localhost";

        @Parameter(names = {"--port"})
        public int port = 9200;

        @Parameter(names = {"--username"})
        public String username;
    }

    static class MainArgs {
        @Parameter(names = {"--snapshot-name"})
        public String snapshotName;

        @Parameter(names = {"--file-system-repo-path"})
        public String fileSystemRepoPath;

        @ParametersDelegate
        public ConnectionArgs connectionArgs = new ConnectionArgs();
    }

    @Parameters(commandNames = "migrate")
    static class MigrateCommand {
        @Parameter(names = {"--source"})
        public String source;

        @Parameter(names = {"--target"})
        public String target;

        @Parameter(names = {"--batch-size"})
        public int batchSize = 1000;
    }

    @Parameters(commandNames = "evaluate")
    static class EvaluateCommand {
        @Parameter(names = {"--dry-run"})
        public boolean dryRun;

        @Parameter(names = {"--report-path"})
        public String reportPath;
    }

    static class CommandWithoutAnnotation {
        @Parameter(names = {"--option"})
        public String option;
    }

    static class InvalidArgsClass {
        @Parameter(names = {"---INLINE-JSON"})
        public String reservedFlag;
    }

    // ============ Tests without commands ============

    @Test
    void testSimpleTraditionalParsing() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        parser.parse(new String[]{"--name", "test", "--count", "42", "--enabled"});

        Assertions.assertEquals("test", args.name);
        Assertions.assertEquals(42, args.count);
        Assertions.assertTrue(args.enabled);
    }

    @Test
    void testSimpleJsonInline() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"testJson\",\"count\":100,\"enabled\":true}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertEquals("testJson", args.name);
        Assertions.assertEquals(100, args.count);
        Assertions.assertTrue(args.enabled);
    }

    @Test
    void testSimpleJsonFile(@TempDir Path tempDir) throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        Path jsonFile = tempDir.resolve("config.json");
        String json = "{\"name\":\"fileTest\",\"count\":999,\"enabled\":false}";
        Files.writeString(jsonFile, json);

        parser.parse(new String[]{"---JSON-FILE", jsonFile.toString()});

        Assertions.assertEquals("fileTest", args.name);
        Assertions.assertEquals(999, args.count);
        Assertions.assertFalse(args.enabled);
    }

    @Test
    void testCamelCaseAndDashCase() throws Exception {
        MainArgs args = new MainArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        // Test camelCase
        String json1 = "{\"snapshotName\":\"snap1\",\"fileSystemRepoPath\":\"/path1\"}";
        parser.parse(new String[]{"---INLINE-JSON", json1});
        Assertions.assertEquals("snap1", args.snapshotName);
        Assertions.assertEquals("/path1", args.fileSystemRepoPath);

        // Test dash-case
        args = new MainArgs();
        parser = JsonCommandLineParser.newBuilder().addObject(args).build();
        String json2 = "{\"snapshot-name\":\"snap2\",\"file-system-repo-path\":\"/path2\"}";
        parser.parse(new String[]{"---INLINE-JSON", json2});
        Assertions.assertEquals("snap2", args.snapshotName);
        Assertions.assertEquals("/path2", args.fileSystemRepoPath);
    }

    @Test
    void testSimpleJsonBase64() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"base64Test\",\"count\":42,\"enabled\":true}";
        String base64 = java.util.Base64.getEncoder().encodeToString(json.getBytes());
        parser.parse(new String[]{"---INLINE-JSON", base64});

        Assertions.assertEquals("base64Test", args.name);
        Assertions.assertEquals(42, args.count);
        Assertions.assertTrue(args.enabled);
    }

    @Test
    void testBase64WithCommand() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"verbose\":true,\"source\":\"/src\",\"target\":\"/dst\",\"batchSize\":500}";
        String base64 = java.util.Base64.getEncoder().encodeToString(json.getBytes());
        parser.parse(new String[]{"migrate", "---INLINE-JSON", base64});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
        Assertions.assertEquals(500, migrateCmd.batchSize);
    }

    @Test
    void testParametersDelegate() throws Exception {
        MainArgs args = new MainArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"snapshotName\":\"test\",\"host\":\"db.example.com\",\"port\":9300,\"username\":\"admin\"}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertEquals("test", args.snapshotName);
        Assertions.assertEquals("db.example.com", args.connectionArgs.host);
        Assertions.assertEquals(9300, args.connectionArgs.port);
        Assertions.assertEquals("admin", args.connectionArgs.username);
    }

    @Test
    void testListParameter() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"test\",\"tags\":[\"tag1\",\"tag2\",\"tag3\"]}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertEquals("test", args.name);
        Assertions.assertNotNull(args.tags);
        Assertions.assertEquals(3, args.tags.size());
        Assertions.assertEquals("tag1", args.tags.get(0));
        Assertions.assertEquals("tag2", args.tags.get(1));
        Assertions.assertEquals("tag3", args.tags.get(2));
    }

    // ============ Tests with commands ============

    @Test
    void testTraditionalCommandParsing() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand("migrate", migrateCmd)
            .build();

        parser.parse(new String[]{"--verbose", "migrate", "--source", "/src", "--target", "/dst"});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
    }

    @Test
    void testAddCommandWithAnnotation() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"source\":\"/src\",\"target\":\"/dst\"}";
        parser.parse(new String[]{"migrate", "---INLINE-JSON", json});

        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
    }

    @Test
    void testAddCommandWithoutAnnotationThrows() {
        GlobalArgs globalArgs = new GlobalArgs();
        CommandWithoutAnnotation cmd = new CommandWithoutAnnotation();

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            JsonCommandLineParser.newBuilder()
                .addObject(globalArgs)
                .addCommand(cmd)
                .build();
        });

        Assertions.assertTrue(exception.getMessage().contains("must have @Parameters annotation"));
    }

    @Test
    void testJsonCommandBeforeFlag() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"verbose\":true,\"source\":\"/src\",\"target\":\"/dst\",\"batchSize\":500}";
        parser.parse(new String[]{"migrate", "---INLINE-JSON", json});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
        Assertions.assertEquals(500, migrateCmd.batchSize);
    }

    @Test
    void testJsonCommandAfterFlag() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"verbose\":true,\"source\":\"/src\",\"target\":\"/dst\"}";
        parser.parse(new String[]{"---INLINE-JSON", json, "migrate"});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
    }

    @Test
    void testJsonCommandInJson() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"jcommanderCommand\":\"migrate\",\"verbose\":true,\"source\":\"/src\",\"target\":\"/dst\"}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
    }

    @Test
    void testJsonFileCommandBeforeFlag(@TempDir Path tempDir) throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        Path configFile = tempDir.resolve("migrate-config.json");
        String json = "{\"verbose\":true,\"source\":\"/data/old\",\"target\":\"/data/new\",\"batchSize\":2000}";
        Files.writeString(configFile, json);

        // Command before file flag: migrate ---JSON-FILE config.json
        parser.parse(new String[]{"migrate", "---JSON-FILE", configFile.toString()});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/data/old", migrateCmd.source);
        Assertions.assertEquals("/data/new", migrateCmd.target);
        Assertions.assertEquals(2000, migrateCmd.batchSize);
    }

    @Test
    void testJsonFileCommandAfterFlag(@TempDir Path tempDir) throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        Path configFile = tempDir.resolve("migrate-config.json");
        String json = "{\"verbose\":true,\"source\":\"/data/old\",\"target\":\"/data/new\"}";
        Files.writeString(configFile, json);

        // Command after file flag: ---JSON-FILE config.json migrate
        parser.parse(new String[]{"---JSON-FILE", configFile.toString(), "migrate"});

        Assertions.assertTrue(globalArgs.verbose);
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/data/old", migrateCmd.source);
        Assertions.assertEquals("/data/new", migrateCmd.target);
    }

    @Test
    void testMultipleCommands() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();
        EvaluateCommand evaluateCmd = new EvaluateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)    // Using annotation
            .addCommand(evaluateCmd)   // Using annotation
            .build();

        // Test migrate command
        String json1 = "{\"source\":\"/src\",\"target\":\"/dst\"}";
        parser.parse(new String[]{"migrate", "---INLINE-JSON", json1});
        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("/src", migrateCmd.source);

        // Test evaluate command
        globalArgs = new GlobalArgs();
        evaluateCmd = new EvaluateCommand();
        parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(new MigrateCommand())
            .addCommand(evaluateCmd)
            .build();

        String json2 = "{\"dryRun\":true,\"reportPath\":\"/reports/eval.txt\"}";
        parser.parse(new String[]{"---INLINE-JSON", json2, "evaluate"});
        Assertions.assertEquals("evaluate", parser.getParsedCommand());
        Assertions.assertTrue(evaluateCmd.dryRun);
        Assertions.assertEquals("/reports/eval.txt", evaluateCmd.reportPath);
    }

    @Test
    void testCommandWithGlobalArgsAndDelegate() throws Exception {
        MainArgs mainArgs = new MainArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(mainArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{"
            + "\"snapshotName\":\"snapshot-001\","
            + "\"host\":\"prod-server.com\","
            + "\"port\":9200,"
            + "\"username\":\"migrator\","
            + "\"source\":\"/old-index\","
            + "\"target\":\"/new-index\","
            + "\"batchSize\":5000"
            + "}";

        parser.parse(new String[]{"migrate", "---INLINE-JSON", json});

        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("snapshot-001", mainArgs.snapshotName);
        Assertions.assertEquals("prod-server.com", mainArgs.connectionArgs.host);
        Assertions.assertEquals(9200, mainArgs.connectionArgs.port);
        Assertions.assertEquals("migrator", mainArgs.connectionArgs.username);
        Assertions.assertEquals("/old-index", migrateCmd.source);
        Assertions.assertEquals("/new-index", migrateCmd.target);
        Assertions.assertEquals(5000, migrateCmd.batchSize);
    }

    // ============ Error handling tests ============

    @Test
    void testReservedFlagValidation() {
        InvalidArgsClass args = new InvalidArgsClass();

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            JsonCommandLineParser.newBuilder()
                .addObject(args)
                .build();
        });

        Assertions.assertTrue(exception.getMessage().contains("reserved for JSON configuration mode"));
        Assertions.assertTrue(exception.getMessage().contains("---INLINE-JSON"));
    }

    @Test
    void testInvalidCommand() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"source\":\"/src\"}";

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"invalid-command", "---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unknown command"));
        Assertions.assertTrue(exception.getMessage().contains("invalid-command"));
    }

    @Test
    void testInvalidCommandInJson() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"jcommanderCommand\":\"invalid\",\"source\":\"/src\"}";

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unknown command in JSON"));
        Assertions.assertTrue(exception.getMessage().contains("invalid"));
    }

    @Test
    void testUnexpectedArgumentWhenNoCommands() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"test\"}";

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json, "extra-arg"});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unexpected argument"));
        Assertions.assertTrue(exception.getMessage().contains("No commands have been configured"));
    }

    @Test
    void testTooManyArguments() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        String json = "{\"source\":\"/src\"}";

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"migrate", "---INLINE-JSON", json, "extra"});
        });

        Assertions.assertTrue(exception.getMessage().contains("Invalid arguments"));
    }

    @Test
    void testMissingJsonArgument() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON"});
        });

        Assertions.assertTrue(exception.getMessage().contains("requires an argument immediately after it"));
    }

    @Test
    void testMissingJsonFileArgument() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---JSON-FILE"});
        });

        Assertions.assertTrue(exception.getMessage().contains("requires an argument immediately after it"));
    }

    @Test
    void testJsonFileNotFound() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---JSON-FILE", "/nonexistent/file.json"});
        });

        Assertions.assertTrue(exception.getMessage().contains("JSON file not found"));
    }

    @Test
    void testNullValues() throws Exception {
        SimpleArgs args = new SimpleArgs();
        args.name = "initialValue";

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":null,\"count\":0}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertNull(args.name);
        Assertions.assertEquals(0, args.count);
    }

    @Test
    void testDefaultValuesPreserved() throws Exception {
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addCommand(migrateCmd)
            .build();

        String json = "{\"source\":\"/src\",\"target\":\"/dst\"}";
        parser.parse(new String[]{"migrate", "---INLINE-JSON", json});

        Assertions.assertEquals("/src", migrateCmd.source);
        Assertions.assertEquals("/dst", migrateCmd.target);
        Assertions.assertEquals(1000, migrateCmd.batchSize); // Default value preserved
    }

    @Test
    void testTraditionalVsJsonEquivalence() throws Exception {
        // Traditional parsing
        GlobalArgs globalArgs1 = new GlobalArgs();
        MigrateCommand migrateCmd1 = new MigrateCommand();
        JsonCommandLineParser parser1 = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs1)
            .addCommand(migrateCmd1)
            .build();
        parser1.parse(new String[]{"--verbose", "migrate", "--source", "/src", "--target", "/dst", "--batch-size", "2000"});

        // JSON parsing
        GlobalArgs globalArgs2 = new GlobalArgs();
        MigrateCommand migrateCmd2 = new MigrateCommand();
        JsonCommandLineParser parser2 = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs2)
            .addCommand(migrateCmd2)
            .build();
        String json = "{\"verbose\":true,\"source\":\"/src\",\"target\":\"/dst\",\"batchSize\":2000}";
        parser2.parse(new String[]{"migrate", "---INLINE-JSON", json});

        // Both should produce identical results
        Assertions.assertEquals(globalArgs1.verbose, globalArgs2.verbose);
        Assertions.assertEquals(parser1.getParsedCommand(), parser2.getParsedCommand());
        Assertions.assertEquals(migrateCmd1.source, migrateCmd2.source);
        Assertions.assertEquals(migrateCmd1.target, migrateCmd2.target);
        Assertions.assertEquals(migrateCmd1.batchSize, migrateCmd2.batchSize);
    }

    @Test
    void testRealWorldScenario(@TempDir Path tempDir) throws Exception {
        MainArgs mainArgs = new MainArgs();
        MigrateCommand migrateCmd = new MigrateCommand();
        EvaluateCommand evaluateCmd = new EvaluateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(mainArgs)
            .addCommand(migrateCmd)
            .addCommand(evaluateCmd)
            .build();

        // Realistic production config file
        Path configFile = tempDir.resolve("production-migration.json");
        String config = "{\n"
            + "  \"snapshotName\": \"prod-backup-2024-10\",\n"
            + "  \"fileSystemRepoPath\": \"/mnt/elasticsearch/snapshots\",\n"
            + "  \"host\": \"es-cluster.prod.company.com\",\n"
            + "  \"port\": 9200,\n"
            + "  \"username\": \"migration-service\",\n"
            + "  \"source\": \"legacy-customer-index\",\n"
            + "  \"target\": \"customers-v2\",\n"
            + "  \"batchSize\": 10000\n"
            + "}";
        Files.writeString(configFile, config);

        // Use like: java App migrate ---JSON-FILE /path/to/production-migration.json
        parser.parse(new String[]{"migrate", "---JSON-FILE", configFile.toString()});

        Assertions.assertEquals("migrate", parser.getParsedCommand());
        Assertions.assertEquals("prod-backup-2024-10", mainArgs.snapshotName);
        Assertions.assertEquals("/mnt/elasticsearch/snapshots", mainArgs.fileSystemRepoPath);
        Assertions.assertEquals("es-cluster.prod.company.com", mainArgs.connectionArgs.host);
        Assertions.assertEquals(9200, mainArgs.connectionArgs.port);
        Assertions.assertEquals("migration-service", mainArgs.connectionArgs.username);
        Assertions.assertEquals("legacy-customer-index", migrateCmd.source);
        Assertions.assertEquals("customers-v2", migrateCmd.target);
        Assertions.assertEquals(10000, migrateCmd.batchSize);
    }

    @Test
    void testAllDataTypes() throws Exception {
        class AllTypesArgs {
            @Parameter(names = "--string")
            public String stringVal;

            @Parameter(names = "--int")
            public int intVal;

            @Parameter(names = "--integer")
            public Integer integerVal;

            @Parameter(names = "--long")
            public long longVal;

            @Parameter(names = "--double")
            public double doubleVal;

            @Parameter(names = "--float")
            public float floatVal;

            @Parameter(names = "--boolean")
            public boolean booleanVal;
        }

        AllTypesArgs args = new AllTypesArgs();
        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"stringVal\":\"test\",\"intVal\":42,\"integerVal\":100," +
            "\"longVal\":1000000,\"doubleVal\":3.14159,\"floatVal\":2.71," +
            "\"booleanVal\":true}";
        parser.parse(new String[]{"---INLINE-JSON", json});

        Assertions.assertEquals("test", args.stringVal);
        Assertions.assertEquals(42, args.intVal);
        Assertions.assertEquals(100, args.integerVal);
        Assertions.assertEquals(1000000L, args.longVal);
        Assertions.assertEquals(3.14159, args.doubleVal, 0.0001);
        Assertions.assertEquals(2.71f, args.floatVal, 0.01f);
        Assertions.assertTrue(args.booleanVal);
    }

    @Test
    void testUnrecognizedJsonKey() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"test\",\"unknownParameter\":\"value\"}";

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unrecognized parameter"));
        Assertions.assertTrue(exception.getMessage().contains("unknownParameter"));
    }

    @Test
    void testMultipleUnrecognizedJsonKeys() throws Exception {
        SimpleArgs args = new SimpleArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"name\":\"test\",\"typo1\":\"value1\",\"typo2\":\"value2\"}";

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unrecognized parameter"));
        Assertions.assertTrue(exception.getMessage().contains("typo1"));
        Assertions.assertTrue(exception.getMessage().contains("typo2"));
    }

    @Test
    void testTypoInParameterName() throws Exception {
        MainArgs args = new MainArgs();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        // Typo: "snapshotNam" instead of "snapshotName"
        String json = "{\"snapshotNam\":\"test\"}";

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unrecognized parameter"));
        Assertions.assertTrue(exception.getMessage().contains("snapshotNam"));
        // Should show valid parameters to help user
        Assertions.assertTrue(exception.getMessage().contains("Valid parameters"));
    }

    @Test
    void testUnrecognizedJsonKeyWithCommand() throws Exception {
        GlobalArgs globalArgs = new GlobalArgs();
        MigrateCommand migrateCmd = new MigrateCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(globalArgs)
            .addCommand(migrateCmd)
            .build();

        // "invalidKey" doesn't belong to GlobalArgs or MigrateCommand
        String json = "{\"source\":\"/src\",\"target\":\"/dst\",\"invalidKey\":\"value\"}";

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(new String[]{"migrate", "---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Unrecognized parameter"));
        Assertions.assertTrue(exception.getMessage().contains("invalidKey"));
    }
}
