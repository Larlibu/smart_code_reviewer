package de.larlibu.smartcodereviewer.model;

import java.util.List;

/**
 *
 * @param feedback
 * @param suggestions
 */
public record AiReviewResult(String feedback, List<SolutionSuggestion> suggestions) {
}
