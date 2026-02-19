package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class JarClassesComparator {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java JarClassesComparator <jar-file> <target/classes-dir>");
            return;
        }

        Path jarPath = Paths.get(args[0]);
        Path classesDir = Paths.get(args[1]);

        // 1. Einträge aus dem JAR sammeln (ohne META-INF/)
        Map<String, byte[]> jarEntries = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    jarEntries.put(entry.getName(), readAllBytes(is));
                }
            }
        }

        // 2. Dateien aus target/classes sammeln
        Map<String, byte[]> classesEntries = new HashMap<>();
        if (Files.exists(classesDir)) {
            Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        String relative = classesDir.relativize(p).toString().replace('\\', '/');
                        try {
                            classesEntries.put(relative, Files.readAllBytes(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        // 3. Vergleichen
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(jarEntries.keySet());
        allKeys.addAll(classesEntries.keySet());

        List<String> onlyInJar = new ArrayList<>();
        List<String> onlyInClasses = new ArrayList<>();
        List<String> different = new ArrayList<>();
        int identical = 0;

        for (String key : allKeys) {
            boolean inJar = jarEntries.containsKey(key);
            boolean inClasses = classesEntries.containsKey(key);

            if (inJar && !inClasses) {
                onlyInJar.add(key);
            } else if (!inJar && inClasses) {
                onlyInClasses.add(key);
            } else {
                if (Arrays.equals(jarEntries.get(key), classesEntries.get(key))) {
                    identical++;
                } else {
                    different.add(key);
                }
            }
        }

        // 4. Ergebnis ausgeben
        System.out.println("=== JAR vs. target/classes Vergleich ===");
        System.out.println("Identische Dateien:  " + identical);
        System.out.println("Unterschiedliche:    " + different.size());
        System.out.println("Nur im JAR:          " + onlyInJar.size());
        System.out.println("Nur in classes:      " + onlyInClasses.size());
        System.out.println();

        if (!different.isEmpty()) {
            System.out.println("--- Unterschiedliche Dateien ---");
            different.forEach(f -> System.out.println("  DIFF: " + f));
        }
        if (!onlyInJar.isEmpty()) {
            System.out.println("--- Nur im JAR ---");
            onlyInJar.forEach(f -> System.out.println("  JAR:  " + f));
        }
        if (!onlyInClasses.isEmpty()) {
            System.out.println("--- Nur in target/classes ---");
            onlyInClasses.forEach(f -> System.out.println("  CLS:  " + f));
        }

        if (different.isEmpty() && onlyInJar.isEmpty() && onlyInClasses.isEmpty()) {
            System.out.println("✔ JAR und target/classes sind identisch.");
        }

        // 5. ZIP mit Unterschieden erstellen
        List<String> toExport = new ArrayList<>();
        toExport.addAll(different);
        toExport.addAll(onlyInClasses);

        if (!toExport.isEmpty()) {
            Path zipPath = Paths.get("differences.zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                for (String file : toExport) {
                    zos.putNextEntry(new ZipEntry(file));
                    zos.write(classesEntries.get(file));
                    zos.closeEntry();
                }
            }
            System.out.println("\nZIP erstellt: " + zipPath.toAbsolutePath());
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }
}
