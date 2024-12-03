package org.opensearch.migrations.transform.jinjava;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import lombok.SneakyThrows;

public class JavaRegexCaptureFilter implements Filter {

    private static LoadingCache<String, Pattern> regexCache =
        CacheBuilder.newBuilder().build(CacheLoader.from((Function<String, Pattern>)Pattern::compile));

    @SneakyThrows
    private static Pattern getCompiledPattern(String pattern) {
        return regexCache.get(pattern);
    }

    @Override
    public String getName() {
        return "regex_capture";
    }

    @Override
    public Object filter(Object inputObject, JinjavaInterpreter interpreter, String... args) {
        if (inputObject == null || args.length < 1) {
            return null;
        }

        String input = inputObject.toString();
        String pattern = args[0];

        try {
            Matcher matcher = getCompiledPattern(pattern).matcher(input);
            if (matcher.find()) {
                var groups = new HashMap<>();
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    groups.put("group" + i, matcher.group(i));
                }
                return groups;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
