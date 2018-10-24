/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.resolve.ResolveTestFixture
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Unroll

class RepositoryContentFilteringIntegrationTest extends AbstractHttpDependencyResolutionTest {
    ResolveTestFixture resolve

    def setup() {
        settingsFile << "rootProject.name = 'test'"
        buildFile << """                      
            configurations {
                conf
            }
        """
        resolve = new ResolveTestFixture(buildFile, 'conf')
        resolve.prepare()
    }

    def "doesn't search for module in repository when rule says so"() {
        def mod = ivyHttpRepo.module('org', 'foo', '1.0').publish()

        given:
        repositories {
            maven("contentFilter { details -> details.notFound() }")
            ivy()
        }
        buildFile << """
            dependencies {
                conf "org:foo:1.0"
            }
        """

        when:
        mod.ivy.expectGet()
        mod.artifact.expectGet()

        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:foo:1.0')
            }
        }
    }

    def "doesn't try to list module versions in repository when rule says so"() {
        def mod = ivyHttpRepo.module('org', 'foo', '1.0').publish()
        def ivyDirectoryList = ivyHttpRepo.directoryList('org', 'foo')

        given:
        repositories {
            maven("contentFilter { details -> details.notFound() }")
            ivy()
        }
        buildFile << """
            dependencies {
                conf "org:foo:+"
            }
        """

        when:
        ivyDirectoryList.allowGet()
        mod.ivy.expectGet()
        mod.artifact.expectGet()

        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                edge('org:foo:+', 'org:foo:1.0')
            }
        }
    }

    def "can filter based on the module identifier"() {
        def mod1 = ivyHttpRepo.module('org', 'foo', '1.0').publish()
        def mod2Ivy = ivyHttpRepo.module('org', 'bar', '1.0').publish()
        def mod2Maven = mavenHttpRepo.module('org', 'bar', '1.0')

        given:
        repositories {
            maven("""contentFilter { details ->
                if (details.id.name == 'foo') { 
                   details.notFound() 
                }
            }""")
            ivy()
        }
        buildFile << """
            dependencies {
                conf "org:foo:1.0"
                conf "org:bar:1.0"
            }
        """

        when:
        mod1.ivy.expectGet()
        mod1.artifact.expectGet()

        mod2Maven.pom.expectGetMissing()
        mod2Maven.artifact.expectHeadMissing()

        mod2Ivy.ivy.expectGet()
        mod2Ivy.artifact.expectGet()

        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:foo:1.0')
                module('org:bar:1.0')
            }
        }
    }

    /**
     * Use case: allow different configurations to resolve the same dependencies but not necessarily from
     * the same repositories. For example, for a distribution we would only allow fetching from blessed
     * repositories while for tests, we would be more lenient. This can be achieved by checking the name
     * of the configuration being resolved, in the rule.
     */
    def "can filter by configuration name"() {
        def mod = ivyHttpRepo.module('org', 'foo', '1.0').publish()

        given:
        repositories {
            maven("""contentFilter { details ->
                if (details.consumerName == 'conf') { 
                   details.notFound() 
                }
            }""")
            ivy()
        }
        buildFile << """
            dependencies {
                conf "org:foo:1.0"
            }
        """

        when:
        mod.ivy.expectGet()
        mod.artifact.expectGet()

        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:foo:1.0')
            }
        }
    }

    @Unroll
    def "two configurations can use the same repositories with filtering and do not interfere with each other"() {
        def mod = mavenHttpRepo.module('org', 'foo', '1.0').publish()

        given:
        repositories {
            maven("""contentFilter { details ->
                if (details.consumerName == 'conf') { 
                   details.notFound() 
                }
            }""")
        }
        buildFile << """
            configurations {
                conf2
            }
            dependencies {
                conf "org:foo:1.0"
                conf2 "org:foo:1.0"
            }
            tasks.register("verify") {
                doFirst {
                    $check1               
                    $check2
                }
            }
        """

        when:
        mod.pom.expectGet()
        mod.artifact.expectGet()

        then:
        succeeds 'verify'

        where:
        check1 << [checkConfIsUnresolved(), checkConf2IsResolved()]
        check2 << [checkConf2IsResolved(), checkConfIsUnresolved()]
    }

    /**
     * Use case: explain that a repository doesn't contain dependencies with specific attributes.
     * This can be useful when a repository only contains dependencies of a certain type (for example, native binaries or JS libraries)
     * so it wouldn't be necessary to look for POM files in them for example.
     */
    def "can filter by attributes"() {
        def mod = ivyHttpRepo.module('org', 'foo', '1.0').publish()
        buildFile << """
            def colorAttribute = Attribute.of('colorAttribute', String)
        """
        given:
        repositories {
            maven("""contentFilter { details ->
                println(details.consumerAttributes)
                if (details.consumerAttributes.getAttribute(colorAttribute) == 'blue') { 
                   details.notFound() 
                }
            }""")
            ivy()
        }
        buildFile << """
            configurations {
                conf {
                    attributes {
                        attribute(colorAttribute, 'blue')
                    }
                }
            }
            dependencies {
                conf("org:foo:1.0")
            }
        """

        when:
        mod.ivy.expectGet()
        mod.artifact.expectGet()

        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:foo:1.0')
            }
        }
    }


    static String checkConfIsUnresolved() {
        """def confIncoming = configurations.conf.incoming.resolutionResult.allDependencies
                    assert confIncoming.every { it instanceof UnresolvedDependencyResult }"""
    }

    static String checkConf2IsResolved() {
        """def conf2Incoming = configurations.conf2.incoming.resolutionResult.allDependencies
                    assert conf2Incoming.every { it instanceof ResolvedDependencyResult }
                    assert configurations.conf2.files.name == ['foo-1.0.jar']"""
    }

    void repositories(@DelegatesTo(value = RepositorySpec, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
        def delegate = new RepositorySpec()
        spec.delegate = delegate
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec()
        delegate.complete(buildFile)
    }

    class RepositorySpec {
        private final StringBuilder dsl = new StringBuilder()

        RepositorySpec() {
            dsl << "repositories {"
        }

        void maven(String conf = "") {
            dsl << """
                maven {
                    url "${mavenHttpRepo.uri}"
                    $conf
                }
            """
        }

        void ivy(String conf = "") {
            dsl << """
                ivy {
                    url "${ivyHttpRepo.uri}"
                    $conf
                }
            """
        }

        void complete(TestFile to) {
            dsl << "\n}"
            to << dsl
            dsl.setLength(0)
        }
    }
}
