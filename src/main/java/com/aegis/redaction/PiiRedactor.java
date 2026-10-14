package com.aegis.redaction;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Detects PII and replaces it with typed placeholders such as {@code <EMAIL_1>}.
 * Detectors run in order, so more specific patterns (cards, IDs) claim digits before the
 * looser phone pattern sees them.
 */
@Component
public class PiiRedactor {

    static final List<Detector> DETECTORS = List.of(
            new Detector(PiiType.EMAIL,
                    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), match -> true),
            new Detector(PiiType.CREDIT_CARD,
                    Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])"), PiiRedactor::passesLuhn),
            new Detector(PiiType.SSN,
                    Pattern.compile("(?<![\\w-])\\d{3}-\\d{2}-\\d{4}(?![\\w-])"), match -> true),
            // 12 digits in 4-4-4 groups, not part of a longer digit run such as a card number
            new Detector(PiiType.AADHAAR,
                    Pattern.compile("(?<![\\w-])(?<!\\d[ -])[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![\\w-])(?![ -]\\d)"),
                    match -> true),
            new Detector(PiiType.IP_ADDRESS,
                    Pattern.compile("(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
                            + "(?!\\w)(?!\\.\\d)"),
                    match -> true),
            new Detector(PiiType.PHONE,
                    Pattern.compile("(?<![\\w])\\+?\\d[\\d ().-]{8,18}\\d(?![\\w])"),
                    match -> {
                        long digits = match.chars().filter(Character::isDigit).count();
                        return digits >= 10 && digits <= 15;
                    }));

    public RedactionSession newSession() {
        return new RedactionSession(DETECTORS);
    }

    static boolean passesLuhn(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    record Detector(PiiType type, Pattern pattern, Predicate<String> validator) {
    }
}
