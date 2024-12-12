package org.opensearch.migrations.transform.jinjava;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opensearch.migrations.transform.JinjavaTransformer;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavaRegexReplaceFilter implements Filter {

    public static final List<Map.Entry<String, String>> JAVA_REGEX_REPLACE_FILTER = List.of();
    public static final List<Map.Entry<String, String>> PYTHONESQUE_REGEX_REPLACE_FILTER = List.of(
        Map.entry("(\\$)", "\\\\\\$"),
        Map.entry("((?:\\\\\\\\)*)(\\\\)(?=\\d)", "\\$"));
    public static final List<Map.Entry<String, String>> DEFAULT_REGEX_REPLACE_FILTER = PYTHONESQUE_REGEX_REPLACE_FILTER;

    private static final LoadingCache<String, Pattern> regexCache =
        CacheBuilder.newBuilder().build(CacheLoader.from((Function<String, Pattern>)Pattern::compile));

    @SneakyThrows
    private static Pattern getCompiledPattern(String pattern) {
        return regexCache.get(pattern);
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    private static class ReplacementAndTransform {
        String replacement;
        List<Map.Entry<String, String>> substitutions;
    }

    private static final LoadingCache<ReplacementAndTransform, String> replacementCache =
        CacheBuilder.newBuilder().build(CacheLoader.from(rat -> {
            var r = rat.replacement;
            if (rat.substitutions != null) {
                for (var kvp : rat.substitutions) {
                    r = r.replaceAll(kvp.getKey(), kvp.getValue());
                }
            }
            return r;
        }));


    @Override
    public String getName() {
        return "regex_replace";
    }

    @Override
    public Object filter(Object inputObject, JinjavaInterpreter interpreter, String... args) {
        if (inputObject == null || args.length < 2) {
            return null;
        }

        String input = inputObject.toString();
        String pattern = args[0];
        String replacement = args[1];

        String rewritten = null;
        try {
            Matcher matcher = getCompiledPattern(pattern).matcher(input);
            rewritten = replacementCache.get(
                new ReplacementAndTransform(replacement,
                    Optional.ofNullable(interpreter)
                        .flatMap(ji->Optional.ofNullable(ji.getContext()))
                        .flatMap(c-> Optional.ofNullable((List<Map.Entry<String,String>>)
                            c.get(JinjavaTransformer.REGEX_REPLACEMENT_CONVERSION_PATTERNS)))
                        .orElse(DEFAULT_REGEX_REPLACE_FILTER)));
            var rval = matcher.replaceAll(rewritten);
            log.atTrace().setMessage("replaced value {} with {}").addArgument(input).addArgument(rval).log();
            return rval;
        } catch (Exception e) {
            throw new RegexReplaceException(e, input, pattern, replacement, rewritten);
        }
    }
}
