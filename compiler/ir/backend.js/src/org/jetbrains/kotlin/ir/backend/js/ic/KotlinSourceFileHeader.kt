/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.ir.backend.js.lower.calls.add
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedOutputStream
import java.util.Collections

@JvmInline
value class KotlinLibraryFile(val path: String) {
    constructor(lib: KotlinLibrary) : this(lib.libraryFile.canonicalPath)

    fun toProtoStream(out: CodedOutputStream) = out.writeStringNoTag(path)

    companion object {
        fun fromProtoStream(input: CodedInputStream) = KotlinLibraryFile(input.readString())
    }
}

@JvmInline
value class KotlinSourceFile(val path: String) {
    constructor(irFile: IrFile) : this(irFile.fileEntry.name)

    fun toProtoStream(out: CodedOutputStream) = out.writeStringNoTag(path)

    companion object {
        fun fromProtoStream(input: CodedInputStream) = KotlinSourceFile(input.readString())
    }
}

open class KotlinSourceFileMap<out T>(files: Map<KotlinLibraryFile, Map<KotlinSourceFile, T>>) :
    Map<KotlinLibraryFile, Map<KotlinSourceFile, T>> by files {

    fun libFiles(libFile: KotlinLibraryFile) = getOrDefault(libFile, emptyMap())

    inline fun forEachFile(f: (KotlinLibraryFile, KotlinSourceFile, T) -> Unit) =
        forEach { (lib, files) -> files.forEach { (file, data) -> f(lib, file, data) } }

    operator fun get(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile): T? = get(libFile)?.get(sourceFile)
}

class KotlinSourceFileMutableMap<T>(
    private val files: MutableMap<KotlinLibraryFile, MutableMap<KotlinSourceFile, T>> = mutableMapOf()
) : KotlinSourceFileMap<T>(files) {

    operator fun set(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile, data: T) =
        files.getOrPut(libFile) { mutableMapOf() }.put(sourceFile, data)

    fun consumeFiles(other: KotlinSourceFileMap<T>) {
        other.forEachFile { libFile, srcFile, data ->
            set(libFile, srcFile, data)
        }
    }

    fun removeFile(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile) {
        val libFiles = files[libFile]
        if (libFiles != null) {
            libFiles.remove(sourceFile)
            if (libFiles.isEmpty()) {
                files.remove(libFile)
            }
        }
    }
}

fun <T> KotlinSourceFileMap<T>.toMutable(): KotlinSourceFileMutableMap<T> {
    return KotlinSourceFileMutableMap(entries.associateTo(mutableMapOf()) { it.key to it.value.toMutableMap() })
}

interface KotlinSourceFileMetadata {
    val inverseDependencies: KotlinSourceFileMap<Set<IdSignature>>
    val directDependencies: KotlinSourceFileMap<Set<IdSignature>>
}

fun KotlinSourceFileMetadata.isEmpty() = inverseDependencies.isEmpty() && directDependencies.isEmpty()
