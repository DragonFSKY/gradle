/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.api.internal.attributes

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

/**
 * Integration tests demonstrating that plain Java {@code Enum} types (those that do not
 * implement {@link org.gradle.api.Named}) are <strong>NOT</strong> supported as attribute values in Gradle's
 * dependency-resolution and publishing pipelines.
 * <p>
 * {@link org.gradle.api.attributes.Attribute#of(String, Class)} validates the requested attribute
 * type up front: it accepts {@code String}, {@code Boolean}, {@code Integer}, and any type
 * implementing {@link org.gradle.api.Named}. Any other type — including plain Java {@code Enum}
 * types — is rejected with {@link IllegalArgumentException} at declaration time, before Gradle
 * ever attempts to configure a configuration or resolve a dependency graph.
 * <p>
 * A Named-implementing enum ({@code enum X implements Named}) delegating {@code getName()} to the
 * built-in {@code name()} is the supported way to use an enum as an attribute value.
 * <p>
 * Each test is parameterized with two enum flavors:
 * <ul>
 *   <li>{@code PLAIN} — a bare {@code enum MyEnum { FOO, BAR }} that does NOT implement
 *       {@link org.gradle.api.Named}. Rejected by {@code Attribute.of} at declaration time.</li>
 *   <li>{@code NAMED} — an {@code enum MyEnum implements Named} that delegates
 *       {@code getName()} to the built-in {@code name()}. Accepted; works end-to-end.</li>
 * </ul>
 * <p>
 * Tests are organized into two regions:
 * <ul>
 *   <li><b>enums succeed</b> — valid usage patterns. The Named row exercises the pattern
 *       end-to-end. The plain row exercises {@code Attribute.of}'s type validation: build
 *       script evaluation fails immediately with the Unsupported-type message.</li>
 *   <li><b>enums fail</b> — enum-semantics consequences (compile-time-closed constants, invalid
 *       GMM values). Both flavors fail, but for different reasons: plain enums at
 *       {@code Attribute.of}, Named enums at the JDK's {@code Enum.valueOf}.</li>
 * </ul>
 */
@Issue("https://github.com/gradle/gradle/issues/38242")
final class EnumAttributeIntegrationTest extends AbstractIntegrationSpec {
    // region setup
    // Enum declaration templates injected into build scripts. Each declares a top-level
    // `MyEnum` type with constants FOO and BAR. The PLAIN flavor is a bare enum. The NAMED
    // flavor implements `org.gradle.api.Named` (default-imported in build scripts) by
    // delegating `getName()` to the built-in `name()`.
    private static final String PLAIN_ENUM = """
        enum MyEnum { FOO, BAR }
    """

    private static final String NAMED_ENUM = """
        enum MyEnum implements Named {
            FOO, BAR

            @Override
            String getName() { return name() }
        }
    """

    private static final String PLAIN_DESC = "plain Enum"
    private static final String NAMED_DESC = "Named-implementing Enum"

    // Expected root-cause message when Attribute.of rejects a plain enum type.
    private static final String UNSUPPORTED_TYPE_MSG = "Unsupported type 'MyEnum' for attribute 'myEnumAttribute'. Attribute values must be of type String, Boolean, a subtype of Number, or implement org.gradle.api.Named."

    /**
     * Runs the given task and asserts the expected outcome depending on whether the enum
     * flavor under test implements {@link org.gradle.api.Named}. Named-implementing enums
     * are expected to succeed and produce the given output lines. Plain enums are expected
     * to fail at the {@link org.gradle.api.attributes.Attribute#of(String, Class)} call
     * with {@link #UNSUPPORTED_TYPE_MSG} as the failure cause.
     */
    private void expectResolve(String taskName, boolean implementsNamed, List<String> expectedOutputs = []) {
        if (implementsNamed) {
            succeeds(taskName)
            expectedOutputs.each { outputContains(it) }
        } else {
            fails(taskName)
            failure.assertHasCause(UNSUPPORTED_TYPE_MSG)
        }
    }
    // endregion setup

