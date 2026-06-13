package com.arashivision.sdk.demo.util

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

object ViewBindingUtils {

    // Кэш inflate-методов per binding-класс: getMethod() дорог и вызывается на каждый
    // onCreateViewHolder. Ключ — (binding class, withViewGroup).
    private val inflateWithGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val inflateNoGroupCache = ConcurrentHashMap<Class<*>, Method>()

    @Suppress("UNCHECKED_CAST")
    fun <T> createBinding(cls: Class<*>, layoutInflater: LayoutInflater?, index: Int, viewGroup: ViewGroup?): T {
        try {
            val tClass = getParameterizedTypeClass(cls, index)
            return viewGroup?.let {
                val method = inflateWithGroupCache.getOrPut(tClass) {
                    tClass.getMethod(
                        "inflate",
                        LayoutInflater::class.java,
                        ViewGroup::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                }
                method.invoke(null, layoutInflater, viewGroup, false) as T
            } ?: run {
                val method = inflateNoGroupCache.getOrPut(tClass) {
                    tClass.getMethod("inflate", LayoutInflater::class.java)
                }
                method.invoke(null, layoutInflater) as T
            }
        } catch (e: NoSuchMethodException) {
            throw RuntimeException("ViewBinding inflate method not found for $cls[$index]", e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException("ViewBinding inflate failed for $cls[$index]", e)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("ViewBinding inflate inaccessible for $cls[$index]", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewModel> createViewModel(owner: ViewModelStoreOwner, index: Int): T {
        try {
            val tClass = getParameterizedTypeClass(owner.javaClass, index) as Class<T>
            return ViewModelProvider(owner)[tClass]
        } catch (e: Exception) {
            // Сохраняем cause/stacktrace (раньше терялось через RuntimeException(e.message)).
            throw RuntimeException("createViewModel failed for ${owner.javaClass}[$index]", e)
        }
    }

    private fun getParameterizedTypeClass(cls: Class<*>, index: Int): Class<*> {
        val generic = cls.genericSuperclass
        check(generic is ParameterizedType) {
            "Expected ${cls.name} to have a parameterized superclass, got $generic"
        }
        val args = generic.actualTypeArguments
        require(index in args.indices) {
            "Type argument index $index out of bounds for ${cls.name} (has ${args.size})"
        }
        val arg = args[index]
        check(arg is Class<*>) { "Type argument $index of ${cls.name} is not a Class: $arg" }
        return arg
    }
}
