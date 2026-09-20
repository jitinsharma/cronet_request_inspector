package dev.cronetinspector.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * Applied by the consumer as a single line in their app module (see plan's
 * "gradle-plugin" section) -- the only app-facing change. Wires up two ASM
 * instrumentations for the debug variant (see CronetCallbackHookVisitorFactory and
 * CronetCallSiteVisitorFactory for what each rewrites and why), and auto-adds the
 * `runtime` dependency only when the project actually depends on Cronet, so applying
 * this plugin to a non-Cronet app module is a harmless no-op rather than dead weight.
 */
class CronetInspectorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents =
                project.extensions.getByType(AndroidComponentsExtension::class.java)

            androidComponents.onVariants(
                androidComponents.selector().withBuildType("debug")
            ) { variant ->
                // ALL, not PROJECT: real apps very often route Cronet through a
                // bridge library (e.g. com.google.net.cronet:cronet-okhttp, whose
                // own OkHttpBridgeRequestCallback/StreamingUploadDataProvider
                // classes implement UrlRequest.Callback/UploadDataProvider) rather
                // than touching Cronet's API directly -- PROJECT scope never sees
                // those classes at all, since they live in a dependency's own
                // compiled bytecode. This is safe unlike instrumenting Cronet's own
                // backend implementation classes would be: a bridge library like
                // cronet-okhttp is an ordinary statically-compiled dependency, not
                // one of play-services-cronet's dynamically-loaded-at-runtime impl
                // classes (see plan's "Interception design" for why THOSE remain
                // unreachable regardless of scope).
                variant.instrumentation.transformClassesWith(
                    CronetCallbackHookVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { }
                variant.instrumentation.transformClassesWith(
                    CronetCallSiteVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { }
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }

            // Runs after the module's own build.gradle.kts (including its
            // `dependencies {}` block) has been fully evaluated, so the Cronet
            // dependency -- if present -- is already visible here.
            project.afterEvaluate {
                if (!hasCronetTransitively(project)) return@afterEvaluate

                val runtimeProject = project.rootProject.findProject(":runtime")
                val runtimeDependencyNotation: Any =
                    runtimeProject ?: "dev.cronetinspector:runtime:0.1.0"
                project.dependencies.add("debugImplementation", runtimeDependencyNotation)
            }
        }
    }

    /**
     * Real apps very often depend on Cronet only *transitively* -- e.g. via
     * `com.google.android.gms:play-services-cronet` or a bridge library like
     * `com.google.net.cronet:cronet-okhttp`, neither of which has group
     * "org.chromium.net" itself -- so checking only declared/direct dependencies
     * (`Configuration.allDependencies`) misses this entirely. Resolving the actual
     * dependency graph is the only reliable way to detect it.
     *
     * Resolves a detached copy of debugRuntimeClasspath rather than the real one:
     * resolving the real configuration here would permanently freeze it against
     * further mutation, and the caller still needs to add debugImplementation(...)
     * (which debugRuntimeClasspath extends) afterward.
     */
    private fun hasCronetTransitively(project: Project): Boolean {
        val runtimeClasspath =
            project.configurations.findByName("debugRuntimeClasspath") ?: return false
        val detached = runtimeClasspath.copyRecursive()
        detached.isCanBeResolved = true
        detached.isCanBeConsumed = false
        return detached.incoming.resolutionResult.allComponents.any { component ->
            (component.id as? ModuleComponentIdentifier)?.group == "org.chromium.net"
        }
    }
}
