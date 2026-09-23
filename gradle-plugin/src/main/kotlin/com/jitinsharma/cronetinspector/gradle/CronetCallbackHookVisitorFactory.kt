package com.jitinsharma.cronetinspector.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

private const val URL_REQUEST_CALLBACK = "org.chromium.net.UrlRequest\$Callback"
private const val UPLOAD_DATA_PROVIDER = "org.chromium.net.UploadDataProvider"

private const val UPLOAD_READ_NAME = "read"
private const val UPLOAD_READ_DESC = "(Lorg/chromium/net/UploadDataSink;Ljava/nio/ByteBuffer;)V"
private const val UPLOAD_READ_HOOK_DESC =
    "(Ljava/lang/Object;Lorg/chromium/net/UploadDataSink;Ljava/nio/ByteBuffer;)V"

private const val UPLOAD_DATA_SINK = "org/chromium/net/UploadDataSink"
private const val ON_READ_SUCCEEDED = "onReadSucceeded"

/**
 * (method name, method descriptor) -> hook name on CronetInspectorRuntime. The
 * runtime's hook methods were deliberately given identical parameter shapes to the
 * Cronet callback methods they mirror (see runtime's CronetInspectorRuntime.kt), so
 * the injected call reuses the SAME descriptor as the method being instrumented --
 * name+descriptor together disambiguate onResponseStarted/onSucceeded/onCanceled,
 * which otherwise share one descriptor.
 */
private val CALLBACK_HOOK_SIGNATURES: Set<Pair<String, String>> = setOf(
    "onRedirectReceived" to
        "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;Ljava/lang/String;)V",
    "onResponseStarted" to "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;)V",
    "onReadCompleted" to
        "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;Ljava/nio/ByteBuffer;)V",
    "onSucceeded" to "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;)V",
    "onFailed" to
        "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;Lorg/chromium/net/CronetException;)V",
    "onCanceled" to "(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;)V",
)

/**
 * Entry-hooks every UrlRequest.Callback/UploadDataProvider subclass reachable at
 * ALL scope (see CronetInspectorPlugin): the app's own classes (named or anonymous),
 * PLUS any statically-compiled library dependency's own subclasses -- e.g.
 * com.google.net.cronet:cronet-okhttp's OkHttpBridgeRequestCallback/
 * StreamingUploadDataProvider, confirmed by inspecting its compiled classes when a
 * real app (httpbench) that routes Cronet through it produced zero captured events
 * under PROJECT scope.
 *
 * Deliberately excludes classes in Cronet's OWN `org.chromium.net` package tree,
 * for two separate reasons found the hard way against real apps/builds:
 *  - play-services-cronet's actual impl classes are loaded dynamically at runtime
 *    and are unreachable at compile-time instrumentation regardless of scope (see
 *    plan's "Interception design").
 *  - cronet-embedded ships its own internal helper classes that also happen to
 *    extend UploadDataProvider (e.g. org.chromium.net.internal.zzey) -- these are
 *    heavily minified/obfuscated, and injecting bytecode into one broke D8's
 *    dexer outright ("Cannot constrain type... by constraint: INT"), the same
 *    class of R8/minification fragility that caused a real production SIGSEGV in
 *    Sentry's Android SDK when it instrumented library-internal classes.
 *
 * Originally validated by Milestone 1's detection spike (which this class
 * supersedes with real bytecode rewriting).
 *
 * isInstrumentable applies the same project-namespace-or-Cronet-related pre-filter
 * as [CronetCallSiteVisitorFactory] (see its doc) BEFORE looking at
 * [ClassData.superClasses] -- computing that full superclass chain is real,
 * non-trivial work AGP has to do for every candidate class, and no unrelated
 * dependency (AndroidX, Compose, Kotlin stdlib, ...) has ever been found defining a
 * `UrlRequest.Callback`/`UploadDataProvider` subclass, so it's safe to skip that
 * computation entirely for classes the pre-filter already rules out.
 */
abstract class CronetCallbackHookVisitorFactory :
    AsmClassVisitorFactory<CronetCallSiteParams> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (className.startsWith("org.chromium.net.")) return false
        if (!isProjectOrCronetRelated(className, parameters.get().projectNamespace.orNull)) return false
        return classData.superClasses.any { it == URL_REQUEST_CALLBACK || it == UPLOAD_DATA_PROVIDER }
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        val isUploadProvider =
            classContext.currentClassData.superClasses.any { it == UPLOAD_DATA_PROVIDER }

        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

                if (isUploadProvider && name == UPLOAD_READ_NAME && descriptor == UPLOAD_READ_DESC) {
                    return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        // NOT an exit hook (an earlier version of this was): confirmed
                        // live against a real device that Cronet consumes/resets the
                        // upload buffer SYNCHRONOUSLY, as a side effect of the app's own
                        // call to sink.onReadSucceeded() -- by the time read() actually
                        // *returns*, the buffer's position was already back to 0 (looked
                        // already-empty, "before writing" state) even though the app had
                        // genuinely just written real bytes into it moments earlier.
                        // Every real request's captured body came back empty because of
                        // this.
                        //
                        // Instead, this intercepts calls the app's own read()
                        // implementation makes to sink.onReadSucceeded(..) and injects
                        // our capture call immediately BEFORE forwarding to the real
                        // one -- i.e. right after the app has finished writing to the
                        // buffer, but strictly before Cronet gets any chance to touch
                        // it. `loadThis()`/`loadArgs()` here load read()'s OWN `this`
                        // (the provider) and its OWN (sink, byteBuffer) parameters --
                        // correct regardless of what's on the operand stack at the
                        // onReadSucceeded call site itself, and correct even if the sink
                        // reference at that call site went through a local variable
                        // (loadArgs() always reflects the enclosing method's declared
                        // parameters, not whatever's on the stack).
                        //
                        // Only onReadSucceeded, not onReadError: on error there's no
                        // guarantee the buffer holds valid/complete intended data.
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            calledName: String,
                            calledDescriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (owner == UPLOAD_DATA_SINK && calledName == ON_READ_SUCCEEDED) {
                                loadThis()
                                loadArgs()
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    RUNTIME_OWNER,
                                    "onUploadRead",
                                    UPLOAD_READ_HOOK_DESC,
                                    false,
                                )
                            }
                            super.visitMethodInsn(opcode, owner, calledName, calledDescriptor, isInterface)
                        }
                    }
                }

                if ((name to descriptor) !in CALLBACK_HOOK_SIGNATURES) return mv

                return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                    override fun onMethodEnter() {
                        loadArgs()
                        visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_OWNER, name, descriptor, false)
                    }
                }
            }
        }
    }
}
