/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.JavaPaths.SRC_ZIP;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.CopyStep.DirectoryMode;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class KotlincToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {

  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraKotlincArguments;

  @AddToRuleKey
  private final ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins;

  @AddToRuleKey private final ImmutableList<SourcePath> friendPaths;
  @AddToRuleKey private final AnnotationProcessingTool annotationProcessingTool;
  @AddToRuleKey private final Optional<String> jvmTarget;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;
  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final boolean withDownwardApi;
  @AddToRuleKey private final boolean abiGenerationEnabled;

  private static final String PLUGIN = "-P";
  private static final String APT_MODE = "aptMode=";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String KAPT3_PLUGIN = "plugin:org.jetbrains.kotlin.kapt3:";
  private static final String AP_CLASSPATH_ARG = KAPT3_PLUGIN + "apclasspath=";
  private static final String AP_PROCESSORS_ARG = KAPT3_PLUGIN + "processors=";
  // output path for generated sources;
  private static final String SOURCES_ARG = KAPT3_PLUGIN + "sources=";
  private static final String CLASSES_ARG = KAPT3_PLUGIN + "classes=";
  // output path for java stubs;
  private static final String STUBS_ARG = KAPT3_PLUGIN + "stubs=";
  private static final String LIGHT_ANALYSIS = KAPT3_PLUGIN + "useLightAnalysis=";
  private static final String CORRECT_ERROR_TYPES = KAPT3_PLUGIN + "correctErrorTypes=";
  private static final String JAVAC_ARG = KAPT3_PLUGIN + "javacArguments=";
  private static final String AP_OPTIONS = KAPT3_PLUGIN + "apoptions=";
  private static final String KAPT_GENERATED = "kapt.kotlin.generated";
  private static final String MODULE_NAME = "-module-name";
  private static final String NO_STDLIB = "-no-stdlib";
  private static final String NO_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private static final PathMatcher KOTLIN_PATH_MATCHER = FileExtensionMatcher.of("kt");
  private static final PathMatcher SRC_ZIP_MATCHER = GlobPatternMatcher.of("**.src.zip");

  KotlincToJarStepFactory(
      Kotlinc kotlinc,
      boolean abiGenerationEnabled,
      ImmutableList<String> extraKotlincArguments,
      ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins,
      ImmutableList<SourcePath> friendPaths,
      AnnotationProcessingTool annotationProcessingTool,
      Optional<String> jvmTarget,
      ExtraClasspathProvider extraClassPath,
      Javac javac,
      JavacOptions javacOptions,
      boolean withDownwardApi) {
    this.kotlinc = kotlinc;
    this.abiGenerationEnabled = abiGenerationEnabled;
    this.extraKotlincArguments = extraKotlincArguments;
    this.kotlinCompilerPlugins = kotlinCompilerPlugins;
    this.friendPaths = friendPaths;
    this.annotationProcessingTool = annotationProcessingTool;
    this.jvmTarget = jvmTarget;
    this.extraClasspathProvider = extraClassPath;
    this.javac = javac;
    this.javacOptions = Objects.requireNonNull(javacOptions);
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public void createCompileStep(
      BuildContext buildContext,
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {

    ImmutableSortedSet<Path> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList();

    boolean generatingCode = !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
    boolean hasKotlinSources =
        sourceFilePaths.stream().anyMatch(KOTLIN_PATH_MATCHER::matches)
            || sourceFilePaths.stream().anyMatch(SRC_ZIP_MATCHER::matches);

    ImmutableSortedSet.Builder<Path> sourceBuilder =
        ImmutableSortedSet.<Path>naturalOrder().addAll(sourceFilePaths);

    // Only invoke kotlinc if we have kotlin or src zip files.
    if (hasKotlinSources) {
      RelPath stubsOutput =
          BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_stubs__");
      RelPath sourcesOutput =
          BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_sources__");
      RelPath classesOutput =
          BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_classes__");
      RelPath kaptGeneratedOutput =
          BuildTargetPaths.getAnnotationPath(
              projectFilesystem, invokingRule, "__%s_kapt_generated__");
      RelPath kotlincPluginGeneratedOutput =
          BuildTargetPaths.getAnnotationPath(
              projectFilesystem, invokingRule, "__%s_kotlinc_plugin_generated__");
      RelPath annotationGenFolder = getKaptAnnotationGenPath(projectFilesystem, invokingRule);
      RelPath genOutputFolder =
          BuildTargetPaths.getGenPath(projectFilesystem, invokingRule, "__%s_gen_sources__");
      RelPath genOutput =
          BuildTargetPaths.getGenPath(
              projectFilesystem, invokingRule, "__%s_gen_sources__/generated" + SRC_ZIP);

      // Javac requires that the root directory for generated sources already exist.
      addCreateFolderStep(steps, projectFilesystem, buildContext, stubsOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, classesOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, kaptGeneratedOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, kotlincPluginGeneratedOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, sourcesOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, annotationGenFolder);
      addCreateFolderStep(steps, projectFilesystem, buildContext, genOutputFolder);

      ImmutableSortedSet<Path> allClasspaths =
          ImmutableSortedSet.<Path>naturalOrder()
              .addAll(
                  RichStream.from(extraClasspathProvider.getExtraClasspath())
                      .map(AbsPath::getPath)
                      .iterator())
              .addAll(declaredClasspathEntries)
              .addAll(kotlinc.getHomeLibraries(buildContext.getSourcePathResolver()))
              .build();

      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();
      String friendPathsArg = getFriendsPath(resolver, friendPaths);
      String moduleName = getModuleName(invokingRule);

      ImmutableList.Builder<String> annotationProcessingOptionsBuilder = ImmutableList.builder();
      Builder<Step> postKotlinCompilationSteps = ImmutableList.builder();

      if (generatingCode && annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
        ImmutableList<String> annotationProcessors =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(ResolvedJavacPluginProperties::getProcessorNames)
                    .flatMap(Set::stream)
                    .map(name -> AP_PROCESSORS_ARG + name)
                    .collect(Collectors.toList()));

        ImmutableList<String> apClassPaths =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(
                        resolvedJavacPluginProperties ->
                            resolvedJavacPluginProperties.getJavacPluginJsr199Fields(
                                buildContext.getSourcePathResolver()))
                    .map(JavacPluginJsr199Fields::getClasspath)
                    .flatMap(List::stream)
                    .map(url -> AP_CLASSPATH_ARG + urlToFile(url))
                    .collect(Collectors.toList()));

        ImmutableMap.Builder<String, String> apOptions = new ImmutableMap.Builder<>();
        ImmutableSortedSet<String> javacAnnotationProcessorParams =
            javacOptions.getJavaAnnotationProcessorParams().getParameters();
        for (String param : javacAnnotationProcessorParams) {
          String[] splitParam = param.split("=");
          Preconditions.checkState(splitParam.length == 2);
          apOptions.put(splitParam[0], splitParam[1]);
        }

        ImmutableList<String> kaptPluginOptions =
            ImmutableList.<String>builder()
                .add(
                    AP_CLASSPATH_ARG
                        + kotlinc.getAnnotationProcessorPath(buildContext.getSourcePathResolver()))
                .add(AP_CLASSPATH_ARG + kotlinc.getStdlibPath(buildContext.getSourcePathResolver()))
                .addAll(apClassPaths)
                .addAll(annotationProcessors)
                .add(SOURCES_ARG + projectFilesystem.resolve(sourcesOutput))
                .add(CLASSES_ARG + projectFilesystem.resolve(classesOutput))
                .add(STUBS_ARG + projectFilesystem.resolve(stubsOutput))
                .add(
                    AP_OPTIONS
                        + encodeKaptApOptions(
                            apOptions.build(),
                            projectFilesystem.resolve(kaptGeneratedOutput).toString()))
                .add(JAVAC_ARG + encodeOptions(Collections.emptyMap()))
                .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
                .add(CORRECT_ERROR_TYPES + "true")
                .build();

        annotationProcessingOptionsBuilder
            .add(
                X_PLUGIN_ARG
                    + kotlinc.getAnnotationProcessorPath(buildContext.getSourcePathResolver()))
            .add(PLUGIN)
            .add(KAPT3_PLUGIN + APT_MODE + "compile," + Joiner.on(",").join(kaptPluginOptions));

        postKotlinCompilationSteps.add(
            CopyStep.forDirectory(
                projectFilesystem,
                sourcesOutput,
                annotationGenFolder,
                DirectoryMode.CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyStep.forDirectory(
                projectFilesystem,
                classesOutput,
                annotationGenFolder,
                DirectoryMode.CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyStep.forDirectory(
                projectFilesystem,
                kaptGeneratedOutput,
                annotationGenFolder,
                DirectoryMode.CONTENTS_ONLY));

        postKotlinCompilationSteps.add(
            new ZipStep(
                projectFilesystem,
                genOutput.getPath(),
                ImmutableSet.of(),
                false,
                ZipCompressionLevel.DEFAULT,
                annotationGenFolder.getPath()));

        // Generated classes should be part of the output. This way generated files
        // such as META-INF dirs will also be added to the final jar.
        postKotlinCompilationSteps.add(
            CopyStep.forDirectory(
                projectFilesystem,
                classesOutput.getPath(),
                outputDirectory.getPath(),
                DirectoryMode.CONTENTS_ONLY));

        sourceBuilder.add(genOutput.getPath());
      }

      ImmutableList.Builder<String> extraArguments =
          ImmutableList.<String>builder()
              .addAll(extraKotlincArguments)
              .add(friendPathsArg)
              .addAll(
                  getKotlinCompilerPluginsArgs(
                      resolver, projectFilesystem.resolve(kotlincPluginGeneratedOutput).toString()))
              .addAll(annotationProcessingOptionsBuilder.build())
              .add(MODULE_NAME)
              .add(moduleName)
              .add(NO_STDLIB)
              .add(NO_REFLECT);

      jvmTarget.ifPresent(
          target -> {
            extraArguments.add("-jvm-target");
            extraArguments.add(target);
          });

      AbsPath tmpSourceAbiFolder;
      if (abiGenerationEnabled || kotlinc.getAbiGenerationPlugin(resolver) != null) {
        tmpSourceAbiFolder =
            projectFilesystem.resolve(
                JavaAbis.getTmpGenPathForSourceAbi(projectFilesystem, invokingRule));
        extraArguments.add("-Xplugin=" + kotlinc.getAbiGenerationPlugin(resolver));

        extraArguments.add(
            "-P", "plugin:org.jetbrains.kotlin.jvm.abi:outputDir=" + tmpSourceAbiFolder);
      }

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory.getPath(),
              sourceFilePaths,
              pathToSrcsList,
              allClasspaths,
              kotlinc,
              kotlinc.getHomeLibraries(buildContext.getSourcePathResolver()),
              extraArguments.build(),
              ImmutableList.of(VERBOSE),
              projectFilesystem,
              Optional.of(parameters.getOutputPaths().getWorkingDirectory()),
              withDownwardApi));

      steps.addAll(postKotlinCompilationSteps.build());
    }

    final JavacOptions finalJavacOptions;

    switch (annotationProcessingTool) {
      case KAPT:
        // If kapt was never invoked then do annotation processing with javac.
        finalJavacOptions =
            hasKotlinSources
                ? javacOptions.withJavaAnnotationProcessorParams(JavacPluginParams.EMPTY)
                : javacOptions;
        break;

      case JAVAC:
        finalJavacOptions = javacOptions;
        break;

      default:
        throw new IllegalStateException(
            "Unexpected annotationProcessingTool " + annotationProcessingTool);
    }

    // Note that this filters out only .kt files, so this keeps both .java and .src.zip files.
    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sourceBuilder.build().stream()
                .filter(input -> !KOTLIN_PATH_MATCHER.matches(input))
                .collect(Collectors.toSet()));

    CompilerParameters javacParameters =
        CompilerParameters.builder()
            .from(parameters)
            .setClasspathEntries(
                ImmutableSortedSet.<Path>naturalOrder()
                    .add(projectFilesystem.resolve(outputDirectory).getPath())
                    .addAll(
                        RichStream.from(extraClasspathProvider.getExtraClasspath())
                            .map(AbsPath::getPath)
                            .iterator())
                    .addAll(declaredClasspathEntries)
                    .build())
            .setSourceFilePaths(javaSourceFiles)
            .build();

    new JavacToJarStepFactory(javac, finalJavacOptions, extraClasspathProvider, withDownwardApi)
        .createCompileStep(
            buildContext,
            projectFilesystem,
            invokingRule,
            javacParameters,
            steps,
            buildableContext);
  }

  /**
   * Safely converts a URL to a File path. Use this instead of {@link URL#getFile} to ensure that
   * htmlencoded literals are not present in the file path.
   */
  private static String urlToFile(URL url) {
    try {
      return Paths.get(url.toURI()).toFile().getPath();
    } catch (URISyntaxException e) {
      // In case of error, fall back to the original implementation.
      return url.getFile();
    }
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    return javacOptions.withBootclasspathFromContext(extraClasspathProvider).getBootclasspath();
  }

  private String encodeKaptApOptions(Map<String, String> kaptApOptions, String kaptGeneratedPath) {
    Map<String, String> kaptApOptionsToEncode = new HashMap<>();
    kaptApOptionsToEncode.put(KAPT_GENERATED, kaptGeneratedPath);
    kaptApOptionsToEncode.putAll(kaptApOptions);

    return encodeOptions(kaptApOptionsToEncode);
  }

  private void addCreateFolderStep(
      ImmutableList.Builder<Step> steps,
      ProjectFilesystem filesystem,
      BuildContext buildContext,
      RelPath location) {
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, location)));
  }

  private String encodeOptions(Map<String, String> options) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os)) {

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String getFriendsPath(
      SourcePathResolverAdapter sourcePathResolverAdapter,
      ImmutableList<SourcePath> friendPathsSourcePaths) {
    if (friendPathsSourcePaths.isEmpty()) {
      return "";
    }

    // https://youtrack.jetbrains.com/issue/KT-29933
    ImmutableSortedSet<String> absoluteFriendPaths =
        friendPathsSourcePaths.stream()
            .map(path -> sourcePathResolverAdapter.getAbsolutePath(path).toString())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    return "-Xfriend-paths="
        + absoluteFriendPaths.stream().reduce("", (path1, path2) -> path1 + "," + path2);
  }

  private ImmutableList<String> getKotlinCompilerPluginsArgs(
      SourcePathResolverAdapter sourcePathResolverAdapter, String outputDir) {
    ImmutableList.Builder<String> pluginArgs = ImmutableList.builder();
    for (SourcePath pluginPath : kotlinCompilerPlugins.keySet()) {
      // Add plugin basic string, e.g. "-Xplugins=<pluginPath>"
      pluginArgs.add(getKotlincPluginBasicString(sourcePathResolverAdapter, pluginPath));

      // If plugin options exist, add plugin option string,
      // e.g. "-P" and "<optionKey>=<optionValue>,<optionKey2>=<optionValue2>,..."
      ImmutableMap<String, String> pluginOptions = kotlinCompilerPlugins.get(pluginPath);
      if (pluginOptions != null && !pluginOptions.isEmpty()) {
        ImmutableList.Builder<String> pluginOptionStrings = ImmutableList.builder();
        for (String pluginOptionKey : pluginOptions.keySet()) {
          String pluginOptionValue = pluginOptions.get(pluginOptionKey);

          // When value is "_codegen_dir_", it means it's asking buck to provide kotlin compiler
          // plugin output dir
          if (pluginOptionValue.equals("__codegen_dir__")) {
            pluginOptionValue = outputDir;
          }

          pluginOptionStrings.add(pluginOptionKey + "=" + pluginOptionValue);
        }
        String pluginOptionString = Joiner.on(",").join(pluginOptionStrings.build());
        pluginArgs.add(PLUGIN).add(pluginOptionString);
      }
    }
    return pluginArgs.build();
  }

  /**
   * Ideally, we would not use getAbsolutePath() here, but getRelativePath() does not appear to work
   * correctly if path is a BuildTargetSourcePath in a different cell than the kotlin_library() rule
   * being defined.
   */
  private String getKotlincPluginBasicString(
      SourcePathResolverAdapter sourcePathResolverAdapter, SourcePath path) {
    return X_PLUGIN_ARG + sourcePathResolverAdapter.getAbsolutePath(path).toString();
  }

  private static String getModuleName(BuildTarget invokingRule) {
    return invokingRule.getCellRelativeBasePath().getPath().toString().replace('/', '.')
        + "."
        + invokingRule.getShortName();
  }

  @Override
  public boolean hasAnnotationProcessing() {
    return !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
  }

  public static RelPath getKaptAnnotationGenPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildPaths.getGenDir(projectFilesystem, buildTarget).resolveRel("__generated__");
  }
}
