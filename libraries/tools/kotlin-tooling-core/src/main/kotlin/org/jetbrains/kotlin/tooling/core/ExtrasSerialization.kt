/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tooling.core

interface SerializedExtras {
    class SerializedEntry(val key: Extras.Key<*>, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SerializedEntry
            if (key != other.key) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = key.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    operator fun get(key: Extras.Key<*>): ByteArray?
}

fun IterableExtras.serialize(service: ExtrasSerializationService): SerializedExtras {
    entries.mapNotNull { entry -> service.serializeOrNull(entry) ?: return@mapNotNull }
}

private fun <T : Any> ExtrasSerializationService.serializeOrNull(entry: Extras.Entry<T>): SerializedExtras.SerializedEntry? {
    return SerializedExtras.SerializedEntry(entry.key, getSerializer(entry.key)?.serialize(entry.value) ?: return null)
}
