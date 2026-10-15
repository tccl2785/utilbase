package io.github.tccl2785.tools.load.dual

import io.github.tccl2785.tools.load.dual.suspend.SuspendMethodInvoker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.cancellation.CancellationException
import java.util.function.Consumer as JConsumer
import java.util.function.Function as JFunction

/**
 * Dual phase load orchestrator.
 *
 * launch():
 * - Synchronous entry.
 * - Designed for Java and normal JVM methods.
 * - Does not execute Kotlin suspend methods.
 * - If any suspend method is found, it fails before executing anything.
 *
 * launchSuspend():
 * - Kotlin suspend entry.
 * - Supports normal methods and Kotlin suspend methods mixed together.
 * - Suspend methods are supported in LoadMode.NONE.
 * - In LoadMode.NONE, target can be Kotlin object instance or normal bean instance.
 */
class DualPhaseOrchestrator {

    private val loadItems: MutableList<LoadItem> = ArrayList()

    private val initializationContext: InitializationContext

    constructor() : this(null, null)

    constructor(
        beanResolver: JFunction<Class<*>, Any?>?,
        beanPublisher: JConsumer<Any?>?
    ) {
        @Suppress("UNCHECKED_CAST")
        this.initializationContext = DefaultInitializationContext(
            beanResolver as JFunction<Class<*>, Any?>?,
            beanPublisher as JConsumer<Any>?
        )
    }

    /**
     * Register one load item.
     */
    fun register(loadItem: LoadItem): DualPhaseOrchestrator {
        loadItems.add(loadItem)
        return this
    }

    /**
     * Register multiple load items.
     */
    fun register(vararg items: LoadItem): DualPhaseOrchestrator {
        for (item in items) {
            register(item)
        }
        return this
    }

    /**
     * Register multiple load items.
     */
    fun register(items: Collection<LoadItem>): DualPhaseOrchestrator {
        for (item in items) {
            register(item)
        }
        return this
    }

    /**
     * Synchronous launch.
     *
     * This method is intentionally friendly to pure Java usage.
     *
     * It will not call suspend methods through runBlocking or any coroutine bridge.
     * If registered classes contain suspend load methods, this method fails before
     * executing any load method.
     */
    fun launch() {
        val plan = createPlan()

        val suspendMethod = plan.allMethods.firstOrNull { it.suspendMethod }
        if (suspendMethod != null) {
            throw IllegalStateException(
                "Suspend method [${suspendMethod.method.declaringClass.name}#${suspendMethod.method.name}] " +
                        "cannot be executed by launch(). Use launchSuspend() instead."
            )
        }

        for (metadata in plan.loadStageMethods.sortedBy { it.order }) {
            invokeRegular(metadata, "LoadStage")
        }

        for (metadata in plan.onTypeMethods.sortedBy { it.order }) {
            if (matchesOnType(metadata)) {
                invokeRegular(metadata, "OnType")
            }
        }
    }

    /**
     * Suspend launch.
     *
     * This method does not create CoroutineScope, does not switch dispatcher,
     * and does not use runBlocking.
     *
     * The coroutine context is inherited from caller.
     */
    suspend fun launchSuspend() {
        val plan = createPlan()

        for (metadata in plan.loadStageMethods.sortedBy { it.order }) {
            invokeSuspendAware(metadata, "LoadStage")
        }

        for (metadata in plan.onTypeMethods.sortedBy { it.order }) {
            if (matchesOnType(metadata)) {
                invokeSuspendAware(metadata, "OnType")
            }
        }
    }

