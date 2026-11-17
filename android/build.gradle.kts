import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.testing.Test
import java.util.ArrayDeque

plugins {
    // Provides the lifecycle `check` / `build` tasks at the repository root so
    // the boundary check can be wired into `./gradlew build`.
    base
}

/**
 * D5: modules depend on the core, never on each other.
 *
 * A project under `:feature:<a>:*` may depend on `:core:*` and on
 * `:feature:<a>:domain`, but on no other feature module. This is enforced by
 * walking the declared project dependencies of every feature module, including
 * the ones reached transitively through `api`/`implementation` of other
 * projects, so `:feature:medications:android` also fails when it pulls in
 * `:feature:appointments:android` indirectly.
 */
val featureModulePath = Regex(""":feature:[^:]+:(?:domain|android)""")

val inspectedConfigurations = setOf(
    "api",
    "implementation",
    "compileOnly",
    "runtimeOnly",
    "testImplementation",
    "testRuntimeOnly",
    "androidTestImplementation",
    "androidTestRuntimeOnly",
)

fun featureOwnerOf(projectPath: String): String? =
    if (featureModulePath.matches(projectPath)) projectPath.split(":")[2] else null

gradle.projectsEvaluated {
    val projectsByPath = rootProject.allprojects.associateBy { it.path }
    val featureProjects = projectsByPath.values
        .filter { featureOwnerOf(it.path) != null }
        .sortedBy { it.path }

    val violations = mutableListOf<String>()
    val report = StringBuilder()

    report.appendLine("checkModuleBoundaries: inspecting ${featureProjects.size} feature module(s)")

    featureProjects.forEach { featureProject ->
        val owner = featureOwnerOf(featureProject.path)!!
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Seed with the feature module itself, then walk every project it
        // reaches through the configurations that end up on a compile or
        // runtime classpath.
        visited.add(featureProject.path)
        queue.add(featureProject.path)

        while (queue.isNotEmpty()) {
            val currentPath = queue.removeFirst()
            val current = projectsByPath[currentPath] ?: continue

            current.configurations
                .filter { it.name in inspectedConfigurations }
                .forEach { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .forEach { dependency ->
                            val targetPath = dependency.path
                            val targetOwner = featureOwnerOf(targetPath)

                            if (targetOwner != null && targetOwner != owner) {
                                violations +=
                                    "${featureProject.path} -> $targetPath " +
                                    "(reached via ${currentPath} ${configuration.name}; " +
                                    "feature '$owner' must not depend on feature '$targetOwner')"
                            }

                            if (visited.add(targetPath)) {
                                queue.add(targetPath)
                            }
                        }
                }
        }

        report.appendLine("  ${featureProject.path} (feature: $owner) -> checked")
    }

    if (violations.isEmpty()) {
        report.appendLine("checkModuleBoundaries: OK - no feature module depends on another feature module")
    } else {
        report.appendLine("checkModuleBoundaries: ${violations.size} violation(s) of D5")
    }

    val reportText = report.toString()
    val violationsSeen = violations.toList()

    tasks.register("checkModuleBoundaries") {
        group = "verification"
        description = "Fails if a :feature:<a>:* module depends on a different feature module (D5)."
        doLast {
            logger.lifecycle(reportText)
            if (violationsSeen.isNotEmpty()) {
                violationsSeen.forEach { logger.error("  D5 violation: $it") }
                throw GradleException(
                    "Module boundaries violated: feature modules must not depend on each other (D5). " +
                        "See $name output above.",
                )
            }
        }
    }

    tasks.named("check") {
        dependsOn("checkModuleBoundaries")
    }
}

// Unit-test output names the tests that ran, locally and in CI logs.
subprojects {
    tasks.withType(Test::class.java).configureEach {
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
