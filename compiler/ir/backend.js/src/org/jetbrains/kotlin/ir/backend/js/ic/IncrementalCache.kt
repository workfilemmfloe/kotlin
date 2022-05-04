/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.serialization.IdSignatureDeserializer
import org.jetbrains.kotlin.backend.common.serialization.IrLibraryBytesSource
import org.jetbrains.kotlin.backend.common.serialization.IrLibraryFileFromBytes
import org.jetbrains.kotlin.backend.common.serialization.codedInputStream
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.impl.javaFile
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedOutputStream
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import java.io.File


class IncrementalCache(private val library: KotlinLibrary, cachePath: String) : ArtifactCache() {
    companion object {
        private const val cacheFullInfoFile = "cache.full.info"
        private const val cacheFastInfoFile = "cache.fast.info"
        private const val binaryAstSuffix = "binary.ast.bin"

        private const val fingerprintsFile = "fingerprints.bin"

        private const val dependenciesSuffix = "dependencies.bin"
        private const val signaturesSuffix = "signatures.bin"
    }

    class CacheFastInfo(
        var moduleName: String? = null,
        var flatHash: ICHash = ICHash(),
        var transHash: ICHash = ICHash(),
        var configHash: ICHash = ICHash(),
        var initialFlatHash: ICHash = ICHash()
    )

    private enum class CacheState { NON_LOADED, FETCHED_FOR_DEPENDENCY, FETCHED_FULL }

    private var state = CacheState.NON_LOADED
    private var forceRebuildJs = false

    private val cacheDir = File(cachePath)
    private val signatureToIdMapping = mutableMapOf<String, Map<IdSignature, Int>>()

    private val fingerprints = mutableMapOf<String, ICHash>()
    private val usedInlineFunctions = mutableMapOf<String, Map<IdSignature, ICHash>>()
    private val implementedInlineFunctions = mutableMapOf<String, Map<IdSignature, ICHash>>()

    private var cacheFastInfo = CacheFastInfo().apply {
        File(cacheDir, cacheFastInfoFile).useCodedInputIfExists {
            moduleName = readString()
            flatHash = ICHash.fromProtoStream(this)
            transHash = ICHash.fromProtoStream(this)
            configHash = ICHash.fromProtoStream(this)
        }
        initialFlatHash = flatHash
    }

    val srcFingerprints: Map<String, ICHash> get() = fingerprints
    val usedFunctions: Map<String, Map<IdSignature, ICHash>> get() = usedInlineFunctions
    val implementedFunctions: Collection<Map<IdSignature, ICHash>> get() = implementedInlineFunctions.values

    val klibUpdated: Boolean get() = cacheFastInfo.run { initialFlatHash == ICHash() || initialFlatHash != flatHash }

    val klibTransitiveHash: ICHash get() = cacheFastInfo.transHash

    var srcFilesInOrderFromKLib: List<String> = emptyList()
        private set

    var deletedSrcFiles: Set<String> = emptySet()
        private set


    private val cachedFingerprints: Map<KotlinSourceFile, ICHash> by lazy {
        File(cacheDir, fingerprintsFile).useCodedInputIfExists {
            val fingerprintsCount = readInt32()
            buildMap(fingerprintsCount) {
                repeat(fingerprintsCount) {
                    val file = KotlinSourceFile.fromProtoStream(this@useCodedInputIfExists)
                    put(file, ICHash.fromProtoStream(this@useCodedInputIfExists))
                }
            }
        } ?: emptyMap()
    }

    private class KotlinLibraryMetadata(
        val sourceFiles: List<KotlinSourceFile>, val signatureDeserializers: Map<KotlinSourceFile, IdSignatureDeserializer>
    )

    private inline fun <E> buildListUntil(to: Int, builderAction: MutableList<E>.(Int) -> Unit): List<E> {
        return buildList(to) { repeat(to) { builderAction(it) } }
    }

    private inline fun <K, V> buildMapUntil(to: Int, builderAction: MutableMap<K, V>.(Int) -> Unit): Map<K, V> {
        return buildMap(to) { repeat(to) { builderAction(it) } }
    }

