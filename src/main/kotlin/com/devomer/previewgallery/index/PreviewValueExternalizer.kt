package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.IOUtil
import java.io.DataInput
import java.io.DataOutput

/**
 * Field-by-field serialization of the index values. Any change to the layout requires bumping
 * [PreviewIndex.VERSION], otherwise stale on-disk data is read with the new layout.
 */
object PreviewValueExternalizer : DataExternalizer<List<IndexedPreview>> {

    override fun save(out: DataOutput, value: List<IndexedPreview>) {
        DataInputOutputUtil.writeINT(out, value.size)
        for (preview in value) {
            IOUtil.writeUTF(out, preview.displayName)
            IOUtil.writeUTF(out, preview.functionName)
            IOUtil.writeUTF(out, preview.packageName)
            IOUtil.writeUTF(out, preview.jvmClassName)
            IOUtil.writeUTF(out, preview.composableFqn)
            DataInputOutputUtil.writeINT(out, preview.offset)
            DataInputOutputUtil.writeINT(out, preview.annotationKind.ordinal)
            out.writeBoolean(preview.isPrivate)
            out.writeBoolean(preview.hasPreviewParameter)
            writeNullable(out, preview.previewGroup)
            writeNullable(out, preview.unsupportedReason)
        }
    }

    override fun read(input: DataInput): List<IndexedPreview> {
        val size = DataInputOutputUtil.readINT(input)
        val result = ArrayList<IndexedPreview>(size)
        repeat(size) {
            val displayName = IOUtil.readUTF(input)
            val functionName = IOUtil.readUTF(input)
            val packageName = IOUtil.readUTF(input)
            val jvmClassName = IOUtil.readUTF(input)
            val composableFqn = IOUtil.readUTF(input)
            val offset = DataInputOutputUtil.readINT(input)
            val kindOrdinal = DataInputOutputUtil.readINT(input)
            val isPrivate = input.readBoolean()
            val hasPreviewParameter = input.readBoolean()
            val previewGroup = readNullable(input)
            val unsupportedReason = readNullable(input)
            result += IndexedPreview(
                displayName = displayName,
                functionName = functionName,
                packageName = packageName,
                jvmClassName = jvmClassName,
                composableFqn = composableFqn,
                offset = offset,
                annotationKind = AnnotationKind.entries.getOrElse(kindOrdinal) { AnnotationKind.UNKNOWN },
                isPrivate = isPrivate,
                hasPreviewParameter = hasPreviewParameter,
                previewGroup = previewGroup,
                unsupportedReason = unsupportedReason,
            )
        }
        return result
    }

    private fun writeNullable(out: DataOutput, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) IOUtil.writeUTF(out, value)
    }

    private fun readNullable(input: DataInput): String? =
        if (input.readBoolean()) IOUtil.readUTF(input) else null
}
