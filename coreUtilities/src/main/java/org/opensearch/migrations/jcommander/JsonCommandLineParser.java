package org.opensearch.migrations.jcommander;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.beust.jcommander.converters.IParameterSplitter;
import com.beust.jcommander.converters.NoConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Unified parser that handles both traditional command-line arguments
 * via JCommander and JSON configuration via Jackson.
 * </p><p>
 * Usage examples:
 * </p><p>
 * Without subcommands:
 *   Traditional: java App --name foo --count 42
 *   JSON inline: java App ---INLINE-JSON '{"name":"foo","count":42}'
 *   JSON file:   java App ---JSON-FILE /path/to/config.json
 * </p><p>
 * With subcommands (command can be before or after JSON flag):
 *   Traditional: java App migrate --source /src --target /dst
 *   JSON inline: java App migrate ---INLINE-JSON '{"source":"/src","target":"/dst"}'
 *   JSON inline: java App ---INLINE-JSON '{"source":"/src","target":"/dst"}' migrate
 *   JSON file:   java App migrate ---JSON-FILE /path/to/config.json
 *   JSON file:   java App ---JSON-FILE /path/to/config.json migrate
 * </p>
 */
@Slf4j
public class JsonCommandLineParser {
    private final ObjectMapper objectMapper;
    private static final String INLINE_JSON_FLAG = "---INLINE-JSON";
    private static final String JSON_FILE_FLAG = "---JSON-FILE";
    public static final String JCOMMANDER_COMMAND_JSON_FIELD_NAME = "jcommanderCommand";

    @Getter
    private final JCommander jCommander;
    private final List<Object> mainObjects = new ArrayList<>();
    private final Map<String, Object> commandObjects = new LinkedHashMap<>();

    private JsonCommandLineParser(Builder builder, ObjectMapper objectMapper) throws ReflectiveOperationException {
        this.objectMapper = objectMapper;
        // Validate that no Parameters use reserved flags
        validateNoReservedFlags(builder.mainObjects);
        for (Object commandObj : builder.commandObjects.values()) {
            validateNoReservedFlags(Collections.singletonList(commandObj));
        }

        JCommander.Builder jcBuilder = JCommander.newBuilder();

        // Add main objects
        for (Object obj : builder.mainObjects) {
            jcBuilder.addObject(obj);
            mainObjects.add(obj);
        }

        // Add commands
        for (Map.Entry<String, Object> entry : builder.commandObjects.entrySet()) {
            jcBuilder.addCommand(entry.getKey(), entry.getValue());
            commandObjects.put(entry.getKey(), entry.getValue());
        }

        this.jCommander = jcBuilder.build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private final List<Object> mainObjects = new ArrayList<>();
        private final Map<String, Object> commandObjects = new LinkedHashMap<>();

        /**
         * Add a main/global object to parse (no subcommand)
         */
        public Builder addObject(Object obj) {
            mainObjects.add(obj);
            return this;
        }

        /**
         * Add a subcommand with explicit name and argument object
         */
        public Builder addCommand(String commandName, Object commandObj) {
            commandObjects.put(commandName, commandObj);
            return this;
        }

        /**
         * Add a subcommand, extracting the command name from @Parameters annotation
         */
        public Builder addCommand(Object commandObj) {
            Parameters params = commandObj.getClass().getAnnotation(Parameters.class);
            if (params == null || params.commandNames() == null || params.commandNames().length == 0) {
                throw new IllegalArgumentException(
                    "Command object " + commandObj.getClass().getSimpleName() +
                        " must have @Parameters annotation with commandNames defined");
            }
            String commandName = params.commandNames()[0];
            commandObjects.put(commandName, commandObj);
            return this;
        }

        @SneakyThrows
        public JsonCommandLineParser build() {
            return build(new ObjectMapper());
        }

        @SneakyThrows
        public JsonCommandLineParser build(ObjectMapper objectMapper) {
            return new JsonCommandLineParser(this, objectMapper);
        }
    }

