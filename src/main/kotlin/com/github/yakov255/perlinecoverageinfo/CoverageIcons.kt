package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.IconManager
import com.intellij.ui.LayeredIcon
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

object CoverageIcons {
    val COVERED: Icon = createCircleIcon(Color(0, 160, 0))
    val UNCOVERED: Icon = createCircleIcon(Color(200, 0, 0))

    private fun createCircleIcon(color: Color): Icon {
        val size = 8
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.fillOval(0, 0, size, size)
        g.dispose()
        return ImageIcon(img)
    }
}
