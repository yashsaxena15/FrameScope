package com.merlin.perfmon.data.collectors

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.StorageSnapshot
import com.merlin.perfmon.data.model.toMetric
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun collect(): StorageSnapshot = withContext(Dispatchers.IO) {
        try {
            val path  = Environment.getDataDirectory()
            val stats = StatFs(path.path)
            val blockSize  = stats.blockSizeLong
            val totalBlocks = stats.blockCountLong
            val freeBlocks  = stats.availableBlocksLong

            StorageSnapshot(
                totalBytes = (totalBlocks * blockSize).toMetric(),
                freeBytes  = (freeBlocks  * blockSize).toMetric(),
            )
        } catch (e: Exception) {
            StorageSnapshot(
                totalBytes = MetricValue.Unavailable,
                freeBytes  = MetricValue.Unavailable,
            )
        }
    }
}
