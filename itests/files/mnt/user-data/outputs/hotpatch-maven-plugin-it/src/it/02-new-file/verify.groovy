/**
 * Verifikations-Skript für IT-Szenario 02: Neue Datei
 *
 * Erwartet:
 *   - target/hotpatch.zip existiert
 *   - Das ZIP enthält com/example/Bar.class
 */
import java.util.zip.ZipFile

File zip = new File(basedir, 'target/hotpatch.zip')
assert zip.exists() : "hotpatch.zip wurde nicht erstellt: ${zip.absolutePath}"

List<String> entries = []
new ZipFile(zip).withCloseable { zf ->
    zf.entries().each { entries << it.name }
}

assert entries.contains('com/example/Bar.class') :
    "com/example/Bar.class fehlt im ZIP. Gefundene Einträge: ${entries}"

println "[IT-02 ✓] Neue Datei korrekt im hotpatch.zip: ${entries}"
