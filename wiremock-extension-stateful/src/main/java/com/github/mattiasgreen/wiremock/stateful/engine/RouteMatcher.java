package com.github.mattiasgreen.wiremock.stateful.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RouteMatcher {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    private final String method;
    private final String pathPattern;
    private final Pattern compiledRegex;
    private final List<String> paramNames;

    public RouteMatcher(String method, String pathPattern) {
        this.method = Objects.requireNonNull(method, "method must not be null").toUpperCase(Locale.ROOT);
        this.pathPattern = Objects.requireNonNull(pathPattern, "pathPattern must not be null");

        List<String> names = new ArrayList<>();
        Matcher matcher = PARAM_PATTERN.matcher(pathPattern);
        StringBuilder regexBuilder = new StringBuilder("^");
        int lastIndex = 0;

        while (matcher.find()) {
            regexBuilder.append(Pattern.quote(pathPattern.substring(lastIndex, matcher.start())));
            String paramName = matcher.group(1);
            names.add(paramName);
            regexBuilder.append("([^/]+)");
            lastIndex = matcher.end();
        }

        regexBuilder.append(Pattern.quote(pathPattern.substring(lastIndex)));
        regexBuilder.append("$");

        this.paramNames = List.copyOf(names);
        this.compiledRegex = Pattern.compile(regexBuilder.toString());
    }

    public Optional<Map<String, String>> match(String requestMethod, String requestPath) {
        if (!this.method.equalsIgnoreCase(requestMethod)) {
            return Optional.empty();
        }

        Matcher matcher = compiledRegex.matcher(requestPath);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), matcher.group(i + 1));
        }

        return Optional.of(Collections.unmodifiableMap(params));
    }

    public String getMethod() {
        return method;
    }

    public String getPathPattern() {
        return pathPattern;
    }
}
