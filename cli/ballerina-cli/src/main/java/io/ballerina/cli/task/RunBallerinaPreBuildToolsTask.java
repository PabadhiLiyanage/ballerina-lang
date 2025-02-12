/*
 * Copyright (c) 2023, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.cli.task;

import io.ballerina.cli.tool.CodeGeneratorTool;
import io.ballerina.cli.utils.FileUtils;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.ToolContext;
import io.ballerina.toml.api.Toml;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;
import static io.ballerina.projects.PackageManifest.Tool;

/**
 * Task for running tools integrated with the build.
 *
 * @since 2201.9.0
 */
public class RunBallerinaPreBuildToolsTask implements Task {

    private final PrintStream outStream;

    public RunBallerinaPreBuildToolsTask(PrintStream out) {
        this.outStream = out;
    }

    @Override
    public void execute(Project project) {
        Collection<Diagnostic> toolDiagnostics = project.currentPackage().manifest().diagnostics().diagnostics();
        boolean hasTomlErrors = project.currentPackage().manifest().diagnostics().hasErrors();
        if (hasTomlErrors) {
            toolDiagnostics.forEach(outStream::println);
            throw createLauncherException("compilation contains errors");
        }
        List<Tool> tools = project.currentPackage().manifest().tools();
        ServiceLoader<CodeGeneratorTool> buildRunners = ServiceLoader.load(CodeGeneratorTool.class);
        for (Tool tool : tools) {
            try {
                String commandName = tool.getType();
                CodeGeneratorTool targetTool = getTargetTool(commandName, buildRunners);
                if (targetTool == null) {
                    // TODO: Install tool if not found
                    outStream.println("Command not found: " + commandName);
                    return;
                }
                try {
                    validateOptionsToml(tool.getOptionsToml(), commandName);
                } catch (IOException e) {
                    outStream.println("WARNING: Skipping the validation of tool options due to: " +
                        e.getMessage());
                }
                ToolContext toolContext = ToolContext.from(tool, project.currentPackage());
                targetTool.execute(toolContext);
                toolContext.diagnostics().forEach(outStream::println);
                for (Diagnostic d : toolContext.diagnostics()) {
                    if (d.diagnosticInfo().severity().equals(DiagnosticSeverity.ERROR)) {
                        throw new ProjectException("compilation contains errors");
                    }
                }
            } catch (ProjectException e) {
                throw createLauncherException(e.getMessage());
            }
        }
    }

    private CodeGeneratorTool getTargetTool(String commandName, ServiceLoader<CodeGeneratorTool> buildRunners) {
        for (CodeGeneratorTool buildRunner : buildRunners) {
            if (buildRunner.toolName().equals(commandName)) {
                return buildRunner;
            }
        }
        return null;
    }

    private void validateOptionsToml(Toml optionsToml, String toolName) throws IOException {
        if (optionsToml == null) {
            throw new IOException("No tool options found");
        }
        FileUtils.validateToml(optionsToml, toolName);
        optionsToml.diagnostics().forEach(outStream::println);
        for (Diagnostic d : optionsToml.diagnostics()) {
            if (d.diagnosticInfo().severity().equals(DiagnosticSeverity.ERROR)) {
                throw new ProjectException("compilation contains errors");
            }
        }
    }
}
