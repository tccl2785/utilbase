package io.github.tccl2785.tools.load.dual.suspend



import io.github.tccl2785.tools.load.dual.InitializationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal object SuspendMethodInvoker {

    fun isSuspendMethod(method: Method): Boolean {
        val parameterTypes = method.parameterTypes

        return method.returnType == Any::class.java &&
                parameterTypes.isNotEmpty() &&
                Continuation::class.java.isAssignableFrom(parameterTypes.last())
    }

    fun invokeRegular(
        method: Method,
        target: Any?,
        context: InitializationContext
    ) {
        makeAccessible(method)
        try {
            method.invoke(target, context)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }

    suspend fun invokeSuspend(
        method: Method,
        target: Any?,
        context: InitializationContext
    ) {
        require(isSuspendMethod(method)) {
            "Method [${method.declaringClass.name}#${method.name}] is not a suspend method."
        }

        makeAccessible(method)

        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = try {
                method.invoke(target, context, continuation)
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }

            if (result === COROUTINE_SUSPENDED) {
                COROUTINE_SUSPENDED
            } else {
                Unit
            }
        }
    }

    fun unwrap(throwable: Throwable): Throwable {
        var current = throwable

        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }

        return current
    }

    private fun makeAccessible(method: Method) {
        if (!method.isAccessible) {
            method.isAccessible = true
        }
    }
}