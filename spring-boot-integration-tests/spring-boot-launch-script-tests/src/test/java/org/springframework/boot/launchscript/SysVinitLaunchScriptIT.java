/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.launchscript;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.assertj.core.api.Condition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.springframework.boot.ansi.AnsiColor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

/**
 * Integration tests for Spring Boot's launch script on OSs that use SysVinit.
 *
 * @author Andy Wilkinson
 */
@RunWith(Parameterized.class)
public class SysVinitLaunchScriptIT {

	private static final char ESC = 27;

	private final String os;

	private final String version;

	@Parameters(name = "{0} {1}")
	public static List<Object[]> parameters() {
		List<Object[]> parameters = new ArrayList<Object[]>();
		for (File os : new File("src/test/resources/conf").listFiles()) {
			for (File version : os.listFiles()) {
				parameters.add(new Object[] { os.getName(), version.getName() });
			}
		}
		return parameters;
	}

	public SysVinitLaunchScriptIT(String os, String version) {
		this.os = os;
		this.version = version;
	}

	@Test
	public void statusWhenStopped() throws Exception {
		String output = doTest("status-when-stopped.sh");
		assertThat(output).contains("Status: 3");
		assertThat(output).has(coloredString(AnsiColor.RED, "Not running"));
	}

	@Test
	public void statusWhenStarted() throws Exception {
		String output = doTest("status-when-started.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output).has(
				coloredString(AnsiColor.GREEN, "Started [" + extractPid(output) + "]"));
	}

	@Test
	public void statusWhenKilled() throws Exception {
		String output = doTest("status-when-killed.sh");
		assertThat(output).contains("Status: 1");
		assertThat(output).has(coloredString(AnsiColor.RED,
				"Not running (process " + extractPid(output) + " not found)"));
	}

	@Test
	public void stopWhenStopped() throws Exception {
		String output = doTest("stop-when-stopped.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output)
				.has(coloredString(AnsiColor.YELLOW, "Not running (pidfile not found)"));
	}

	@Test
	public void startWhenStarted() throws Exception {
		String output = doTest("start-when-started.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output).has(coloredString(AnsiColor.YELLOW,
				"Already running [" + extractPid(output) + "]"));
	}

	@Test
	public void restartWhenStopped() throws Exception {
		String output = doTest("restart-when-stopped.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output)
				.has(coloredString(AnsiColor.YELLOW, "Not running (pidfile not found)"));
		assertThat(output).has(
				coloredString(AnsiColor.GREEN, "Started [" + extractPid(output) + "]"));
	}

	@Test
	public void restartWhenStarted() throws Exception {
		String output = doTest("restart-when-started.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output).has(coloredString(AnsiColor.GREEN,
				"Started [" + extract("PID1", output) + "]"));
		assertThat(output).has(coloredString(AnsiColor.GREEN,
				"Stopped [" + extract("PID1", output) + "]"));
		assertThat(output).has(coloredString(AnsiColor.GREEN,
				"Started [" + extract("PID2", output) + "]"));
	}

	@Test
	public void startWhenStopped() throws Exception {
		String output = doTest("start-when-stopped.sh");
		assertThat(output).contains("Status: 0");
		assertThat(output).has(
				coloredString(AnsiColor.GREEN, "Started [" + extractPid(output) + "]"));
	}

	@Test
	public void basicLaunch() throws Exception {
		doLaunch("basic-launch.sh");
	}

	@Test
	public void launchWithSingleCommandLineArgument() throws Exception {
		doLaunch("launch-with-single-command-line-argument.sh");
	}

	@Test
	public void launchWithMultipleCommandLineArguments() throws Exception {
		doLaunch("launch-with-multiple-command-line-arguments.sh");
	}

	@Test
	public void launchWithSingleRunArg() throws Exception {
		doLaunch("launch-with-single-run-arg.sh");
	}

	@Test
	public void launchWithMultipleRunArgs() throws Exception {
		doLaunch("launch-with-multiple-run-args.sh");
	}

	@Test
	public void launchWithSingleJavaOpt() throws Exception {
		doLaunch("launch-with-single-java-opt.sh");
	}

	@Test
	public void launchWithMultipleJavaOpts() throws Exception {
		doLaunch("launch-with-multiple-java-opts.sh");
	}

