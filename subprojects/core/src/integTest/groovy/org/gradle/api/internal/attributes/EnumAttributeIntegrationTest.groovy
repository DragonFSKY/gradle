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
 * Attribute values that are not {@code String}, {@code Boolean}, or {@code Integer} are
 * desugared by {@link org.gradle.internal.resolve.caching.DesugaringAttributeContainerSerializer}
 * to their {@link org.gradle.api.Named#getName()} name. Any path that reaches that serializer
 * with a plain enum now throws {@link IllegalArgumentException} — most notably the
 * task-graph-time resolution of a detached configuration with external dependencies.
 * <p>
 * In practice most other paths accept plain enums today because they bypass the desugaring
 * serializer. Those paths still work, but relying on them is unsupported and depends on which
 * serialization route Gradle happens to pick for the request. The safe path is to have the enum
 * implement {@code Named}, at which point every path — including the desugaring serializer —
 * accepts it.
 * <p>
 * Each test is parameterized with two enum flavors:
 * <ul>
 *   <li>{@code PLAIN} — a bare {@code enum MyEnum { FOO, BAR }} that does NOT implement
 *       {@link org.gradle.api.Named}. Unsupported: works only on paths that never reach the
 *       desugaring serializer.</li>
 *   <li>{@code NAMED} — an {@code enum MyEnum implements Named} that delegates
 *       {@code getName()} to the built-in {@code name()}. Supported: works on all paths.</li>
 * </ul>
 * <p>
 * Tests are organized into two main regions:
 * <ul>
 *   <li><b>enums succeed</b> — paths that never cross the desugaring serializer. Both flavors
 *       currently pass, but only the Named-implementing flavor is intended to be relied on.</li>
 *   <li><b>enums fail</b> — the ES-style task-graph-resolution path (plain fails with the new
 *       serializer error), plus enum-semantics consequences that fail regardless of whether
 *       the enum implements {@code Named} (compile-time-closed constants, invalid GMM values).</li>
 * </ul>
 */
@Issue("https://github.com/gradle/gradle/issues/38242")
final class EnumAttributeIntegrationTest extends AbstractIntegrationSpec {
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

    // region enums succeed
    // -------------------------------------------------------------------------
    // These tests exercise paths that never cross DesugaringAttributeContainerSerializer.
    // Both PLAIN and NAMED flavors pass today.
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: output.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: output.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: bar.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: output.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Dep: project ':producer'")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds("resolve")
        outputContains("Resolved: producer-1.0.jar")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds("resolve")
        outputContains("Resolved: ")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds("resolve")
        outputContains("Reselected: producer-1.0-bar.jar")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Enum: FOO")
        outputContains("Resolved: output.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Consumer-classloader singleton: OK")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: output.txt")

        where:
        enumDesc                | enumDecl
        "plain Enum with body"  | """enum MyEnum {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                   }"""
        "Named Enum with body"  | """enum MyEnum implements Named {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                       @Override String getName() { return name() }
                                   }"""
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
        succeeds(":consumer:resolve")
        outputContains("Resolved: output.txt")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
    }
    // endregion enum-as-JVM-singleton
    // endregion enums succeed

    // region enums fail
    // -------------------------------------------------------------------------
    // Paths that DO cross DesugaringAttributeContainerSerializer (the ES-detached
    // scenario), plus enum-semantics consequences that fail regardless of whether
    // the enum implements Named.
    // -------------------------------------------------------------------------
    def "task-input on a detached configuration with a #enumDesc attribute value (regression from Gradle 9.5.1)"() {
        // Reproducer for the ClassCastException reported by the Elasticsearch team:
        // detached configuration + external Maven dependency + task inputs.files →
        // triggers task-graph-time resolution which streams the graph through
        // StreamingResolutionResultBuilder → DesugaringAttributeContainerSerializer →
        // the plain enum is rejected with the new Unsupported-type message.
        //
        // The IAE raised by the serializer is caught inside DefaultBinaryStore's write
        // path and rewrapped as "Problems writing to Binary store" — the underlying
        // cause is still the Unsupported-type IAE, but the user-facing message shows
        // the wrapper only. A Named-implementing enum succeeds cleanly.
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
        if (implementsNamed) {
            succeeds("myTask")
        } else {
            fails("myTask")
            failure.assertHasCause("Problems writing to Binary store")
        }

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consequence (#enumDesc): enum constants are compile-time closed — producer offers a constant absent from the consumer's enum"() {
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
        failure.assertHasCause("No enum constant MyEnum.BAR")

        where:
        enumDesc             | enumDeclProducer                                                                        | enumDeclConsumer
        "plain Enum"         | "enum MyEnum { FOO, BAR }"                                                              | "enum MyEnum { FOO }"
        "Named-implementing" | "enum MyEnum implements Named { FOO, BAR;\n@Override String getName() { return name() } }" | "enum MyEnum implements Named { FOO;\n@Override String getName() { return name() } }"
    }

    def "consequence (#enumDesc): GMM value that is not a valid enum constant fails coercion"() {
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
        failure.assertHasCause("No enum constant MyEnum.NOT_A_CONSTANT")

        where:
        enumDesc   | enumDecl
        PLAIN_DESC | PLAIN_ENUM
        NAMED_DESC | NAMED_ENUM
    }
    // endregion
}
