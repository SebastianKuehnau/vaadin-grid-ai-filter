# Task / Requirements

Die `CustomerListViewBrowserlessIT` in `02-ai-agent-filter` (7 Testfälle) und
`03-ai-structured-filter` (5 Testfälle) sollen künftig **dieselbe Funktionalität** testen, damit
die beiden Ansätze (Tool Calling vs. Structured Output) bei Konferenz-Talks direkt anhand
identischer Queries verglichen werden können.

**Aktueller Stand (Ist-Abgleich):**

| Testfall | 02-ai-agent-filter | 03-ai-structured-filter |
|---|---|---|
| `customersInBerlin` | ✅ | ✅ |
| `creditworthyCustomers` | ✅ | ✅ |
| `atRiskCustomers` | ✅ | ✅ |
| `customersSince2020` | ✅ | ✅ (Assertion leicht anders: `getYear()==2020` vs. `isAfterOrEqualTo(LocalDate.of(2020,1,1))` — auf Konsistenz prüfen) |
| `companyNameContainsData` | ✅ | ✅ |
| `multiValueCities` ("Berlin or Hamburg") | ✅ | ❌ fehlt |
| `annualRevenueOverThreshold` ("over 200000") | ✅ | ❌ fehlt |

**Funktionale Anforderungen:**
- Die beiden fehlenden Testfälle `multiValueCities` und `annualRevenueOverThreshold` aus
  `02-ai-agent-filter` in `03-ai-structured-filter` übernehmen (gleicher Testname, gleiche Query,
  auf die jeweilige Datenmodell-API von Modul 03 angepasste Assertion).
- Die `customersSince2020`-Assertion zwischen beiden Dateien angleichen (einheitlicher Stil), ohne
  die fachliche Aussage zu verändern.
- Neuen, in **beiden** Dateien identischen Testfall ergänzen:
  - Query: `"show all customer from Berlin and Cologne with a positive creditrating and a revenue over 100000"`
  - Erwartung: nur Kunden aus Berlin **oder** Cologne, mit `CreditRating.GOOD` ("positive" = kreditwürdig)
    **und** `annualRevenue` über 100.000 (jeweils UND-verknüpft über die drei Kriterien; Stadt
    intern OR-verknüpft).
  - Testdaten für Cologne mit `CREDIT_SCORE >= 70` und `ANNUAL_REVENUE > 100000` sind in beiden
    Modulen bereits über `data.sql` vorhanden (nicht verändern, nur verifizieren) — siehe Context.
- Nach Abschluss enthalten beide `CustomerListViewBrowserlessIT`-Dateien denselben Satz an
  Testmethoden mit denselben Queries und vergleichbaren Assertions.

**Nicht im Scope:**
- Keine Änderungen an Produktionscode (`CustomerSearchToolCallingService`,
  `CustomerSearchStructuredOutputService`, Specifications etc.) — beide Module unterstützen
  Multi-Value-City, CreditRating- und Revenue-Filter laut vorherigen Tasks bereits. Falls sich beim
  Ausführen zeigt, dass ein Modul die neue kombinierte Query nicht korrekt auflöst, ist das ein
  Befund für den Plan-Schritt (siehe Approach), keine implizite Erlaubnis, Produktionscode ad-hoc zu
  ändern.
- `CustomerListViewBrowserlessTest` (Fake-Agent, kein LLM) und `CustomerSearchAgentIT` sind nicht
  Teil dieser Aufgabe.

# Context
- **Affected modules:** `02-ai-agent-filter`, `03-ai-structured-filter` — jeweils nur
  `src/test/java/dev/demo/vaadin/aigridfilter/ui/CustomerListViewBrowserlessIT.java`.
- **Purpose:** Demo für Konferenz-Talks — die beiden Module sollen mit identischen Natural-Language-
  Queries direkt vergleichbar sein (Geschwindigkeit, Ergebnisqualität) im `-Pit-local-ollama`-Lauf.
- **Relevant existing parts:**
  - Beide `CustomerListViewBrowserlessIT.java` (siehe Tabelle oben).
  - `data.sql` beider Module enthält bereits Cologne-Datensätze (z. B. IDs 9, 15, 21, 27, 33, 39,
    45, 51, 57, 63, 69, 75, 81, 87, 93, 99) mit unterschiedlichen `CREDIT_SCORE`- und
    `ANNUAL_REVENUE`-Werten — für die neue Query eignen sich z. B. Kunden mit Score ≥ 70 und Revenue
    > 100000 (mehrere davon vorhanden, keine SQL-Änderung nötig).
  - `CreditRating.fromScore` (identisch in beiden Modulen): Score ≥ 70 → `GOOD` ("kreditwürdig" /
    "positiv").
  - `CLAUDE.md` Definition of Done, Punkt 4: neue Filter-**Fähigkeiten** benötigen einen Testfall in
    `04-ollama-benchmark/BenchmarkLocalModels.java`. Da die neue Query nur bereits unterstützte
    Fähigkeiten (Multi-Value-Stadt, CreditRating, Revenue-Schwelle) kombiniert, im Plan-Schritt
    klären, ob das als "neue Fähigkeit" zählt oder nicht.

