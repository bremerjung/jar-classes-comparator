#!/bin/sh
set -e

# Pfadliste als Parameter oder Standard
PURGE_LIST="${1:-purge-paths.txt}"

if [ ! -f "$PURGE_LIST" ]; then
    echo "Keine Pfadliste gefunden: $PURGE_LIST"
    echo "Bitte zuerst das Maven-Goal ausfuehren:"
    echo "  mvn com.company:purge-plugin:purge-local-repository"
    exit 1
fi

echo "Lese Pfadliste: $PURGE_LIST"
COUNT=0
ERRORS=0

while IFS= read -r TARGET; do
    [ -z "$TARGET" ] && continue

    if [ -d "$TARGET" ]; then
        echo "  Loesche Verzeichnis: $TARGET"
        rm -rf "$TARGET" 2>/dev/null
        if [ -d "$TARGET" ]; then
            echo "  FEHLER: Konnte nicht geloescht werden: $TARGET"
            ERRORS=$((ERRORS + 1))
        else
            COUNT=$((COUNT + 1))
        fi
    elif [ -f "$TARGET" ]; then
        echo "  Loesche Datei: $TARGET"
        rm -f "$TARGET" 2>/dev/null
        if [ -f "$TARGET" ]; then
            echo "  FEHLER: Konnte nicht geloescht werden: $TARGET"
            ERRORS=$((ERRORS + 1))
        else
            COUNT=$((COUNT + 1))
        fi
    else
        echo "  Bereits entfernt: $TARGET"
    fi
done < "$PURGE_LIST"

rm -f "$PURGE_LIST"

echo ""
echo "Fertig: $COUNT geloescht, $ERRORS Fehler."
if [ "$ERRORS" -gt 0 ]; then
    echo "Tipp: Sicherstellen, dass keine IDE oder JVM die Dateien sperrt."
    exit 1
fi
