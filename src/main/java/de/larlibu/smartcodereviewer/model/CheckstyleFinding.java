package de.larlibu.smartcodereviewer.model;

/**
 * @param file
 * @param line
 * @param column
 * @param message
 * @param sourceName
 */
public record CheckstyleFinding(String file, int line, int column, String message, String sourceName) {
}