    /**
     * Parse arguments - automatically detects traditional vs JSON mode
     */
    @SneakyThrows
    public void parse(String[] args) {
        OuterParsedArgs parsedArgs = detectMode(args);

        if (parsedArgs.isJsonMode()) {
            parseJson(parsedArgs);
        } else {
            jCommander.parse(args);
        }
    }

    /**
     * Get the parsed command name (for subcommand mode), or null if no command was used
     */
    public String getParsedCommand() {
        return jCommander.getParsedCommand();
    }

    private static class OuterParsedArgs {
        private static final String[] NO_EXTRA_ARGS = new String[0];
        final String jsonContent;
        final String commandName;
        final String[] extraArgs;

        public OuterParsedArgs() {
            this.commandName = null;
            this.jsonContent = null;
            this.extraArgs = NO_EXTRA_ARGS;
        }

        public OuterParsedArgs(String jsonContent, String commandName, String[] extraArgs) {
            this.commandName = commandName;
            this.extraArgs = extraArgs;
            String trimmedContent = jsonContent.trim();
            this.jsonContent = (trimmedContent.startsWith("{") || trimmedContent.startsWith("[")) ? jsonContent :
                decodeBase64String(jsonContent);
        }

        private String decodeBase64String(String str) {
            log.atInfo().setMessage("Decoding argument as base64").log(); // don't show the contents because they might include sensitive data
            return new String(Base64.getDecoder().decode(str));
        }

        boolean isJsonMode() { return jsonContent != null; }
    }

    private static String loadContentsFromFile(String jsonArg) throws IOException {
        File file = new File(jsonArg);
        if (!file.exists()) {
            throw new IllegalArgumentException("JSON file not found: " + jsonArg);
        }
        return new String(Files.readAllBytes(Paths.get(jsonArg)));
    }

    private OuterParsedArgs detectMode(String[] args) throws IOException {
        if (args.length == 0) {
            return new OuterParsedArgs();
        }

        // Look for JSON flags
        int jsonFlagIndex = -1;
        boolean isInline = false;

        for (int i = 0; i < args.length; i++) {
            if (INLINE_JSON_FLAG.equals(args[i])) {
                jsonFlagIndex = i;
                isInline = true;
                break;
            } else if (JSON_FILE_FLAG.equals(args[i])) {
                jsonFlagIndex = i;
                break;
            }
        }

        if (jsonFlagIndex == -1) {
            return new OuterParsedArgs(); // Should use JCommander - no JSON flags found
        }

        if (jsonFlagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(
                (isInline ? INLINE_JSON_FLAG : JSON_FILE_FLAG) + " requires an argument immediately after it");
        }

        String jsonArg = args[jsonFlagIndex + 1];
        var jsonContent = isInline ? jsonArg : loadContentsFromFile(jsonArg);

        // Valid shapes: [flag, json], [command, flag, json], [flag, json, command] — optionally
        // followed by ordinary --name value arguments, which overlay the JSON's values (how a
        // launcher adds per-invocation parameters without rewriting the JSON document).
        if (jsonFlagIndex > 1) {
            throw new IllegalArgumentException(
                "Invalid arguments. Expected: [---INLINE-JSON|---JSON-FILE] <json> [command] " +
                    "or [command] [---INLINE-JSON|---JSON-FILE] <json>");
        }
        String commandName = jsonFlagIndex == 1 ? args[0] : null;
        int consumed = jsonFlagIndex + 2;
        if (commandName == null && consumed < args.length && !args[consumed].startsWith("-")) {
            commandName = args[consumed];
            consumed++;
        }
        String[] extraArgs = Arrays.copyOfRange(args, consumed, args.length);

        if (commandName != null) {
            if (commandObjects.isEmpty()) {
                throw new IllegalArgumentException(
                    "Unexpected argument: '" + commandName + "'. No commands have been configured.");
            }
            if (!commandObjects.containsKey(commandName)) {
                throw new IllegalArgumentException(
                    "Unknown command: '" + commandName + "'. " +
                        "Available commands: " + commandObjects.keySet());
            }
        } else if (!commandObjects.isEmpty()) { // check json fields
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            if (rootNode.has(JCOMMANDER_COMMAND_JSON_FIELD_NAME)) {
                commandName = rootNode.get(JCOMMANDER_COMMAND_JSON_FIELD_NAME).asText();
                if (!commandObjects.containsKey(commandName)) {
                    throw new IllegalArgumentException(
                        "Unknown command in JSON: '" + commandName + "'. " +
                            "Available commands: " + commandObjects.keySet());
                }
            }
        }

        return new OuterParsedArgs(jsonContent, commandName, extraArgs);
    }

