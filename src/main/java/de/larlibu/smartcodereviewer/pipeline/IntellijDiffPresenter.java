package de.larlibu.smartcodereviewer.pipeline;

import de.larlibu.smartcodereviewer.api.DiffViewerLauncher;
import de.larlibu.smartcodereviewer.api.ReviewPresenter;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Schritt 4 (letzter Schritt) der Pipeline: praesentiert die eingesammelten
 * {@link SolutionSuggestion}s (von {@code CheckstyleFixer} und der KI) im IntelliJ-Diff-Viewer.
 *
 * <p>Fuer jeden Vorschlag wird eine temporaere Datei mit dem Vorschlagscode erzeugt und
 * IntelliJ im Diff-Modus (echte Datei links, Vorschlag rechts) gestartet. In den
 * Vorschlagscode werden zusaetzlich einzeilige Inline-Kommentare an den Aenderungsblock-Grenzen
 * eingefuegt (Begruendung, Herkunft, KI-Feedback) — rein zur Vorschau, nie in der Zieldatei.
 * Die Aenderungsbloecke werden dafuer per LCS-Diff (siehe {@link #buildDiffLines}) ermittelt.
 * Es findet keine automatische Uebernahme statt: Der Nutzer entscheidet in IntelliJ selbst,
 * was er anwendet.</p>
 */
public class IntellijDiffPresenter implements ReviewPresenter {

    private static final Logger LOGGER = LogManager.getLogger(IntellijDiffPresenter.class);

    private final DiffViewerLauncher launcher;

    /** Nutzt den echten IntelliJ-Launcher ({@link #tryOpenIntellijDiff}) zum Oeffnen des Diffs. */
    public IntellijDiffPresenter() {
        this.launcher = this::tryOpenIntellijDiff;
    }

    /** @param launcher austauschbarer {@link DiffViewerLauncher}, z. B. ein Test-Double. */
    public IntellijDiffPresenter(DiffViewerLauncher launcher) {
        this.launcher = launcher;
    }

    /**
     * Oeffnet fuer jeden Vorschlag einen eigenen Diff-Viewer-Tab. Ist {@code suggestions} leer,
     * passiert nichts weiter als eine Log-Warnung — die Zieldatei bleibt unangetastet.
     */
    @Override
    public void present(List<SolutionSuggestion> suggestions, Path targetFile, String originalCode, String aiFeedback) {
        if (suggestions.isEmpty()) {
            LOGGER.warn("Keine konkreten Code-Vorschlaege von KI oder Checkstyle erhalten. Datei bleibt unveraendert.");
            return;
        }

        LOGGER.info("[Step 3] {} Loesungsvorschlag/-vorschlaege verfuegbar.", suggestions.size());
        for (int i = 0; i < suggestions.size(); i++) {
            SolutionSuggestion suggestion = suggestions.get(i);
            LOGGER.info("[{}] [{}] {}", i + 1, suggestion.origin(), suggestion.title());
            if (!suggestion.rationale().isBlank()) {
                LOGGER.info("    {}", suggestion.rationale());
            }
        }

        LOGGER.info("Es werden {} Vorschlag/-vorschlaege im Diff-Viewer geoeffnet.", suggestions.size());

        int opened = 0;
        for (int i = 0; i < suggestions.size(); i++) {
            SolutionSuggestion suggestion = suggestions.get(i);
            LOGGER.info("Oeffne Vorschlag {}/{}: [{}] {}", i + 1, suggestions.size(), suggestion.origin(), suggestion.title());
            String previewCode = buildDiffViewerPreviewCode(originalCode, suggestion, aiFeedback);
            boolean applyOpened = openIntellijApplyDiffViewer(targetFile, previewCode);

            if (!applyOpened) {
                LOGGER.error("IntelliJ Diff-Viewer konnte nicht gestartet werden. Kein CLI-Fallback aktiv.");
                continue;
            }
            opened++;
            LOGGER.info("Diff-Viewer geoeffnet (links: echte Datei, rechts: Vorschlag mit Inline-Kommentaren).");
        }

        if (opened == 0) {
            LOGGER.error("Kein Diff-Viewer konnte geoeffnet werden.");
            return;
        }
        LOGGER.info("Nur Diff-Viewer-Modus aktiv: keine CLI-Abfrage und keine automatische Datei-Uebernahme.");
    }

    /**
     * Die Kommentare sind nur fuer die Vorschau und werden nie in die Zieldatei geschrieben.
     */
    private String buildDiffViewerPreviewCode(String originalCode, SolutionSuggestion suggestion, String aiFeedback) {
        String suggestedCode = suggestion.code() == null ? "" : suggestion.code();
        List<String> inlineComments = buildInlinePreviewComments(suggestion, aiFeedback);
        if (inlineComments.isEmpty()) {
            return suggestedCode;
        }
        return injectInlinePreviewComments(originalCode, suggestedCode, inlineComments);
    }

    private List<String> buildInlinePreviewComments(SolutionSuggestion suggestion, String aiFeedback) {
        List<String> comments = new ArrayList<>();

        String rationale = sanitizeInlineCommentText(suggestion.rationale());
        if (!rationale.isBlank()) {
            comments.add(truncateInlineComment("Begruendung: " + rationale, 220));
        }

        String source = sanitizeInlineCommentText(suggestion.origin());
        String title = sanitizeInlineCommentText(suggestion.title());
        if (!source.isBlank() || !title.isBlank()) {
            String header = source.isBlank()
                    ? title
                    : (title.isBlank() ? "[" + source + "]" : "[" + source + "] " + title);
            comments.add(truncateInlineComment("SCR-Hinweis: " + header, 200));
        }

        String feedback = sanitizeInlineCommentText(firstNonBlankLine(aiFeedback));
        if (!feedback.isBlank()) {
            comments.add(truncateInlineComment("KI-Feedback: " + feedback, 220));
        }

        return comments;
    }

    private String injectInlinePreviewComments(String originalCode, String suggestedCode, List<String> inlineComments) {
        List<Integer> insertionIndexes = collectDiffCommentInsertionIndexes(
                buildDiffLines(originalCode == null ? "" : originalCode, suggestedCode)
        );
        if (insertionIndexes.isEmpty()) {
            return suggestedCode;
        }

        List<String> suggestedLines = new ArrayList<>(List.of(suggestedCode.split("\\R", -1)));
        // lineOffset verfolgt, wie viele Kommentarzeilen bisher eingefuegt wurden,
        // damit spaetere Indizes korrekt verschoben werden
        int lineOffset = 0;
        for (int blockIndex = 0; blockIndex < insertionIndexes.size(); blockIndex++) {
            if (blockIndex >= inlineComments.size()) {
                break;
            }
            int index = Math.max(0, Math.min(insertionIndexes.get(blockIndex) + lineOffset, suggestedLines.size()));
            String indent = detectIndentation(suggestedLines, index);
            List<String> commentLines = List.of(indent + "// " + inlineComments.get(blockIndex));
            suggestedLines.addAll(index, commentLines);
            lineOffset += commentLines.size();
        }

        return String.join(System.lineSeparator(), suggestedLines);
    }

    /**
     * State-Machine: Pro zusammenhaengendem Aenderungsblock wird genau ein Einfuegepunkt
     * gemeldet – moeglichst die erste ADD-Zeile des Blocks.
     */
    private List<Integer> collectDiffCommentInsertionIndexes(List<DiffLine> diffLines) {
        List<Integer> insertionIndexes = new ArrayList<>();
        int newLineCursor = 0;
        // inChangeBlock: wir befinden uns innerhalb eines zusammenhaengenden ADD/REMOVE-Blocks
        boolean inChangeBlock = false;
        int blockStartNewLine = 0;
        // blockInsertion: Index der ersten ADD-Zeile im aktuellen Block (-1 = noch keine ADD gesehen)
        int blockInsertion = -1;

        for (DiffLine diffLine : diffLines) {
            if (diffLine.type() == DiffType.EQUAL) {
                if (inChangeBlock) {
                    // Block abgeschlossen: Einfuegepunkt = erste ADD-Zeile oder Blockstart
                    insertionIndexes.add(blockInsertion >= 0 ? blockInsertion : blockStartNewLine);
                    inChangeBlock = false;
                    blockInsertion = -1;
                }
                newLineCursor++;
                continue;
            }

            if (!inChangeBlock) {
                inChangeBlock = true;
                blockStartNewLine = newLineCursor;
                blockInsertion = -1;
            }

            if (diffLine.type() == DiffType.ADD) {
                if (blockInsertion < 0) {
                    blockInsertion = newLineCursor;
                }
                newLineCursor++;
            }
        }

        if (inChangeBlock) {
            insertionIndexes.add(blockInsertion >= 0 ? blockInsertion : blockStartNewLine);
        }

        List<Integer> deduplicated = new ArrayList<>();
        Integer previous = null;
        for (Integer index : insertionIndexes) {
            if (previous == null || !previous.equals(index)) {
                deduplicated.add(index);
                previous = index;
            }
        }
        return deduplicated;
    }

    /**
     * Verwendet LCS (Longest Common Subsequence) mit rueckwaerts-DP:
     * {@code lcs[i][j]} = Laenge der LCS fuer {@code originalLines[i..]} und {@code suggestedLines[j..]}.
     * Rueckwaerts-DP vermeidet einen separaten Backtracking-Schritt.
     */
    private List<DiffLine> buildDiffLines(String originalCode, String suggestedCode) {
        String[] originalLines = originalCode.split("\\R", -1);
        String[] suggestedLines = suggestedCode.split("\\R", -1);

        int n = originalLines.length;
        int m = suggestedLines.length;
        int[][] lcs = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (originalLines[i].equals(suggestedLines[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffLine> diffLines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (originalLines[i].equals(suggestedLines[j])) {
                diffLines.add(new DiffLine(DiffType.EQUAL, originalLines[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                // Originalzeile wurde entfernt (kein Gegenstueck in suggestedLines)
                diffLines.add(new DiffLine(DiffType.REMOVE, originalLines[i]));
                i++;
            } else {
                diffLines.add(new DiffLine(DiffType.ADD, suggestedLines[j]));
                j++;
            }
        }
        while (i < n) {
            diffLines.add(new DiffLine(DiffType.REMOVE, originalLines[i++]));
        }
        while (j < m) {
            diffLines.add(new DiffLine(DiffType.ADD, suggestedLines[j++]));
        }

        return diffLines;
    }

    private boolean openIntellijApplyDiffViewer(Path targetFile, String suggestedCode) {
        try {
            String suffix = guessFileSuffix(targetFile);
            Path right = Files.createTempFile("scr-apply-", suffix);
            right.toFile().deleteOnExit();
            Files.writeString(right, suggestedCode == null ? "" : suggestedCode, StandardCharsets.UTF_8);
            return openIntellijDiffViewer(targetFile, right);
        } catch (Exception e) {
            LOGGER.debug("IntelliJ Apply-Diff konnte nicht vorbereitet werden: {}", e.getMessage());
        }
        return false;
    }

    private boolean openIntellijDiffViewer(Path left, Path right) {
        boolean opened = launcher.launch(left, right);
        if (!opened) {
            LOGGER.warn("Kein IntelliJ-Launcher gefunden. Optional SCR_INTELLIJ_LAUNCHER setzen (z. B. idea64.exe).");
        }
        return opened;
    }

    private boolean tryOpenIntellijDiff(Path left, Path right) {
        for (List<String> command : buildIntellijDiffCommands(left, right)) {
            if (command.isEmpty() || command.getFirst().isBlank()) {
                continue;
            }
            try {
                Process process = new ProcessBuilder(command).start();
                boolean exited = process.waitFor(2, TimeUnit.SECONDS);
                if (!exited || process.exitValue() == 0) {
                    return true;
                }
            } catch (Exception e) {
                LOGGER.debug("IntelliJ-Diff Kommando fehlgeschlagen ({}): {}", command, e.getMessage());
            }
        }
        return false;
    }

    private List<List<String>> buildIntellijDiffCommands(Path left, Path right) {
        LinkedHashSet<String> launchers = new LinkedHashSet<>();
        String configuredLauncher = firstNonBlank(System.getenv("SCR_INTELLIJ_LAUNCHER"), System.getenv("IDEA_LAUNCHER"));
        if (configuredLauncher != null && !configuredLauncher.isBlank()) {
            launchers.add(configuredLauncher);
        }
        launchers.addAll(findIntellijLauncherCandidates());
        launchers.add("idea64.exe");
        launchers.add("idea.exe");
        launchers.add("idea.bat");
        launchers.add("idea");

        List<List<String>> commands = new ArrayList<>();
        for (String launcher : launchers) {
            commands.add(List.of(launcher, "diff", left.toString(), right.toString()));
        }
        return commands;
    }

    private List<String> findIntellijLauncherCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String userHome = System.getProperty("user.home");

        addIntellijBinCandidates(candidates, localAppData == null ? null : Path.of(localAppData, "Programs", "IntelliJ IDEA", "bin"));
        addIntellijBinCandidates(candidates, userHome == null ? null : Path.of(userHome, "AppData", "Local", "Programs", "IntelliJ IDEA", "bin"));
        addIntellijBinCandidates(candidates, programFiles == null ? null : Path.of(programFiles, "JetBrains", "IntelliJ IDEA", "bin"));

        if (programFiles != null) {
            Path jetbrainsDir = Path.of(programFiles, "JetBrains");
            if (Files.isDirectory(jetbrainsDir)) {
                try (var stream = Files.list(jetbrainsDir)) {
                    stream.filter(Files::isDirectory)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("intellij"))
                            .forEach(p -> addIntellijBinCandidates(candidates, p.resolve("bin")));
                } catch (IOException ignored) {
                }
            }
        }

        Path toolboxDir = localAppData == null ? null : Path.of(localAppData, "JetBrains", "Toolbox", "apps");
        if (toolboxDir != null && Files.isDirectory(toolboxDir)) {
            try (var stream = Files.walk(toolboxDir, 5)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase("bin"))
                        .forEach(p -> addIntellijBinCandidates(candidates, p));
            } catch (IOException ignored) {
            }
        }

        return new ArrayList<>(candidates);
    }

    private void addIntellijBinCandidates(LinkedHashSet<String> candidates, Path binDir) {
        if (binDir == null || !Files.isDirectory(binDir)) {
            return;
        }
        for (String name : List.of("idea64.exe", "idea.exe", "idea.bat")) {
            Path candidate = binDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                candidates.add(candidate.toString());
            }
        }
    }

    private String detectIndentation(List<String> lines, int index) {
        for (int i = index; i < lines.size(); i++) {
            String indentation = leadingWhitespaceOrNull(lines.get(i));
            if (indentation != null) {
                return indentation;
            }
        }
        for (int i = index - 1; i >= 0; i--) {
            String indentation = leadingWhitespaceOrNull(lines.get(i));
            if (indentation != null) {
                return indentation;
            }
        }
        return "";
    }

    private String leadingWhitespaceOrNull(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        int pos = 0;
        while (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) {
            pos++;
        }
        return line.substring(0, pos);
    }

    /**
     * Entfernt Zeilenumbrueche und kollabiert mehrfache Leerzeichen, da ein
     * Java-Inline-Kommentar ({@code //}) immer einzeilig sein muss.
     */
    private String sanitizeInlineCommentText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\R+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private String firstNonBlankLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    private String truncateInlineComment(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, Math.max(0, maxLength));
        }
        return text.substring(0, maxLength - 3).trim() + "...";
    }

    private String guessFileSuffix(Path file) {
        String name = file == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot) : ".txt";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Klassifiziert eine {@link DiffLine} als unveraendert, hinzugefuegt oder entfernt. */
    private enum DiffType {
        EQUAL, ADD, REMOVE
    }

    /** Eine Zeile des per {@link #buildDiffLines} berechneten LCS-Diffs. */
    private record DiffLine(DiffType type, String text) {
    }
}
