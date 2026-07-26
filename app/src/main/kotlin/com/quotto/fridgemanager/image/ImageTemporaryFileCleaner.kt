package com.quotto.fridgemanager.image

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

enum class ImageDeletionResult {
    Deleted,
    Scheduled,
    PersistenceFailed,
    Rejected,
}

/** 監視へ渡せるのは集計値だけとし、ファイル名や画像由来情報を含めない。 */
data class ImageCleanupReport(
    val pendingCount: Int,
    val overdueCount: Int,
    val oldestPendingAgeMillis: Long,
    val persistenceFailureCount: Int = 0,
    val deadlineMarkFailureCount: Int = 0,
    val monitoringFailureCount: Int = 0,
)

data class ImageCleanupRun(
    val results: List<ImageDeletionResult>,
    val report: ImageCleanupReport,
)

/**
 * 画像一時ファイルをcache直下の既知prefixに限定して削除する。
 *
 * 削除できない場合は内部メタデータへbasenameと初回失敗時刻を残すため、
 * プロセス再起動後も再試行できる。
 */
class ImageTemporaryFileCleaner(
    private val cacheDirectory: File,
    private val deleteFile: (File) -> Boolean = File::delete,
    private val monitor: (ImageCleanupReport) -> Boolean = { true },
    private val beforeAtomicMove: (File) -> Unit = {},
    private val markLastModified: (File, Long) -> Boolean = File::setLastModified,
    private val deleteMonitoringMarker: (File) -> Boolean = File::delete,
) {
    fun deleteOrSchedule(
        file: File,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImageDeletionResult = synchronized(REGISTRY_LOCK) {
        val root = canonicalRoot() ?: return ImageDeletionResult.Rejected
        val target = safeCandidate(root, file) ?: return ImageDeletionResult.Rejected
        val pending = readPending(root).toMutableMap()
        val result = deleteOrRemember(root, target, nowMillis, pending)
        if (!writePending(root, pending) && result == ImageDeletionResult.Scheduled) {
            // レジストリ障害時も次回起動の孤児走査が直ちに拾えるよう期限切れにする。
            val markFailures = markPendingExpired(root, pending, nowMillis)
            deliverReport(
                root,
                report(pending, nowMillis).copy(
                    persistenceFailureCount = 1,
                    deadlineMarkFailureCount = markFailures,
                ),
            )
            return ImageDeletionResult.PersistenceFailed
        }
        val monitoring = publishReport(root, pending, nowMillis)
        if (monitoring.monitoringFailureCount > 0 &&
            !monitoringMarker(root).isFile &&
            pending.isNotEmpty()
        ) {
            pending.keys.toList().forEach { pending[it] = MONITORING_BLOCKED_TIMESTAMP }
            writePending(root, pending)
        }
        return result
    }

    /**
     * 保留対象は経過時間によらず再試行し、記録されていない孤児だけは
     * 実行中ファイルとの競合を避けて1時間経過後に回収する。
     */
    fun cleanup(
        nowMillis: Long = System.currentTimeMillis(),
        minimumAgeMillis: Long = DELETION_DEADLINE_MILLIS,
    ): ImageCleanupRun = synchronized(REGISTRY_LOCK) {
        val root = canonicalRoot()
            ?: return ImageCleanupRun(emptyList(), ImageCleanupReport(0, 0, 0))
        val pending = readPending(root).toMutableMap()
        val results = mutableListOf<ImageDeletionResult>()
        if (pending.values.any { it == MONITORING_BLOCKED_TIMESTAMP }) {
            val preflight = deliverReport(
                root,
                ImageCleanupReport(pending.size, 0, 0),
            )
            if (preflight.monitoringFailureCount > 0) {
                return ImageCleanupRun(emptyList(), preflight)
            }
            pending.keys.toList().forEach { name ->
                val modified = safeCandidate(root, File(root, name))?.lastModified() ?: nowMillis
                pending[name] = modified.coerceIn(0, nowMillis)
            }
            if (!writePending(root, pending)) {
                return ImageCleanupRun(
                    listOf(ImageDeletionResult.PersistenceFailed),
                    preflight.copy(persistenceFailureCount = 1),
                )
            }
        }
        if (monitoringMarker(root).isFile) {
            val preflight = deliverReport(root, ImageCleanupReport(0, 0, 0))
            if (preflight.monitoringFailureCount > 0) {
                return ImageCleanupRun(emptyList(), preflight)
            }
        }

        // 永続記録からのみ復元し、canonical境界とprefixを再検証する。
        pending.toMap().forEach { (name, firstFailureAt) ->
            val target = safeCandidate(root, File(root, name))
            if (target == null) {
                pending.remove(name)
            } else {
                results += deleteOrRemember(root, target, firstFailureAt, pending)
            }
        }

        root.listFiles().orEmpty().forEach { candidate ->
            val target = safeCandidate(root, candidate) ?: return@forEach
            if (target.name in pending) return@forEach
            val age = (nowMillis - target.lastModified()).coerceAtLeast(0)
            if (target.isFile && age >= minimumAgeMillis) {
                results += deleteOrRemember(root, target, nowMillis - age, pending)
            }
        }

        val persistenceFailed = !writePending(root, pending)
        val markFailures = if (persistenceFailed) {
            markPendingExpired(root, pending, nowMillis).also {
                results += ImageDeletionResult.PersistenceFailed
            }
        } else {
            0
        }
        var report = report(pending, nowMillis).copy(
            persistenceFailureCount = if (persistenceFailed) 1 else 0,
            deadlineMarkFailureCount = markFailures,
        )
        report = deliverReport(root, report)
        return ImageCleanupRun(results, report)
    }

    private fun deleteOrRemember(
        root: File,
        target: File,
        firstFailureAt: Long,
        pending: MutableMap<String, Long>,
    ): ImageDeletionResult {
        if (!target.exists()) {
            pending.remove(target.name)
            return ImageDeletionResult.Deleted
        }
        // 削除操作の直前にもlstat/canonical境界を検証する。
        val verified = safeCandidate(root, target) ?: return ImageDeletionResult.Rejected
        // 検証済みentryを同一directory内の非公開名へatomic moveしてパス差替え競合を閉じる。
        val quarantined = moveToQuarantine(root, verified) ?: run {
            if (!verified.exists()) {
                pending.remove(target.name)
                return ImageDeletionResult.Deleted
            }
            pending.putIfAbsent(target.name, firstFailureAt)
            return ImageDeletionResult.Scheduled
        }
        pending.remove(target.name)
        if (!quarantined.identityMatches || Files.isSymbolicLink(quarantined.file.toPath())) {
            // symlink自体の削除は外部実体へ影響しないが、画像削除経路では一律拒否する。
            pending.putIfAbsent(quarantined.file.name, firstFailureAt)
            return ImageDeletionResult.Rejected
        }
        if (deleteFile(quarantined.file)) {
            return ImageDeletionResult.Deleted
        }
        pending.putIfAbsent(quarantined.file.name, firstFailureAt)
        return ImageDeletionResult.Scheduled
    }

    private fun moveToQuarantine(root: File, target: File): QuarantinedFile? {
        val prefix = PREFIXES.firstOrNull(target.name::startsWith) ?: return null
        val quarantine = File(root, "${prefix}delete-${UUID.randomUUID()}")
        return runCatching {
            val before = Files.readAttributes(
                target.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()
            beforeAtomicMove(target)
            Files.move(target.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val after = Files.readAttributes(
                quarantine.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()
            QuarantinedFile(quarantine, before != null && before == after)
        }.getOrNull()
    }

    private data class QuarantinedFile(val file: File, val identityMatches: Boolean)

    private fun canonicalRoot(): File? =
        runCatching { cacheDirectory.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }

    private fun safeCandidate(root: File, candidate: File): File? {
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (Files.isSymbolicLink(candidate.toPath())) return null
        if (!canonical.isFile ||
            canonical.parentFile != root ||
            !PREFIXES.any(canonical.name::startsWith)
        ) return null
        return canonical
    }

    private fun readPending(root: File): Map<String, Long> {
        val registry = File(root, PENDING_FILE_NAME)
        if (!registry.isFile) return emptyMap()
        return runCatching {
            registry.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split('\t')
                    if (fields.size != 2) return@mapNotNull null
                    val name = fields[0]
                    val timestamp = fields[1].toLongOrNull()?.takeIf {
                        it >= 0 || it == MONITORING_BLOCKED_TIMESTAMP
                    }
                        ?: return@mapNotNull null
                    val target = safeCandidate(root, File(root, name)) ?: return@mapNotNull null
                    target.name to timestamp
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun writePending(root: File, pending: Map<String, Long>): Boolean {
        val registry = File(root, PENDING_FILE_NAME)
        if (pending.isEmpty()) {
            return !registry.exists() || registry.delete()
        }
        val temporary = File(root, "$PENDING_FILE_NAME.tmp")
        val content = pending.entries.sortedBy { it.key }
            .joinToString(separator = "\n", postfix = "\n") { "${it.key}\t${it.value}" }
        return runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(content.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                registry.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            registry.isFile
        }.getOrDefault(false)
    }

    private fun publishReport(
        root: File,
        pending: Map<String, Long>,
        nowMillis: Long,
    ): ImageCleanupReport {
        if (pending.isNotEmpty() || monitoringMarker(root).isFile) {
            return deliverReport(root, report(pending, nowMillis))
        }
        return report(pending, nowMillis)
    }

    private fun markPendingExpired(
        root: File,
        pending: Map<String, Long>,
        nowMillis: Long,
    ): Int = pending.keys.count { name ->
        val candidate = safeCandidate(root, File(root, name))
        candidate == null || !markLastModified(
            candidate,
            (nowMillis - DELETION_DEADLINE_MILLIS).coerceAtLeast(0),
        )
    }

    private fun ImageCleanupReport.requiresMonitoring(): Boolean =
        pendingCount > 0 || persistenceFailureCount > 0 || deadlineMarkFailureCount > 0

    /**
     * 監視送信が成功するまで集計値だけを永続化する。
     * 画像一時ファイルが先に削除されても、次のWorkerが未送信通知を回収できる。
     */
    private fun deliverReport(root: File, current: ImageCleanupReport): ImageCleanupReport {
        val marker = monitoringMarker(root)
        val queued = readMonitoringReport(root)
        if (marker.exists() && queued == null) {
            // marker破損・型異常は未通知状態として削除前に停止する。
            return current.copy(monitoringFailureCount = 1)
        }
        if (!current.requiresMonitoring() && queued == null) return current
        val delivery = queued?.merge(current) ?: current
        return if (monitor(delivery)) {
            if (!marker.exists() || deleteMonitoringMarker(marker)) {
                current
            } else {
                // 送信済みでもmarkerを消せなければ重複送信を成功扱いしない。
                current.copy(monitoringFailureCount = 1)
            }
        } else {
            writeMonitoringReport(root, delivery)
            current.copy(monitoringFailureCount = 1)
        }
    }

    private fun ImageCleanupReport.merge(other: ImageCleanupReport): ImageCleanupReport =
        ImageCleanupReport(
            pendingCount = maxOf(pendingCount, other.pendingCount),
            overdueCount = maxOf(overdueCount, other.overdueCount),
            oldestPendingAgeMillis = maxOf(oldestPendingAgeMillis, other.oldestPendingAgeMillis),
            persistenceFailureCount = persistenceFailureCount + other.persistenceFailureCount,
            deadlineMarkFailureCount = deadlineMarkFailureCount + other.deadlineMarkFailureCount,
        )

    private fun monitoringMarker(root: File) = File(root, MONITORING_PENDING_FILE_NAME)

    private fun writeMonitoringReport(root: File, report: ImageCleanupReport): Boolean {
        val marker = monitoringMarker(root)
        val temporary = File(root, "$MONITORING_PENDING_FILE_NAME.tmp")
        val content = listOf(
            report.pendingCount.toLong(),
            report.overdueCount.toLong(),
            report.oldestPendingAgeMillis,
            report.persistenceFailureCount.toLong(),
            report.deadlineMarkFailureCount.toLong(),
        ).joinToString("\t")
        return runCatching {
            FileOutputStream(temporary).use {
                it.write(content.encodeToByteArray())
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            marker.isFile
        }.getOrDefault(false)
    }

    private fun readMonitoringReport(root: File): ImageCleanupReport? = runCatching {
        val fields = monitoringMarker(root).readText().split('\t').map(String::toLong)
        if (fields.size != 5 || fields.any { it < 0 }) return@runCatching null
        ImageCleanupReport(
            pendingCount = fields[0].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            overdueCount = fields[1].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            oldestPendingAgeMillis = fields[2],
            persistenceFailureCount = fields[3].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            deadlineMarkFailureCount = fields[4].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }.getOrNull()

    private fun report(pending: Map<String, Long>, nowMillis: Long): ImageCleanupReport {
        val ages = pending.values.map { timestamp ->
            if (timestamp >= nowMillis) 0 else nowMillis - timestamp
        }
        return ImageCleanupReport(
            pendingCount = ages.size,
            overdueCount = ages.count { it >= DELETION_DEADLINE_MILLIS },
            oldestPendingAgeMillis = ages.maxOrNull() ?: 0,
        )
    }

    companion object {
        const val PENDING_FILE_NAME = ".image-cleanup-pending-v1"
        const val MONITORING_PENDING_FILE_NAME = ".image-cleanup-monitor-pending-v1"
        const val DELETION_DEADLINE_MILLIS = 60L * 60L * 1_000L
        private val PREFIXES = listOf("image-source-", "image-upload-")
        private val REGISTRY_LOCK = Any()
        private const val MONITORING_BLOCKED_TIMESTAMP = -1L
    }
}
