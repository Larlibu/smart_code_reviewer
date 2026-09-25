package de.larlibu.smartcodereviewer.api;

import java.nio.file.Path;

@FunctionalInterface
public interface DiffViewerLauncher {
    boolean launch(Path left, Path right);
}
