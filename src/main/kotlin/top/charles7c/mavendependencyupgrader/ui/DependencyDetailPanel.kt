package top.charles7c.mavendependencyupgrader.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import top.charles7c.mavendependencyupgrader.model.DependencyInfo
import top.charles7c.mavendependencyupgrader.model.UpdateType
import top.charles7c.mavendependencyupgrader.model.VulnerabilityInfo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * 依赖详情面板，展示选中依赖的版本信息与漏洞详情
 */
class DependencyDetailPanel : JBPanel<DependencyDetailPanel>(BorderLayout()) {

    private val placeholderLabel = JBLabel("选择一个依赖以查看详情", SwingConstants.CENTER).apply {
        foreground = JBColor.GRAY
    }

    private val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val scrollPane = JBScrollPane(contentPanel)

    init {
        border = JBUI.Borders.empty(4)
        add(placeholderLabel, BorderLayout.CENTER)
        scrollPane.border = BorderFactory.createEmptyBorder()
    }

    /**
     * 清空并显示占位提示
     */
    fun clear() {
        removeAll()
        add(placeholderLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * 展示指定依赖的详细信息
     */
    fun showDependency(dep: DependencyInfo) {
        contentPanel.removeAll()
        contentPanel.add(buildDetailContent(dep), BorderLayout.NORTH)

        removeAll()
        add(scrollPane, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun buildDetailContent(dep: DependencyInfo): JPanel {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(8, 8, 4, 8)
        var row = 0

        // ── 基本信息标题 ─────────────────────────
        panel.addTitle("基本信息", row++)

        panel.addRow("GroupId", dep.groupId, row++)
        panel.addRow("ArtifactId", dep.artifactId, row++)
        panel.addRow("当前版本", dep.currentVersion, row++)
        panel.addRow("范围", dep.scope, row++)

        if (dep.isPropertyVersion && dep.propertyName != null) {
            panel.addRow("版本属性", "\${${dep.propertyName}}", row++)
        }

        // ── 版本信息 ──────────────────────────────
        panel.addSeparator(row++)
        panel.addTitle("版本信息", row++)

        val latestText = when {
            dep.isVersionLoading -> "加载中..."
            dep.versionFetched && dep.latestVersion == null -> "查询失败（网络不可用）"
            dep.latestVersion != null -> dep.latestVersion!!
            else -> "-"
        }
        panel.addRow("最新版本", latestText, row++)

        if (dep.hasUpdate) {
            val updateColor = when (dep.updateType) {
                UpdateType.MAJOR -> JBColor(Color(0xCC2222), Color(0xFF6B6B))
                UpdateType.MINOR -> JBColor(Color(0xCC7700), Color(0xFFA040))
                UpdateType.PATCH -> JBColor(Color(0x2E7D32), Color(0x66BB6A))
                else -> JBColor.foreground()
            }
            panel.addRow("升级类型", dep.updateType.label, row++, valueColor = updateColor)
        } else if (dep.updateType == UpdateType.UP_TO_DATE) {
            panel.addRow("升级类型", "已是最新版本", row++, valueColor = JBColor(Color(0x2E7D32), Color(0x66BB6A)))
        }

        // ── 安全漏洞 ──────────────────────────────
        panel.addSeparator(row++)
        panel.addTitle("安全漏洞", row++)

        when {
            dep.isVulnLoading -> {
                panel.addInfoLabel("正在扫描漏洞...", row++, JBColor.GRAY)
            }
            !dep.vulnFetched -> {
                panel.addInfoLabel("点击工具栏「扫描漏洞」按钮开始检查", row++, JBColor.GRAY)
            }
            dep.vulnerabilities.isEmpty() -> {
                panel.addInfoLabel("未发现已知安全漏洞", row++, JBColor(Color(0x2E7D32), Color(0x66BB6A)))
            }
            else -> {
                val vulnCount = dep.vulnerabilities.size
                val maxSeverity = dep.maxSeverity
                val severityColor = severityColor(maxSeverity)
                panel.addInfoLabel(
                    "发现 $vulnCount 个漏洞（最高严重等级：${maxSeverity?.label ?: "未知"}）",
                    row++, severityColor
                )

                dep.vulnerabilities.forEach { vuln ->
                    row = addVulnerabilityCard(panel, vuln, row)
                }
            }
        }

        // 底部填充空白
        val filler = GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2
            fill = GridBagConstraints.BOTH; weighty = 1.0
        }
        panel.add(Box.createVerticalGlue(), filler)

        return panel
    }

    private fun addVulnerabilityCard(panel: JPanel, vuln: VulnerabilityInfo, startRow: Int): Int {
        var row = startRow
        val color = severityColor(vuln.severity)

        // 漏洞 ID + 严重等级
        val headerLabel = JBLabel("${vuln.id}  [${vuln.severity.label}]").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = color
            border = JBUI.Borders.emptyTop(6)
        }
        panel.add(headerLabel, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
        row++

        // 标题
        if (vuln.title.isNotBlank() && vuln.title != vuln.id) {
            val titleLabel = JBLabel("<html><i>${vuln.title}</i></html>").apply {
                foreground = JBColor.foreground()
            }
            panel.add(titleLabel, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
            row++
        }

        // CVSS 评分
        if (vuln.cvssScore > 0f) {
            panel.addRow("CVSS 评分", String.format("%.1f", vuln.cvssScore), row++, valueColor = color)
        }

        // 描述（截断超长文本）
        if (vuln.description.isNotBlank()) {
            val desc = if (vuln.description.length > 300) vuln.description.take(300) + "..." else vuln.description
            val descLabel = JLabel("<html><div style='width:380px;'>$desc</div></html>").apply {
                foreground = JBColor.foreground()
                border = JBUI.Borders.emptyBottom(4)
            }
            panel.add(descLabel, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
            row++
        }

        return row
    }

    // ── 辅助布局方法 ─────────────────────────────────────────────

    private fun JPanel.addTitle(text: String, row: Int) {
        val label = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            border = JBUI.Borders.emptyTop(if (row == 0) 0 else 4)
        }
        add(label, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun JPanel.addSeparator(row: Int) {
        val sep = JSeparator().apply { border = JBUI.Borders.emptyTop(4) }
        add(sep, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun JPanel.addRow(label: String, value: String, row: Int, valueColor: Color? = null) {
        val keyLabel = JBLabel("$label：").apply {
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.RIGHT
        }
        val valLabel = JBLabel(value).apply {
            if (valueColor != null) foreground = valueColor
        }
        add(keyLabel, gbc(0, row, 1, weightx = 0.0, ipadx = 4))
        add(valLabel, gbc(1, row, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun JPanel.addInfoLabel(text: String, row: Int, color: Color) {
        val label = JBLabel(text).apply { foreground = color }
        add(label, gbc(0, row, 2, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun gbc(
        x: Int, y: Int, width: Int,
        fill: Int = GridBagConstraints.NONE,
        weightx: Double = 0.0,
        ipadx: Int = 0
    ) = GridBagConstraints().apply {
        gridx = x; gridy = y; gridwidth = width
        this.fill = fill; this.weightx = weightx; this.ipadx = ipadx
        insets = JBUI.insets(2, 2, 0, 2)
        anchor = GridBagConstraints.WEST
    }

    private fun severityColor(severity: VulnerabilityInfo.Severity?): Color = when (severity) {
        VulnerabilityInfo.Severity.CRITICAL -> JBColor(Color(0xAA0000), Color(0xFF5555))
        VulnerabilityInfo.Severity.HIGH -> JBColor(Color(0xCC2222), Color(0xFF7777))
        VulnerabilityInfo.Severity.MEDIUM -> JBColor(Color(0xCC7700), Color(0xFFAA33))
        VulnerabilityInfo.Severity.LOW -> JBColor(Color(0x886600), Color(0xCCAA00))
        else -> JBColor.GRAY
    }
}
