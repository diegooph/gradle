/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.testkit.runner

import org.gradle.integtests.fixtures.daemon.DaemonFixture
import org.gradle.integtests.fixtures.daemon.DaemonsFixture
import org.gradle.integtests.fixtures.executer.DaemonGradleExecuter
import org.gradle.integtests.fixtures.executer.UnderDevelopmentGradleDistribution
import org.gradle.test.fixtures.ConcurrentTestUtil
import org.gradle.test.fixtures.file.TestFile
import org.gradle.testkit.runner.fixtures.annotations.CaptureExecutedTasks
import org.gradle.testkit.runner.fixtures.annotations.NoDebug
import org.gradle.testkit.runner.internal.DefaultGradleRunner
import org.junit.Rule

import static org.gradle.testkit.runner.TaskOutcome.*

@CaptureExecutedTasks
@NoDebug
class GradleRunnerIsolatedDaemonIntegrationTest extends GradleRunnerIntegrationTest {

    def setup() {
        requireIsolatedTestKitDir = true
    }

    @Rule
    final ConcurrentTestUtil concurrent = new ConcurrentTestUtil(20000)

    def "configuration in default Gradle user home directory is ignored for test execution with daemon"() {
        given:
        def userHome = file("user-home")
        def gradleUserHome = userHome.file(".gradle")

        and:
        writeGradlePropertiesFile(gradleUserHome, 'myProp1=propertiesFile')
        writeInitScriptFile(gradleUserHome, "allprojects { ext.myProp2 = 'initScript' }")

        and:
        buildFile << """
            task check {
                doLast {
                    assert !project.ext.has('myProp1')
                    assert !project.ext.has('myProp2')
                }
            }
        """

        when:
        DefaultGradleRunner gradleRunner = runner('check')
        gradleRunner.withJvmArguments("-Duser.home=$userHome")
        BuildResult result = gradleRunner.build()

        then:
        result.tasks.collect { it.path } == [':check']
        result.taskPaths(SUCCESS) == [':check']
        result.taskPaths(SKIPPED).empty
        result.taskPaths(UP_TO_DATE).empty
        result.taskPaths(FAILED).empty
    }

    def "configuration in custom Gradle user home directory is used for test execution with daemon"() {
        setup:
        writeGradlePropertiesFile(testKitDir, 'myProp1=propertiesFile')
        writeInitScriptFile(testKitDir, "allprojects { ext.myProp2 = 'initScript' }")

        and:
        buildFile << """
            task check {
                doLast {
                    assert project.ext.has('myProp1')
                    assert project.ext.has('myProp2')
                }
            }
        """

        when:
        GradleRunner gradleRunner = runner('check')
        BuildResult result = gradleRunner.build()

        then:
        result.tasks.collect { it.path } == [':check']
        result.taskPaths(SUCCESS) == [':check']
        result.taskPaths(SKIPPED).empty
        result.taskPaths(UP_TO_DATE).empty
        result.taskPaths(FAILED).empty
    }

    def "daemon process dedicated to test execution uses short idle timeout"() {
        when:
        runner().build()

        then:
        onlyDaemon().context.idleTimeout == 120000
    }

    def "daemon process dedicated to test execution is reused if one already exists"() {
        when:
        runner().build()

        then:
        def pid = onlyDaemon().with {
            assertIdle()
            context.pid
        }

        when:
        runner().build()

        then:
        onlyDaemon().context.pid == pid
    }

    def "user daemon process does not reuse existing daemon process intended for test execution even when using same gradle user home"() {
        given:
        def nonTestKitDaemons = daemons(testKitDir, "daemon", buildContext.version.version)

        when:
        runner().build()

        then:
        def testKitDaemon = onlyDaemon()
        testKitDaemon.assertIdle()
        nonTestKitDaemons.visible.empty

        when:
        new DaemonGradleExecuter(new UnderDevelopmentGradleDistribution(), testDirectoryProvider)
            .usingProjectDirectory(testDirectory)
            .withGradleUserHomeDir(testKitDir)
            .withDaemonBaseDir(testKitDir.file("daemon")) // simulate default, our fixtures deviate from the default
            .run()

        then:
        def userDaemon = onlyDaemon(nonTestKitDaemons)
        userDaemon.assertIdle()
        userDaemon.context.pid != testKitDaemon.context.pid

        cleanup:
        userDaemon?.kill()
    }

    def "executing a build with a -g option does not affect daemon mechanics"() {
        when:
        runner("-g", file("custom-gradle-user-home").absolutePath).build()

        then:
        onlyDaemon().assertIdle()
    }

    def "runners executed concurrently can share the same Gradle user home directory"() {
        when:
        3.times {
            concurrent.start {
                runner().build()
            }
        }

        then:
        concurrent.finished()
    }

    protected DaemonFixture onlyDaemon(DaemonsFixture daemons = this.daemons()) {
        List<DaemonFixture> userDaemons = daemons.daemons
        assert userDaemons.size() == 1
        userDaemons[0]
    }

    static TestFile writeGradlePropertiesFile(File gradleUserHomeDir, String content) {
        new TestFile(gradleUserHomeDir, 'gradle.properties') << content
    }

    static TestFile writeInitScriptFile(File gradleUserHomeDir, String content) {
        new TestFile(gradleUserHomeDir, 'init.gradle') << content
    }
}
