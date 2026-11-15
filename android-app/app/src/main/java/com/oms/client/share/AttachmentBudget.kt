package com.oms.client.share

import java.io.InputStream
import java.io.OutputStream

/** One submission budget, including any recorded voice attachment. Unknown sizes are checked while copying. */
object AttachmentBudget {
    const val MAX_COUNT = 9
    const val MAX_FILE_BYTES = 30L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    fun validateCount(count: Int) {
        require(count <= MAX_COUNT) { "最多 9 个附件（含语音），请减少后重新分享" }
    }

    fun validateSizes(sizes: List<Long?>) {
        validateCount(sizes.size)
        var total = 0L
        sizes.forEach { size ->
            if (size != null && size >= 0) {
                require(size <= MAX_FILE_BYTES) { "单个附件超过 30 MiB，请重新分享较小文件" }
                total += size
                require(total <= MAX_TOTAL_BYTES) { "附件总大小超过 64 MiB，请分批分享" }
            }
        }
    }

    class CopyBudget {
        private var total = 0L
        private var count = 0
        fun copy(input: InputStream, output: OutputStream): Long {
            require(++count <= MAX_COUNT) { "最多 9 个附件（含语音），请减少后重新分享" }
            var fileBytes = 0L
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return fileBytes
                fileBytes += n
                total += n
                require(fileBytes <= MAX_FILE_BYTES) { "单个附件超过 30 MiB，请重新分享较小文件" }
                require(total <= MAX_TOTAL_BYTES) { "附件总大小超过 64 MiB，请分批分享" }
                output.write(buffer, 0, n)
            }
        }
    }
}