    /**
     * Create full execution plan.
     *
     * Important:
     * launch() and launchSuspend() both scan and validate all methods before
     * executing any method.
     */
    private fun createPlan(): ExecutionPlan {
        val loadStageMethods = ArrayList<InvocationMetadata>()
        val onTypeMethods = ArrayList<InvocationMetadata>()

        for (item in loadItems) {
            if (!getLoadItemEnable(item)) {
                continue
            }

            val hostClass = getLoadItemClass(item)
            val mode = getLoadItemMode(item)
            val providedTarget = getLoadItemObj(item)

            val resolved = resolveTargetAndScanClass(
                hostClass = hostClass,
                providedTarget = providedTarget,
                mode = mode
            )

            val target = resolved.target
            val scanClass = resolved.scanClass

            for (method in scanClass.declaredMethods) {
                val loadStage = method.getAnnotation(LoadStage::class.java)
                val onType = method.getAnnotation(OnType::class.java)

                if (loadStage == null && onType == null) {
                    continue
                }

                val suspendMethod = SuspendMethodInvoker.isSuspendMethod(method)

                validateMethod(
                    hostClass = hostClass,
                    scanClass = scanClass,
                    target = target,
                    mode = mode,
                    method = method,
                    suspendMethod = suspendMethod
                )

                method.isAccessible = true

                if (loadStage != null) {
                    val order = annotationInt(loadStage, "order", 0)
                    val ignoreException = annotationBoolean(loadStage, "ignoreException", false)

                    loadStageMethods.add(
                        InvocationMetadata(
                            hostClass = hostClass,
                            scanClass = scanClass,
                            method = method,
                            target = target,
                            mode = mode,
                            order = order,
                            ignoreException = ignoreException,
                            suspendMethod = suspendMethod,
                            onTypeClass = null
                        )
                    )
                }

                if (onType != null) {
                    val order = annotationInt(onType, "order", 0)
                    val ignoreException = annotationBoolean(onType, "ignoreException", false)
                    val type = annotationClass(
                        annotation = onType,
                        names = arrayOf("value", "type", "classz", "clazz"),
                        defaultValue = Any::class.java
                    )

                    onTypeMethods.add(
                        InvocationMetadata(
                            hostClass = hostClass,
                            scanClass = scanClass,
                            method = method,
                            target = target,
                            mode = mode,
                            order = order,
                            ignoreException = ignoreException,
                            suspendMethod = suspendMethod,
                            onTypeClass = type
                        )
                    )
                }
            }
        }

        return ExecutionPlan(
            loadStageMethods = loadStageMethods,
            onTypeMethods = onTypeMethods
        )
    }

    /**
     * Resolve target object and actual class used for scanning methods.
     */
    private fun resolveTargetAndScanClass(
        hostClass: Class<*>,
        providedTarget: Any?,
        mode: LoadMode
    ): ResolvedTarget {
        return when (mode) {
            LoadMode.NONE -> {
                val target = resolveNoneTarget(hostClass, providedTarget)
                ResolvedTarget(
                    target = target,
                    /*
                     * 这里使用 hostClass，而不是 target.javaClass。
                     *
                     * 原因：
                     * 1. Spring Bean 可能是 CGLIB 代理类。
                     * 2. @LoadStage / @OnType 往往声明在原始类上。
                     * 3. private 方法不会被代理子类继承成 declaredMethods。
                     *
                     * 因此扫描 hostClass 更稳定。
                     */
                    scanClass = hostClass
                )
            }

            LoadMode.STATIC -> {
                ResolvedTarget(
                    target = null,
                    scanClass = hostClass
                )
            }

            LoadMode.COMPANION -> {
                val target = resolveCompanionTarget(hostClass, providedTarget)
                ResolvedTarget(
                    target = target,
                    scanClass = target.javaClass
                )
            }

            LoadMode.DISCOVER_ONLY ->      ResolvedTarget(
                target = null,
                scanClass = hostClass
            )
        }
    }
    /**
     * NONE mode:
     *
     * - If obj is provided, use it directly.
     * - If obj is null and class is Kotlin object, use INSTANCE.
     * - If obj is null and class is normal Java/Kotlin class, fail.
     */
    private fun resolveNoneTarget(
        hostClass: Class<*>,
        providedTarget: Any?
    ): Any {
        if (providedTarget != null) {
            if (!hostClass.isInstance(providedTarget)) {
                throw IllegalStateException(
                    "LoadItem.obj type [${providedTarget.javaClass.name}] is not compatible with " +
                            "LoadItem.class [${hostClass.name}]."
                )
            }

            return providedTarget
        }

        return getKotlinObjectInstanceOrNull(hostClass)
            ?: throw IllegalStateException(
                "LoadItem.obj cannot be null in LoadMode.NONE for class [${hostClass.name}]. " +
                        "Only Kotlin object can be resolved automatically. " +
                        "For normal class, provide an instance or Spring bean."
            )
    }

