package com.aegis.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.aegis.support.MessageText;

/**
 * Weighted rule-based detection of prompt-injection attempts. Each rule that matches adds its
 * weight; a request scoring at or above {@link #THRESHOLD} is treated as an injection attempt.
 * Weak signals (weight below the threshold) only count when they appear together.
 *
 * <p>Only untrusted roles are inspected: system and assistant messages come from the calling
 * application, user and tool messages may carry attacker-controlled text.
 */
@Component
public class PromptInjectionDetector {

    static final double THRESHOLD = 1.0;

    private static final Set<String> UNTRUSTED_ROLES = Set.of("user", "tool", "function");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    private static final List<Rule> RULES = List.of(
            new Rule("ignore_instructions", 1.0,
                    "\\b(ignore|disregard|forget|override|bypass)\\b[^.\\n]{0,40}"
                            + "\\b(previous|prior|above|earlier|all|any|your|the|system)\\b[^.\\n]{0,20}"
                            + "\\b(instructions?|prompts?|rules|directions|guidelines)\\b"),
            new Rule("system_prompt_exfiltration", 1.0,
                    "\\b(reveal|show|print|repeat|output|display|leak|tell me)\\b[^.\\n]{0,30}"
                            + "\\b(system prompt|initial prompt|hidden (instructions|prompt)|your (instructions|rules)"
                            + "|developer message)\\b"),
            new Rule("jailbreak_persona", 0.8,
                    "\\b(DAN|do anything now|developer mode|jailbreak(ed)?|god mode)\\b"),
            // Chat-template control tokens have no business in user text
            new Rule("chat_template_tokens", 1.0,
                    "<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|\\[/?INST\\]|<</?SYS>>"),
            new Rule("fake_system_header", 0.5,
                    "^\\s*#{2,}\\s*(system|instructions?)\\b|^\\s*system\\s*:"),
            new Rule("role_override", 0.6,
                    "\\byou are (now|no longer)\\b|\\bfrom now on,? you (are|will|must)\\b"
                            + "|\\bpretend (to be|you are)\\b|\\bact as\\b"),
            new Rule("safety_bypass", 0.5,
                    "\\b(without|no|ignore|disable|turn off)\\b[^.\\n]{0,20}"
                            + "\\b(restrictions|filters|guardrails|safety|censorship|limitations)\\b"));

    public GuardVerdict inspect(ChatCompletionRequest request) {
        StringBuilder untrusted = new StringBuilder();
        for (ChatMessage message : request.getMessages()) {
            if (UNTRUSTED_ROLES.contains(message.getRole())) {
                untrusted.append(MessageText.of(message)).append('\n');
            }
        }
        return inspect(untrusted.toString());
    }

    GuardVerdict inspect(String text) {
        if (text.isBlank()) {
            return GuardVerdict.clean();
        }
        double score = 0;
        List<String> matched = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                score += rule.weight();
                matched.add(rule.name());
            }
        }
        return new GuardVerdict(score >= THRESHOLD, score, List.copyOf(matched));
    }

    private record Rule(String name, double weight, Pattern pattern) {

        Rule(String name, double weight, String regex) {
            this(name, weight, Pattern.compile(regex, FLAGS));
        }
    }
}
