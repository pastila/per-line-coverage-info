package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ide.BrowserUtil

/**
 * Shows a balloon notification when a newer plugin version is available on GitHub.
 * The user can either open the release page or opt out of future checks.
 */
object GitHubUpdateNotifier {

    private val log = CoverageLog.get(GitHubUpdateNotifier::class.java)

    fun notifyIfUpdateAvailable(project: Project, result: GitHubUpdateCheckService.Result) {
        if (result !is GitHubUpdateCheckService.Result.UpdateAvailable) return

        val settings = GitHubUpdateCheckSettings.getInstance()
        if (settings.lastSeenVersion == result.latestVersion) {
            // Already notified for this version — don't nag.
            return
        }

        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Coverage Notifications")
                .createNotification(
                    "Per-line coverage update available",
                    "Version ${result.latestVersion} is available on GitHub " +
                        "(installed: ${result.currentVersion}).",
                    NotificationType.INFORMATION,
                )
                .addAction(object : NotificationAction("Open Releases") {
                    override fun actionPerformed(e: AnActionEvent, n: com.intellij.notification.Notification) {
                        BrowserUtil.browse(result.releaseUrl)
                        settings.lastSeenVersion = result.latestVersion
                        n.expire()
                    }
                })
                .addAction(object : NotificationAction("Don't check again") {
                    override fun actionPerformed(e: AnActionEvent, n: com.intellij.notification.Notification) {
                        settings.dontCheckAgain = true
                        log.info("GitHub update check disabled by user")
                        n.expire()
                    }
                })

            notification.notify(project)
            settings.lastSeenVersion = result.latestVersion
        }
    }
}
