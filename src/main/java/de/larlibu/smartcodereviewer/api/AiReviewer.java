package de.larlibu.smartcodereviewer.api;

import de.larlibu.smartcodereviewer.model.AiReviewResult;

public interface AiReviewer {
    AiReviewResult review(String code);
}