    /**
     * {@code --name value} / {@code --flag} arguments trailing the JSON, as a JSON object so they
     * populate (and validate) through exactly the same path as the document itself. A boolean
     * parameter takes a value only when the next token is {@code true} or {@code false}; a list
     * parameter collects every occurrence; an unknown name keeps its value as text so validation
     * reports it.
     */
    private ObjectNode buildOverlayNode(String[] extraArgs, String commandName) throws ReflectiveOperationException {
        Map<String, FieldInfo> parameters = new HashMap<>();
        for (Object obj : mainObjects) {
            parameters.putAll(buildParameterMap(obj, obj.getClass()));
        }
        if (commandName != null) {
            Object commandObj = commandObjects.get(commandName);
            parameters.putAll(buildParameterMap(commandObj, commandObj.getClass()));
        }
        var overlay = objectMapper.createObjectNode();
        for (int i = 0; i < extraArgs.length; i++) {
            String arg = extraArgs[i];
            if (!arg.startsWith("-")) {
                throw new IllegalArgumentException(
                    "Invalid arguments: expected a --name argument after the JSON content, but got '" + arg + "'");
            }
            String name = arg.replaceAll("^-+", "");
            FieldInfo fieldInfo = parameters.get(name);
            Class<?> type = fieldInfo == null ? String.class : fieldInfo.field.getType();
            String next = i + 1 < extraArgs.length ? extraArgs[i + 1] : null;
            if (type == boolean.class || type == Boolean.class) {
                boolean explicit = "true".equalsIgnoreCase(next) || "false".equalsIgnoreCase(next);
                overlay.put(name, explicit ? Boolean.parseBoolean(extraArgs[++i]) : true);
            } else if (next == null || next.startsWith("-")) {
                throw new IllegalArgumentException("Invalid arguments: " + arg + " requires a value");
            } else if (List.class.isAssignableFrom(type)) {
                JsonNode existing = overlay.get(name);
                var values = existing instanceof ArrayNode array ? array : overlay.putArray(name);
                values.add(extraArgs[++i]);
            } else {
                overlay.put(name, extraArgs[++i]);
            }
        }
        return overlay;
    }

    private void parseJson(OuterParsedArgs parsedArgs) throws JsonProcessingException, ReflectiveOperationException
    {

        JsonNode rootNode = objectMapper.readTree(parsedArgs.jsonContent);

        // Build combined parameter map from ALL objects that will be populated
        validateAllKeysAreParameters(parsedArgs.commandName, rootNode);

        for (Object obj : mainObjects) {
            populateFromJson(objectMapper, obj, rootNode);
        }

        if (parsedArgs.commandName != null) {
            Object commandObj = commandObjects.get(parsedArgs.commandName);
            populateFromJson(objectMapper, commandObj, rootNode);

            Field parsedCommandField = JCommander.class.getDeclaredField("parsedCommand");
            parsedCommandField.setAccessible(true);
            parsedCommandField.set(jCommander, parsedArgs.commandName);
        }

        if (parsedArgs.extraArgs.length > 0) {
            // Explicit command-line arguments overlay the JSON document's values.
            ObjectNode overlay = buildOverlayNode(parsedArgs.extraArgs, parsedArgs.commandName);
            validateAllKeysAreParameters(parsedArgs.commandName, overlay);
            for (Object obj : mainObjects) {
                populateFromJson(objectMapper, obj, overlay);
            }
            if (parsedArgs.commandName != null) {
                populateFromJson(objectMapper, commandObjects.get(parsedArgs.commandName), overlay);
            }
        }
    }