    /**
     * COMPANION mode:
     *
     * - If obj is provided, use it directly.
     * - If obj is null, use static field Companion.
     */
    private fun resolveCompanionTarget(
        hostClass: Class<*>,
        providedTarget: Any?
    ): Any {
        if (providedTarget != null) {
            return providedTarget
        }

        return try {
            val field = hostClass.getDeclaredField("Companion")
            if (!Modifier.isStatic(field.modifiers)) {
                throw IllegalStateException(
                    "Field [${hostClass.name}#Companion] is not static."
                )
            }

            field.isAccessible = true
            field.get(null)
                ?: throw IllegalStateException(
                    "Companion object of class [${hostClass.name}] is null."
                )
        } catch (exception: NoSuchFieldException) {
            throw IllegalStateException(
                "Cannot find Companion object for class [${hostClass.name}].",
                exception
            )
        } catch (exception: IllegalAccessException) {
            throw IllegalStateException(
                "Cannot access Companion object for class [${hostClass.name}].",
                exception
            )
        }
    }

    private fun getKotlinObjectInstanceOrNull(hostClass: Class<*>): Any? {
        return try {
            val field = hostClass.getDeclaredField("INSTANCE")
            if (!Modifier.isStatic(field.modifiers)) {
                return null
            }

            field.isAccessible = true
            val value = field.get(null)

            if (value != null && hostClass.isInstance(value)) {
                value
            } else {
                null
            }
        } catch (_: NoSuchFieldException) {
            null
        } catch (_: IllegalAccessException) {
            null
        }
    }

    /**
     * Validate method signature and mode.
     */
    private fun validateMethod(
        hostClass: Class<*>,
        scanClass: Class<*>,
        target: Any?,
        mode: LoadMode,
        method: Method,
        suspendMethod: Boolean
    ) {
        if (suspendMethod) {
            validateSuspendMethod(
                hostClass = hostClass,
                scanClass = scanClass,
                target = target,
                mode = mode,
                method = method
            )
        } else {
            validateRegularMethod(
                hostClass = hostClass,
                mode = mode,
                method = method
            )
        }
    }

    private fun validateRegularMethod(
        hostClass: Class<*>,
        mode: LoadMode,
        method: Method
    ) {
        if (method.parameterCount != 1 ||
            !InitializationContext::class.java.isAssignableFrom(method.parameterTypes[0])
        ) {
            throw IllegalStateException(
                "Method [${method.declaringClass.name}#${method.name}] must have exactly one " +
                        "InitializationContext argument."
            )
        }

        if (mode == LoadMode.STATIC && !Modifier.isStatic(method.modifiers)) {
            throw IllegalStateException(
                "Method [${hostClass.name}#${method.name}] must be static in LoadMode.STATIC."
            )
        }
    }

