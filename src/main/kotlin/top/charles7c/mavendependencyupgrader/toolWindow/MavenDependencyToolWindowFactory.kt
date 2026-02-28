package top.charles7c.mavendependencyupgrader.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import top.charles7c.mavendependencyupgrader.ui.MavenDependencyPanel

/**
 * Maven 依赖管理工具窗口工厂
 *
 * 负责创建并注册"Maven 依赖管理"工具窗口内容
 */
class MavenDependencyToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MavenDependencyPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * 仅在 Maven 项目中显示工具窗口
     */
    override fun shouldBeAvailable(project: Project): Boolean = true
}