    private void validateAllKeysAreParameters(String commandName, JsonNode rootNode)
        throws ReflectiveOperationException
    {
        var allParameters = new HashSet<>();
        for (Object obj : mainObjects) {
            allParameters.addAll(buildParameterMap(obj, obj.getClass()).keySet());
        }
        if (commandName != null) {
            Object commandObj = commandObjects.get(commandName);
            allParameters.addAll(buildParameterMap(commandObj, commandObj.getClass()).keySet());
        }

        var unrecognizedKeys = new ArrayList<>();
        var jsonKeys = rootNode.fieldNames();
        while (jsonKeys.hasNext()) {
            String jsonKey = jsonKeys.next();
            if (!JCOMMANDER_COMMAND_JSON_FIELD_NAME.equals(jsonKey) && !allParameters.contains(jsonKey)) {
                unrecognizedKeys.add(jsonKey);
            }
        }

        if (!unrecognizedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                "Unrecognized parameter(s) in JSON: " + unrecognizedKeys + ". " + "Valid parameters: " + allParameters);
        }
    }

    private static void validateNoReservedFlags(List<Object> objects) throws ReflectiveOperationException {
        for (Object obj : objects) {
            validateNoReservedFlagsRecursive(obj, obj.getClass());
        }
    }

    private static void validateNoReservedFlagsRecursive(Object obj, Class<?> clazz)
        throws ReflectiveOperationException
    {
        for (Field field : clazz.getDeclaredFields()) {
            Parameter param = field.getAnnotation(Parameter.class);
            if (param != null) {
                for (String name : param.names()) {
                    if (INLINE_JSON_FLAG.equals(name) ||
                        JSON_FILE_FLAG.equals(name) ||
                        JCOMMANDER_COMMAND_JSON_FIELD_NAME.equals(name))
                    {
                        throw new IllegalArgumentException(
                            "Parameter name '" + name + "' is reserved for JSON configuration mode. " +
                                "Please use a different parameter name in " + clazz.getSimpleName() +
                                "." + field.getName());
                    }
                }
            }

            ParametersDelegate delegate = field.getAnnotation(ParametersDelegate.class);
            if (delegate != null) {
                field.setAccessible(true);
                Object delegateObj = field.get(obj);
                if (delegateObj == null) {
                    delegateObj = field.getType().getDeclaredConstructor().newInstance();
                }
                validateNoReservedFlagsRecursive(delegateObj, delegateObj.getClass());
            }
        }
    }

    private static void populateFromJson(ObjectMapper objectMapper, Object obj, JsonNode jsonNode)
        throws ReflectiveOperationException, JsonProcessingException {
        Map<String, FieldInfo> parameterMap = buildParameterMap(obj, obj.getClass());

        var fieldNames = jsonNode.fieldNames();
        while (fieldNames.hasNext()) {
            var jsonKey = fieldNames.next();
            JsonNode valueNode = jsonNode.get(jsonKey);

            if (JCOMMANDER_COMMAND_JSON_FIELD_NAME.equals(jsonKey)) {
                continue;
            }

            FieldInfo fieldInfo = parameterMap.get(jsonKey);
            if (fieldInfo != null) {
                fieldInfo.field.setAccessible(true);
                setFieldValue(objectMapper, fieldInfo.field, fieldInfo.owner, valueNode);
            }
        }
    }

    private static class FieldInfo {
        final Field field;
        final Object owner;

        FieldInfo(Field field, Object owner) {
            this.field = field;
            this.owner = owner;
        }
    }

    private static Map<String, FieldInfo> buildParameterMap(Object rootObj, Class<?> clazz)
        throws ReflectiveOperationException
    {
        Map<String, FieldInfo> map = new HashMap<>();
        buildParameterMapRecursive(rootObj, clazz, map);
        return map;
    }

    private static void buildParameterMapRecursive(Object obj, Class<?> clazz, Map<String, FieldInfo> map)
        throws ReflectiveOperationException
    {
        if (clazz.getSuperclass() != null) {
            buildParameterMapRecursive(obj, clazz.getSuperclass(), map);
        }
        for (Field field : clazz.getDeclaredFields()) {
            Parameter param = field.getAnnotation(Parameter.class);
            if (param != null) {
                for (String name : param.names()) {
                    String cleanName = name.replaceAll("^-+", "");
                    map.put(cleanName, new FieldInfo(field, obj));
                    map.put(toCamelCase(cleanName), new FieldInfo(field, obj));
                }
            }

            ParametersDelegate delegate = field.getAnnotation(ParametersDelegate.class);
            if (delegate != null) {
                field.setAccessible(true);
                Object delegateObj = field.get(obj);
                if (delegateObj == null) {
                    delegateObj = field.getType().getDeclaredConstructor().newInstance();
                    field.set(obj, delegateObj);
                }
                buildParameterMapRecursive(delegateObj, delegateObj.getClass(), map);
            }
        }
    }

    private static String toCamelCase(String dashSeparated) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : dashSeparated.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(c);
                }
            }
        }

        return result.toString();
    }

    private static IStringConverter<?> instantiateConverter(Class<? extends IStringConverter<?>> converterClass)
        throws ReflectiveOperationException {
        try {
            // Try default constructor first
            return converterClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // Some converters might need a String argument (for error messages)
            try {
                return converterClass.getDeclaredConstructor(String.class).newInstance("PREFIX:");
            } catch (NoSuchMethodException e2) {
                throw new IllegalArgumentException("Cannot instantiate converter: " + converterClass.getName(), e);
            }
        }
    }

    private static void setFieldValue(ObjectMapper objectMapper, Field field, Object obj, JsonNode valueNode)
        throws JsonProcessingException, ReflectiveOperationException {

        // Check if field has @Parameter annotation with converter
        Parameter param = field.getAnnotation(Parameter.class);
        if (param != null && param.converter() != null && !param.converter().equals(NoConverter.class)) {
            // Use the specified converter
            IStringConverter<?> converter = instantiateConverter(param.converter());

            Object value;
            if (valueNode.isNull()) {
                value = null;
            } else if (valueNode.isArray() && List.class.isAssignableFrom(field.getType())) {
                // Handle list parameters with converters
                List<Object> list = new ArrayList<>();

                // Check if there's also a splitter defined
                if (param.splitter() != null && !param.splitter().equals(NoSplitter.class)) {
                    // If splitter is defined, join array elements and let splitter handle it
                    String joinedValue = StreamSupport.stream(valueNode.spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.joining(","));

                    IParameterSplitter splitter = param.splitter().getDeclaredConstructor().newInstance();
                    for (String item : splitter.split(joinedValue)) {
                        list.add(converter.convert(item));
                    }
                } else {
                    // No splitter, convert each array element
                    for (JsonNode item : valueNode) {
                        list.add(converter.convert(item.asText()));
                    }
                }
                value = list;
            } else {
                // Convert single value
                String textValue = valueNode.asText();
                value = converter.convert(textValue);
            }

            field.set(obj, value);
            return;
        }

        // Fall back to existing logic if no converter specified
        Class<?> fieldType = field.getType();

        if (valueNode.isNull()) {
            field.set(obj, null);
        } else if (fieldType == String.class) {
            field.set(obj, valueNode.asText());
        } else if (fieldType == int.class || fieldType == Integer.class) {
            field.set(obj, valueNode.asInt());
        } else if (fieldType == long.class || fieldType == Long.class) {
            field.set(obj, valueNode.asLong());
        } else if (fieldType == boolean.class || fieldType == Boolean.class) {
            field.set(obj, valueNode.asBoolean());
        } else if (fieldType == double.class || fieldType == Double.class) {
            field.set(obj, valueNode.asDouble());
        } else if (fieldType == float.class || fieldType == Float.class) {
            field.set(obj, (float) valueNode.asDouble());
        } else if (List.class.isAssignableFrom(fieldType)) {
            List<String> list = new ArrayList<>();
            if (valueNode.isArray()) {
                for (JsonNode item : valueNode) {
                    list.add(item.asText());
                }
            }
            field.set(obj, list);
        } else {
            Object value = objectMapper.treeToValue(valueNode, fieldType);
            field.set(obj, value);
        }
    }
}
