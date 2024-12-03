package org.opensearch.migrations.transform.jinjava;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavaRegexReplaceFilter implements Filter {

    private static LoadingCache<String, Pattern> regexCache =
        CacheBuilder.newBuilder().build(CacheLoader.from((Function<String, Pattern>)Pattern::compile));

    @SneakyThrows
    private static Pattern getCompiledPattern(String pattern) {
        return regexCache.get(pattern);
    }

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

        try {
            Matcher matcher = getCompiledPattern(pattern).matcher(input);
            var rval = matcher.replaceAll(replacement);
            log.atError().setMessage("replaced value {}").addArgument(rval).log();
            return rval;
        } catch (Exception e) {
            return null;
        }
    }
}
