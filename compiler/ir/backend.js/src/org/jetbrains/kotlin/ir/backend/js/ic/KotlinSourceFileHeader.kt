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
value class KotlinSourceFile(val path: String) {
    constructor(irFile: IrFile) : this(irFile.fileEntry.name)

    fun toProtoStream(out: CodedOutputStream) = out.writeStringNoTag(path)

    companion object {
        fun fromProtoStream(input: CodedInputStream) = KotlinSourceFile(input.readString())
    }
}

@JvmInline
value class KotlinLibraryFile(val path: String) {
    constructor(lib: KotlinLibrary) : this(lib.libraryFile.canonicalPath)

    fun toProtoStream(out: CodedOutputStream) = out.writeStringNoTag(path)

    companion object {
        fun fromProtoStream(input: CodedInputStream) = KotlinLibraryFile(input.readString())
    }
}

interface KotlinSourceFileMetadata

interface KotlinSourceFileExports : KotlinSourceFileMetadata {
    val exportedSignatures: Collection<IdSignature>
}

interface KotlinSourceFileImports : KotlinSourceFileMetadata {
    val importedSignatures: Collection<IdSignature>
}

interface KotlinSourceFileDepends : KotlinSourceFileMetadata {
    val dependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
    val reverseDependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
}

interface KotlinSourceFileHeader : KotlinSourceFileExports, KotlinSourceFileImports, KotlinSourceFileDepends

fun KotlinSourceFileHeader.isEmpty(): Boolean {
    return importedSignatures.isEmpty() && exportedSignatures.isEmpty() && dependencies.isEmpty() && reverseDependencies.isEmpty()
}

class KotlinSourceFileExportsImpl(override val exportedSignatures: Collection<IdSignature>) : KotlinSourceFileExports

class KotlinSourceFileDependsImpl(
    override val dependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>,
    override val reverseDependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
) : KotlinSourceFileDepends

class KotlinSourceFileHeaderImpl(
    override val exportedSignatures: Collection<IdSignature>,
    override val importedSignatures: Collection<IdSignature>,

    override val dependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>,
    override val reverseDependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
) : KotlinSourceFileHeader

object KotlinSourceFileNonExistentHeader : KotlinSourceFileHeader {
    override val exportedSignatures = emptyList<IdSignature>()
    override val importedSignatures = emptyList<IdSignature>()

    override val dependencies = emptyMap<KotlinLibraryFile, Collection<KotlinSourceFile>>()
    override val reverseDependencies = emptyMap<KotlinLibraryFile, Collection<KotlinSourceFile>>()
}

open class KotlinSourceFileMap<out T : KotlinSourceFileMetadata>(files: Map<KotlinLibraryFile, Map<KotlinSourceFile, T>>) :
    Map<KotlinLibraryFile, Map<KotlinSourceFile, T>> by files {

    fun libFiles(libFile: KotlinLibraryFile) = getOrDefault(libFile, emptyMap())

    inline fun forEachFile(f: (KotlinLibraryFile, KotlinSourceFile, T) -> Unit) =
        forEach { (lib, files) -> files.forEach { (file, data) -> f(lib, file, data) } }
}

class KotlinSourceFileMutableMap<T : KotlinSourceFileMetadata>(
    private val files: MutableMap<KotlinLibraryFile, MutableMap<KotlinSourceFile, T>> = mutableMapOf()
) : KotlinSourceFileMap<T>(files) {

    operator fun set(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile, data: T) =
        files.getOrPut(libFile) { mutableMapOf() }.put(sourceFile, data)

    operator fun get(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile): T? = files[libFile]?.get(sourceFile)

    fun consumeFiles(other: KotlinSourceFileMap<T>) {
        other.forEachFile { libFile, srcFile, data ->
            set(libFile, srcFile, data)
        }
    }
}
