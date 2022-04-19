/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tooling.core

@Suppress("unchecked_cast")
internal class MutableExtrasImpl(
    initialEntries: Iterable<Extras.Entry<*>> = emptyList()
) : MutableExtras, AbstractIterableExtras() {

    private val extras: MutableMap<Extras.Key<*>, Extras.Entry<*>> =
        initialEntries.associateByTo(mutableMapOf()) { it.key }

    override val keys: Set<Extras.Key<*>>
        get() = extras.keys

    override val entries: Set<Extras.Entry<*>>
        get() = extras.values.toSet()

    override val size: Int
        get() = extras.size

    override fun isEmpty(): Boolean = extras.isEmpty()

    override fun <T : Any> set(key: Extras.Key<T>, value: T): T? {
        return put(Extras.Entry(key, value))
    }

    override fun <T : Any> put(entry: Extras.Entry<T>): T? {
        return extras.put(entry.key, entry)?.let { it.value as T }
    }

    override fun putAll(from: Iterable<Extras.Entry<*>>) {
        this.extras.putAll(from.associateBy { it.key })
    }

    override fun <T : Any> get(key: Extras.Key<T>): T? {
        return extras[key]?.let { it.value as T }
    }

    override fun <T : Any> remove(key: Extras.Key<T>): T? {
        return extras.remove(key)?.let { it.value as T }
    }

    override fun clear() {
        extras.clear()
    }
}

@Suppress("unchecked_cast")
internal class ImmutableExtrasImpl private constructor(
    private val extras: Map<Extras.Key<*>, Extras.Entry<*>>
) : AbstractIterableExtras() {
    constructor(extras: Iterable<Extras.Entry<*>>) : this(extras.associateBy { it.key })

    constructor(extras: Array<out Extras.Entry<*>>) : this(extras.associateBy { it.key })

    override val keys: Set<Extras.Key<*>> = extras.keys

    override fun isEmpty(): Boolean = extras.isEmpty()

    override val size: Int = extras.size

    override val entries: Set<Extras.Entry<*>> = extras.values.toSet()

    override fun <T : Any> get(key: Extras.Key<T>): T? {
        return extras[key]?.let { it.value as T }
    }
}

abstract class AbstractIterableExtras : IterableExtras {

    override val size: Int get() = entries.size

    override fun isEmpty(): Boolean = entries.isEmpty()

    override fun contains(element: Extras.Entry<*>): Boolean =
        entries.contains(element)

    override fun containsAll(elements: Collection<Extras.Entry<*>>): Boolean =
        entries.containsAll(elements)

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is IterableExtras) return false
        if (other.entries != this.entries) return false
        return true
    }

    override fun hashCode(): Int {
        return 31 * entries.hashCode()
    }

    override fun toString(): String {
        return "Extras($entries)"
    }
}

internal object EmptyExtras : AbstractIterableExtras() {
    override val size: Int = 0
    override val keys: Set<Extras.Key<*>> = emptySet()
    override val entries: Set<Extras.Entry<*>> = emptySet()
    override fun isEmpty(): Boolean = true
    override fun <T : Any> get(key: Extras.Key<T>): T? = null
}
