package com.jitinsharma.cronetinspector.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
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
 */
abstract class CronetCallbackHookVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (classData.className.startsWith("org.chromium.net.")) return false
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
                        // Deliberately an EXIT hook, not entry: unlike onReadCompleted
                        // (where Cronet has already filled the buffer before invoking
                        // the app's callback), UploadDataProvider.read()'s own method
                        // BODY is what writes the upload bytes -- at method entry the
                        // buffer is still empty. `this` (the provider instance) is the
                        // hook's correlation key here, unlike the Callback methods
                        // below where `this` is the callback object, not the request.
                        override fun onMethodExit(opcode: Int) {
                            if (opcode != Opcodes.RETURN) return // skip exceptional exits (ATHROW)
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
