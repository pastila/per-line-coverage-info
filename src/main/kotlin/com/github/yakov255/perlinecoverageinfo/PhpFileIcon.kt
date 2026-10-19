package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import javax.swing.Icon

/** The icon of a PHP file from the PHP plugin of the IDE (PhpStorm's or another vendor's), without a dependency on it. */
object PhpFileIcon {
    fun forPath(path: String): Icon {
        if (!path.endsWith(".php")) return AllIcons.FileTypes.Any_type
        val type = FileTypeManager.getInstance().getFileTypeByExtension("php")
        return type.takeUnless { it is UnknownFileType }?.icon ?: AllIcons.FileTypes.Any_type
    }
}