    // region enums succeed
    // -------------------------------------------------------------------------
    // Every test in this region exercises a valid Gradle attribute-usage pattern.
    // Named-implementing enums pass end-to-end. Plain enums are rejected at
    // Attribute.of during script evaluation with UNSUPPORTED_TYPE_MSG.
    // -------------------------------------------------------------------------
    def "in-memory attribute matching accepts a #enumDesc as an attribute value"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "AttributeCompatibilityRule typed on a #enumDesc makes candidate values compatible"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            abstract class MyEnumCompatibilityRule implements AttributeCompatibilityRule<MyEnum> {
                void execute(CompatibilityCheckDetails<MyEnum> details) { details.compatible() }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(MyEnumCompatibilityRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "AttributeDisambiguationRule typed on a #enumDesc picks a candidate"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/foo.txt") << "foo output"
        file("producer/bar.txt") << "bar output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("fooVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("foo.txt"))
                }
                consumable("barVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("bar.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            abstract class MyEnumCompatibilityRule implements AttributeCompatibilityRule<MyEnum> {
                void execute(CompatibilityCheckDetails<MyEnum> details) { details.compatible() }
            }
            abstract class MyEnumDisambiguationRule implements AttributeDisambiguationRule<MyEnum> {
                void execute(MultipleCandidatesDetails<MyEnum> details) {
                    if (details.candidateValues.contains(MyEnum.BAR)) {
                        details.closestMatch(MyEnum.BAR)
                    }
                }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(MyEnumCompatibilityRule)
                        disambiguationRules.add(MyEnumDisambiguationRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: bar.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "supplying a #enumDesc attribute value via a lazy Provider works end-to-end"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes {
                        attributeProvider(ATTRIBUTE_TYPE, project.provider { MyEnum.FOO })
                    }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes {
                        attributeProvider(ATTRIBUTE_TYPE, project.provider { MyEnum.FOO })
                    }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "materializing resolutionResult with a #enumDesc on a local project dependency"() {
        // Local project dependencies do not stream the resolution result through the
        // desugaring serializer, so both flavors pass here today.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                    root.dependencies.each { d -> println("Dep: " + d) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Dep: project ':producer'"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consuming a Maven-published variant with a #enumDesc-typed request attribute"() {
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: producer-1.0.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consuming an Ivy-published variant with a #enumDesc-typed request attribute"() {
        given:
        ivyRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"])
            .withVariant("myVariant") {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                ivy { url = uri("${ivyRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "withVariantReselection using a #enumDesc as the reselection attribute"() {
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("fooVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0-foo.jar")
            }
            .variant("barVariant", [myEnumAttribute: "BAR"]) {
                artifact("producer-1.0-bar.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def reselected = configurations.myResolver.incoming.artifactView {
                    withVariantReselection()
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                }.files
                doLast {
                    reselected.each { println("Reselected: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Reselected: producer-1.0-bar.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "config-cache round-trip on a task holding a resolvable configuration with a #enumDesc"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            abstract class HoldEnum extends DefaultTask {
                @Input
                abstract Property<MyEnum> getEnumInput()
                @InputFiles
                abstract ConfigurableFileCollection getFiles()
                @TaskAction
                void run() {
                    println("Enum: " + enumInput.get())
                    files.each { println("Resolved: " + it.name) }
                }
            }

            tasks.register("resolve", HoldEnum) {
                enumInput.set(MyEnum.FOO)
                files.from(configurations.myResolver)
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Enum: FOO", "Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    // region enum-as-JVM-singleton
    // -------------------------------------------------------------------------
    // Consequences of enum-as-JVM-singleton semantics — apply to both flavors
    // but produce non-failure outcomes (the tests below verify the expected
    // outcome and thus belong in "enums succeed").
    // -------------------------------------------------------------------------
    def "consequence (#enumDesc): cross-classloader coercion silently creates a different singleton"() {
        // Producer and consumer scripts each declare their own MyEnum in independent
        // classloaders. Consumer-side coercion returns the CONSUMER's FOO singleton,
        // not the producer's. This test verifies the retrieved attribute value belongs
        // to the consumer's classloader — the "trap" being that a plugin author
        // holding a reference to a DIFFERENT classloader's FOO would see reference
        // inequality even though names match.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def artifacts = configurations.myResolver.incoming.artifactView {}.artifacts.resolvedArtifacts
                doLast {
                    artifacts.get().each { r ->
                        def retrieved = r.variant.attributes.getAttribute(ATTRIBUTE_TYPE)
                        assert retrieved.is(MyEnum.FOO): "retrieved \$retrieved is not identical to consumer's MyEnum.FOO"
                        assert retrieved.declaringClass.classLoader.is(MyEnum.class.classLoader)
                        println("Consumer-classloader singleton: OK")
                    }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Consumer-classloader singleton: OK"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consequence (#enumDesc): enum with anonymous per-constant inner-class bodies works via getDeclaringClass"() {
        // Enum constants declared with per-constant bodies produce anonymous subclasses
        // (MyEnum$1, MyEnum$2). Gradle uses Enum#getDeclaringClass, not Object#getClass,
        // so no code path treats MyEnum$1 as the attribute value type.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc                | enumDecl                                                         | implementsNamed
        "plain Enum with body"  | """enum MyEnum {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                   }"""                                                            | false
        "Named Enum with body"  | """enum MyEnum implements Named {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                       @Override String getName() { return name() }
                                   }"""                                                            | true
    }

    def "consequence (#enumDesc): config-cache save-and-reuse preserves the attribute value across script re-parse"() {
        // A build script declaring its own enum gets a fresh classloader on every
        // configuration. Config-cache save serializes attribute values by class name +
        // constant name; restore re-runs the script and re-resolves the constant via
        // Enum.valueOf. This verifies a save→reuse cycle produces the same result.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect: "the configCacheIntegTest task variant already replays every test through configuration-cache save+load; a single successful run here proves the enum attribute survives that round-trip"
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }
    // endregion enum-as-JVM-singleton
    // endregion enums succeed

    // region un-named enums fail
    // -------------------------------------------------------------------------
    // The reported detached-configuration regression (now caught up front by
    // Attribute.of), plus enum-semantics consequences where both flavors fail — plain at
    // Attribute.of, Named at the JDK's Enum.valueOf when the wire value doesn't match a
    // constant of the requested enum type.
    // -------------------------------------------------------------------------
    def "task-input on a detached configuration with a #enumDesc attribute value (regression from Gradle 9.5.1) (#enumDesc)"() {
        // Reproducer for the ClassCastException reported by the Elasticsearch team:
        // With enforcement now at Attribute.of (invoked during script evaluation), a plain
        // enum is rejected before Gradle even attempts to configure the detached
        // configuration. The user-facing error is the Unsupported-type IAE, with no need
        // to trace through DesugaringAttributeContainerSerializer or DefaultBinaryStore
        // wrapping. A Named-implementing enum succeeds end-to-end.
        given:
        mavenRepo.module("org.example", "producer", "1.0").publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            def detachedConf = configurations.detachedConfiguration(
                dependencies.create("org.example:producer:1.0")
            )
            detachedConf.attributes {
                attribute(ATTRIBUTE_TYPE, MyEnum.FOO)
            }

            tasks.register("myTask") {
                inputs.files(detachedConf)
                doLast { }
            }
        """)

        expect:
        expectResolve("myTask", implementsNamed)

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "enum constants are compile-time closed — producer offers a constant absent from the consumer's enum (#enumDesc)"() {
        // The producer publishes a variant using MyEnum.BAR. The consumer's MyEnum
        // has only FOO. Consumer-side coercion calls `Enum.valueOf(consumer.MyEnum, "BAR")`
        // which raises `IllegalArgumentException: No enum constant MyEnum.BAR`. This
        // failure applies to both flavors because it is a JDK-level enum-semantics
        // constraint, not a Gradle-attribute-support constraint.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDeclProducer}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDeclConsumer}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        fails(":consumer:resolve")
        failure.assertHasCause(expectedCause)

        where:
        enumDesc             | enumDeclProducer                                                                          | enumDeclConsumer                                                                     | expectedCause
        "plain Enum"         | "enum MyEnum { FOO, BAR }"                                                                | "enum MyEnum { FOO }"                                                                | UNSUPPORTED_TYPE_MSG
        "Named-implementing" | "enum MyEnum implements Named { FOO, BAR;\n@Override String getName() { return name() } }" | "enum MyEnum implements Named { FOO;\n@Override String getName() { return name() } }" | "No enum constant MyEnum.BAR"
    }

    def "GMM value that is not a valid enum constant fails coercion (#enumDesc)"() {
        // The GMM wire attribute value must be exactly a constant name of the consumer's
        // enum type. Any drift (typo, renamed constant, spurious value from a
        // component-metadata rule) surfaces as the raw JDK IAE. Named subtypes have no
        // equivalent failure mode because Named lookups return an instance for any string.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "NOT_A_CONSTANT"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        fails("resolve")
        failure.assertHasCause(expectedCause)

        where:
        enumDesc   | enumDecl    | expectedCause
        PLAIN_DESC | PLAIN_ENUM  | UNSUPPORTED_TYPE_MSG
        NAMED_DESC | NAMED_ENUM  | "No enum constant MyEnum.NOT_A_CONSTANT"
    }

    def "publishing a variant with a #enumDesc-typed attribute to a Maven repository (#enumDesc)"() {
        // Exercises the producer-side publishing pipeline via maven-publish.
        // GradleModuleMetadataWriter → ModuleMetadataSpecBuilder.attributeValueFor already
        // handles Enum values by name (line 391-392), but Attribute.of rejects plain enums
        // upstream — so the plain row fails at script eval on the producer side.
        given:
        file("output.txt") << "sample output"
        buildFile("""
            plugins { id 'maven-publish' }

            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            group = 'org.example'
            version = '1.0'

            def myVariant = configurations.consumable("myVariant") {
                attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                outgoing.artifact(file("output.txt"))
            }

            def component = publishing.softwareComponentFactory.adhoc("myComponent")
            component.addVariantsFromConfiguration(myVariant.get()) {
                mapToMavenScope("runtime")
            }
            components.add(component)

            publishing {
                repositories { maven { url = uri("${mavenRepo.uri}") } }
                publications {
                    maven(MavenPublication) { from components.myComponent }
                }
            }
        """)

        expect:
        expectResolve("publish", implementsNamed)

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolution emits build operations with a #enumDesc attribute value"() {
        // Probes AttributesToMapConverter.getAttributeValueAsString: the build-op path
        // uses value.toString() as a fallback (line 61), which for an enum yields the
        // constant name. Any successful resolution emits build operations that carry
        // the attribute container through this code path.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolution build-op result desugars a #enumDesc attribute via toString"() {
        // Probes ResolveConfigurationResolutionBuildOperationResult.desugarAttributes
        // (line 100-128), which has its own desugaring: primitives, then Named, then a
        // .toString() fallback (line 124). Enums fall into the fallback branch.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Root: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "component metadata rule adds a #enumDesc attribute to a resolved module"() {
        // A component metadata rule mutates the resolved graph's attributes at rule
        // execution time. The rule references MyEnum inside its execute() body — but the
        // consumer script's own Attribute.of call fires first at script eval for plain
        // enums.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [:]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            abstract class AddEnumAttributeRule implements ComponentMetadataRule {
                @Override
                void execute(ComponentMetadataContext ctx) {
                    def attr = Attribute.of("myEnumAttribute", MyEnum.class)
                    ctx.details.allVariants {
                        attributes { attribute(attr, MyEnum.FOO) }
                    }
                }
            }

            dependencies {
                components {
                    withModule("org.example:producer", AddEnumAttributeRule)
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: producer-1.0.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "materializing resolutionResult with a #enumDesc on an external Maven dependency"() {
        // The 'materializing resolutionResult…on a local project dependency' test above uses
        // a local project dep, which sidesteps the streaming serializer path. External Maven
        // deps stream through StreamingResolutionResultBuilder → DesugaringAttributeContainerSerializer,
        // so this variant probes a distinct pipeline that's closer to the ES/9.5.1 regression.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                    root.dependencies.each { d -> println("Dep: " + d) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Dep: org.example:producer:1.0"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "deduplicated serialization when multiple variants share a #enumDesc attribute container"() {
        // DeduplicatingAttributeContainerSerializer wraps DesugaringAttributeContainerSerializer
        // and interns identical attribute containers on write. Two variants with overlapping
        // attribute sets exercise the dedup path. Requires the streaming resolution result to
        // be materialized to hit the wrapper.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("variant1", [myEnumAttribute: "FOO", tag: "one"]) {
                artifact("producer-1.0-a.jar")
            }
            .variant("variant2", [myEnumAttribute: "FOO", tag: "two"]) {
                artifact("producer-1.0-b.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)
            def TAG_ATTR = Attribute.of("tag", String.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes {
                        attribute(ATTRIBUTE_TYPE, MyEnum.FOO)
                        attribute(TAG_ATTR, "one")
                    }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Root: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolvable configuration inheriting a #enumDesc attribute via extendsFrom"() {
        // Attribute inheritance goes through the container's addAllLater / concat chain,
        // which doesn't touch the desugaring serializer. Any failure comes from Attribute.of
        // during script eval, not from any downstream serialization.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myBase") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myBase"))
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "registering a #enumDesc-typed attribute in the attributes schema"() {
        // Registering an attribute in the schema (dependencies.attributesSchema { attribute(...) })
        // walks through DefaultAttributesSchema.attribute(...). The consumer script's own
        // Attribute.of call fires the validation first, so plain row fails identically to
        // other consumer-side plain rows.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE)
                }
            }

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE)
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }
    // endregion un-named enums fail
}
