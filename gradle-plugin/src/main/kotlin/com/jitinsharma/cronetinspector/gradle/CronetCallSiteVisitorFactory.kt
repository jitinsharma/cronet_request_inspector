package com.jitinsharma.cronetinspector.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

private const val CRONET_ENGINE = "org/chromium/net/CronetEngine"
private const val URL_REQUEST_BUILDER = "org/chromium/net/UrlRequest\$Builder"

private const val NEW_URL_REQUEST_BUILDER = "newUrlRequestBuilder"
private const val NEW_URL_REQUEST_BUILDER_DESC =
    "(Ljava/lang/String;Lorg/chromium/net/UrlRequest\$Callback;Ljava/util/concurrent/Executor;)" +
        "Lorg/chromium/net/UrlRequest\$Builder;"

private const val SET_HTTP_METHOD = "setHttpMethod"
private const val SET_HTTP_METHOD_DESC = "(Ljava/lang/String;)Lorg/chromium/net/UrlRequest\$Builder;"

private const val ADD_HEADER = "addHeader"
private const val ADD_HEADER_DESC =
    "(Ljava/lang/String;Ljava/lang/String;)Lorg/chromium/net/UrlRequest\$Builder;"

private const val SET_UPLOAD_DATA_PROVIDER = "setUploadDataProvider"
private const val SET_UPLOAD_DATA_PROVIDER_DESC =
    "(Lorg/chromium/net/UploadDataProvider;Ljava/util/concurrent/Executor;)" +
        "Lorg/chromium/net/UrlRequest\$Builder;"

private const val BUILD = "build"
private const val BUILD_DESC = "()Lorg/chromium/net/UrlRequest;"

private val STRING_TYPE: Type = Type.getObjectType("java/lang/String")
private val EXECUTOR_TYPE: Type = Type.getObjectType("java/util/concurrent/Executor")
private val CALLBACK_TYPE: Type = Type.getObjectType("org/chromium/net/UrlRequest\$Callback")
private val UPLOAD_PROVIDER_TYPE: Type = Type.getObjectType("org/chromium/net/UploadDataProvider")
private val BUILDER_TYPE: Type = Type.getObjectType(URL_REQUEST_BUILDER)
private val URL_REQUEST_TYPE: Type = Type.getObjectType("org/chromium/net/UrlRequest")

/**
 * Rewrites five narrow, well-known call sites, reached via ALL scope (both the
 * app's own bytecode AND any statically-compiled library dependency's, e.g. a
 * Cronet-OkHttp bridge library's own request-building code -- see plan's
 * "Interception design" and CronetCallbackHookVisitorFactory's doc for why ALL
 * scope is needed and safe here). Each rewrite tees the call's own arguments into a
 * CronetInspectorRuntime `record*`/`attachToRequest` call via an
 * IdentityHashMap-keyed builder/request, then re-emits the original instruction
 * completely unchanged, so app behavior and return values are untouched:
 *
 *  - `CronetEngine.newUrlRequestBuilder(url, callback, executor)` -> recordUrl
 *  - `UrlRequest.Builder.setHttpMethod(method)` -> recordMethod
 *  - `UrlRequest.Builder.addHeader(name, value)` -> recordHeader
 *  - `UrlRequest.Builder.setUploadDataProvider(provider, executor)` -> recordUploadProvider
 *  - `UrlRequest.Builder.build()` -> attachToRequest(builder, request)
 *
 * Each of these is call-site-independent within a single instruction (no fragile
 * multi-call-site pattern matching), which is what makes this the narrow, robust kind
 * of call-site rewrite the plan calls out as safe -- unlike wrapping every possible
 * callback-construction site.
 *
 * isInstrumentable excludes Cronet's own `org.chromium.net` package tree (mirrors
 * CronetCallbackHookVisitorFactory's exclusion -- see its doc for why instrumenting
 * Cronet's own, often minified, internal classes is unsafe, not just unnecessary),
 * then applies a cheap pre-filter for everything else, since at ALL scope this
 * factory would otherwise be asked to fully scan (every method, every instruction)
 * every class in every dependency -- AndroidX, Compose, Kotlin stdlib, all of it --
 * to find five call sites that can only plausibly occur in the app's own code or a
 * Cronet-adjacent bridge library (see ROADMAP.md's now-resolved entry for the
 * before/after and why this wasn't done inline the first time):
 *
 *  - the app's OWN classes are always instrumentable, regardless of name -- there's
 *    no naming convention to rely on for those (a call site could be in a class
 *    named anything), so this is approximated via [CronetCallSiteParams.projectNamespace]:
 *    the project's declared `android { namespace }`, which in practice is also the
 *    package root every real Android project's own source lives under. Imperfect --
 *    a project with source outside its declared namespace would miss instrumentation
 *    there -- but that layout is exceedingly rare, and the alternative (no filter at
 *    all) is the exact per-class-in-every-dependency cost this exists to avoid.
 *  - everything else is only instrumentable if its name looks Cronet-related (see
 *    [looksCronetRelated]) -- true for known bridge libraries like
 *    `com.google.net.cronet:cronet-okhttp`'s `com.google.net.cronet.okhttptransport`
 *    package, and, being a broad substring match, likely true for other
 *    not-yet-encountered ones too. A random unrelated dependency (Compose, OkHttp
 *    itself, Kotlin stdlib, ...) never calls Cronet's builder APIs, so skipping it
 *    here is safe, not just fast.
 */
