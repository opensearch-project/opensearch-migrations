/*
 * SPDX-License-Identifier: Apache-2.0
 */

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Produces a deterministic structural representation of Java source.
 *
 * <p>Comments, formatting, import order, method order, uninitialized-field order, and nested-type order are
 * ignored. Field initializers, initializer blocks, enum constants, record headers, and method bodies remain
 * order-sensitive where Java behavior can depend on their order.
 */
public final class JavaStructuralEqualizer {
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");
    private static final List<String> OPERATORS = List.of(
            ">>>=", "<<=", ">>=", "...", "::", "->", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
            "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", ">>>", "<<", ">>");

    private JavaStructuralEqualizer() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !arguments[0].equals("canonicalize-tree")) {
            System.err.println("usage: JavaStructuralEqualizer canonicalize-tree DIRECTORY");
            System.exit(2);
        }
        System.out.print(canonicalizeTree(Path.of(arguments[1])));
    }

    private static String canonicalizeTree(Path root) throws IOException {
        List<String> units = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(value -> value.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                units.add(canonicalizeSource(path, Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        Collections.sort(units);
        return section("COMPILATION_UNITS", units);
    }

    private static String canonicalizeSource(Path path, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK is required; no system Java compiler is available");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavaFileObject sourceObject = new StringJavaFileObject(path, source);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, diagnostics, List.of("-proc:none"), null, List.of(sourceObject));
            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
            }
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(JavaStructuralEqualizer::formatDiagnostic)
                    .toList();
            if (!errors.isEmpty()) {
                return "UNPARSEABLE_JAVA\n" + sha256(source);
            }
            if (units.size() != 1) {
                throw new IOException(path + ": expected one compilation unit, found " + units.size());
            }
            return canonicalizeUnit(units.get(0));
        }
    }

    private static String sha256(String source) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        return "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null);
    }

    private static String canonicalizeUnit(CompilationUnitTree unit) {
        List<String> packageAnnotations = unit.getPackageAnnotations().stream()
                .map(AnnotationTree::toString)
                .map(JavaStructuralEqualizer::canonicalTokens)
                .toList();
        List<String> imports = unit.getImports().stream()
                .map(ImportTree::toString)
                .map(JavaStructuralEqualizer::canonicalTokens)
                .sorted()
                .toList();
        List<String> declarations = unit.getTypeDecls().stream()
                .filter(tree -> tree instanceof ClassTree)
                .map(tree -> canonicalizeClass((ClassTree) tree))
                .sorted()
                .toList();

        String packageName = unit.getPackageName() == null
                ? ""
                : canonicalTokens(unit.getPackageName().toString());
        return "PACKAGE\n"
                + packageName
                + "\n"
                + section("PACKAGE_ANNOTATIONS", packageAnnotations)
                + section("IMPORTS", imports)
                + section("TOP_LEVEL_TYPES", declarations);
    }

    private static String canonicalizeClass(ClassTree classTree) {
        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        List<String> nestedTypes = new ArrayList<>();
        List<String> initializationSequence = new ArrayList<>();
        List<String> otherMembers = new ArrayList<>();

        for (Tree member : classTree.getMembers()) {
            switch (member.getKind()) {
                case VARIABLE -> {
                    VariableTree variable = (VariableTree) member;
                    String canonical = canonicalTokens(variable.toString());
                    fields.add(canonical);
                    if (variable.getInitializer() != null) {
                        initializationSequence.add(
                                "FIELD_INITIALIZER "
                                        + (isStatic(variable) ? "STATIC " : "INSTANCE ")
                                        + canonicalTokens(variable.getName().toString())
                                        + " = "
                                        + canonicalTokens(variable.getInitializer().toString()));
                    }
                }
                case METHOD -> methods.add(canonicalTokens(((MethodTree) member).toString()));
                case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE ->
                        nestedTypes.add(canonicalizeClass((ClassTree) member));
                case BLOCK -> {
                    BlockTree block = (BlockTree) member;
                    initializationSequence.add(
                            "INITIALIZER "
                                    + (block.isStatic() ? "STATIC " : "INSTANCE ")
                                    + canonicalTokens(block.toString()));
                }
                case EMPTY_STATEMENT -> {
                    // Empty declarations have no structural effect.
                }
                default -> otherMembers.add(member.getKind() + " " + canonicalTokens(member.toString()));
            }
        }

        Collections.sort(fields);
        Collections.sort(methods);
        Collections.sort(nestedTypes);
        Collections.sort(otherMembers);

        return "TYPE\n"
                + canonicalTypeHeader(classTree)
                + "\n"
                + section("INITIALIZATION_SEQUENCE", initializationSequence)
                + section("FIELDS", fields)
                + section("METHODS", methods)
                + section("NESTED_TYPES", nestedTypes)
                + section("OTHER_MEMBERS", otherMembers);
    }

    private static boolean isStatic(VariableTree variable) {
        return variable.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private static String canonicalTypeHeader(ClassTree classTree) {
        List<String> tokens = tokenize(classTree.toString());
        int keyword = -1;
        for (int index = 0; index < tokens.size(); index++) {
            if (TYPE_KEYWORDS.contains(tokens.get(index))) {
                keyword = index;
                break;
            }
        }
        if (keyword < 0) {
            throw new IllegalArgumentException("Cannot locate type keyword in " + classTree.getSimpleName());
        }
        int body = -1;
        for (int index = keyword + 1; index < tokens.size(); index++) {
            if (tokens.get(index).equals("{")) {
                body = index;
                break;
            }
        }
        if (body < 0) {
            throw new IllegalArgumentException("Cannot locate type body in " + classTree.getSimpleName());
        }
        return encodeTokens(tokens.subList(0, body));
    }

    private static String section(String name, List<String> entries) {
        StringBuilder result = new StringBuilder(name).append(' ').append(entries.size()).append('\n');
        for (String entry : entries) {
            result.append(entry.length()).append(':').append(entry).append('\n');
        }
        return result.toString();
    }

    private static String canonicalTokens(String source) {
        return encodeTokens(tokenize(source));
    }

    private static String encodeTokens(List<String> tokens) {
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            result.append(token.length()).append(':').append(token).append('\n');
        }
        return result.toString();
    }

    private static List<String> tokenize(String source) {
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index += 2;
                    while (index < source.length() && source.charAt(index) != '\n') {
                        index++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", index + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException("Unterminated block comment");
                    }
                    index = end + 2;
                    continue;
                }
            }
            if (source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated text block");
                }
                tokens.add(source.substring(index, end + 3));
                index = end + 3;
                continue;
            }
            if (current == '"' || current == '\'') {
                int end = scanQuoted(source, index, current);
                tokens.add(source.substring(index, end));
                index = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int end = index + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                tokens.add(source.substring(index, end));
                index = end;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < source.length()) {
                    char value = source.charAt(end);
                    if (!Character.isLetterOrDigit(value)
                            && value != '.'
                            && value != '_'
                            && value != '+'
                            && value != '-') {
                        break;
                    }
                    end++;
                }
                tokens.add(source.substring(index, end));
                index = end;
                continue;
            }
            String operator = null;
            for (String candidate : OPERATORS) {
                if (source.startsWith(candidate, index)
                        && (operator == null || candidate.length() > operator.length())) {
                    operator = candidate;
                }
            }
            if (operator != null) {
                tokens.add(operator);
                index += operator.length();
                continue;
            }
            tokens.add(String.valueOf(current));
            index++;
        }
        return tokens;
    }

    private static int scanQuoted(String source, int start, char delimiter) {
        int index = start + 1;
        boolean escaped = false;
        while (index < source.length()) {
            char value = source.charAt(index++);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == delimiter) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unterminated quoted literal");
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(Path path, String source) {
            super(URI.create("string:///" + path.toString().replace('\\', '/')), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
