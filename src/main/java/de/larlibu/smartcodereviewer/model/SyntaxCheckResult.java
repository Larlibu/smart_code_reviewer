package de.larlibu.smartcodereviewer.model;

/**
 *
 * @param syntaxValid
 * @param checkstyleOutcome
 */
public record SyntaxCheckResult(boolean syntaxValid, CheckstyleOutcome checkstyleOutcome) {
}