    private val kotlinLibraryMetadata: KotlinLibraryMetadata by lazy {
        val filesCount = library.fileCount()
        val extReg = ExtensionRegistryLite.newInstance()
        val files = buildListUntil(filesCount) {
            val fileProto = IrFile.parseFrom(library.file(it).codedInputStream, extReg)
            add(KotlinSourceFile(fileProto.fileEntry.name))
        }

        val deserializers = buildMapUntil(filesCount) {
            put(files[it], IdSignatureDeserializer(IrLibraryFileFromBytes(object : IrLibraryBytesSource() {
                private fun err(): Nothing = error("Not supported")
                override fun irDeclaration(index: Int): ByteArray = err()
                override fun type(index: Int): ByteArray = err()
                override fun signature(index: Int): ByteArray = library.signature(index, it)
                override fun string(index: Int): ByteArray = library.string(index, it)
                override fun body(index: Int): ByteArray = err()
                override fun debugInfo(index: Int): ByteArray? = null
            }), null))
        }

        KotlinLibraryMetadata(files, deserializers)
    }

    private val kotlinSourceDependencies = mutableMapOf<KotlinSourceFile, KotlinSourceFileDependencies>()

    private fun KotlinSourceFile.loadDependencies() = kotlinSourceDependencies.getOrPut(this) {
        getCacheFile(dependenciesSuffix).useCodedInputIfExists {
            fun readDepends() = buildMapUntil(readInt32()) {
                val libraryFile = KotlinLibraryFile.fromProtoStream(this@useCodedInputIfExists)
                val depends = buildListUntil(readInt32()) {
                    add(KotlinSourceFile.fromProtoStream(this@useCodedInputIfExists))
                }
                put(libraryFile, depends)
            }

            val dependencies = readDepends()
            val reverseDependencies = readDepends()

            KotlinSourceFileDependencies(dependencies, reverseDependencies)
        } ?: KotlinSourceFileDependencies(emptyMap(), emptyMap())
    }

    private val kotlinSourceSignatures = mutableMapOf<KotlinSourceFile, KotlinSourceFileSignatures>()

    fun updateSignatureToIdMapping(srcPath: String, mapping: Map<IdSignature, Int>) {
        signatureToIdMapping[srcPath] = mapping
    }

    fun updateHashes(
        srcPath: String, fingerprint: ICHash, usedFunctions: Map<IdSignature, ICHash>?, implementedFunctions: Map<IdSignature, ICHash>?
    ) {
        fingerprints[srcPath] = fingerprint
        usedFunctions?.let { usedInlineFunctions[srcPath] = it }
        implementedFunctions?.let { implementedInlineFunctions[srcPath] = it }
    }

    fun invalidateCacheForNewConfig(configHash: ICHash) {
        if (cacheFastInfo.configHash != configHash) {
            invalidate()
            cacheFastInfo.configHash = configHash
        }
    }

    fun checkAndUpdateCacheFastInfo(flatHash: ICHash, transHash: ICHash): Boolean {
        if (cacheFastInfo.transHash != transHash) {
            cacheFastInfo.flatHash = flatHash
            cacheFastInfo.transHash = transHash
            return false
        }
        return true
    }

    private fun commitCacheFastInfo(klibModuleName: String? = null): Unit = cacheFastInfo.run {
        moduleName = klibModuleName ?: moduleName
        val name = moduleName ?: error("Internal error: uninitialized fast cache info for ${library.libraryName}")
        File(cacheDir, cacheFastInfoFile).useCodedOutput {
            writeStringNoTag(name)
            flatHash.toProtoStream(this)
            transHash.toProtoStream(this)
            configHash.toProtoStream(this)
        }
    }

    private fun CodedInputStream.readFunctionHashes(
        deserializer: IdSignatureDeserializer, signatureToId: MutableMap<IdSignature, Int>? = null
    ): Map<IdSignature, ICHash>? {
        val functions = readInt32()
        if (functions == 0) {
            return null
        }
        val result = mutableMapOf<IdSignature, ICHash>()
        for (funIndex in 0 until functions) {
            val sigId = readInt32()
            val hash = ICHash.fromProtoStream(this)
            try {
                val signature = deserializer.deserializeIdSignature(sigId)
                result[signature] = hash
                signatureToId?.let { it[signature] = sigId }
            } catch (ex: IndexOutOfBoundsException) {
                // Signature has been removed
            }
        }
        return result
    }

