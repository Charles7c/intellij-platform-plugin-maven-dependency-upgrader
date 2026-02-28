package top.charles7c.mavendependencyupgrader.ui

import top.charles7c.mavendependencyupgrader.model.DependencyInfo
import top.charles7c.mavendependencyupgrader.model.UpdateType
import top.charles7c.mavendependencyupgrader.model.VulnerabilityInfo
import javax.swing.table.AbstractTableModel

/**
 * 依赖列表表格数据模型，支持关键字搜索与多维度过滤
 */
class DependencyTableModel : AbstractTableModel() {

    companion object {
        const val COL_GROUP_ID = 0
        const val COL_ARTIFACT_ID = 1
        const val COL_CURRENT_VERSION = 2
        const val COL_LATEST_VERSION = 3
        const val COL_UPDATE_TYPE = 4
        const val COL_VULNERABILITY = 5
        const val COL_SCOPE = 6

        private val COLUMN_NAMES = arrayOf(
            "GroupId", "ArtifactId", "当前版本", "最新版本", "升级类型", "安全漏洞", "范围"
        )
    }

    // 全量依赖列表
    private val allDependencies = mutableListOf<DependencyInfo>()

    // 过滤后的依赖列表（用于显示）
    private val filteredDependencies = mutableListOf<DependencyInfo>()

    // 过滤条件
    var searchText: String = ""
        set(value) {
            field = value
            applyFilters()
        }
    var filterHasUpdate: Boolean = false
        set(value) {
            field = value
            applyFilters()
        }
    var filterHasVulnerability: Boolean = false
        set(value) {
            field = value
            applyFilters()
        }

    /**
     * 替换整个依赖列表
     */
    fun setDependencies(deps: List<DependencyInfo>) {
        allDependencies.clear()
        allDependencies.addAll(deps)
        applyFilters()
    }

    /**
     * 通知指定依赖的数据已更新（如版本或漏洞信息加载完成）
     */
    fun notifyDependencyUpdated(dep: DependencyInfo) {
        val filtered = filteredDependencies.indexOf(dep)
        if (filtered >= 0) {
            fireTableRowsUpdated(filtered, filtered)
        }
        // 若过滤后不存在但全量中存在，说明过滤条件已匹配，重新应用
        if (filtered < 0 && allDependencies.contains(dep)) {
            applyFilters()
        }
    }

    /**
     * 重新应用当前过滤条件，刷新 filteredDependencies
     */
    fun applyFilters() {
        filteredDependencies.clear()
        filteredDependencies.addAll(allDependencies.filter { dep ->
            val matchesSearch = searchText.isBlank() ||
                    dep.groupId.contains(searchText, ignoreCase = true) ||
                    dep.artifactId.contains(searchText, ignoreCase = true)
            val matchesUpdate = !filterHasUpdate || dep.hasUpdate
            val matchesVuln = !filterHasVulnerability || dep.hasVulnerability
            matchesSearch && matchesUpdate && matchesVuln
        })
        fireTableDataChanged()
    }

    /** 获取过滤后第 row 行对应的 DependencyInfo */
    fun getDependencyAt(row: Int): DependencyInfo? = filteredDependencies.getOrNull(row)

    /** 返回全量依赖列表（未过滤） */
    fun getAllDependencies(): List<DependencyInfo> = allDependencies.toList()

    /** 返回当前可见的过滤后依赖列表 */
    fun getFilteredDependencies(): List<DependencyInfo> = filteredDependencies.toList()

    override fun getRowCount(): Int = filteredDependencies.size

    override fun getColumnCount(): Int = COLUMN_NAMES.size

    override fun getColumnName(column: Int): String = COLUMN_NAMES.getOrElse(column) { "" }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_UPDATE_TYPE -> UpdateType::class.java
        COL_VULNERABILITY -> DependencyInfo::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val dep = filteredDependencies.getOrNull(rowIndex) ?: return ""
        return when (columnIndex) {
            COL_GROUP_ID -> dep.groupId
            COL_ARTIFACT_ID -> dep.artifactId
            COL_CURRENT_VERSION -> dep.currentVersion
            COL_LATEST_VERSION -> when {
                dep.isVersionLoading -> "加载中..."
                dep.versionFetched && dep.latestVersion == null -> "查询失败"
                dep.latestVersion != null -> dep.latestVersion!!
                else -> "-"
            }
            COL_UPDATE_TYPE -> dep.updateType
            COL_VULNERABILITY -> dep  // 由 renderer 处理显示逻辑
            COL_SCOPE -> dep.scope
            else -> ""
        }
    }
}
