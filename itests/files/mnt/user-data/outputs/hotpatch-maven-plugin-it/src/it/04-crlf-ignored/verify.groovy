/**
 * Verifikations-Skript für IT-Szenario 04: CRLF vs. LF wird ignoriert
 *
 * Das Fixture-JAR enthält META-INF/app.properties mit \r\n.
 * target/classes enthält dieselbe Datei mit \n.
 * Das Plugin soll keinen Unterschied melden → kein ZIP.
 */
File zip = new File(basedir, 'target/hotpatch.zip')

assert !zip.exists() :
    "hotpatch.zip wurde unerwartet erstellt: " +
    "Ein reiner CRLF-vs-LF-Unterschied darf nicht als Änderung gelten.\n" +
    "Pfad: ${zip.absolutePath}"

println "[IT-04 ✓] Kein hotpatch.zip – CRLF/LF-Unterschied wurde korrekt ignoriert."
