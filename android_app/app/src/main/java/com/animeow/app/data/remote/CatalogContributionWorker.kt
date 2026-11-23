package com.animeow.app.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogContributionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Work scheduled by an earlier build uses individual source/id input instead of the queue.
        inputData.getString("source")?.let { source -> inputData.getString("id")?.let { id ->
            CatalogContributionQueue(applicationContext).add(listOf(MetadataReference(source, id)))
        } }
        return when (workerMutex.withLock { drainQueue(applicationContext, runAttemptCount) }) {
            QueueState.RETRY -> Result.retry()
            QueueState.EMPTY, QueueState.PENDING -> Result.success()
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60_000L
        private val workerMutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val flushLock = Any()
        private var availableNetworkFlush: Job? = null
        private val updates = MutableSharedFlow<RemoteAnime>(extraBufferCapacity = 24)
        internal val localizedResults = updates.asSharedFlow()
        private enum class QueueState { EMPTY, PENDING, RETRY }

        private suspend fun drainQueue(applicationContext: Context, runAttemptCount: Int): QueueState {
            val queue = CatalogContributionQueue(applicationContext)
            val preferences = DiscoveryPreferences(applicationContext)
            if (!preferences.snapshot().contributePublicMetadata) {
                queue.clearPending()
                return QueueState.EMPTY
            }
            val service = ServerCatalogService()
            val alternatives = com.animeow.app.data.media.RemoteCoverAlternatives(applicationContext)
            repeat(12) {
                if (!preferences.snapshot().contributePublicMetadata) {
                    queue.clearPending()
                    return QueueState.EMPTY
                }
                val reference = queue.first() ?: return QueueState.EMPTY
                val response = runCatchingCancellable { service.contribute(reference.source, reference.id) }
                val error = response.exceptionOrNull()
                if (error == null) {
                    val record = response.getOrNull()
                    queue.complete(reference, if (!record?.coverUrl.isNullOrBlank() && !record?.summary.isNullOrBlank()) DAY_MS else 10 * 60_000L)
                    record?.let {
                        alternatives.remember(it)
                        updates.emit(it)
                    }
                } else {
                    val status = (error as? RemoteHttpException)?.statusCode
                    if (status in listOf(400, 401, 403, 404, 405, 410, 422)) {
                        queue.complete(reference, 6 * 60 * 60_000L)
                    } else if (runAttemptCount >= 8) {
                        // One unavailable identity cannot keep every subsequent work waiting forever.
                        queue.complete(reference, 10 * 60_000L)
                    } else return QueueState.RETRY
                }
                // The API has a shared 40/minute per-IP guard. Leave room for foreground requests.
                delay(2_000)
            }
            if (queue.first() == null) return QueueState.EMPTY
            schedule(applicationContext)
            return QueueState.PENDING
        }

        private fun flushOnAvailableNetwork(context: Context) = synchronized(flushLock) {
            if (availableNetworkFlush?.isActive == true) return@synchronized
            // A successful foreground lookup proves connectivity even on networks that Android
            // cannot validate. Keep this attempt bounded; durable WorkManager jobs handle retries.
            availableNetworkFlush = scope.launch {
                repeat(4) {
                    if (workerMutex.withLock { drainQueue(context, 0) } != QueueState.PENDING) return@launch
                }
            }
        }

        fun enqueue(context: Context, remote: RemoteAnime) = enqueueAll(context, listOf(remote))

        fun enqueueAll(context: Context, items: List<RemoteAnime>) {
            val appContext = context.applicationContext
            scope.launch {
                val alternatives = com.animeow.app.data.media.RemoteCoverAlternatives(appContext)
                items.forEach { alternatives.remember(it) }
                if (!DiscoveryPreferences(appContext).snapshot().contributePublicMetadata) return@launch
                val references = items.flatMap { remote ->
                    remote.metadataReferences + if (remote.source != RemoteCatalogSource.SERVER)
                        setOf(MetadataReference(remote.importSource, remote.importId)) else emptySet()
                }
                val queue = CatalogContributionQueue(appContext)
                if (queue.add(references)) schedule(appContext)
                if (queue.first() != null) flushOnAvailableNetwork(appContext)
            }
        }

        private fun schedule(context: Context) {
            val work = OneTimeWorkRequestBuilder<CatalogContributionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Appending covers an enqueue racing the previous worker's last empty-queue check.
            WorkManager.getInstance(context).enqueueUniqueWork("catalog-contributions", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
