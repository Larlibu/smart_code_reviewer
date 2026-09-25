package de.larlibu.smartcodereviewer;

// Begruendung: Erhöht die Stabilität in Multi-Threaded-Umgebungen und verbessert die Observability durch Logging-Frameworks.
import java.util.Collections;
import java.util.List;
import java.util.Optional;
// SCR-Hinweis: [KI] Thread-sichere Implementierung und Logging
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Thread-sichere Demo-Implementierung eines einfachen Bestellmanagers.
 *
 * <p>Diese Klasse dient als Testdatei fuer die SCR-Pipeline und zeigt den verbesserten Stand
 * nach einem KI-Review: {@link CopyOnWriteArrayList} statt {@link java.util.ArrayList} fuer
 * Thread-Sicherheit und {@link Logger} statt {@code System.out.println} fuer konfigurierbare
 * Observability.</p>
 *
 */
public class TestKlasseOrderManager {

    // KI-Feedback: Summary: Der Code weist Mängel in der Thread-Sicherheit, der Fehlerbehandlung und der Kapselung auf.
    private static final Logger LOGGER = Logger.getLogger(TestKlasseOrderManager.class.getName());
    private final List<String> orders = new CopyOnWriteArrayList<>();

    /**
     * Fuegt eine neue Bestellung hinzu, wenn der Name nicht leer ist.
     *
     * @param orderName Name der Bestellung; {@code null} und Leerstrings werden ignoriert
     */
    public void addOrder(String orderName) {
        if (orderName != null && !orderName.isBlank()) {
            orders.add(orderName.trim());
        }
    }

    /**
     * Protokolliert eine simulierte Datenbankabfrage fuer eine Kundennummer.
     *
     * <p>In einer echten Implementierung wuerde hier ein {@code PreparedStatement}
     * verwendet, um SQL-Injection zu verhindern.</p>
     *
     * @param customerId Kundennummer; leere und {@code null}-Werte werden ignoriert
     */
    public void printOrderDatabaseStatus(String customerId) {
        if (customerId == null || customerId.isBlank()) return;
        LOGGER.info("Führe Datenbankabfrage aus mit ID: " + customerId);
    }

    /**
     * Sucht eine Bestellung anhand des Namens (Gross-/Kleinschreibung wird ignoriert).
     *
     * @param search gesuchter Bestellungsname
     * @return gefundene Bestellung oder ein leeres Optional
     */
    public Optional<String> findOrder(String search) {
        return orders.stream()
                .filter(o -> o.equalsIgnoreCase(search))
                .findFirst();
    }

    /**
     * Gibt eine unveraenderliche Sicht auf die Bestellungsliste zurueck.
     *
     * @return unveraenderliche Liste aller gespeicherten Bestellungen
     */
    public List<String> getOrders() {
        return Collections.unmodifiableList(orders);
    }

    /**
     * Verarbeitet alle vorhandenen Bestellungen und protokolliert die Anzahl.
     */
    public void processOrders() {
        if (!orders.isEmpty()) {
            LOGGER.info("Verarbeite " + orders.size() + " Bestellungen...");
        }
    }
}