/**
 * @property projectNamespace the app module's declared `android { namespace }`, used
 * by [CronetCallSiteVisitorFactory.isInstrumentable] to always instrument the app's
 * own classes regardless of name (see that class's doc). `@get:Input`, not
 * `@get:Internal`: this IS a real instrumentation input -- a namespace change should
 * invalidate the transform's up-to-date state, since it changes which classes get
 * instrumented. `@get:Optional` since a project with no `namespace` set at all
 * (legacy `package` attribute in the manifest instead) still needs the transform to
 * run, just without the project-class fast path.
 */
interface CronetCallSiteParams : InstrumentationParameters {
    @get:Input
    @get:Optional
    val projectNamespace: Property<String>
}

/**
 * Broad, deliberately over-inclusive substring match -- see
 * [CronetCallSiteVisitorFactory]'s doc for why being over-inclusive here is safe.
 * Also used by [CronetCallbackHookVisitorFactory]'s own pre-filter.
 */
internal fun looksCronetRelated(className: String): Boolean = className.contains("cronet", ignoreCase = true)

/** True for the app's own classes (see [CronetCallSiteParams.projectNamespace]'s doc) or
 * anything Cronet-related -- shared pre-filter for both ASM visitor factories. */
internal fun isProjectOrCronetRelated(className: String, projectNamespace: String?): Boolean =
    (projectNamespace != null && className.startsWith(projectNamespace)) || looksCronetRelated(className)

abstract class CronetCallSiteVisitorFactory :
    AsmClassVisitorFactory<CronetCallSiteParams> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (className.startsWith("org.chromium.net.")) return false
        return isProjectOrCronetRelated(className, parameters.get().projectNamespace.orNull)
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return CallSiteMethodVisitor(Opcodes.ASM9, access, descriptor, mv)
            }
        }
    }
}

