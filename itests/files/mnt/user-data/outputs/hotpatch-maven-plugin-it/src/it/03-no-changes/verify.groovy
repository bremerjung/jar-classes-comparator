/**
 * Verifikations-Skript für IT-Szenario 03: Keine Änderungen
 *
 * Erwartet:
 *   - target/hotpatch.zip existiert NICHT
 *     (identische Dateien → kein Patch nötig)
 */
File zip = new File(basedir, 'target/hotpatch.zip')

assert !zip.exists() :
    "hotpatch.zip wurde unerwartet erstellt, obwohl keine Unterschiede " +
    "vorliegen: ${zip.absolutePath}"

println "[IT-03 ✓] Kein hotpatch.zip erstellt – korrekt bei identischen Dateien."
