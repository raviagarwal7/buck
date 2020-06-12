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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.google.common.base.Preconditions;
import java.util.Optional;

/** Provides access to the various output paths for a java library. */
@BuckStyleValueWithBuilder
public abstract class CompilerOutputPaths {

  public abstract RelPath getClassesDir();

  public abstract RelPath getOutputJarDirPath();

  public abstract Optional<RelPath> getAbiJarPath();

  public abstract RelPath getAnnotationPath();

  public abstract RelPath getPathToSourcesList();

  public abstract RelPath getWorkingDirectory();

  public abstract Optional<RelPath> getOutputJarPath();

  /** Creates {@link CompilerOutputPaths} */
  public static CompilerOutputPaths of(BuildTarget target, ProjectFilesystem filesystem) {
    RelPath genRoot = BuildTargetPaths.getGenPath(filesystem, target, "lib__%s__output");
    RelPath scratchRoot = BuildTargetPaths.getScratchPath(filesystem, target, "lib__%s__scratch");

    return ImmutableCompilerOutputPaths.builder()
        .setClassesDir(scratchRoot.resolveRel("classes"))
        .setOutputJarDirPath(genRoot)
        .setAbiJarPath(
            hasAbiJar(target)
                ? Optional.of(
                    genRoot.resolveRel(String.format("%s-abi.jar", target.getShortName())))
                : Optional.empty())
        .setOutputJarPath(
            isLibraryJar(target)
                ? Optional.of(
                    genRoot.resolveRel(
                        String.format("%s.jar", target.getShortNameAndFlavorPostfix())))
                : Optional.empty())
        .setAnnotationPath(BuildTargetPaths.getAnnotationPath(filesystem, target, "__%s_gen__"))
        .setPathToSourcesList(BuildTargetPaths.getGenPath(filesystem, target, "__%s__srcs"))
        .setWorkingDirectory(
            BuildTargetPaths.getGenPath(filesystem, target, "lib__%s__working_directory"))
        .build();
  }

  /** Returns a path to a file that contains dependencies used in the compilation */
  public static RelPath getDepFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem)
        .getOutputJarDirPath()
        .resolveRel("used-classes.json");
  }

  public static RelPath getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getClassesDir();
  }

  public static RelPath getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getOutputJarDirPath();
  }

  public static Optional<RelPath> getAnnotationPath(
      ProjectFilesystem filesystem, BuildTarget target) {
    return Optional.of(CompilerOutputPaths.of(target, filesystem).getAnnotationPath());
  }

  public static RelPath getAbiJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    Preconditions.checkArgument(hasAbiJar(buildTarget));
    return CompilerOutputPaths.of(buildTarget, filesystem).getAbiJarPath().get();
  }

  public static RelPath getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getOutputJarPath().get();
  }

  private static boolean isLibraryJar(BuildTarget target) {
    return JavaAbis.isLibraryTarget(target);
  }

  private static boolean hasAbiJar(BuildTarget target) {
    return JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target);
  }
}