    /**
     * Validate Kotlin suspend method.
     *
     * 修改点：
     *
     * 原先这里强制要求 suspend 方法只能写在 Kotlin object 里。
     * 现在改为：
     *
     * 1. suspend 方法只支持 LoadMode.NONE。
     * 2. LoadMode.NONE 下必须有 target。
     * 3. target 可以是：
     *    - Kotlin object 的 INSTANCE
     *    - Spring Bean 实例
     *    - 手动传入的普通实例
     *    - Spring CGLIB 代理实例
     */
    private fun validateSuspendMethod(
        hostClass: Class<*>,
        scanClass: Class<*>,
        target: Any?,
        mode: LoadMode,
        method: Method
    ) {
        if (mode != LoadMode.NONE) {
            throw IllegalStateException(
                "Suspend method [${method.declaringClass.name}#${method.name}] is only supported " +
                        "in LoadMode.NONE."
            )
        }

        if (target == null) {
            throw IllegalStateException(
                "Suspend method [${method.declaringClass.name}#${method.name}] requires a target instance " +
                        "in LoadMode.NONE."
            )
        }

        if (!hostClass.isInstance(target)) {
            throw IllegalStateException(
                "Target type [${target.javaClass.name}] is not compatible with suspend method host class " +
                        "[${hostClass.name}]."
            )
        }

        /*
         * Kotlin suspend 方法编译到 JVM 后参数会变成：
         *
         * private final Object test2(
         *     InitializationContext context,
         *     Continuation continuation
         * )
         *
         * 所以反射看到的 parameterCount 是 2。
         */
        if (method.parameterCount != 2 ||
            !InitializationContext::class.java.isAssignableFrom(method.parameterTypes[0])
        ) {
            throw IllegalStateException(
                "Suspend method [${method.declaringClass.name}#${method.name}] must have exactly " +
                        "one InitializationContext argument."
            )
        }

        /*
         * 正常情况下 NONE 模式下 scanClass 应该就是 hostClass。
         *
         * 这里保留校验，防止未来其他扫描逻辑传入不相关 class。
         */
        if (scanClass != hostClass) {
            throw IllegalStateException(
                "Invalid scan class [${scanClass.name}] for suspend method [${method.name}], " +
                        "expected host class [${hostClass.name}]."
            )
        }
    }

    private fun invokeRegular(
        metadata: InvocationMetadata,
        phaseName: String
    ) {
        try {
            SuspendMethodInvoker.invokeRegular(
                method = metadata.method,
                target = metadata.target,
                context = initializationContext
            )
        } catch (throwable: Throwable) {
            handleInvocationException(
                metadata = metadata,
                phaseName = phaseName,
                throwable = throwable
            )
        }
    }

    private suspend fun invokeSuspendAware(
        metadata: InvocationMetadata,
        phaseName: String
    ) {
        try {
            if (metadata.suspendMethod) {
                SuspendMethodInvoker.invokeSuspend(
                    method = metadata.method,
                    target = metadata.target,
                    context = initializationContext
                )
            } else {
                SuspendMethodInvoker.invokeRegular(
                    method = metadata.method,
                    target = metadata.target,
                    context = initializationContext
                )
            }
        } catch (throwable: Throwable) {
            handleInvocationException(
                metadata = metadata,
                phaseName = phaseName,
                throwable = throwable
            )
        }
    }

    private fun handleInvocationException(
        metadata: InvocationMetadata,
        phaseName: String,
        throwable: Throwable
    ) {
        val cause = SuspendMethodInvoker.unwrap(throwable)

        if (cause is CancellationException) {
            throw cause
        }

        if (isFatal(cause)) {
            throw cause
        }

        if (!metadata.ignoreException) {
            throw OrchestrationException(
                "Failed to execute $phaseName method " +
                        "[${metadata.method.declaringClass.name}#${metadata.method.name}].",
                cause
            )
        }

        System.err.println(
            "Ignored exception while executing $phaseName method " +
                    "[${metadata.method.declaringClass.name}#${metadata.method.name}]: " +
                    "${cause.javaClass.name}: ${cause.message}"
        )
    }

    private fun isFatal(throwable: Throwable): Boolean {
        return throwable is VirtualMachineError ||
                throwable is ThreadDeath ||
                throwable is LinkageError
    }

