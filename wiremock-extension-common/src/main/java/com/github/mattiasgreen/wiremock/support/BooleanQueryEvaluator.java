package com.github.mattiasgreen.wiremock.support;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Shared boolean search-query evaluator supporting {@code OR}, {@code AND}, and whitespace-delimited
 * implicit-AND semantics.
 *
 * <p>Tokens passed to the supplied predicate are normalized to lowercase using {@link Locale#ROOT}.
 */
public final class BooleanQueryEvaluator {

    private static final Pattern OR_SPLIT = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND_SPLIT = Pattern.compile("\\bAND\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private BooleanQueryEvaluator() {}

    /**
     * Evaluates a boolean query string against a token predicate.
     *
     * <p>Top-level {@code OR} branches succeed when any branch matches; within each branch, explicit
     * {@code AND} operators and whitespace-separated terms require every non-blank token to match.
     *
     * @param query raw search query; {@code null} or blank queries match unconditionally
     * @param tokenPredicate predicate tested against each lowercased query token
     * @return {@code true} if the query is {@code null}/blank or satisfied by {@code tokenPredicate}
     */
    public static boolean evaluate(String query, Predicate<String> tokenPredicate) {
        if (query == null || query.isBlank()) {
            return true;
        }
        Objects.requireNonNull(tokenPredicate, "tokenPredicate must not be null");

        String[] orClauses = OR_SPLIT.split(query);
        for (String orClause : orClauses) {
            String trimmedOr = orClause.trim();
            if (trimmedOr.isEmpty()) {
                continue;
            }

            String[] andClauses = AND_SPLIT.split(trimmedOr);
            boolean allAndMatch = true;
            for (String andPart : andClauses) {
                String trimmedAnd = andPart.trim();
                if (trimmedAnd.isEmpty()) {
                    continue;
                }

                String[] tokens = WHITESPACE_SPLIT.split(trimmedAnd);
                for (String token : tokens) {
                    if (!token.isBlank()) {
                        if (!tokenPredicate.test(token.toLowerCase(Locale.ROOT))) {
                            allAndMatch = false;
                            break;
                        }
                    }
                }
                if (!allAndMatch) {
                    break;
                }
            }

            if (allAndMatch) {
                return true;
            }
        }

        return false;
    }
}
