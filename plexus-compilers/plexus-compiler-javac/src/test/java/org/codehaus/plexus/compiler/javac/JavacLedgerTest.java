package org.codehaus.plexus.compiler.javac;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacLedgerTest {

    private static final String SOURCE = "/work/repo/mod/src/main/java/A.java";

    private static Map<String, String> blazarEnv(Path vba) {
        Map<String, String> env = new HashMap<>();
        env.put(JavacLedger.BLAZAR_ENV_KEY, "github.com-HubSpot-repo-master-mod");
        env.put(JavacLedger.VBA_ENV_KEY, vba.toString());
        return env;
    }

    private static CompilerConfiguration config(String outputDirName, boolean fork) {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setWorkingDirectory(new File("/work/repo/mod"));
        config.setOutputLocation("/work/repo/mod/target/" + outputDirName);
        config.setFork(fork);
        return config;
    }

    private static String[] args() {
        return new String[] {"-d", "/work/repo/mod/target/classes", "-classpath", "/libs/a.jar:/libs/b.jar", SOURCE};
    }

    private static CompilerResult success() {
        return new CompilerResult(true, Collections.emptyList());
    }

    private static List<String> lines(Path vba) throws Exception {
        return Files.readAllLines(vba.resolve(JavacLedger.FILE_NAME), StandardCharsets.UTF_8);
    }

    @Test
    void isOffOutsideBlazar() {
        Map<String, String> vbaOnly = new HashMap<>();
        vbaOnly.put(JavacLedger.VBA_ENV_KEY, "/vba");
        Map<String, String> blazarOnly = new HashMap<>();
        blazarOnly.put(JavacLedger.BLAZAR_ENV_KEY, "x");
        assertNull(JavacLedger.path(vbaOnly));
        assertNull(JavacLedger.path(blazarOnly));
        Map<String, String> both = new HashMap<>(vbaOnly);
        both.putAll(blazarOnly);
        assertEquals(new File("/vba", JavacLedger.FILE_NAME).toPath(), JavacLedger.path(both));
    }

    @Test
    void recordsArgsWithoutTheSourcesAndListsTheSourcesSeparately(@TempDir Path vba) throws Exception {
        JavacLedger.record(
                blazarEnv(vba),
                config("classes", true),
                args(),
                new String[] {SOURCE},
                "/jdk/bin/javac",
                System.nanoTime(),
                success(),
                null,
                w -> {});

        List<String> lines = lines(vba);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains(
                "\"javacArgs\":[\"-d\",\"/work/repo/mod/target/classes\",\"-classpath\",\"/libs/a.jar:/libs/b.jar\"]"));
        assertTrue(line.contains("\"sources\":[\"" + SOURCE + "\"]"));
        assertTrue(line.contains("\"sourceCount\":1"));
        assertTrue(line.contains("\"module\":\"mod\""));
        assertTrue(line.contains("\"task\":\"compile\""));
        assertTrue(line.contains("\"fork\":true"));
        assertTrue(line.contains("\"executable\":\"/jdk/bin/javac\""));
        assertTrue(line.contains("\"javaHome\":\""));
        assertTrue(line.contains("\"outcome\":\"success\""));
        assertTrue(line.contains("\"error\":null"));
    }

    @Test
    void namesTestCompilesFromTheOutputDirectory(@TempDir Path vba) throws Exception {
        JavacLedger.record(
                blazarEnv(vba),
                config("test-classes", false),
                args(),
                new String[] {SOURCE},
                null,
                System.nanoTime(),
                success(),
                null,
                w -> {});

        String line = lines(vba).get(0);
        assertTrue(line.contains("\"task\":\"testCompile\""));
        assertTrue(line.contains("\"executable\":null"));
    }

    @Test
    void recordsFailedAndThrowingCompiles(@TempDir Path vba) throws Exception {
        JavacLedger.record(
                blazarEnv(vba),
                config("classes", false),
                args(),
                new String[] {SOURCE},
                null,
                System.nanoTime(),
                new CompilerResult(false, Collections.emptyList()),
                null,
                w -> {});
        JavacLedger.record(
                blazarEnv(vba),
                config("classes", false),
                args(),
                new String[] {SOURCE},
                null,
                System.nanoTime(),
                null,
                new CompilerException("boom"),
                w -> {});

        List<String> lines = lines(vba);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"outcome\":\"failure\""));
        assertTrue(lines.get(0).contains("\"error\":\"compilation failed\""));
        assertTrue(lines.get(1).contains("\"outcome\":\"failure\""));
        assertTrue(lines.get(1).contains("boom"));
    }

    @Test
    void writesNothingOutsideBlazar(@TempDir Path vba) {
        Map<String, String> env = new HashMap<>();
        env.put(JavacLedger.VBA_ENV_KEY, vba.toString());
        JavacLedger.record(
                env,
                config("classes", false),
                args(),
                new String[] {SOURCE},
                null,
                System.nanoTime(),
                success(),
                null,
                w -> {});

        assertFalse(Files.exists(vba.resolve(JavacLedger.FILE_NAME)));
    }

    @Test
    void keepsLinesWholeUnderConcurrentWriters(@TempDir Path vba) throws Exception {
        int writers = 200;
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            big.append('x');
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(writers);
        try {
            for (int i = 0; i < writers; i++) {
                String source = "/work/repo/m" + i + "/A.java";
                pool.execute(() -> {
                    try {
                        JavacLedger.record(
                                blazarEnv(vba),
                                config("classes", false),
                                new String[] {"-processorpath", big.toString(), source},
                                new String[] {source},
                                null,
                                System.nanoTime(),
                                success(),
                                null,
                                w -> {});
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<String> lines = lines(vba);
        assertEquals(writers, lines.size());
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"));
            int start = line.indexOf("\"sources\":[\"") + "\"sources\":[\"".length();
            seen.add(line.substring(start, line.indexOf('"', start)));
        }
        assertEquals(writers, seen.size());
    }

    @Test
    void doesNotThrowWhenTheLedgerCannotBeWritten(@TempDir Path dir) throws Exception {
        Path blocker = Files.createFile(dir.resolve("blocker"));
        List<String> warnings = new ArrayList<>();
        JavacLedger.record(
                blazarEnv(blocker.resolve("nested")),
                config("classes", false),
                args(),
                new String[] {SOURCE},
                null,
                System.nanoTime(),
                success(),
                null,
                warnings::add);

        assertTrue(warnings.size() <= 1);
    }

    @Test
    void escapesJsonStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", JavacLedger.quote("a\"b\\c\nd\u0001"));
    }
}