    /**
     * Check whether @OnType target exists in InitializationContext.
     */
    private fun matchesOnType(metadata: InvocationMetadata): Boolean {
        val type = metadata.onTypeClass ?: return true

        if (type == Any::class.java) {
            return true
        }

        val context = initializationContext

        val booleanMethodNames = arrayOf(
            "contains",
            "containsType",
            "containsBean",
            "has",
            "hasType",
            "hasBean",
            "exists",
            "existsType"
        )

        for (name in booleanMethodNames) {
            val result = tryInvokeContextBooleanMethod(context, name, type)
            if (result != null) {
                return result
            }
        }

        val getterMethodNames = arrayOf(
            "get",
            "getBean",
            "getByType",
            "require",
            "requireBean",
            "requireByType"
        )

        for (name in getterMethodNames) {
            val result = tryInvokeContextGetterMethod(context, name, type)
            if (result != UnknownContextValue) {
                return result != null
            }
        }

        return true
    }

    private fun tryInvokeContextBooleanMethod(
        context: InitializationContext,
        methodName: String,
        type: Class<*>
    ): Boolean? {
        return try {
            val method = context.javaClass.methods.firstOrNull {
                it.name == methodName &&
                        it.parameterCount == 1 &&
                        Class::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        (
                                it.returnType == Boolean::class.javaPrimitiveType ||
                                        it.returnType == java.lang.Boolean::class.java
                                )
            } ?: return null

            method.isAccessible = true
            method.invoke(context, type) as Boolean
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryInvokeContextGetterMethod(
        context: InitializationContext,
        methodName: String,
        type: Class<*>
    ): Any? {
        return try {
            val method = context.javaClass.methods.firstOrNull {
                it.name == methodName &&
                        it.parameterCount == 1 &&
                        Class::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return UnknownContextValue

            method.isAccessible = true
            method.invoke(context, type)
        } catch (_: Throwable) {
            UnknownContextValue
        }
    }

    private fun getLoadItemClass(item: LoadItem): Class<*> {
        return item.classz
    }

    private fun getLoadItemObj(item: LoadItem): Any? {
        return item.obj
    }

    private fun getLoadItemMode(item: LoadItem): LoadMode {
        return item.mode
    }

    private fun getLoadItemEnable(item: LoadItem): Boolean {
        return item.isEnable
    }

    private fun annotationInt(
        annotation: Annotation,
        name: String,
        defaultValue: Int
    ): Int {
        return try {
            val method = annotation.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return defaultValue

            val value = method.invoke(annotation)
            if (value is Int) {
                value
            } else {
                defaultValue
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun annotationBoolean(
        annotation: Annotation,
        name: String,
        defaultValue: Boolean
    ): Boolean {
        return try {
            val method = annotation.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return defaultValue

            val value = method.invoke(annotation)
            if (value is Boolean) {
                value
            } else {
                defaultValue
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun annotationClass(
        annotation: Annotation,
        names: Array<String>,
        defaultValue: Class<*>
    ): Class<*> {
        for (name in names) {
            try {
                val method = annotation.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0
                } ?: continue

                val value = method.invoke(annotation)
                if (value is Class<*>) {
                    return value
                }
            } catch (_: Throwable) {
                // Try next name.
            }
        }

        return defaultValue
    }

    private data class ExecutionPlan(
        val loadStageMethods: List<InvocationMetadata>,
        val onTypeMethods: List<InvocationMetadata>
    ) {
        val allMethods: List<InvocationMetadata>
            get() = loadStageMethods + onTypeMethods
    }

    private data class InvocationMetadata(
        val hostClass: Class<*>,
        val scanClass: Class<*>,
        val method: Method,
        val target: Any?,
        val mode: LoadMode,
        val order: Int,
        val ignoreException: Boolean,
        val suspendMethod: Boolean,
        val onTypeClass: Class<*>?
    )

    private data class ResolvedTarget(
        val target: Any?,
        val scanClass: Class<*>
    )

    private object UnknownContextValue
}