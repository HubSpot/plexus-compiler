package org.codehaus.plexus.compiler.javac;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerResult;

final class JavacLedger {

    static final String FILE_NAME = "javac-ledger.jsonl";
    static final String BLAZAR_ENV_KEY = "BLAZAR_COORDINATES";
    static final String VBA_ENV_KEY = "VIEWABLE_BUILD_ARTIFACTS_DIR";

    private static final Object LOCK = new Object();
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private JavacLedger() {}

    static Path path(Map<String, String> env) {
        if (!env.containsKey(BLAZAR_ENV_KEY)) {
            return null;
        }
        String vba = env.get(VBA_ENV_KEY);
        if (vba == null || vba.isEmpty()) {
            return null;
        }
        return Paths.get(vba, FILE_NAME);
    }

    static void record(
            Map<String, String> env,
            CompilerConfiguration config,
            String[] args,
            String[] sourceFiles,
            String executable,
            long startNanos,
            CompilerResult result,
            Throwable error,
            Consumer<String> warn) {
        Path file = path(env);
        if (file == null) {
            return;
        }
        try {
            String line = entry(config, args, sourceFiles, executable, startNanos, result, error);
            append(file, line);
        } catch (IOException e) {
            if (WARNED.compareAndSet(false, true)) {
                warn.accept("[javac-ledger] could not write to " + file + ": " + e);
            }
        }
    }

    static String entry(
            CompilerConfiguration config,
            String[] args,
            String[] sourceFiles,
            String executable,
            long startNanos,
            CompilerResult result,
            Throwable error) {
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        File output = new File(config.getOutputLocation());
        File workingDirectory = config.getWorkingDirectory();
        Set<String> sources = new HashSet<>(Arrays.asList(sourceFiles));
        StringBuilder javacArgs = new StringBuilder();
        for (String arg : args) {
            if (!sources.contains(arg)) {
                appendSeparated(javacArgs, quote(arg));
            }
        }
        StringBuilder sourceList = new StringBuilder();
        for (String source : sourceFiles) {
            appendSeparated(sourceList, quote(source));
        }
        boolean success = error == null && result != null && result.isSuccess();
        String errorText = error != null ? error.toString() : (success ? null : "compilation failed");

        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "timestamp", quote(Instant.now().toString()));
        field(json, "module", quote(workingDirectory != null ? workingDirectory.getName() : ""));
        field(json, "task", quote(output.getName().equals("test-classes") ? "testCompile" : "compile"));
        field(json, "workingDirectory", workingDirectory != null ? quote(workingDirectory.getAbsolutePath()) : "null");
        field(json, "outputLocation", quote(output.getAbsolutePath()));
        field(json, "fork", Boolean.toString(config.isFork()));
        field(json, "executable", executable != null ? quote(executable) : "null");
        field(json, "javaHome", quote(System.getProperty("java.home")));
        field(json, "javacArgs", "[" + javacArgs + "]");
        field(json, "sourceCount", Integer.toString(sourceFiles.length));
        field(json, "sources", "[" + sourceList + "]");
        field(json, "durationMillis", Long.toString(durationMillis));
        field(json, "outcome", quote(success ? "success" : "failure"));
        json.append(quote("error")).append(':').append(errorText != null ? quote(errorText) : "null");
        json.append('}');
        return json.toString();
    }

    static void append(Path file, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u").append("0000", hex.length(), 4).append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append(quote(name)).append(':').append(value).append(',');
    }

    private static void appendSeparated(StringBuilder list, String value) {
        if (list.length() > 0) {
            list.append(',');
        }
        list.append(value);
    }
}
