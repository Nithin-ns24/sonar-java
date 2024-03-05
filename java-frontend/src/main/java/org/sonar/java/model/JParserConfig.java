/*
 * SonarQube Java
 * Copyright (C) 2012-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.StreamSupport;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.java.AnalysisProgress;
import org.sonar.java.ExecutionTimeReport;
import org.sonar.java.ProgressMonitor;
import org.sonar.java.SonarComponents;
import org.sonar.java.annotations.VisibleForTesting;
import org.sonar.plugins.java.api.JavaVersion;
import org.sonarsource.analyzer.commons.ProgressReport;
import org.sonarsource.performance.measure.PerformanceMeasure;

public abstract class JParserConfig {

  public static final JavaVersion MAXIMUM_SUPPORTED_JAVA_VERSION = new JavaVersionImpl(JavaVersionImpl.MAX_SUPPORTED, true);

  private static final Logger LOG = LoggerFactory.getLogger(JParserConfig.class);

  private static final String MAXIMUM_ECJ_WARNINGS = "42000";
  private static final Set<String> JRE_JARS = new HashSet<>(Arrays.asList("rt.jar", "jrt-fs.jar", "android.jar"));

  final JavaVersion javaVersion;
  final List<File> classpath;
  final boolean shouldIgnoreUnnamedModuleForSplitPackage;

  private JParserConfig(JavaVersion javaVersion, List<File> classpath, boolean shouldIgnoreUnnamedModuleForSplitPackage) {
    this.javaVersion = javaVersion;
    this.classpath = classpath;
    this.shouldIgnoreUnnamedModuleForSplitPackage = shouldIgnoreUnnamedModuleForSplitPackage;
  }

  public abstract void parse(Iterable<? extends InputFile> inputFiles, BooleanSupplier isCanceled,
    AnalysisProgress analysisProgress, BiConsumer<InputFile, Result> action);

  public enum Mode {
    BATCH(Batch::new),
    FILE_BY_FILE(FileByFile::new);

    private final ParserConfigConstructor supplier;

    Mode(ParserConfigConstructor supplier) {
      this.supplier = supplier;
    }

    public JParserConfig create(JavaVersion javaVersion, List<File> classpath) {
      return create(javaVersion, classpath, false);
    }

    public JParserConfig create(JavaVersion javaVersion, List<File> classpath, boolean shouldIgnoreUnnamedModuleForSplitPackage) {
      if (shouldIgnoreUnnamedModuleForSplitPackage) {
        LOG.info("The Java analyzer will ignore the unnamed module for split packages.");
      }
      return supplier.apply(javaVersion, classpath, shouldIgnoreUnnamedModuleForSplitPackage);
    }
  }

  public static class Result {
    private final Exception e;
    private final JavaTree.CompilationUnitTreeImpl t;

    private Result(Exception e) {
      this.e = e;
      this.t = null;
    }

    private Result(JavaTree.CompilationUnitTreeImpl t) {
      this.e = null;
      this.t = t;
    }

    public JavaTree.CompilationUnitTreeImpl get() throws Exception {
      if (e != null) {
        throw e;
      }
      return t;
    }
  }

  public ASTParser astParser() {
    ASTParser astParser = ASTParser.newParser(AST.getJLSLatest());
    Map<String, String> options = new HashMap<>();
    options.put(JavaCore.COMPILER_COMPLIANCE, javaVersion.effectiveJavaVersionAsString());
    options.put(JavaCore.COMPILER_SOURCE, javaVersion.effectiveJavaVersionAsString());
    options.put(JavaCore.COMPILER_PB_MAX_PER_UNIT, MAXIMUM_ECJ_WARNINGS);
    if (shouldIgnoreUnnamedModuleForSplitPackage) {
      options.put(JavaCore.COMPILER_IGNORE_UNNAMED_MODULE_FOR_SPLIT_PACKAGE, "enabled");
    }
    if (shouldEnablePreviewFlag(javaVersion)) {
      options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, "enabled");
    }
    // enabling all supported compiler warnings
    JProblem.Type.compilerOptions()
      .forEach(option -> options.put(option, "warning"));

    astParser.setCompilerOptions(options);

    boolean includeRunningVMBootclasspath = classpath.stream()
      .noneMatch(f -> JRE_JARS.contains(f.getName()));

    astParser.setEnvironment(classpath.stream()
      .map(File::getAbsolutePath)
      .toArray(String[]::new), new String[] {}, new String[] {}, includeRunningVMBootclasspath);

    astParser.setResolveBindings(true);
    astParser.setBindingsRecovery(true);

    return astParser;
  }

  @VisibleForTesting
  static class Batch extends JParserConfig {

    Batch(JavaVersion javaVersion, List<File> classpath, boolean shouldIgnoreUnnamedModuleForSplitPackage) {
      super(javaVersion, classpath, shouldIgnoreUnnamedModuleForSplitPackage);
    }

    private static void appendFileToAnalyzed(String file) {
      var path = SonarComponents.debugLocation.resolve("analyzed-" + SonarComponents.subCounter + ".log");

      try {
        Files.writeString(path, file + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException e) {
        LOG.error("Failed to write analyzed file to {}", path, e);
      }
    }

    private static void appendFileToProvided(String file) {
      var path = SonarComponents.debugLocation.resolve("provided-" + SonarComponents.subCounter + ".log");

      try {
        Files.writeString(path, file + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException e) {
        LOG.error("Failed to write analyzed file to {}", path, e);
      }
    }

    @Override
    public void parse(Iterable<? extends InputFile> inputFiles, BooleanSupplier isCanceled,
      AnalysisProgress analysisProgress, BiConsumer<InputFile, Result> action) {

      for (InputFile inputFile : inputFiles) {
        appendFileToProvided(inputFile.absolutePath());
      }

      List<String> sourceFilePaths = new ArrayList<>();
      Set<String> analyzedSourceFilePaths = new HashSet<>();
      List<String> encodings = new ArrayList<>();
      Map<File, InputFile> inputs = new HashMap<>();
      for (InputFile inputFile : inputFiles) {
        String sourceFilePath = inputFile.absolutePath();
        inputs.put(new File(sourceFilePath), inputFile);
        sourceFilePaths.add(sourceFilePath);
        encodings.add(inputFile.charset().name());
      }

      ExecutionTimeReport executionTimeReport = new ExecutionTimeReport();
      ProgressMonitor monitor = new ProgressMonitor(isCanceled, analysisProgress);
      PerformanceMeasure.Duration batchPerformance = PerformanceMeasure.start("ParseAsBatch");
      try {
        astParser().createASTs(sourceFilePaths.toArray(new String[0]), encodings.toArray(new String[0]), new String[0], new FileASTRequestor() {
          @Override
          public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            PerformanceMeasure.Duration convertDuration = PerformanceMeasure.start("Convert");
            analyzedSourceFilePaths.add(sourceFilePath);

            appendFileToAnalyzed(sourceFilePath);

            InputFile inputFile = inputs.get(new File(sourceFilePath));
            executionTimeReport.start(inputFile);
            Result result;
            try {
              result = new Result(JParser.convert(javaVersion.effectiveJavaVersionAsString(), inputFile.filename(), inputFile.contents(), ast));
            } catch (Exception e) {
              result = new Result(e);
            }
            convertDuration.stop();
            PerformanceMeasure.Duration analyzeDuration = PerformanceMeasure.start("Analyze");
            action.accept(inputFile, result);

            executionTimeReport.end();
            analyzeDuration.stop();
          }
        }, monitor);
      } catch (OperationCanceledException e) {
        throw e;
      } catch (RuntimeException e) {
        List<InputFile> notYetAnalyzedFiles = sourceFilePaths.stream()
          .filter(file -> !analyzedSourceFilePaths.contains(file))
          .map(file -> inputs.get(new File(file)))
          .toList();

        if (!notYetAnalyzedFiles.isEmpty()) {
          action.accept(notYetAnalyzedFiles.get(0), new Result(e));
          fallbackToFileByFileMode(notYetAnalyzedFiles, isCanceled, action);
        } else if (!sourceFilePaths.isEmpty()) {
          InputFile lastInputFile = inputs.get(new File(sourceFilePaths.get(sourceFilePaths.size() - 1)));
          action.accept(lastInputFile, new Result(e));
        } else {
          LOG.warn("Unexpected {}: {}", e.getClass().getName(), e.getMessage());
        }
      } finally {
        if (sourceFilePaths.size() != analyzedSourceFilePaths.size()) {
          var notYetAnalyzedFiles = sourceFilePaths.stream()
            .filter(file -> !analyzedSourceFilePaths.contains(file))
            .toList();

          var path = SonarComponents.debugLocation.resolve("not-analyzed-" + SonarComponents.subCounter + ".log");

          try {
            Files.writeString(path, String.join(System.lineSeparator(), notYetAnalyzedFiles) + System.lineSeparator(),
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
          } catch (IOException e) {
            LOG.error("Failed to write not analyzed files to {}", path, e);
          }
        }

        batchPerformance.stop();
        // ExecutionTimeReport will not include the parsing time by file when using batch mode.
        executionTimeReport.reportAsBatch();
        monitor.done();
      }
    }

    private void fallbackToFileByFileMode(List<InputFile> inputFiles, BooleanSupplier isCanceled, BiConsumer<InputFile, Result> action) {
      LOG.warn("Fallback to file by file analysis for {} files", inputFiles.size());
      for (InputFile inputFile : inputFiles) {
        if (isCanceled.getAsBoolean()) {
          break;
        }
        FileByFile.parse(astParser(), inputFile, javaVersion, action);
      }
    }

  }

  private static class FileByFile extends JParserConfig {

    private FileByFile(JavaVersion javaVersion, List<File> classpath, boolean shouldIgnoreUnnamedModuleForSplitPackage) {
      super(javaVersion, classpath, shouldIgnoreUnnamedModuleForSplitPackage);
    }

    @Override
    public void parse(Iterable<? extends InputFile> inputFiles, BooleanSupplier isCanceled,
      AnalysisProgress analysisProgress, BiConsumer<InputFile, Result> action) {
      boolean successfullyCompleted = false;
      boolean cancelled = false;

      ExecutionTimeReport executionTimeReport = new ExecutionTimeReport();
      ProgressReport progressReport = new ProgressReport("Report about progress of Java AST analyzer", TimeUnit.SECONDS.toMillis(10));
      List<String> filesNames = StreamSupport.stream(inputFiles.spliterator(), false)
        .map(InputFile::toString)
        .toList();
      progressReport.start(filesNames);
      try {
        for (InputFile inputFile : inputFiles) {
          if (isCanceled.getAsBoolean()) {
            cancelled = true;
            break;
          }
          executionTimeReport.start(inputFile);
          parse(astParser(), inputFile, javaVersion, action);
          executionTimeReport.end();
          progressReport.nextFile();
        }
        successfullyCompleted = !cancelled;
      } finally {
        if (successfullyCompleted) {
          progressReport.stop();
        } else {
          progressReport.cancel();
        }
        executionTimeReport.report();
      }
    }

    private static void parse(ASTParser astParser, InputFile inputFile, JavaVersion javaVersion, BiConsumer<InputFile, Result> action) {
      Result result;
      PerformanceMeasure.Duration parseDuration = PerformanceMeasure.start("JParser");
      try {
        result = new Result(JParser.parse(astParser, javaVersion.effectiveJavaVersionAsString(), inputFile.filename(), inputFile.contents()));
      } catch (Exception e) {
        result = new Result(e);
      } finally {
        parseDuration.stop();
      }
      action.accept(inputFile, result);
    }
  }

  @VisibleForTesting
  static boolean shouldEnablePreviewFlag(JavaVersion currentVersion) {
    return currentVersion.arePreviewFeaturesEnabled();
  }
  @FunctionalInterface
  public interface ParserConfigConstructor {
    JParserConfig apply(JavaVersion version, List<File> files, Boolean shouldIgnoreUnnamedModuleForSplitPackage);
  }

}
