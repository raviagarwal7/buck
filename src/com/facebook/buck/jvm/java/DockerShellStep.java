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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public abstract class DockerShellStep extends ShellStep {
  private static final Logger LOG = Logger.get(DockerShellStep.class);

  protected DockerShellStep(Path workingDirectory) {
    super(workingDirectory);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws InterruptedException, IOException {
    ImmutableList<String> command = getShellCommand(context);
    if (command.isEmpty()) {
      return StepExecutionResults.SUCCESS;
    }

    Path currentDir = context.getBuildCellRootPath().resolve(workingDirectory);

    ImmutableList.Builder<String> dockerArgs = ImmutableList.builder();

    // Add generic docker options
    dockerArgs
        .add("docker")
        .add("run")
        .add("-i")
        .add("--rm")
        .add("--mount")
        .add("type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock");

    // Map TCP port 5005 on docker container to 5005 on host which is used for debugging.
    //    dockerArgs.add("-p")
    //      .add("5005:5005");

    // Add mounts and current dirs
    dockerArgs
        .add("--mount")
        .add(
            String.format(
                "type=bind,source=%s,target=%s,readonly",
                currentDir.toString(), currentDir.toString()))
        .add("--mount")
        .add(
            String.format(
                "type=bind,source=%s/buck-out,target=%s/buck-out",
                currentDir.toString(), currentDir.toString()))
        .add("-w")
        .add(currentDir.toString());

    // Get step environment variables
    Map<String, String> testEnvironment = getEnvironmentVariables(context);
    // Add test env variables
    testEnvironment.forEach(
        (k, v) -> {
          dockerArgs.add("--env");
          dockerArgs.add(String.format("%s=%s", k, v));
        });

    // Add docker image
    dockerArgs.add("openjdk:8");

    // Get Docker process environment
    Map<String, String> environment = new HashMap<>();
    setProcessEnvironment(context, environment, workingDirectory.toString());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Environment: %s", Joiner.on(" ").withKeyValueSeparator('=').join(environment));
    }

    // Docker Command
    ImmutableList<String> dockerCommand = dockerArgs.addAll(command).build();

    // Kick off a Process in which this ShellCommand will be run.
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setCommand(dockerCommand);
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    builder.setDirectory(context.getBuildCellRootPath().resolve(workingDirectory));

    Optional<String> stdin = getStdin(context);
    if (stdin.isPresent()) {
      builder.setRedirectInput(ProcessBuilder.Redirect.PIPE);
    }

    double initialLoad = OS_JMX.getSystemLoadAverage();
    startTime = System.currentTimeMillis();
    ProcessExecutor.Result result = launchAndInteractWithProcess(context, builder.build());
    int exitCode = getExitCodeFromResult(context, result);
    endTime = System.currentTimeMillis();
    double endLoad = OS_JMX.getSystemLoadAverage();
    if (LOG.isDebugEnabled()) {
      boolean hasOutput =
          (stdout.isPresent() && !stdout.get().isEmpty())
              || (stderr.isPresent() && !stderr.get().isEmpty());
      String outputFormat = hasOutput ? "\nstdout:\n%s\nstderr:\n%s\n" : " (no output)%s%s";
      LOG.debug(
          "%s: exit code: %d. os load (before, after): (%f, %f). CPU count: %d." + outputFormat,
          dockerCommand,
          exitCode,
          initialLoad,
          endLoad,
          OS_JMX.getAvailableProcessors(),
          stdout.orElse(""),
          stderr.orElse(""));
    }

    return StepExecutionResult.builder()
        .setExitCode(exitCode)
        .setExecutedCommand(result.getCommand())
        .setStderr(stderr)
        .build();
  }
}
