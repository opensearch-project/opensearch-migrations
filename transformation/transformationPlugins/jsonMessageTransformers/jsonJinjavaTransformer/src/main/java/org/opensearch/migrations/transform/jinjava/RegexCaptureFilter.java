package org.opensearch.migrations.transform.jinjava;

import java.util.HashMap;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

public class RegexCaptureFilter implements Filter {
    @Override
    public String getName() {
        return "regex_capture";
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null || args.length < 1) {
            return null;
        }

        String input = var.toString();
        String pattern = args[0];

        try {
            Matcher matcher = Pattern.compile(pattern).matcher(input);
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