# Approach
1. **Plan Mode:** Erstelle einen Plan und präsentiere ihn. Prüfe insbesondere:
   - Wie soll der neue Testfall heißen (z. B. `citiesWithGoodRatingAndRevenueAboveThreshold`)?
   - Wie soll die Assertion für die kombinierte Query aussehen (Stadt: `containsAnyOf("Berlin",
     "Cologne")`; CreditRating: `isEqualTo(CreditRating.GOOD)`; Revenue: `isGreaterThanOrEqualTo(...)`
     — analog zum bestehenden Muster mit Toleranz-Puffer wie bei `annualRevenueOverThreshold`)?
   - Soll die `customersSince2020`-Assertion vereinheitlicht werden, und falls ja, auf welchen Stil
     (Jahr-Vergleich vs. `LocalDate`-Vergleich)?
   - Zählt die kombinierte Query als neue Fähigkeit im Sinne von `CLAUDE.md` Punkt 4 (Benchmark)?
   - Gibt es Inkonsistenzen zwischen den beiden Dateien (Imports, Javadoc-Verweise auf "5 Testfälle"
     in 03s Klassenkommentar), die zusätzlich korrigiert werden müssen?

   Warte auf meine Freigabe, bevor du mit der Implementierung beginnst.

2. **Implementierung:** Arbeite in logischen, verifizierbaren Schritten. Nach jedem Schritt einen
   Commit (Conventional Commits, kein Push). Mögliche Schritte:
   - `multiValueCities` und `annualRevenueOverThreshold` nach `03-ai-structured-filter` übernehmen.
   - `customersSince2020`-Assertion angleichen.
   - Neuen kombinierten Testfall in beiden Dateien ergänzen.
   - Javadoc-Kommentar in `03-ai-structured-filter`s Datei aktualisieren (verweist aktuell auf "the
     same 5 queries").
   - Falls im Plan als nötig identifiziert: Testfall in `04-ollama-benchmark/BenchmarkLocalModels.java`
     ergänzen.

3. **Verifikation:** Gemäß `CLAUDE.md` Definition of Done — `./mvnw verify -pl 02-ai-agent-filter`
   und `-pl 03-ai-structured-filter`, sowie `-Pit-local-ollama` für beide Module. Iteriere
   eigenständig auf Fehlern (insb. falls das reale Modell die kombinierte Query nicht sauber löst —
   ggf. Query-Formulierung oder Toleranz-Puffer in der Assertion anpassen, bevor Produktionscode
   angefasst wird).

4. **Dokumentation:** `README.md` beider Module aktualisieren, falls dort Testfälle/Queries als
   Beispiele aufgeführt sind und nicht mehr aktuell sind.

# Definition of Done (zusätzlich zu CLAUDE.md)
- Beide `CustomerListViewBrowserlessIT`-Dateien enthalten dieselben Testmethoden mit denselben
  Queries: `customersInBerlin`, `creditworthyCustomers`, `atRiskCustomers`, `customersSince2020`,
  `companyNameContainsData`, `multiValueCities`, `annualRevenueOverThreshold`, sowie den neuen
  kombinierten Testfall.
- Neuer Testfall mit Query `"show all customer from Berlin and Cologne with a positive
  creditrating and a revenue over 100000"` ist in beiden Modulen vorhanden und grün.
- `./mvnw verify -pl 02-ai-agent-filter` und `./mvnw verify -pl 03-ai-structured-filter` laufen
  fehlerfrei durch.
- `-Pit-local-ollama` läuft für beide Module durch (sofern Ollama erreichbar).
- Keine Regressionen bei den bestehenden Testfällen.
- Javadoc-Verweise auf die Anzahl/Übereinstimmung der Testfälle sind aktuell.

# Final Report
Am Ende eine Zusammenfassung mit:
- **Commits:** Was wurde pro Commit geändert.
- **Tests:** Welche Testfälle jetzt in welcher Datei existieren, Ergebnis der `-Pit-local-ollama`-Läufe.
- **Offene Punkte:** z. B. ob die kombinierte Query als neue Fähigkeit ins Benchmark aufgenommen
  wurde, und ob/wie die `customersSince2020`-Assertion vereinheitlicht wurde.