private class CallSiteMethodVisitor(
    api: Int,
    access: Int,
    descriptor: String,
    methodVisitor: MethodVisitor,
) : LocalVariablesSorter(api, access, descriptor, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        when {
            owner == CRONET_ENGINE && name == NEW_URL_REQUEST_BUILDER && descriptor == NEW_URL_REQUEST_BUILDER_DESC ->
                rewriteNewUrlRequestBuilder(opcode, owner, name, descriptor, isInterface)

            owner == URL_REQUEST_BUILDER && name == SET_HTTP_METHOD && descriptor == SET_HTTP_METHOD_DESC ->
                rewriteSetHttpMethod(opcode, owner, name, descriptor, isInterface)

            owner == URL_REQUEST_BUILDER && name == ADD_HEADER && descriptor == ADD_HEADER_DESC ->
                rewriteAddHeader(opcode, owner, name, descriptor, isInterface)

            owner == URL_REQUEST_BUILDER && name == SET_UPLOAD_DATA_PROVIDER && descriptor == SET_UPLOAD_DATA_PROVIDER_DESC ->
                rewriteSetUploadDataProvider(opcode, owner, name, descriptor, isInterface)

            owner == URL_REQUEST_BUILDER && name == BUILD && descriptor == BUILD_DESC ->
                rewriteBuild(opcode, owner, name, descriptor, isInterface)

            else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }

    // Stack on entry: engine, url, callback, executor. The url argument is consumed
    // by the real call and unavailable afterward, so it must be stashed in a local
    // before the call runs (same for callback/executor, purely to restore the
    // original argument order for the real invocation).
    private fun rewriteNewUrlRequestBuilder(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val executorLocal = newLocal(EXECUTOR_TYPE)
        val callbackLocal = newLocal(CALLBACK_TYPE)
        val urlLocal = newLocal(STRING_TYPE)
        visitVarInsn(Opcodes.ASTORE, executorLocal)
        visitVarInsn(Opcodes.ASTORE, callbackLocal)
        visitVarInsn(Opcodes.ASTORE, urlLocal)

        visitVarInsn(Opcodes.ALOAD, urlLocal)
        visitVarInsn(Opcodes.ALOAD, callbackLocal)
        visitVarInsn(Opcodes.ALOAD, executorLocal)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        // stack: builder

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ALOAD, urlLocal)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RUNTIME_OWNER, "recordUrl",
            "(Ljava/lang/Object;Ljava/lang/String;)V", false,
        )
        // stack: builder (unchanged from the real call's return value)
    }

    // Stack on entry: builder, method.
    private fun rewriteSetHttpMethod(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val methodLocal = newLocal(STRING_TYPE)
        visitVarInsn(Opcodes.ASTORE, methodLocal)
        // stack: builder

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ALOAD, methodLocal)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RUNTIME_OWNER, "recordMethod",
            "(Ljava/lang/Object;Ljava/lang/String;)V", false,
        )
        // stack: builder

        visitVarInsn(Opcodes.ALOAD, methodLocal)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    // Stack on entry: builder, name, value.
    private fun rewriteAddHeader(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val valueLocal = newLocal(STRING_TYPE)
        val nameLocal = newLocal(STRING_TYPE)
        visitVarInsn(Opcodes.ASTORE, valueLocal)
        visitVarInsn(Opcodes.ASTORE, nameLocal)
        // stack: builder

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ALOAD, nameLocal)
        visitVarInsn(Opcodes.ALOAD, valueLocal)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RUNTIME_OWNER, "recordHeader",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false,
        )
        // stack: builder

        visitVarInsn(Opcodes.ALOAD, nameLocal)
        visitVarInsn(Opcodes.ALOAD, valueLocal)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    // Stack on entry: builder, provider, executor.
    private fun rewriteSetUploadDataProvider(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val executorLocal = newLocal(EXECUTOR_TYPE)
        val providerLocal = newLocal(UPLOAD_PROVIDER_TYPE)
        visitVarInsn(Opcodes.ASTORE, executorLocal)
        visitVarInsn(Opcodes.ASTORE, providerLocal)
        // stack: builder

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ALOAD, providerLocal)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RUNTIME_OWNER, "recordUploadProvider",
            "(Ljava/lang/Object;Ljava/lang/Object;)V", false,
        )
        // stack: builder

        visitVarInsn(Opcodes.ALOAD, providerLocal)
        visitVarInsn(Opcodes.ALOAD, executorLocal)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    // Stack on entry: builder. The builder receiver is consumed by the real call, so
    // it must be stashed before invoking build() in order to still have it available
    // afterward for attachToRequest(builder, request).
    private fun rewriteBuild(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val builderLocal = newLocal(BUILDER_TYPE)
        val requestLocal = newLocal(URL_REQUEST_TYPE)

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ASTORE, builderLocal)
        // stack: builder

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        // stack: request

        visitInsn(Opcodes.DUP)
        visitVarInsn(Opcodes.ASTORE, requestLocal)
        // stack: request (exactly the real call's own return value, untouched from here)

        visitVarInsn(Opcodes.ALOAD, builderLocal)
        visitVarInsn(Opcodes.ALOAD, requestLocal)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RUNTIME_OWNER, "attachToRequest",
            "(Ljava/lang/Object;Lorg/chromium/net/UrlRequest;)V", false,
        )
        // stack: request
    }
}
