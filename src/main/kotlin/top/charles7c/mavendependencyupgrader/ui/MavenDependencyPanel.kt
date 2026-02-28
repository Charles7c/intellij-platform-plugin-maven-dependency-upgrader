package top.charles7c.mavendependencyupgrader.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import top.charles7c.mavendependencyupgrader.model.DependencyInfo
import top.charles7c.mavendependencyupgrader.model.UpdateType
import top.charles7c.mavendependencyupgrader.model.VulnerabilityInfo
import top.charles7c.mavendependencyupgrader.service.MavenDependencyService
import top.charles7c.mavendependencyupgrader.service.MavenVersionService
import top.charles7c.mavendependencyupgrader.service.VulnerabilityService
import top.charles7c.mavendependencyupgrader.util.VersionUtils
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Maven 依赖管理主面板，包含工具栏、过滤栏、依赖表格和详情面板
 */
class MavenDependencyPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = DependencyTableModel()
    private val table = buildTable()
    private val detailPanel = DependencyDetailPanel()
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY }

    // 版本获取执行器（最多 10 个并发请求）
    private var versionFetchExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
        "MavenVersionFetcher", 10
    )

    init {
        build()
        setupMavenListener()
        loadDependencies()
    }

    // ──────────────────────────── 构建 UI ────────────────────────────

    private fun build() {
        val toolbar = buildToolbar()
        val filterBar = buildFilterBar()
        val topPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(filterBar, BorderLayout.SOUTH)
        }

        val splitter = OnePixelSplitter(true, 0.65f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = detailPanel
            setHonorComponentsMinimumSize(true)
        }

        add(topPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    private fun buildTable(): JBTable {
        val t = JBTable(tableModel).apply {
            rowSelectionAllowed = true
            columnSelectionAllowed = false
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            emptyText.text = "暂无依赖（请打开 Maven 项目）"
            fillsViewportHeight = true
        }

        // 列宽设置
        t.columnModel.getColumn(DependencyTableModel.COL_GROUP_ID).preferredWidth = 160
        t.columnModel.getColumn(DependencyTableModel.COL_ARTIFACT_ID).preferredWidth = 180
        t.columnModel.getColumn(DependencyTableModel.COL_CURRENT_VERSION).preferredWidth = 90
        t.columnModel.getColumn(DependencyTableModel.COL_LATEST_VERSION).preferredWidth = 90
        t.columnModel.getColumn(DependencyTableModel.COL_UPDATE_TYPE).preferredWidth = 70
        t.columnModel.getColumn(DependencyTableModel.COL_VULNERABILITY).preferredWidth = 80
        t.columnModel.getColumn(DependencyTableModel.COL_SCOPE).preferredWidth = 70

        // 自定义渲染器
        t.columnModel.getColumn(DependencyTableModel.COL_UPDATE_TYPE).cellRenderer = UpdateTypeCellRenderer()
        t.columnModel.getColumn(DependencyTableModel.COL_VULNERABILITY).cellRenderer = VulnerabilityCellRenderer()

        // 支持列排序
        val sorter = TableRowSorter(tableModel)
        sorter.setComparator(DependencyTableModel.COL_UPDATE_TYPE, Comparator<UpdateType> { a, b ->
            val order = listOf(UpdateType.MAJOR, UpdateType.MINOR, UpdateType.PATCH, UpdateType.UNKNOWN, UpdateType.UP_TO_DATE)
            order.indexOf(a).compareTo(order.indexOf(b))
        })
        t.rowSorter = sorter

        // 选中行时更新详情面板
        t.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = t.selectedRow
                if (selected >= 0) {
                    val modelRow = t.convertRowIndexToModel(selected)
                    tableModel.getDependencyAt(modelRow)?.let { dep -> detailPanel.showDependency(dep) }
                } else {
                    detailPanel.clear()
                }
            }
        }

        return t
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            add(FetchVersionsAction())
            addSeparator()
            add(ScanVulnerabilitiesAction())
            addSeparator()
            add(UpgradeSelectedAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("MavenDependencyUpgrader", group, true)
        toolbar.targetComponent = this
        return toolbar
    }

    private fun buildFilterBar(): JPanel {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.setText("搜索依赖（GroupId / ArtifactId）")
            textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onTextChange()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onTextChange()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onTextChange()
                private fun onTextChange() { tableModel.searchText = this@apply.text }
            })
        }

        val hasUpdateCheckbox = JBCheckBox("有更新").apply {
            addActionListener { tableModel.filterHasUpdate = isSelected }
        }
        val hasVulnCheckbox = JBCheckBox("有漏洞").apply {
            addActionListener { tableModel.filterHasVulnerability = isSelected }
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
            add(searchField)
            add(hasUpdateCheckbox)
            add(hasVulnCheckbox)
            add(statusLabel)
        }
    }

    // ──────────────────────────── 业务操作 ────────────────────────────

    /**
     * 监听 Maven 导入完成事件，自动刷新依赖列表
     */
    private fun setupMavenListener() {
        val mavenManager = MavenProjectsManager.getInstance(project)
        mavenManager.addManagerListener(
            object : MavenProjectsManager.Listener {
                override fun projectImportCompleted() {
                    invokeLaterIfActive { loadDependencies() }
                }
            },
            project  // 绑定生命周期：项目关闭时自动移除监听器
        )
    }

    /**
     * 从 pom.xml 加载依赖列表
     */
    private fun loadDependencies() {
        val mavenManager = MavenProjectsManager.getInstance(project)

        if (!mavenManager.isMavenizedProject) {
            table.emptyText.text = "当前项目不是 Maven 项目"
            updateStatus("非 Maven 项目")
            return
        }

        updateStatus("正在扫描 pom.xml...")
        table.emptyText.text = "正在加载..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val deps = try {
                project.service<MavenDependencyService>().getDependencies()
            } catch (e: Exception) {
                emptyList()
            }

            invokeLaterIfActive {
                tableModel.setDependencies(deps)
                if (deps.isEmpty()) {
                    val stillImporting = MavenProjectsManager.getInstance(project).projects.isEmpty()
                    table.emptyText.text = if (stillImporting)
                        "Maven 项目正在导入中，完成后将自动刷新..."
                    else
                        "未找到依赖（pom.xml 中无直接声明的依赖）"
                    updateStatus(if (stillImporting) "等待 Maven 导入完成..." else "已加载 0 个依赖")
                } else {
                    table.emptyText.text = ""
                    updateStatus("已加载 ${deps.size} 个依赖")
                }
            }
        }
    }

    /**
     * 在后台获取所有依赖的最新版本（逐个并发查询）
     */
    private fun fetchVersions() {
        val deps = tableModel.getAllDependencies()
        if (deps.isEmpty()) {
            updateStatus("暂无依赖可查询")
            return
        }

        updateStatus("正在获取版本信息（共 ${deps.size} 个依赖）...")

        // 标记所有依赖为加载中
        deps.forEach { it.isVersionLoading = true }
        tableModel.applyFilters()

        val versionService = ApplicationManager.getApplication().getService(MavenVersionService::class.java)
        val total = deps.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)

        // 重建执行器（清除前一次未完成的任务）
        versionFetchExecutor.shutdownNow()
        versionFetchExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("MavenVersionFetcher", 10)

        deps.forEach { dep ->
            versionFetchExecutor.submit {
                try {
                    val latest = versionService.getLatestStableVersion(dep.groupId, dep.artifactId)
                    dep.latestVersion = latest
                    dep.updateType = if (latest != null) {
                        VersionUtils.determineUpdateType(dep.currentVersion, latest)
                    } else UpdateType.UNKNOWN
                } finally {
                    dep.isVersionLoading = false
                    dep.versionFetched = true
                    val done = completed.incrementAndGet()
                    invokeLaterIfActive {
                        tableModel.notifyDependencyUpdated(dep)
                        if (done == total) {
                            val hasUpdate = tableModel.getAllDependencies().count { it.hasUpdate }
                            updateStatus("版本信息已更新，$hasUpdate 个依赖可升级")
                        } else {
                            updateStatus("正在获取版本信息（$done / $total）...")
                        }
                    }
                }
            }
        }
    }

    /**
     * 在后台扫描所有依赖的安全漏洞
     */
    private fun scanVulnerabilities() {
        val deps = tableModel.getAllDependencies()
        if (deps.isEmpty()) {
            updateStatus("暂无依赖可扫描")
            return
        }

        updateStatus("正在扫描安全漏洞（共 ${deps.size} 个依赖）...")
        deps.forEach { it.isVulnLoading = true }
        tableModel.applyFilters()

        val vulnService = ApplicationManager.getApplication().getService(VulnerabilityService::class.java)

        ApplicationManager.getApplication().executeOnPooledThread {
            val resultMap = try {
                vulnService.checkVulnerabilities(deps)
            } catch (e: Exception) {
                emptyMap()
            }

            invokeLaterIfActive {
                deps.forEach { dep ->
                    dep.vulnerabilities = resultMap[dep.key] ?: emptyList()
                    dep.isVulnLoading = false
                    dep.vulnFetched = true
                }
                tableModel.applyFilters()

                val vulnCount = deps.count { it.hasVulnerability }
                updateStatus(if (vulnCount > 0) "发现 $vulnCount 个依赖存在已知漏洞" else "未发现已知安全漏洞")

                // 若当前选中行有漏洞数据，刷新详情面板
                val selectedRow = table.selectedRow
                if (selectedRow >= 0) {
                    val modelRow = table.convertRowIndexToModel(selectedRow)
                    tableModel.getDependencyAt(modelRow)?.let { detailPanel.showDependency(it) }
                }
            }
        }
    }

    /**
     * 升级选中的依赖到最新版本（支持批量）
     */
    private fun upgradeSelected() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            Messages.showInfoMessage(project, "请先在列表中选择要升级的依赖", "提示")
            return
        }

        val upgradeable = selectedRows.toList().mapNotNull { row ->
            val modelRow = table.convertRowIndexToModel(row)
            tableModel.getDependencyAt(modelRow)
        }.filter { it.latestVersion != null && it.hasUpdate }

        if (upgradeable.isEmpty()) {
            Messages.showInfoMessage(project, "选中的依赖均已是最新版本或尚未获取版本信息", "提示")
            return
        }

        val message = if (upgradeable.size == 1) {
            val dep = upgradeable[0]
            "将 ${dep.groupId}:${dep.artifactId}\n从 ${dep.currentVersion} 升级到 ${dep.latestVersion}？"
        } else {
            val lines = upgradeable.joinToString("\n") { "• ${it.artifactId}: ${it.currentVersion} → ${it.latestVersion}" }
            "确认升级以下 ${upgradeable.size} 个依赖？\n\n$lines"
        }

        val result = Messages.showYesNoDialog(project, message, "确认升级", Messages.getQuestionIcon())
        if (result != Messages.YES) return

        val depService = project.service<MavenDependencyService>()
        val upgrades = upgradeable.mapNotNull { dep -> dep.latestVersion?.let { dep to it } }

        updateStatus("正在升级...")
        ApplicationManager.getApplication().executeOnPooledThread {
            depService.upgradeDependencies(upgrades)
            invokeLaterIfActive {
                updateStatus("升级完成，正在重新加载...")
                loadDependencies()
            }
        }
    }

    private fun updateStatus(text: String) {
        statusLabel.text = text
    }

    /**
     * 仅当项目未销毁时在 EDT 执行代码块，避免内存泄漏
     */
    private fun invokeLaterIfActive(block: () -> Unit) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) block()
        }
    }

    // ──────────────────────────── 工具栏 Actions ────────────────────────────

    private inner class RefreshAction : AnAction("刷新依赖列表", "重新扫描 pom.xml", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = loadDependencies()
    }

    private inner class FetchVersionsAction : AnAction(
        "获取最新版本", "从 Maven Central 查询最新稳定版本", AllIcons.Actions.Download
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = fetchVersions()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tableModel.getAllDependencies().isNotEmpty()
        }
    }

    private inner class ScanVulnerabilitiesAction : AnAction(
        "扫描安全漏洞", "通过 Sonatype OSS Index 检查已知漏洞", AllIcons.Debugger.Db_exception_breakpoint
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = scanVulnerabilities()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tableModel.getAllDependencies().isNotEmpty()
        }
    }

    private inner class UpgradeSelectedAction : AnAction(
        "升级所选依赖", "将选中的依赖升级到最新版本", AllIcons.Actions.Upload
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = upgradeSelected()
        override fun update(e: AnActionEvent) {
            val hasSelected = table.selectedRows.isNotEmpty()
            val hasUpgradeable = table.selectedRows.any { row ->
                val modelRow = table.convertRowIndexToModel(row)
                tableModel.getDependencyAt(modelRow)?.hasUpdate == true
            }
            e.presentation.isEnabled = hasSelected && hasUpgradeable
        }
    }

    // ──────────────────────────── 自定义单元格渲染器 ────────────────────────────

    private class UpdateTypeCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            val type = value as? UpdateType
            comp.text = type?.label ?: "-"
            comp.horizontalAlignment = SwingConstants.CENTER
            if (!isSelected && type != null) {
                comp.foreground = when (type) {
                    UpdateType.MAJOR -> JBColor(Color(0xCC2222), Color(0xFF7777))
                    UpdateType.MINOR -> JBColor(Color(0xCC7700), Color(0xFFAA33))
                    UpdateType.PATCH -> JBColor(Color(0x2E7D32), Color(0x66BB6A))
                    UpdateType.UP_TO_DATE -> JBColor(Color(0x6A8759), Color(0x6A8759))
                    UpdateType.UNKNOWN -> UIUtil.getLabelDisabledForeground()
                }
            }
            return comp
        }
    }

    private class VulnerabilityCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            val dep = value as? DependencyInfo
            comp.horizontalAlignment = SwingConstants.CENTER

            when {
                dep == null -> comp.text = "-"
                dep.isVulnLoading -> {
                    comp.text = "扫描中..."
                    comp.foreground = if (isSelected) table.selectionForeground else UIUtil.getLabelDisabledForeground()
                }
                dep.vulnerabilities.isEmpty() -> {
                    comp.text = if (dep.vulnFetched) "✓" else "-"
                    comp.foreground = if (isSelected) table.selectionForeground
                    else if (dep.vulnFetched) JBColor(Color(0x2E7D32), Color(0x66BB6A))
                    else UIUtil.getLabelDisabledForeground()
                }
                else -> {
                    val count = dep.vulnerabilities.size
                    comp.text = "$count 个"
                    if (!isSelected) {
                        comp.foreground = when (dep.maxSeverity) {
                            VulnerabilityInfo.Severity.CRITICAL -> JBColor(Color(0xAA0000), Color(0xFF5555))
                            VulnerabilityInfo.Severity.HIGH -> JBColor(Color(0xCC2222), Color(0xFF7777))
                            VulnerabilityInfo.Severity.MEDIUM -> JBColor(Color(0xCC7700), Color(0xFFAA33))
                            else -> JBColor(Color(0x886600), Color(0xCCAA00))
                        }
                    }
                }
            }
            return comp
        }
    }
}
