/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedOutputStream

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

class KotlinSourceFileDependencies(
    val dependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>,
    val reverseDependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
)

class KotlinSourceFileSignatures(
    val importedSymbols: Collection<IdSignature>,
    val exportedSymbols: Collection<IdSignature>
)

class KotlinFileHeader(
    var fingerprint: ICHash,
    val dependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>,
    val reverseDependencies: Map<KotlinLibraryFile, Collection<KotlinSourceFile>>
) {
    val usedInlineFunctions: MutableMap<IdSignature, ICHash> = mutableMapOf()
    val implementedInlineFunctions: MutableMap<IdSignature, ICHash> = mutableMapOf()
    val exportedSymbols: MutableList<IdSignature> = mutableListOf()
    val importedSymbols: MutableSet<IdSignature> = mutableSetOf()

    var signatureToIndexMapping: Map<IdSignature, Int> = emptyMap()

    private fun clearDepends() {
//        dependencies.clear()
//        reverseDependencies.clear()
        exportedSymbols.clear()
        importedSymbols.clear()
    }

    companion object {
        fun rebuildDependencies(
            jsIrLinker: JsIrLinker,
            irModules: Map<KotlinLibrary, IrModuleFragment>,
            modifiedFiles: Map<KotlinLibrary, Map<KotlinSourceFile, KotlinFileHeader>>
        ) {
//            fun getSrcHeader(srcFile: Pair<KotlinLibrary, KotlinSourceFile>) = modifiedFiles[srcFile.first]?.get(srcFile.second)
//
//            val idSignatureToFile = mutableMapOf<IdSignature, Pair<KotlinLibrary, KotlinSourceFile>>()
//            val fileToUsedSignatures = mutableMapOf<Pair<KotlinLibrary, KotlinSourceFile>, Set<IdSignature>>()
//            for ((lib, irModule) in irModules) {
//                val moduleDeserializer = jsIrLinker.moduleDeserializer(irModule.descriptor)
//                for (fileDeserializer in moduleDeserializer.fileDeserializers()) {
//                    val libSrcFile = lib to KotlinSourceFile(fileDeserializer.file)
//                    val allTopLevelSignatures = fileDeserializer.reversedSignatureIndex
//                    idSignatureToFile.putAll(allTopLevelSignatures.keys.asSequence().map { it to libSrcFile })
//
//                    val reachableSignatures = fileDeserializer.symbolDeserializer.signatureDeserializer.signatureToIndexMapping()
//                    fileToUsedSignatures[libSrcFile] = reachableSignatures.keys - allTopLevelSignatures.keys
//
//                    val srcFileHeader = getSrcHeader(libSrcFile) ?: continue
//                    srcFileHeader.signatureToIndexMapping = reachableSignatures
//                    srcFileHeader.clearDepends()
//                    for (topLevelDecl in fileDeserializer.file.declarations) {
//                        val signature = topLevelDecl.symbol.signature ?: continue
//                        srcFileHeader.exportedSymbols += signature
//                    }
//                }
//            }
//
//            fun MutableMap<KotlinLibraryFile, MutableSet<KotlinSourceFile>>.addDependencyFrom(srcFile: Pair<KotlinLibrary, KotlinSourceFile>) {
//                getOrPut(KotlinLibraryFile(srcFile.first)) { mutableSetOf() } += srcFile.second
//            }
//
//            for ((libSrcFile, usedSignatures) in fileToUsedSignatures) {
//                val srcFileHeader = getSrcHeader(libSrcFile)
//
//                for (signature in usedSignatures) {
//                    val signatureFrom = idSignatureToFile[signature] ?: continue
//                    srcFileHeader?.apply {
//                        importedSymbols += signature
//                        dependencies.addDependencyFrom(signatureFrom)
//                    }
//
//                    if (libSrcFile != signatureFrom) {
//                        getSrcHeader(signatureFrom)?.reverseDependencies?.addDependencyFrom(libSrcFile)
//                    }
//                }
//            }
        }
    }
}
