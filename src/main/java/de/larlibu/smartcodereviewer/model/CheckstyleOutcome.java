package de.larlibu.smartcodereviewer.model;

import java.util.List;

/**
 *
 * @param ok
 * @param messages
 * @param findings
 */
public record CheckstyleOutcome(boolean ok, List<String> messages, List<CheckstyleFinding> findings) {
}
