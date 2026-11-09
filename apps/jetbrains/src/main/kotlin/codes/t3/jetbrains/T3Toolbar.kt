package codes.t3.jetbrains

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

internal data class ToolbarColors(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
    val primary: Color,
    val primaryForeground: Color,
) {
    companion object {
        fun default(): ToolbarColors {
            val background = UIManager.getColor("Panel.background") ?: Color.BLACK
            return if (background.red + background.green + background.blue < 384) {
                ToolbarColors(Color.BLACK, Color(0xF5F5F5), Color(0xA3A3A3), Color(0x191A1D), Color(0x141414), Color(0x3565E8), Color.WHITE)
            } else {
                ToolbarColors(Color(0xFCFCFC), Color(0x18181B), Color(0x71717A), Color(0xF4F4F5), Color(0xE4E4E7), Color(0x2448CF), Color.WHITE)
            }
        }
    }
}

internal class T3Toolbar(
    onConnect: () -> Unit,
    onSelection: () -> Unit,
    onMenu: (Component) -> Unit,
    private val scale: (Int) -> Int = { it },
) : JPanel(BorderLayout(scale(8), 0)) {
    private var colors = ToolbarColors.default()
    private var indicator = colors.muted
    private val connection = JLabel("Disconnected").apply {
        font = UIManager.getFont("Label.font").deriveFont(Font.PLAIN, scale(12).toFloat())
        iconTextGap = scale(7)
        icon = object : Icon {
            override fun getIconWidth() = scale(8)
            override fun getIconHeight() = scale(8)
            override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = indicator
                g.fillOval(x + scale(1), y + scale(1), scale(6), scale(6))
                g.dispose()
            }
        }
    }
    private val connect = ToolbarButton("Connect", primary = true).apply {
        addActionListener { onConnect() }
    }
    private val selection = ToolbarButton("Send selection").apply {
        toolTipText = "Add selected editor code to the current T3 draft"
        addActionListener { onSelection() }
    }
    private val menu = ToolbarButton("").apply {
        preferredSize = Dimension(scale(28), scale(28))
        toolTipText = "Connection settings and server actions"
        accessibleContext.accessibleName = "Connection settings and server actions"
        icon = object : Icon {
            override fun getIconWidth() = scale(16)
            override fun getIconHeight() = scale(16)
            override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = colors.muted
                for (offset in listOf(3, 7, 11)) g.fillOval(x + scale(offset), y + scale(7), scale(2), scale(2))
                g.dispose()
            }
        }
        addActionListener { onMenu(this) }
    }

    init {
        border = BorderFactory.createEmptyBorder(scale(6), scale(12), scale(7), scale(8))
        add(connection, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, scale(4), 0)).apply {
            isOpaque = false
            add(connect)
            add(selection)
            add(menu)
        }, BorderLayout.EAST)
        applyColors(colors)
        showConnection("Disconnected", null, "Connect to T3 to get started.", false, false, false)
    }

    fun showConnection(
        label: String,
        endpoint: String?,
        detail: String,
        connected: Boolean,
        busy: Boolean,
        failed: Boolean,
        canConnect: Boolean = true,
        canShare: Boolean = connected,
    ) {
        connection.text = label
        connection.toolTipText = if (endpoint == null) detail else "$endpoint — $detail"
        indicator = when {
            failed -> Color(0xEF6B73)
            busy -> Color(0xD9A441)
            connected -> Color(0x56B88A)
            else -> colors.muted
        }
        connect.isVisible = !connected && !busy
        connect.isEnabled = canConnect
        selection.isVisible = connected
        selection.isEnabled = canShare
        revalidate()
        repaint()
    }

    fun applyColors(next: ToolbarColors) {
        if (indicator == colors.muted) indicator = next.muted
        colors = next
        background = next.background
        connection.foreground = next.muted
        for (button in listOf(connect, selection, menu)) {
            button.foreground = if (button.primary) next.primaryForeground else next.foreground
            button.background = next.background
        }
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        graphics.color = colors.border
        graphics.fillRect(0, height - scale(1), width, scale(1))
    }

    private inner class ToolbarButton(text: String, val primary: Boolean = false) : JButton(text) {
        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isRolloverEnabled = true
            isFocusPainted = false
            font = UIManager.getFont("Label.font").deriveFont(Font.PLAIN, scale(12).toFloat())
            border = BorderFactory.createEmptyBorder(scale(5), scale(9), scale(5), scale(9))
            preferredSize = Dimension(preferredSize.width, scale(28))
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (primary || (isEnabled && (model.isRollover || model.isPressed))) {
                g.color = if (primary) colors.primary else colors.accent
                g.fillRoundRect(0, 0, width, height, scale(12), scale(12))
            }
            if (isFocusOwner) {
                g.color = colors.primary
                g.stroke = BasicStroke(scale(2).toFloat())
                g.drawRoundRect(scale(1), scale(1), width - scale(3), height - scale(3), scale(12), scale(12))
            }
            g.dispose()
            super.paintComponent(graphics)
        }
    }
}
