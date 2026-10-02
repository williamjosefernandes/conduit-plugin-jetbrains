package dev.conduit.ui.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Balloons in the IDE's "Conduit" notification group. */
object Notifications {
    private const val GROUP = "Conduit"

    fun info(
        project: Project,
        message: String,
    ) = show(project, message, NotificationType.INFORMATION)

    fun error(
        project: Project,
        message: String,
    ) = show(project, message, NotificationType.ERROR)

    private fun show(
        project: Project,
        message: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(message, type)
            .notify(project)
    }
}