	@Test
	public void launchWithUseOfStartStopDaemonDisabled() throws Exception {
		// CentOS doesn't have start-stop-daemon
		assumeThat(this.os, is(not("CentOS")));
		doLaunch("launch-with-use-of-start-stop-daemon-disabled.sh");
	}

	private void doLaunch(String script) throws Exception {
		assertThat(doTest(script)).contains("Launched");
	}

	private String doTest(String script) throws Exception {
		DockerClient docker = createClient();
		String imageId = buildImage(docker);
		String container = createContainer(docker, imageId, script);
		try {
			copyFilesToContainer(docker, container, script);
			docker.startContainerCmd(container).exec();
			StringBuilder output = new StringBuilder();
			AttachContainerResultCallback resultCallback = docker
					.attachContainerCmd(container).withStdOut(true).withStdErr(true)
					.withFollowStream(true).withLogs(true)
					.exec(new AttachContainerResultCallback() {

						@Override
						public void onNext(Frame item) {
							output.append(new String(item.getPayload()));
							super.onNext(item);
						}

					});
			resultCallback.awaitCompletion(60, TimeUnit.SECONDS).close();
			docker.waitContainerCmd(container).exec(new WaitContainerResultCallback())
					.awaitCompletion(60, TimeUnit.SECONDS);
			return output.toString();
		}
		finally {
			docker.removeContainerCmd(container).exec();
		}
	}

	private DockerClient createClient() {
		DockerClientConfigBuilder builder = DockerClientConfig
				.createDefaultConfigBuilder();
		DockerClientConfig config;
		try {
			config = builder.build();
		}
		catch (DockerClientException ex) {
			config = builder.withDockerTlsVerify(false).build();
		}
		return DockerClientBuilder.getInstance(config).build();
	}

	private String buildImage(DockerClient docker) {
		BuildImageResultCallback resultCallback = new BuildImageResultCallback();
		String dockerfile = "src/test/resources/conf/" + this.os + "/" + this.version
				+ "/Dockerfile";
		String tag = "spring-boot-it/" + this.os.toLowerCase() + ":" + this.version;
		docker.buildImageCmd(new File(dockerfile)).withTag(tag).exec(resultCallback);
		String imageId = resultCallback.awaitImageId();
		return imageId;
	}

	private String createContainer(DockerClient docker, String imageId,
			String testScript) {
		return docker.createContainerCmd(imageId).withTty(false).withCmd("/bin/bash",
				"-c", "chmod +x " + testScript + " && ./" + testScript).exec().getId();
	}

	private void copyFilesToContainer(DockerClient docker, final String container,
			String script) {
		copyToContainer(docker, container, findApplication());
		copyToContainer(docker, container,
				new File("src/test/resources/scripts/test-functions.sh"));
		copyToContainer(docker, container,
				new File("src/test/resources/scripts/" + script));
	}

	private void copyToContainer(DockerClient docker, String container, File file) {
		docker.copyArchiveToContainerCmd(container)
				.withHostResource(file.getAbsolutePath()).exec();
	}

	private File findApplication() {
		File targetDir = new File("target");
		for (File file : targetDir.listFiles()) {
			if (file.getName().startsWith("spring-boot-launch-script-tests")
					&& file.getName().endsWith(".jar")
					&& !file.getName().endsWith("-sources.jar")) {
				return file;
			}
		}
		throw new IllegalStateException(
				"Could not find test application in target directory. Have you built it (mvn package)?");
	}

	private Condition<String> coloredString(AnsiColor color, String string) {
		String colorString = ESC + "[0;" + color + "m" + string + ESC + "[0m";
		return new Condition<String>() {

			@Override
			public boolean matches(String value) {
				return containsString(colorString).matches(value);
			}

		};
	}

	private String extractPid(String output) {
		return extract("PID", output);
	}

	private String extract(String label, String output) {
		Pattern pattern = Pattern.compile(".*" + label + ": ([0-9]+).*", Pattern.DOTALL);
		java.util.regex.Matcher matcher = pattern.matcher(output);
		if (matcher.matches()) {
			return matcher.group(1);
		}
		throw new IllegalArgumentException(
				"Failed to extract " + label + " from output: " + output);
	}

}
