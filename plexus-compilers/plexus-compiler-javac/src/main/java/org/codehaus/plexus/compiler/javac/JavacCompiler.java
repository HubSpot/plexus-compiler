package org.codehaus.plexus.compiler.javac;

/**
 * The MIT License
 *
 * Copyright (c) 2005, The Codehaus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 *
 * Copyright 2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.plexus.compiler.AbstractCompiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @author <a href="mailto:matthew.pocock@ncl.ac.uk">Matthew Pocock</a>
 * @author <a href="mailto:joerg.wassmer@web.de">J&ouml;rg Wa&szlig;mer</a>
 * @author Others
 *
 */
@Named("javac")
@Singleton
public class JavacCompiler extends AbstractCompiler {

    // see compiler.warn.warning in compiler.properties of javac sources
    private static final String[] WARNING_PREFIXES = {"warning: ", "\u8b66\u544a: ", "\u8b66\u544a\uff1a "};

    // see compiler.note.note in compiler.properties of javac sources
    private static final String[] NOTE_PREFIXES = {"Note: ", "\u6ce8: ", "\u6ce8\u610f\uff1a "};

    // see compiler.misc.verbose in compiler.properties of javac sources
    private static final String[] MISC_PREFIXES = {"["};

    private static final Object LOCK = new Object();

    private static final String JAVAC_CLASSNAME = "com.sun.tools.javac.Main";

    private volatile Class<?> javacClass;

    private final Deque<Class<?>> javacClasses = new ConcurrentLinkedDeque<>();

    private static final Pattern JAVA_MAJOR_AND_MINOR_VERSION_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");

    /** Cache of javac version per executable (never invalidated) */
    private static final Map<String, String> VERSION_PER_EXECUTABLE = new ConcurrentHashMap<>();

    @Inject
    private InProcessCompiler inProcessCompiler;

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public JavacCompiler() {
        super(CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE, ".java", ".class", null);
    }

    // ----------------------------------------------------------------------
    // Compiler Implementation
    // ----------------------------------------------------------------------

    @Override
    public String getCompilerId() {
        return "javac";
    }

    private String getInProcessJavacVersion() throws CompilerException {
        return System.getProperty("java.version");
    }

    private String getOutOfProcessJavacVersion(String executable) throws CompilerException {
        String version = VERSION_PER_EXECUTABLE.get(executable);
        if (version == null) {
            Commandline cli = new Commandline();
            cli.setExecutable(executable);
            /*
             * The option "-version" should be supported by javac since 1.6 (https://docs.oracle.com/javase/6/docs/technotes/tools/solaris/javac.html)
             * up to 21 (https://docs.oracle.com/en/java/javase/21/docs/specs/man/javac.html#standard-options)
             */
            cli.addArguments(new String[] {"-version"}); //
            CommandLineUtils.StringStreamConsumer out = new CommandLineUtils.StringStreamConsumer();
            try {
                int exitCode = CommandLineUtils.executeCommandLine(cli, out, out);
                if (exitCode != 0) {
                    throw new CompilerException("Could not retrieve version from " + executable + ". Exit code "
                            + exitCode + ", Output: " + out.getOutput());
                }
            } catch (CommandLineException e) {
                throw new CompilerException("Error while executing the external compiler " + executable, e);
            }
            version = extractMajorAndMinorVersion(out.getOutput());
            VERSION_PER_EXECUTABLE.put(executable, version);
        }
        return version;
    }

