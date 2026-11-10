package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Project-level user notifications for coverage failures.
 *
 * One balloon per kind: a new notification of the same kind expires the previous one, so a
 * repeated failure cannot flood the notification area. The last 20 notifications are kept in
 * memory (title, content, type, action labels and time) for [CoverageUiTestBridge] and tests.
 *
 * The balloon actions are the user's escape hatches: retry the load despite an exhausted
 * budget or a block, open the plugin log tab, or go straight to the GitLab token setting.
 */
@Service(Service.Level.PROJECT)
class CoverageNotifier(private val project: Project) {

    private val log = CoverageLog.get(CoverageNotifier::class.java)

    private val lock = Any()
    private val records = ArrayDeque<Record>()
    private val active = HashMap<String, Notification>()

    /** One recorded notification, as the bridge and the tests see it. */
    data class Record(
        val title: String,
        val content: String,
        val type: String,
        val actions: List<String>,
        val atMs: Long,
    )

    /** A notification after the automatic retries ran out. */
    fun notifyRetryExhausted(message: String) {
        show(
            kind = KIND_RETRY,
            title = "Coverage load failed",
            content = message,
            type = NotificationType.ERROR,
            actions = listOf(
                ACTION_RETRY to { CoverageLoadService.getInstance(project).loadFromGitLab(showErrors = false, bypassBlock = true) },
                ACTION_SHOW_LOG to { showLogTab() },
            ),
        )
    }

    /** A sticky notification for an expired, invalid or under-privileged GitLab token. */
    fun notifyAuthInvalid() {
        show(
            kind = KIND_AUTH,
            title = "GitLab token expired or invalid",
            content = "Update the token in Settings → Tools → GitLab Coverage. The new token is used without restarting the IDE.",
            type = NotificationType.ERROR,
            actions = listOf(ACTION_UPDATE_TOKEN to { showTokenSettings() }),
        )
    }

    /** Recent notifications, oldest first, at most [MAX_RECORDS]. */
    fun notifications(): List<Record> = synchronized(lock) { records.toList() }

    /** Test seam: drops the record list and expires the active balloons of this project. */
    internal fun clearForTest() {
        synchronized(lock) {
            records.clear()
            active.values.forEach { it.expire() }
            active.clear()
        }
    }

    private fun show(
        kind: String,
        title: String,
        content: String,
        type: NotificationType,
        actions: List<Pair<String, () -> Unit>>,
    ) {
        val record = Record(
            title = title,
            content = content,
            type = type.name,
            actions = actions.map { it.first },
            atMs = System.currentTimeMillis(),
        )
        synchronized(lock) {
            records.addLast(record)
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
        CoveragePerfLog.event("notify.shown", "kind" to kind, "type" to type.name)
        log.info("Coverage: notification '$kind' — $title: $content")

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            synchronized(lock) {
                active.remove(kind)?.expire()
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(title, content, type)
                for ((label, handler) in actions) {
                    notification.addAction(NotificationAction.createSimple(label) { handler() })
                }
                notification.notify(project)
                active[kind] = notification
            }
        }
    }

    private fun showLogTab() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate(null)
        toolWindow.contentManager.contents
            .firstOrNull { it.displayName == LOG_TAB_TITLE }
            ?.let { toolWindow.contentManager.setSelectedContent(it) }
    }

    private fun showTokenSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CoverageApiSettingsConfigurable::class.java)
    }

    companion object {
        private const val MAX_RECORDS = 20
        private const val NOTIFICATION_GROUP = "Coverage Notifications"
        private const val TOOL_WINDOW_ID = "Coverage Tests"
        private const val LOG_TAB_TITLE = "Log"

        const val KIND_RETRY = "retry"
        const val KIND_AUTH = "auth"

        const val ACTION_RETRY = "Retry now"
        const val ACTION_SHOW_LOG = "Show log"
        const val ACTION_UPDATE_TOKEN = "Update token…"

        fun getInstance(project: Project): CoverageNotifier = project.service()
    }
}
