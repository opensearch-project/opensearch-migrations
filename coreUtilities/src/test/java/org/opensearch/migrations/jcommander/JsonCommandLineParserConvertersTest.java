package org.opensearch.migrations.jcommander;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.beust.jcommander.converters.IParameterSplitter;
import com.beust.jcommander.converters.IntegerConverter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;



public class JsonCommandLineParserConvertersTest {
    static class FileConverter implements IStringConverter<File> {
        @Override
        public File convert(String value) {
            return new File(value);
        }
    }

    static class PathConverter implements IStringConverter<Path> {
        @Override
        public Path convert(String value) {
            return Paths.get(value);
        }
    }

    static class UpperCaseConverter implements IStringConverter<String> {
        @Override
        public String convert(String value) {
            return value.toUpperCase();
        }
    }

    static class CustomIntegerConverter implements IStringConverter<Integer> {
        @Override
        public Integer convert(String value) {
            // Add 1000 to any integer to verify converter is being used
            return Integer.parseInt(value) + 1000;
        }
    }

    static class ThrowingConverter implements IStringConverter<String> {
        @Override
        public String convert(String value) {
            throw new IllegalArgumentException("Converter failed: " + value);
        }
    }

    // Converter with String constructor (some JCommander converters use this)
    static class ConverterWithStringConstructor implements IStringConverter<String> {
        private final String prefix;

        public ConverterWithStringConstructor(String errorMessagePrefix) {
            this.prefix = errorMessagePrefix != null ? errorMessagePrefix : "PREFIX:";
        }

        @Override
        public String convert(String value) {
            return prefix + value;
        }
    }

    // Converter with no valid constructor
    static class BadConstructorConverter implements IStringConverter<String> {
        public BadConstructorConverter(int someInt, String someString) {
            // This constructor signature won't be callable
        }

        @Override
        public String convert(String value) {
            return value;
        }
    }

    static class CommaSplitter implements IParameterSplitter {
        @Override
        public List<String> split(String value) {
            return Arrays.asList(value.split(","));
        }
    }

// ============ Test Classes with Converters ============

    static class ArgsWithConverters {
        @Parameter(names = "--file", converter = FileConverter.class)
        public File configFile;

        @Parameter(names = "--path", converter = PathConverter.class)
        public Path workingPath;

        @Parameter(names = "--upper", converter = UpperCaseConverter.class)
        public String upperCaseString;

        @Parameter(names = "--custom-int", converter = CustomIntegerConverter.class)
        public Integer customInteger;

        @Parameter(names = "--regular-string")
        public String regularString;
    }

    static class ArgsWithListConverter {
        @Parameter(names = "--ports", converter = IntegerConverter.class)
        public List<Integer> ports;

        @Parameter(names = "--files", converter = FileConverter.class)
        public List<File> files;

        @Parameter(names = "--upper-list", converter = UpperCaseConverter.class)
        public List<String> upperCaseList;
    }

    static class ArgsWithSplitterAndConverter {
        @Parameter(names = "--ports", converter = IntegerConverter.class, splitter = CommaSplitter.class)
        public List<Integer> ports;

        @Parameter(names = "--files", converter = FileConverter.class, splitter = CommaSplitter.class)
        public List<File> files;
    }

    static class ArgsWithThrowingConverter {
        @Parameter(names = "--bad", converter = ThrowingConverter.class)
        public String badField;
    }

    static class ArgsWithStringConstructorConverter {
        @Parameter(names = "--prefixed", converter = ConverterWithStringConstructor.class)
        public String prefixedValue;
    }

    static class ArgsWithBadConstructorConverter {
        @Parameter(names = "--bad", converter = BadConstructorConverter.class)
        public String badField;
    }

// ============ Converter Tests ============

