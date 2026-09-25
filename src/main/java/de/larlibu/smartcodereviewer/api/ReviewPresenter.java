package de.larlibu.smartcodereviewer.api;

import de.larlibu.smartcodereviewer.model.SolutionSuggestion;

import java.nio.file.Path;
import java.util.List;

public interface ReviewPresenter {
    void present(List<SolutionSuggestion> suggestions, Path targetFile, String originalCode, String aiFeedback);
}
