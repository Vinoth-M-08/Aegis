package com.aegis.guard;

import java.util.List;

/**
 * @param matchedRules names of the rules that fired, for the audit log
 */
public record GuardVerdict(boolean detected, double score, List<String> matchedRules) {

    public static GuardVerdict clean() {
        return new GuardVerdict(false, 0, List.of());
    }
}