    @Test
    void testBasicConverters() throws Exception {
        ArgsWithConverters args = new ArgsWithConverters();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{"
            + "\"file\":\"/path/to/config.txt\","
            + "\"path\":\"/home/user/work\","
            + "\"upper\":\"hello world\","
            + "\"customInt\":42,"
            + "\"regularString\":\"unchanged\""
            + "}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // File converter should create File object
        Assertions.assertNotNull(args.configFile);
        Assertions.assertEquals("/path/to/config.txt", args.configFile.getPath());

        // Path converter should create Path object
        Assertions.assertNotNull(args.workingPath);
        Assertions.assertEquals("/home/user/work", args.workingPath.toString());

        // UpperCase converter should uppercase the string
        Assertions.assertEquals("HELLO WORLD", args.upperCaseString);

        // Custom integer converter adds 1000 to verify it's being used
        Assertions.assertEquals(1042, args.customInteger);

        // Regular string should not be converted
        Assertions.assertEquals("unchanged", args.regularString);
    }

    @Test
    void testListConverters() throws Exception {
        ArgsWithListConverter args = new ArgsWithListConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{"
            + "\"ports\":[\"8080\",\"9090\",\"3000\"],"
            + "\"files\":[\"/tmp/a.txt\",\"/tmp/b.txt\"],"
            + "\"upperList\":[\"hello\",\"world\",\"test\"]"
            + "}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // Integer converter on list
        Assertions.assertNotNull(args.ports);
        Assertions.assertEquals(3, args.ports.size());
        Assertions.assertEquals(8080, args.ports.get(0));
        Assertions.assertEquals(9090, args.ports.get(1));
        Assertions.assertEquals(3000, args.ports.get(2));

        // File converter on list
        Assertions.assertNotNull(args.files);
        Assertions.assertEquals(2, args.files.size());
        Assertions.assertEquals("/tmp/a.txt", args.files.get(0).getPath());
        Assertions.assertEquals("/tmp/b.txt", args.files.get(1).getPath());

        // UpperCase converter on list
        Assertions.assertNotNull(args.upperCaseList);
        Assertions.assertEquals(3, args.upperCaseList.size());
        Assertions.assertEquals("HELLO", args.upperCaseList.get(0));
        Assertions.assertEquals("WORLD", args.upperCaseList.get(1));
        Assertions.assertEquals("TEST", args.upperCaseList.get(2));
    }

    @Test
    void testConverterWithSplitter() throws Exception {
        ArgsWithSplitterAndConverter args = new ArgsWithSplitterAndConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        // When using JSON arrays with splitter, the array should be joined
        // and then split according to the splitter
        String json = "{"
            + "\"ports\":[\"8080,9090\",\"3000\"],"
            + "\"files\":[\"/tmp/a.txt,/tmp/b.txt\",\"/tmp/c.txt\"]"
            + "}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // Ports should be split by comma and converted to integers
        Assertions.assertNotNull(args.ports);
        Assertions.assertEquals(3, args.ports.size());
        Assertions.assertEquals(8080, args.ports.get(0));
        Assertions.assertEquals(9090, args.ports.get(1));
        Assertions.assertEquals(3000, args.ports.get(2));

        // Files should be split by comma and converted to File objects
        Assertions.assertNotNull(args.files);
        Assertions.assertEquals(3, args.files.size());
        Assertions.assertEquals("/tmp/a.txt", args.files.get(0).getPath());
        Assertions.assertEquals("/tmp/b.txt", args.files.get(1).getPath());
        Assertions.assertEquals("/tmp/c.txt", args.files.get(2).getPath());
    }

    @Test
    void testConverterWithNullValue() throws Exception {
        ArgsWithConverters args = new ArgsWithConverters();
        args.configFile = new File("/initial/file.txt");
        args.upperCaseString = "INITIAL";

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{"
            + "\"file\":null,"
            + "\"upper\":null"
            + "}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // Null values should set fields to null even with converters
        Assertions.assertNull(args.configFile);
        Assertions.assertNull(args.upperCaseString);
    }

    @Test
    void testConverterException() throws Exception {
        ArgsWithThrowingConverter args = new ArgsWithThrowingConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"bad\":\"some value\"}";

        // Converter throws exception, which should propagate
        Exception exception = Assertions.assertThrows(Exception.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Converter failed") ||
            exception.getCause().getMessage().contains("Converter failed"));
    }

    @Test
    void testConverterWithStringConstructor() throws Exception {
        ArgsWithStringConstructorConverter args = new ArgsWithStringConstructorConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"prefixed\":\"test-value\"}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // Should use the String constructor and prefix the value
        Assertions.assertEquals("PREFIX:test-value", args.prefixedValue);
    }

    @Test
    void testBadConverterConstructor() throws Exception {
        ArgsWithBadConstructorConverter args = new ArgsWithBadConstructorConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{\"bad\":\"value\"}";

        // Should fail because converter has no valid constructor
        Exception exception = Assertions.assertThrows(Exception.class, () -> {
            parser.parse(new String[]{"---INLINE-JSON", json});
        });

        Assertions.assertTrue(exception.getMessage().contains("Cannot instantiate converter") ||
            exception.getCause().getMessage().contains("Cannot instantiate converter"));
    }

    @Test
    void testConverterNotCalledForNonJsonValues() throws Exception {
        // This test verifies that converters work correctly with traditional parsing too
        ArgsWithConverters args = new ArgsWithConverters();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        // Traditional command line parsing should still use JCommander's converter
        parser.parse(new String[]{"--upper", "hello", "--custom-int", "50"});

        // These should use JCommander's built-in converter handling, not our JSON converter
        // JCommander would apply the converters, so these assertions verify
        // that we haven't broken traditional parsing
        Assertions.assertEquals("HELLO", args.upperCaseString); // JCommander applies converter
        Assertions.assertEquals(1050, args.customInteger); // JCommander applies converter
    }

    // Test that converters work with @ParametersDelegate
    static class DelegateWithConverter {
        @Parameter(names = "--delegate-file", converter = FileConverter.class)
        public File delegateFile;

        @Parameter(names = "--delegate-upper", converter = UpperCaseConverter.class)
        public String delegateUpper;
    }

    static class MainWithDelegateAndConverter {
        @Parameter(names = "--main-path", converter = PathConverter.class)
        public Path mainPath;

        @ParametersDelegate
        public DelegateWithConverter delegate = new DelegateWithConverter();
    }

    @Test
    void testMixedConvertersAndDelegates() throws Exception {
        MainWithDelegateAndConverter args = new MainWithDelegateAndConverter();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addObject(args)
            .build();

        String json = "{"
            + "\"mainPath\":\"/main/path\","
            + "\"delegateFile\":\"/delegate/file.txt\","
            + "\"delegateUpper\":\"hello delegate\""
            + "}";

        parser.parse(new String[]{"---INLINE-JSON", json});

        // Main converter
        Assertions.assertNotNull(args.mainPath);
        Assertions.assertEquals("/main/path", args.mainPath.toString());

        // Delegate converters
        Assertions.assertNotNull(args.delegate.delegateFile);
        Assertions.assertEquals("/delegate/file.txt", args.delegate.delegateFile.getPath());
        Assertions.assertEquals("HELLO DELEGATE", args.delegate.delegateUpper);
    }

    @Parameters(commandNames = "convert")
    static class ConvertCommand {
        @Parameter(names = "--input", converter = FileConverter.class)
        public File inputFile;

        @Parameter(names = "--output", converter = FileConverter.class)
        public File outputFile;

        @Parameter(names = "--format", converter = UpperCaseConverter.class)
        public String format;
    }

    @Test
    void testConverterWithCommand() throws Exception {
        ConvertCommand cmd = new ConvertCommand();

        JsonCommandLineParser parser = JsonCommandLineParser.newBuilder()
            .addCommand(cmd)
            .build();

        String json = "{"
            + "\"input\":\"/input/data.txt\","
            + "\"output\":\"/output/result.txt\","
            + "\"format\":\"json\""
            + "}";

        parser.parse(new String[]{"convert", "---INLINE-JSON", json});

        Assertions.assertEquals("convert", parser.getParsedCommand());
        Assertions.assertNotNull(cmd.inputFile);
        Assertions.assertEquals("/input/data.txt", cmd.inputFile.getPath());
        Assertions.assertNotNull(cmd.outputFile);
        Assertions.assertEquals("/output/result.txt", cmd.outputFile.getPath());
        Assertions.assertEquals("JSON", cmd.format);
    }
}