    fun fetchFullCacheData() {
        when (state) {
            CacheState.FETCHED_FULL -> return
            CacheState.FETCHED_FOR_DEPENDENCY -> error("Internal error: cache for ${library.libraryName} has been already fetched for dependency")
            CacheState.NON_LOADED -> {
                state = CacheState.FETCHED_FULL
                val signatureReaders = library.filesAndSigReaders()
                srcFilesInOrderFromKLib = signatureReaders.map { it.first }

                File(cacheDir, cacheFullInfoFile).useCodedInputIfExists {
                    val deleted = mutableSetOf<String>()

                    val signatureReadersMap = signatureReaders.toMap()
                    val srcFiles = readInt32()
                    for (srcIndex in 0 until srcFiles) {
                        val srcPath = readString()
                        val fingerprint = ICHash.fromProtoStream(this)

                        val deserializer = signatureReadersMap[srcPath]
                        if (deserializer != null) {
                            fingerprints[srcPath] = fingerprint
                            val signatureToId = mutableMapOf<IdSignature, Int>()
                            readFunctionHashes(deserializer, signatureToId)?.let { implementedInlineFunctions[srcPath] = it }
                            readFunctionHashes(deserializer, signatureToId)?.let { usedInlineFunctions[srcPath] = it }
                            if (signatureToId.isNotEmpty()) {
                                signatureToIdMapping[srcPath] = signatureToId
                            }
                        } else {
                            deleted.add(srcPath)
                            skipFunctionHashes()
                            skipFunctionHashes()
                        }
                    }

                    deletedSrcFiles = deleted
                }
            }
        }
    }

    private fun CodedInputStream.skipFunctionHashes() {
        val functions = readInt32()
        for (funIndex in 0 until functions) {
            readInt32() // skip sigid
            readFixed64() // skip hash
        }
    }

    fun fetchCacheDataForDependency() = File(cacheDir, cacheFullInfoFile).useCodedInputIfExists {
        if (state == CacheState.NON_LOADED) {
            state = CacheState.FETCHED_FOR_DEPENDENCY
            val signatureReadersMap = library.filesAndSigReaders().toMap()

            val srcFiles = readInt32()
            for (srcIndex in 0 until srcFiles) {
                val srcPath = readString()
                val fingerprint = ICHash.fromProtoStream(this)

                val deserializer = signatureReadersMap[srcPath]
                if (deserializer != null) {
                    fingerprints[srcPath] = fingerprint
                    readFunctionHashes(deserializer)?.let { implementedInlineFunctions[srcPath] = it }
                } else {
                    skipFunctionHashes()
                }
                skipFunctionHashes()
            }
        }
    }

    private fun getBinaryAstPath(srcFile: String): File {
        val binaryAstFileName = "${File(srcFile).name}.${srcFile.stringHashForIC()}.$binaryAstSuffix"
        return File(cacheDir, binaryAstFileName)
    }

    private fun KotlinSourceFile.getCacheFile(suffix: String) = File(cacheDir, "${File(path).name}.${path.stringHashForIC()}.$suffix")

    private fun CodedOutputStream.writeFunctionHashes(sigToIndexMap: Map<IdSignature, Int>, hashes: Map<IdSignature, ICHash>) {
        writeInt32NoTag(hashes.size)
        for ((sig, functionHash) in hashes) {
            val sigId = sigToIndexMap[sig] ?: error("No index found for sig $sig")
            writeInt32NoTag(sigId)
            functionHash.toProtoStream(this)
        }
    }

    private fun commitCacheFullInfo() {
        if (state != CacheState.FETCHED_FULL) {
            error("Internal error: cache for ${library.libraryName} has not been fetched fully")
        }

        File(cacheDir, cacheFullInfoFile).useCodedOutput {
            writeInt32NoTag(fingerprints.size)
            for ((srcPath, fingerprint) in fingerprints) {
                writeStringNoTag(srcPath)
                fingerprint.toProtoStream(this)

                val sigToIndexMap = signatureToIdMapping[srcPath] ?: emptyMap()
                writeFunctionHashes(sigToIndexMap, implementedInlineFunctions[srcPath] ?: emptyMap())
                writeFunctionHashes(sigToIndexMap, usedInlineFunctions[srcPath] ?: emptyMap())
            }
        }
    }

    private fun clearCacheAfterCommit() {
        state = CacheState.FETCHED_FOR_DEPENDENCY
        forceRebuildJs = deletedSrcFiles.isNotEmpty()
        signatureToIdMapping.clear()
        usedInlineFunctions.clear()
        srcFilesInOrderFromKLib = emptyList()
        deletedSrcFiles = emptySet()
        binaryAsts.clear()
    }

