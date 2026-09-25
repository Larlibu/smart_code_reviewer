package de.larlibu.smartcodereviewer.api;

import de.larlibu.smartcodereviewer.model.SyntaxCheckResult;

import java.nio.file.Path;

public interface SyntaxAnalyzer {
    SyntaxCheckResult analyze(Path file, String code);
}
