package de.larlibu.smartcodereviewer.pipeline;

import de.larlibu.smartcodereviewer.model.CheckstyleFinding;
import de.larlibu.smartcodereviewer.model.CheckstyleOutcome;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Erzeugt automatische Code-Fixes fuer erkannte Checkstyle-Findings.
 *
 * <p>Aktuell unterstuetzt: Entfernen ungenutzter Imports ({@code UnusedImportsCheck}).</p>
 *
 * <p><b>Hinweis:</b> Eine fruehere Version dieser Klasse hat Stern-Importe
 * ({@code AvoidStarImportCheck}) automatisch in explizite Imports aufgeloest, indem
 * Grossbuchstaben-Tokens aus dem zu analysierenden Fremdcode per {@link Class#forName}
 * gegen den Classpath dieser Anwendung aufgeloest wurden. Das war sowohl ein
 * Sicherheitsrisiko (Laden/Initialisieren von Klassen anhand von aus nicht-vertrauenswuerdigem
 * Quellcode extrahierten Namen) als auch fachlich falsch (der Classpath des reviewten Projekts
 * ist nicht der Classpath von SmartCodeReviewer selbst). Die Funktionalitaet wurde entfernt,
 * anstatt sie zu patchen - ein korrekter Fix braucht einen echten Java-Parser/Symbol-Resolver
 * mit dem Classpath des Zielprojekts.</p>
 */
public class CheckstyleFixer {

    /**
     * Erzeugt Auto-Fix-Vorschlaege aus {@code checkstyleOutcome}, unabhaengig von der KI.
     * Gibt eine leere Liste zurueck, wenn kein Outcome vorliegt oder keine unterstuetzten
     * Findings ({@code UnusedImportsCheck}) enthalten sind.
     */
    public List<SolutionSuggestion> buildSuggestions(String originalCode, CheckstyleOutcome checkstyleOutcome) {
        if (checkstyleOutcome == null || checkstyleOutcome.findings().isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(buildUnusedImportFix(originalCode, checkstyleOutcome.findings()));
    }

    private List<SolutionSuggestion> buildUnusedImportFix(String originalCode, List<CheckstyleFinding> findings) {
        TreeSet<Integer> removableImportLines = new TreeSet<>(Comparator.reverseOrder());
        for (CheckstyleFinding finding : findings) {
            if (!isUnusedImportsCheck(finding)) {
                continue;
            }
            int lineIndex = finding.line() - 1;
            if (lineIndex >= 0) {
                removableImportLines.add(lineIndex);
            }
        }

        if (removableImportLines.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(List.of(originalCode.split("\\R", -1)));
        int removed = 0;
        for (int lineIndex : removableImportLines) {
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                continue;
            }
            if (!lines.get(lineIndex).trim().startsWith("import ")) {
                continue;
            }
            lines.remove(lineIndex);
            removed++;
        }

        if (removed == 0) {
            return List.of();
        }

        String fixedCode = String.join(System.lineSeparator(), lines);
        if (fixedCode.equals(originalCode)) {
            return List.of();
        }

        String rationale = "Automatisch aus Checkstyle-Findings erzeugt: " + removed + " unbenutzte Imports entfernt.";
        return List.of(new SolutionSuggestion("Checkstyle", "Unused Imports entfernen", rationale, fixedCode));
    }

    private boolean isUnusedImportsCheck(CheckstyleFinding finding) {
        String sourceName = finding.sourceName();
        if (sourceName != null && sourceName.endsWith("UnusedImportsCheck")) {
            return true;
        }
        String message = finding.message() == null ? "" : finding.message().toLowerCase(Locale.ROOT);
        return message.contains("unused import") || message.contains("unbenutzter import");
    }
}