    static String extractMajorAndMinorVersion(String text) {
        Matcher matcher = JAVA_MAJOR_AND_MINOR_VERSION_PATTERN.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Could not extract version from \"" + text + "\"");
        }
        return matcher.group();
    }

    @Override
    public CompilerResult performCompile(CompilerConfiguration config) throws CompilerException {
        File destinationDir = new File(config.getOutputLocation());

        if (!destinationDir.exists()) {
            destinationDir.mkdirs();
        }

        String[] sourceFiles = getSourceFiles(config);

        if ((sourceFiles == null) || (sourceFiles.length == 0)) {
            return new CompilerResult();
        }

        logCompiling(sourceFiles, config);

        final String javacVersion;
        final String executable;
        if (config.isFork()) {
            executable = getJavacExecutable(config);
            javacVersion = getOutOfProcessJavacVersion(executable);
        } else {
            javacVersion = getInProcessJavacVersion();
            executable = null;
        }

        String[] args = buildCompilerArguments(config, sourceFiles, javacVersion);

        CompilerResult result;

        if (config.isFork()) {

            result = compileOutOfProcess(config, executable, args);
        } else {
            if (hasJavaxToolProvider() && !config.isForceJavacCompilerUse()) {
                // use fqcn to prevent loading of the class on 1.5 environment !
                result = inProcessCompiler().compileInProcess(args, config, sourceFiles);
            } else {
                result = compileInProcess(args, config);
            }
        }

        return result;
    }

    protected InProcessCompiler inProcessCompiler() {
        return inProcessCompiler;
    }

    /**
     *
     * @return {@code true} if the current context class loader has access to {@code javax.tools.ToolProvider}
     */
    protected static boolean hasJavaxToolProvider() {
        try {
            Thread.currentThread().getContextClassLoader().loadClass("javax.tools.ToolProvider");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String[] createCommandLine(CompilerConfiguration config) throws CompilerException {
        final String javacVersion;
        if (config.isFork()) {
            String executable = getJavacExecutable(config);
            javacVersion = getOutOfProcessJavacVersion(executable);
        } else {
            javacVersion = getInProcessJavacVersion();
        }
        return buildCompilerArguments(config, getSourceFiles(config), javacVersion);
    }

    public static String[] buildCompilerArguments(
            CompilerConfiguration config, String[] sourceFiles, String javacVersion) {
        List<String> args = new ArrayList<>();

        // ----------------------------------------------------------------------
        // Set output
        // ----------------------------------------------------------------------

        File destinationDir = new File(config.getOutputLocation());

        args.add("-d");

        args.add(destinationDir.getAbsolutePath());

        // ----------------------------------------------------------------------
        // Set the class and source paths
        // ----------------------------------------------------------------------

        List<String> classpathEntries = config.getClasspathEntries();
        if (classpathEntries != null && !classpathEntries.isEmpty()) {
            args.add("-classpath");

            args.add(getPathString(classpathEntries));
        }

        List<String> modulepathEntries = config.getModulepathEntries();
        if (modulepathEntries != null && !modulepathEntries.isEmpty()) {
            args.add("--module-path");

            args.add(getPathString(modulepathEntries));
        }

        List<String> sourceLocations = config.getSourceLocations();
        if (sourceLocations != null && !sourceLocations.isEmpty()) {
            // always pass source path, even if sourceFiles are declared,
            // needed for jsr269 annotation processing, see MCOMPILER-98
            args.add("-sourcepath");

            args.add(getPathString(sourceLocations));
        }
        if (!hasJavaxToolProvider() || config.isForceJavacCompilerUse() || config.isFork()) {
            args.addAll(Arrays.asList(sourceFiles));
        }

        if (JavaVersion.JAVA_1_6.isOlderOrEqualTo(javacVersion)) {
            // now add jdk 1.6 annotation processing related parameters

            if (config.getGeneratedSourcesDirectory() != null) {
                config.getGeneratedSourcesDirectory().mkdirs();

                args.add("-s");
                args.add(config.getGeneratedSourcesDirectory().getAbsolutePath());
            }
            if (config.getProc() != null) {
                args.add("-proc:" + config.getProc());
            }
            if (config.getAnnotationProcessors() != null) {
                args.add("-processor");
                String[] procs = config.getAnnotationProcessors();
                StringBuilder buffer = new StringBuilder();
                for (int i = 0; i < procs.length; i++) {
                    if (i > 0) {
                        buffer.append(",");
                    }

                    buffer.append(procs[i]);
                }
                args.add(buffer.toString());
            }
            if (config.getProcessorPathEntries() != null
                    && !config.getProcessorPathEntries().isEmpty()) {
                args.add("-processorpath");
                args.add(getPathString(config.getProcessorPathEntries()));
            }
            if (config.getProcessorModulePathEntries() != null
                    && !config.getProcessorModulePathEntries().isEmpty()) {
                args.add("--processor-module-path");
                args.add(getPathString(config.getProcessorModulePathEntries()));
            }
        }

        if (config.isOptimize()) {
            args.add("-O");
        }

        if (config.isDebug()) {
            if (StringUtils.isNotEmpty(config.getDebugLevel())) {
                args.add("-g:" + config.getDebugLevel());
            } else {
                args.add("-g");
            }
        }

        if (config.isVerbose()) {
            args.add("-verbose");
        }

        if (JavaVersion.JAVA_1_8.isOlderOrEqualTo(javacVersion) && config.isParameters()) {
            args.add("-parameters");
        }

        if (config.isEnablePreview()) {
            args.add("--enable-preview");
        }

        if (config.getImplicitOption() != null) {
            args.add("-implicit:" + config.getImplicitOption());
        }

        if (config.isShowDeprecation()) {
            args.add("-deprecation");

            // This is required to actually display the deprecation messages
            config.setShowWarnings(true);
        }

        if (!config.isShowWarnings()) {
            args.add("-nowarn");
        } else {
            String warnings = config.getWarnings();
            if (config.isShowLint()) {
                if (config.isShowWarnings() && StringUtils.isNotEmpty(warnings)) {
                    args.add("-Xlint:" + warnings);
                } else {
                    args.add("-Xlint");
                }
            }
        }

        if (config.isFailOnWarning()) {
            args.add("-Werror");
        }

        if (JavaVersion.JAVA_9.isOlderOrEqualTo(javacVersion) && !StringUtils.isEmpty(config.getReleaseVersion())) {
            args.add("--release");
            args.add(config.getReleaseVersion());
        } else {
            // TODO: this could be much improved
            if (StringUtils.isEmpty(config.getTargetVersion())) {
                // Required, or it defaults to the target of your JDK (eg 1.5)
                args.add("-target");
                args.add("1.1");
            } else {
                args.add("-target");
                args.add(config.getTargetVersion());
            }

            if (JavaVersion.JAVA_1_4.isOlderOrEqualTo(javacVersion) && StringUtils.isEmpty(config.getSourceVersion())) {
                // If omitted, later JDKs complain about a 1.1 target
                args.add("-source");
                args.add("1.3");
            } else if (JavaVersion.JAVA_1_4.isOlderOrEqualTo(javacVersion)) {
                args.add("-source");
                args.add(config.getSourceVersion());
            }
        }

        if (JavaVersion.JAVA_1_4.isOlderOrEqualTo(javacVersion) && !StringUtils.isEmpty(config.getSourceEncoding())) {
            args.add("-encoding");
            args.add(config.getSourceEncoding());
        }

        if (!StringUtils.isEmpty(config.getModuleVersion())) {
            args.add("--module-version");
            args.add(config.getModuleVersion());
        }

        for (Map.Entry<String, String> entry : config.getCustomCompilerArgumentsEntries()) {
            String key = entry.getKey();

            if (StringUtils.isEmpty(key) || key.startsWith("-J")) {
                continue;
            }

            args.add(key);

            String value = entry.getValue();

            if (StringUtils.isEmpty(value)) {
                continue;
            }

            args.add(value);
        }

        if (!config.isFork() && !args.contains("-XDuseUnsharedTable=false")) {
            args.add("-XDuseUnsharedTable=true");
        }

        return args.toArray(new String[0]);
    }

    /**
     * Represents a particular Java version (through their according version prefixes)
     */
    enum JavaVersion {
        JAVA_1_3_OR_OLDER("1.3", "1.2", "1.1", "1.0"),
        JAVA_1_4("1.4"),
        JAVA_1_5("1.5"),
        JAVA_1_6("1.6"),
        JAVA_1_7("1.7"),
        JAVA_1_8("1.8"),
        JAVA_9("9"); // since Java 9 a different versioning scheme was used (https://openjdk.org/jeps/223)
        final Set<String> versionPrefixes;

        JavaVersion(String... versionPrefixes) {
            this.versionPrefixes = new HashSet<>(Arrays.asList(versionPrefixes));
        }

        /**
         * The internal logic checks if the given version starts with the prefix of one of the enums preceding the current one.
         *
         * @param version the version to check
         * @return {@code true} if the version represented by this enum is older than or equal (in its minor and major version) to a given version
         */
        boolean isOlderOrEqualTo(String version) {
            // go through all previous enums
            JavaVersion[] allJavaVersionPrefixes = JavaVersion.values();
            for (int n = ordinal() - 1; n > -1; n--) {
                if (allJavaVersionPrefixes[n].versionPrefixes.stream().anyMatch(version::startsWith)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Compile the java sources in a external process, calling an external executable,
     * like javac.
     *
     * @param config     compiler configuration
     * @param executable name of the executable to launch
     * @param args       arguments for the executable launched
     * @return a CompilerResult object encapsulating the result of the compilation and any compiler messages
     * @throws CompilerException
     */
    protected CompilerResult compileOutOfProcess(CompilerConfiguration config, String executable, String[] args)
            throws CompilerException {
        Commandline cli = new Commandline();

        cli.setWorkingDirectory(config.getWorkingDirectory().getAbsolutePath());

        cli.setExecutable(executable);

        try {
            File argumentsFile =
                    createFileWithArguments(args, config.getBuildDirectory().getAbsolutePath());
            cli.addArguments(
                    new String[] {"@" + argumentsFile.getCanonicalPath().replace(File.separatorChar, '/')});

            if (!StringUtils.isEmpty(config.getMaxmem())) {
                cli.addArguments(new String[] {"-J-Xmx" + config.getMaxmem()});
            }

            if (!StringUtils.isEmpty(config.getMeminitial())) {
                cli.addArguments(new String[] {"-J-Xms" + config.getMeminitial()});
            }

            for (String key : config.getCustomCompilerArgumentsAsMap().keySet()) {
                if (StringUtils.isNotEmpty(key) && key.startsWith("-J")) {
                    cli.addArguments(new String[] {key});
                }
            }
        } catch (IOException e) {
            throw new CompilerException("Error creating file with javac arguments", e);
        }

        CommandLineUtils.StringStreamConsumer out = new CommandLineUtils.StringStreamConsumer();

        int returnCode;

        List<CompilerMessage> messages;

        if (getLog().isDebugEnabled()) {
            String debugFileName = StringUtils.isEmpty(config.getDebugFileName()) ? "javac" : config.getDebugFileName();

            File commandLineFile = new File(
                    config.getBuildDirectory(),
                    StringUtils.trim(debugFileName) + "." + (Os.isFamily(Os.FAMILY_WINDOWS) ? "bat" : "sh"));
            try {
                FileUtils.fileWrite(
                        commandLineFile.getAbsolutePath(), cli.toString().replaceAll("'", ""));

                if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
                    Runtime.getRuntime().exec(new String[] {"chmod", "a+x", commandLineFile.getAbsolutePath()});
                }
            } catch (IOException e) {
                if (getLog().isWarnEnabled()) {
                    getLog().warn("Unable to write '" + commandLineFile.getName() + "' debug script file", e);
                }
            }
        }

        try {
            returnCode = CommandLineUtils.executeCommandLine(cli, out, out);

            messages = parseModernStream(returnCode, new BufferedReader(new StringReader(out.getOutput())));
        } catch (CommandLineException | IOException e) {
            throw new CompilerException("Error while executing the external compiler.", e);
        }

        boolean success = returnCode == 0;
        return new CompilerResult(success, messages);
    }

    /**
     * Compile the java sources in the current JVM, without calling an external executable,
     * using <code>com.sun.tools.javac.Main</code> class
     *
     * @param args   arguments for the compiler as they would be used in the command line javac
     * @param config compiler configuration
     * @return a CompilerResult object encapsulating the result of the compilation and any compiler messages
     * @throws CompilerException
     */
    CompilerResult compileInProcess(String[] args, CompilerConfiguration config) throws CompilerException {
        final Class<?> javacClass = getJavacClass(config);
        final Thread thread = Thread.currentThread();
        final ClassLoader contextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(javacClass.getClassLoader());
        if (getLog().isDebugEnabled()) {
            getLog().debug("ttcl changed run compileInProcessWithProperClassloader");
        }
        try {
            return compileInProcessWithProperClassloader(javacClass, args);
        } finally {
            releaseJavaccClass(javacClass, config);
            thread.setContextClassLoader(contextClassLoader);
        }
    }

    protected CompilerResult compileInProcessWithProperClassloader(Class<?> javacClass, String[] args)
            throws CompilerException {
        return compileInProcess0(javacClass, args);
    }

    /**
     * Helper method for compileInProcess()
     */
    private static CompilerResult compileInProcess0(Class<?> javacClass, String[] args) throws CompilerException {
        StringWriter out = new StringWriter();

        Integer ok;

        List<CompilerMessage> messages;

        try {
            Method compile = javacClass.getMethod("compile", new Class[] {String[].class, PrintWriter.class});

            ok = (Integer) compile.invoke(null, new Object[] {args, new PrintWriter(out)});

            messages = parseModernStream(ok, new BufferedReader(new StringReader(out.toString())));
        } catch (NoSuchMethodException | IOException | InvocationTargetException | IllegalAccessException e) {
            throw new CompilerException("Error while executing the compiler.", e);
        }

        boolean success = ok == 0;
        return new CompilerResult(success, messages);
    }

    // Match ~95% of existing JDK exception name patterns (last checked for JDK 21)
    private static final Pattern STACK_TRACE_FIRST_LINE = Pattern.compile("^\\s*(?:[\\w+.-]+\\.)[\\w$]*?(?:"
            + "Exception|Error|Throwable|Failure|Result|Abort|Fault|ThreadDeath|Overflow|Warning|"
            + "NotSupported|NotFound|BadArgs|BadClassFile|Illegal|Invalid|Unexpected|Unchecked|Unmatched\\w+"
            + ").*$");

    // Match exception causes, existing and omitted stack trace elements
    private static final Pattern STACK_TRACE_OTHER_LINE =
            Pattern.compile("^\\s*(?:Caused by:\\s.*|\\s*at .*|\\s*\\.\\.\\.\\s\\d+\\smore)$");

    // Match generic javac errors with 'javac:' prefix, JMV init and boot layer init errors
    private static final Pattern JAVAC_OR_JVM_ERROR =
            Pattern.compile("^(?:javac:|Error occurred during initialization of (?:boot layer|VM)).*", Pattern.DOTALL);

    /**
     * Parse the output from the compiler into a list of CompilerMessage objects
     *
     * @param exitCode The exit code of javac.
     * @param input    The output of the compiler
     * @return List of CompilerMessage objects
     * @throws IOException
     */
    static List<CompilerMessage> parseModernStream(int exitCode, BufferedReader input) throws IOException {
        List<CompilerMessage> errors = new ArrayList<>();

        String line;

        StringBuilder buffer = new StringBuilder();

        boolean hasPointer = false;
        int stackTraceLineCount = 0;

        CompilerMessage.Kind workingKind = null;

        while (true) {
            line = input.readLine();

            if (line == null || line.isEmpty()) {
                // javac output not detected by other parsing
                // maybe better to ignore only the summary and mark the rest as error
                String bufferAsString = buffer.toString();
                if (buffer.length() > 0) {
                    if (JAVAC_OR_JVM_ERROR.matcher(bufferAsString).matches()) {
                        errors.add(new CompilerMessage(bufferAsString, CompilerMessage.Kind.ERROR));
                    } else if (hasPointer) {
                        // A compiler message remains in buffer at end of parse stream
                        errors.add(parseModernError(exitCode, bufferAsString));
                    } else if (workingKind != null) {
                        errors.add(new CompilerMessage(buffer.toString().trim(), workingKind));
                        buffer = new StringBuilder();
                        workingKind = null;
                    } else if (stackTraceLineCount > 0) {
                        // Extract stack trace from end of buffer
                        String[] lines = bufferAsString.split("\\R");
                        int linesTotal = lines.length;
                        buffer = new StringBuilder();
                        int firstLine = linesTotal - stackTraceLineCount;

                        // Salvage Javac localized message 'javac.msg.bug' ("An exception has occurred in the
                        // compiler ... Please file a bug")
                        if (firstLine > 0) {
                            final String lineBeforeStackTrace = lines[firstLine - 1];
                            // One of those two URL substrings should always appear, without regard to JVM locale.
                            // TODO: Update, if the URL changes, last checked for JDK 21.
                            if (lineBeforeStackTrace.contains("java.sun.com/webapps/bugreport")
                                    || lineBeforeStackTrace.contains("bugreport.java.com")) {
                                firstLine--;
                            }
                        }

                        // Note: For message 'javac.msg.proc.annotation.uncaught.exception' ("An annotation processor
                        // threw an uncaught exception"), there is no locale-independent substring, and the header is
                        // also multi-line. It was discarded in the removed method 'parseAnnotationProcessorStream',
                        // and we continue to do so.

                        for (int i = firstLine; i < linesTotal; i++) {
                            buffer.append(lines[i]).append(EOL);
                        }
                        errors.add(new CompilerMessage(buffer.toString(), CompilerMessage.Kind.ERROR));
                    }
                }
                if (line == null) {
                    return errors;
                }
            }

            if (stackTraceLineCount == 0 && STACK_TRACE_FIRST_LINE.matcher(line).matches()
                    || STACK_TRACE_OTHER_LINE.matcher(line).matches()) {
                stackTraceLineCount++;
            } else {
                stackTraceLineCount = 0;
            }

            // new error block?
            if (!line.startsWith(" ") && hasPointer) {
                // add the error bean
                errors.add(parseModernError(exitCode, buffer.toString()));

                // reset for next error block
                buffer = new StringBuilder(); // this is quicker than clearing it

                hasPointer = false;
            } else if (!line.startsWith(" ") && workingKind != null) {
                errors.add(new CompilerMessage(buffer.toString().trim(), workingKind));
                buffer = new StringBuilder();
                workingKind = null;
            }

            // TODO: there should be a better way to parse these
            if (line.startsWith("error: ")) {
                workingKind = CompilerMessage.Kind.ERROR;
                buffer = new StringBuilder().append(line).append(EOL);
                continue;
            } else if (line.startsWith("warning: ")) {
                workingKind = CompilerMessage.Kind.WARNING;
                buffer = new StringBuilder().append(line).append(EOL);
                continue;
            } else if (isNote(line)) {
                workingKind = CompilerMessage.Kind.NOTE;
                buffer = new StringBuilder().append(line).append(EOL);
                continue;
            } else if ((buffer.length() == 0) && isMisc(line)) {
                // verbose output was set
                errors.add(new CompilerMessage(line, CompilerMessage.Kind.OTHER));
            } else {
                buffer.append(line);

                buffer.append(EOL);
            }

            if (line.endsWith("^")) {
                hasPointer = true;
            }
        }
    }

    private static boolean isMisc(String line) {
        return startsWithPrefix(line, MISC_PREFIXES);
    }

    private static boolean isNote(String line) {
        return startsWithPrefix(line, NOTE_PREFIXES);
    }

    private static boolean startsWithPrefix(String line, String[] prefixes) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Construct a CompilerMessage object from a line of the compiler output
     *
     * @param exitCode The exit code from javac.
     * @param error    output line from the compiler
     * @return the CompilerMessage object
     */
    static CompilerMessage parseModernError(int exitCode, String error) {
        final StringTokenizer tokens = new StringTokenizer(error, ":");

        boolean isError = exitCode != 0;

        try {
            // With Java 6 error output lines from the compiler got longer. For backward compatibility
            // .. and the time being, we eat up all (if any) tokens up to the erroneous file and source
            // .. line indicator tokens.

            boolean tokenIsAnInteger;

            StringBuilder file = null;

            String currentToken = null;

            do {
                if (currentToken != null) {
                    if (file == null) {
                        file = new StringBuilder(currentToken);
                    } else {
                        file.append(':').append(currentToken);
                    }
                }

                currentToken = tokens.nextToken();

                // Probably the only backward compatible means of checking if a string is an integer.

                tokenIsAnInteger = true;

                try {
                    Integer.parseInt(currentToken);
                } catch (NumberFormatException e) {
                    tokenIsAnInteger = false;
                }
            } while (!tokenIsAnInteger);

            final String lineIndicator = currentToken;

            final int startOfFileName = file.toString().lastIndexOf(']');

            if (startOfFileName > -1) {
                file = new StringBuilder(file.substring(startOfFileName + 1 + EOL.length()));
            }

            final int line = Integer.parseInt(lineIndicator);

            final StringBuilder msgBuffer = new StringBuilder();

            String msg = tokens.nextToken(EOL).substring(2);

            // Remove the 'warning: ' prefix
            final String warnPrefix = getWarnPrefix(msg);
            if (warnPrefix != null) {
                isError = false;
                msg = msg.substring(warnPrefix.length());
            } else {
                isError = exitCode != 0;
            }

            msgBuffer.append(msg);

            msgBuffer.append(EOL);

            String context = tokens.nextToken(EOL);

            String pointer = null;

            do {
                final String msgLine = tokens.nextToken(EOL);

                if (pointer != null) {
                    msgBuffer.append(msgLine);

                    msgBuffer.append(EOL);
                } else if (msgLine.endsWith("^")) {
                    pointer = msgLine;
                } else {
                    msgBuffer.append(context);

                    msgBuffer.append(EOL);

                    context = msgLine;
                }
            } while (tokens.hasMoreTokens());

            msgBuffer.append(EOL);

            final String message = msgBuffer.toString();

            final int startcolumn = pointer.indexOf("^");

            int endcolumn = (context == null) ? startcolumn : context.indexOf(" ", startcolumn);

            if (endcolumn == -1) {
                endcolumn = context.length();
            }

            return new CompilerMessage(file.toString(), isError, line, startcolumn, line, endcolumn, message.trim());
        } catch (NoSuchElementException e) {
            return new CompilerMessage("no more tokens - could not parse error message: " + error, isError);
        } catch (Exception e) {
            return new CompilerMessage("could not parse error message: " + error, isError);
        }
    }

    private static String getWarnPrefix(String msg) {
        for (String warningPrefix : WARNING_PREFIXES) {
            if (msg.startsWith(warningPrefix)) {
                return warningPrefix;
            }
        }
        return null;
    }

    /**
     * put args into a temp file to be referenced using the @ option in javac command line
     *
     * @param args
     * @return the temporary file wth the arguments
     * @throws IOException
     */
    private File createFileWithArguments(String[] args, String outputDirectory) throws IOException {
        PrintWriter writer = null;
        try {
            File tempFile;
            if (getLog().isDebugEnabled()) {
                tempFile = File.createTempFile(JavacCompiler.class.getName(), "arguments", new File(outputDirectory));
            } else {
                tempFile = File.createTempFile(JavacCompiler.class.getName(), "arguments");
                tempFile.deleteOnExit();
            }

            writer = new PrintWriter(new FileWriter(tempFile));

            for (String arg : args) {
                String argValue = arg.replace(File.separatorChar, '/');

                writer.write("\"" + argValue + "\"");

                writer.println();
            }

            writer.flush();

            return tempFile;

        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Get the path of the javac tool executable to use.
     * Either given through explicit configuration or via {@link #getJavacExecutable()}.
     * @param config the configuration
     * @return the path of the javac tool
     */
    protected String getJavacExecutable(CompilerConfiguration config) {
        String executable = config.getExecutable();

        if (StringUtils.isEmpty(executable)) {
            try {
                executable = getJavacExecutable();
            } catch (IOException e) {
                if (getLog().isWarnEnabled()) {
                    getLog().warn("Unable to autodetect 'javac' path, using 'javac' from the environment.");
                }
                executable = "javac";
            }
        }
        return executable;
    }

    /**
     * Get the path of the javac tool executable: try to find it depending the OS or the <code>java.home</code>
     * system property or the <code>JAVA_HOME</code> environment variable.
     *
     * @return the path of the javac tool
     * @throws IOException if not found
     */
    private static String getJavacExecutable() throws IOException {
        String javacCommand = "javac" + (Os.isFamily(Os.FAMILY_WINDOWS) ? ".exe" : "");

        String javaHome = System.getProperty("java.home");
        File javacExe;
        if (Os.isName("AIX")) {
            javacExe = new File(javaHome + File.separator + ".." + File.separator + "sh", javacCommand);
        } else if (Os.isName("Mac OS X")) {
            javacExe = new File(javaHome + File.separator + "bin", javacCommand);
        } else {
            javacExe = new File(javaHome + File.separator + ".." + File.separator + "bin", javacCommand);
        }

        // ----------------------------------------------------------------------
        // Try to find javacExe from JAVA_HOME environment variable
        // ----------------------------------------------------------------------
        if (!javacExe.isFile()) {
            Properties env = CommandLineUtils.getSystemEnvVars();
            javaHome = env.getProperty("JAVA_HOME");
            if (StringUtils.isEmpty(javaHome)) {
                throw new IOException("The environment variable JAVA_HOME is not correctly set.");
            }
            if (!new File(javaHome).isDirectory()) {
                throw new IOException("The environment variable JAVA_HOME=" + javaHome
                        + " doesn't exist or is not a valid directory.");
            }

            javacExe = new File(env.getProperty("JAVA_HOME") + File.separator + "bin", javacCommand);
        }

        if (!javacExe.isFile()) {
            throw new IOException("The javadoc executable '" + javacExe
                    + "' doesn't exist or is not a file. Verify the JAVA_HOME environment variable.");
        }

        return javacExe.getAbsolutePath();
    }

    private void releaseJavaccClass(Class<?> javaccClass, CompilerConfiguration compilerConfiguration) {
        if (compilerConfiguration.getCompilerReuseStrategy()
                == CompilerConfiguration.CompilerReuseStrategy.ReuseCreated) {
            javacClasses.add(javaccClass);
        }
    }

    /**
     * Find the main class of JavaC. Return the same class for subsequent calls.
     *
     * @return the non-null class.
     * @throws CompilerException if the class has not been found.
     */
    private Class<?> getJavacClass(CompilerConfiguration compilerConfiguration) throws CompilerException {
        Class<?> c;
        switch (compilerConfiguration.getCompilerReuseStrategy()) {
            case AlwaysNew:
                return createJavacClass();
            case ReuseCreated:
                c = javacClasses.poll();
                if (c == null) {
                    c = createJavacClass();
                }
                return c;
            case ReuseSame:
            default:
                c = javacClass;
                if (c == null) {
                    synchronized (this) {
                        c = javacClass;
                        if (c == null) {
                            javacClass = c = createJavacClass();
                        }
                    }
                }
                return c;
        }
    }

    /**
     * Helper method for create Javac class
     */
    protected Class<?> createJavacClass() throws CompilerException {
        try {
            // look whether JavaC is on Maven's classpath
            // return Class.forName( JavacCompiler.JAVAC_CLASSNAME, true, JavacCompiler.class.getClassLoader() );
            return JavacCompiler.class.getClassLoader().loadClass(JavacCompiler.JAVAC_CLASSNAME);
        } catch (ClassNotFoundException ex) {
            // ok
        }

        final File toolsJar = new File(System.getProperty("java.home"), "../lib/tools.jar");
        if (!toolsJar.exists()) {
            throw new CompilerException("tools.jar not found: " + toolsJar);
        }

        try {
            // Combined classloader with no parent/child relationship, so classes in our classloader
            // can reference classes in tools.jar
            URL[] originalUrls = ((URLClassLoader) JavacCompiler.class.getClassLoader()).getURLs();
            URL[] urls = new URL[originalUrls.length + 1];
            urls[0] = toolsJar.toURI().toURL();
            System.arraycopy(originalUrls, 0, urls, 1, originalUrls.length);
            ClassLoader javacClassLoader = new URLClassLoader(urls);

            final Thread thread = Thread.currentThread();
            final ClassLoader contextClassLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(javacClassLoader);
            try {
                // return Class.forName( JavacCompiler.JAVAC_CLASSNAME, true, javacClassLoader );
                return javacClassLoader.loadClass(JavacCompiler.JAVAC_CLASSNAME);
            } finally {
                thread.setContextClassLoader(contextClassLoader);
            }
        } catch (MalformedURLException ex) {
            throw new CompilerException(
                    "Could not convert the file reference to tools.jar to a URL, path to tools.jar: '"
                            + toolsJar.getAbsolutePath() + "'.",
                    ex);
        } catch (ClassNotFoundException ex) {
            throw new CompilerException(
                    "Unable to locate the Javac Compiler in:" + EOL + "  " + toolsJar + EOL
                            + "Please ensure you are using JDK 1.4 or above and" + EOL
                            + "not a JRE (the com.sun.tools.javac.Main class is required)." + EOL
                            + "In most cases you can change the location of your Java" + EOL
                            + "installation by setting the JAVA_HOME environment variable.",
                    ex);
        }
    }
}