    fun commitCacheForRemovedSrcFiles() {
        commitCacheFastInfo()
        if (deletedSrcFiles.isNotEmpty()) {
            commitCacheFullInfo()
        }
        clearCacheAfterCommit()
    }

    fun commitCacheForRebuiltSrcFiles(klibModuleName: String) {
        commitCacheFastInfo(klibModuleName)
        commitCacheFullInfo()
        for ((srcPath, ast) in binaryAsts) {
            getBinaryAstPath(srcPath).apply { recreate() }.writeBytes(ast)
        }
        clearCacheAfterCommit()
    }

    override fun fetchArtifacts() = ModuleArtifact(
        moduleName = cacheFastInfo.moduleName ?: error("Internal error: missing module name"), fileArtifacts = fingerprints.keys.map {
            SrcFileArtifact(it, fragments[it], getBinaryAstPath(it))
        }, artifactsDir = cacheDir, forceRebuildJs = forceRebuildJs
    )

    fun invalidate() {
        cacheDir.deleteRecursively()
        signatureToIdMapping.clear()
        implementedInlineFunctions.clear()
        usedInlineFunctions.clear()
        fingerprints.clear()
        binaryAsts.clear()
        fragments.clear()
        cacheFastInfo = CacheFastInfo()
        srcFilesInOrderFromKLib = emptyList()
        deletedSrcFiles = emptySet()
    }

    fun invalidateForSrcFile(srcPath: String) {
        getBinaryAstPath(srcPath).delete()
        signatureToIdMapping.remove(srcPath)
        implementedInlineFunctions.remove(srcPath)
        usedInlineFunctions.remove(srcPath)
        fingerprints.remove(srcPath)
        binaryAsts.remove(srcPath)
        fragments.remove(srcPath)
    }

    private fun KotlinLibrary.filesAndSigReaders(): List<Pair<String, IdSignatureDeserializer>> {
        val fileSize = fileCount()
        val result = ArrayList<Pair<String, IdSignatureDeserializer>>(fileSize)
        val extReg = ExtensionRegistryLite.newInstance()

        for (i in 0 until fileSize) {
            val fileStream = file(i).codedInputStream
            val fileProto = IrFile.parseFrom(fileStream, extReg)
            val sigReader = IdSignatureDeserializer(IrLibraryFileFromBytes(object : IrLibraryBytesSource() {
                private fun err(): Nothing = error("Not supported")
                override fun irDeclaration(index: Int): ByteArray = err()
                override fun type(index: Int): ByteArray = err()
                override fun signature(index: Int): ByteArray = signature(index, i)
                override fun string(index: Int): ByteArray = string(index, i)
                override fun body(index: Int): ByteArray = err()
                override fun debugInfo(index: Int): ByteArray? = null
            }), null)

            result.add(fileProto.fileEntry.name to sigReader)
        }

        return result
    }

    fun collectModifiedFiles(): Map<KotlinSourceFile, KotlinSourceFileDependencies> {
        val flatHash = library.libraryFile.javaFile().fileHashForIC()
        if (cacheFastInfo.flatHash == flatHash) {
            return emptyMap()
        }

        val newFingerprints = kotlinLibraryMetadata.sourceFiles.mapIndexed { index, file -> file to library.fingerprint(index) }
        val modifiedFiles = buildMap(newFingerprints.size) {
            for ((file, fileNewFingerprint) in newFingerprints) {
                if (cachedFingerprints[file] != fileNewFingerprint) {
                    put(file, file.loadDependencies())
                }
            }
        }

        if (modifiedFiles.isNotEmpty()) {
            // TODO: commit newFingerprints
        }

        return modifiedFiles
    }

    fun loadSourceFileSignatures(sourceFile: KotlinSourceFile) = kotlinSourceSignatures.getOrPut(sourceFile) {
        sourceFile.getCacheFile(signaturesSuffix).useCodedInputIfExists {
            val deserializer = kotlinLibraryMetadata.signatureDeserializers[sourceFile] ?: error("TODO write an error")
            fun readSignatures() = buildListUntil(readInt32()) { add(deserializer.deserializeIdSignature(readInt32())) }

            val importedSymbols = readSignatures()
            val exportedSymbols = readSignatures()
            KotlinSourceFileSignatures(importedSymbols, exportedSymbols)
        } ?: KotlinSourceFileSignatures(emptyList(), emptyList())
    }
}
