package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import javax.swing.Icon

/**
 * File icons from the plugins of the IDE (PhpStorm's or another vendor's), without a dependency on them: the PHP file icon, and the
 * Gherkin `.feature` icon for Behat nodes (`icons.BehatIcons` exists only in PhpStorm's Behat plugin).
 */
object PhpFileIcon {
    fun forPath(path: String): Icon {
        if (!path.endsWith(".php")) return AllIcons.FileTypes.Any_type
        return byExtension("php") ?: AllIcons.FileTypes.Any_type
    }

    fun forFeature(): Icon = byExtension("feature") ?: AllIcons.Nodes.Test

    private fun byExtension(extension: String): Icon? =
        FileTypeManager.getInstance().getFileTypeByExtension(extension).takeUnless { it is UnknownFileType }?.icon
}
