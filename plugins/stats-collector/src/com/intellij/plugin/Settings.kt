package com.intellij.plugin

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.sorting.SortingTimeStatistics
import com.intellij.stats.completion.experiment.WebServiceStatus
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import java.awt.event.ActionEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel


class PluginSettingsConfigurableProvider : ConfigurableProvider() {
    override fun createConfigurable() = PluginSettingsConfigurable()
    override fun canCreateConfigurable() = ApplicationManager.getApplication().isInternal
}

class PluginSettingsConfigurable : Configurable {

    private lateinit var manualControlCb: JBCheckBox
    private lateinit var manualSortingCb: JBCheckBox

    override fun isModified(): Boolean {
        val isModifiedStates = mutableListOf<Boolean>()
        isModifiedStates += manualControlCb.isSelected != ManualExperimentControl.isOn
        
        if (manualControlCb.isSelected) {
            isModifiedStates += manualSortingCb.isSelected == ManualMlSorting.isOn    
        }
        
        return isModifiedStates.contains(true)
    }

    override fun getDisplayName() = "Completion Stats Collector"

    override fun apply() {
        ManualExperimentControl.isOn = manualControlCb.isSelected
        if (ManualExperimentControl.isOn) {
            ManualMlSorting.isOn = manualSortingCb.isSelected
        }
    }

    override fun createComponent(): JComponent? {
        val manualControlPanel = manualControlCheckBoxPanel()
        val timingPanel = timingPanel()
        val autoExperimentPanel = autoExperimentStatusPanel()
        val manualExperimentPanel = manualExperimentPanel()

        val updateStatus: (ActionEvent?) -> Unit = {
            val inManualExperimentMode = manualControlCb.isSelected
            autoExperimentPanel.isVisible = !inManualExperimentMode
            manualExperimentPanel.isVisible = inManualExperimentMode
        }
        
        manualControlCb.addActionListener(updateStatus)
        
        val panel = JPanel()
        panel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(timingPanel)
            add(manualControlPanel)
            add(manualExperimentPanel)
            add(autoExperimentPanel)
        }
        
        updateStatus(null)

        return panel
    }

    private fun manualControlCheckBoxPanel(): JPanel {
        manualControlCb = JBCheckBox("Control experiment manually", ManualExperimentControl.isOn).apply { 
            border = IdeBorderFactory.createEmptyBorder()
        }
        
        val panel = JPanel()
        return panel.apply {
            layout = BoxLayout(panel, BoxLayout.Y_AXIS)    
            add(manualControlCb)
        }
    }

    private fun manualExperimentPanel(): JPanel {
        val action = ActionManager.getInstance().getAction("ToggleManualMlSorting")
        val shortcuts = action.shortcutSet.shortcuts.firstOrNull()?.let { " $it" } ?: ""
        val text = "(can be changed by \"${action.templatePresentation.text}\" action$shortcuts)"
        
        manualSortingCb = JBCheckBox("Enable sorting $text", ManualMlSorting.isOn).apply { 
            border = IdeBorderFactory.createEmptyBorder()
        }
        
        val panel = JPanel()
        return panel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = IdeBorderFactory.createEmptyBorder(5, 0, 0, 0)
            add(manualSortingCb)
        }
    }
    
    private fun timingPanel(): JPanel {
        val panel = JPanel()
        
        val stats = SortingTimeStatistics.getInstance()
        val time: List<String> = stats.state.getTimeDistribution()
        val avgTime: List<String> = stats.state.getAvgTimeByElementsSortedDistribution()
        
        return panel.apply {
            border = IdeBorderFactory.createEmptyBorder(5)
            layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            
            if (time.isNotEmpty()) {
                add(JBLabel("<html><b>Time to Sorts Number Distribution:</b></html>"))
                time.forEach { add(JBLabel(it)) }
            }
            if (avgTime.isNotEmpty()) {
                add(JBLabel("<html><b>Elements Count to Avg Sorting Time:</b></html>"))
                avgTime.forEach { add(JBLabel(it)) }
            }
            if (time.isEmpty() && avgTime.isEmpty()) {
                add(JBLabel("<html><b>No Stats Available</b></html>"))
            }
        }
    }
    
    private fun autoExperimentStatusPanel(): JPanel {
        val status = WebServiceStatus.getInstance()
        val isExperimentOnCurrentIDE = status.isExperimentOnCurrentIDE()
        val isExperimentGoingOnNow = status.isExperimentGoingOnNow()

        val panel = JPanel()
        return panel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = IdeBorderFactory.createEmptyBorder(5)
            add(JBLabel("<html><b>Is experiment going on now:</b> $isExperimentGoingOnNow</html>"))
            add(JBLabel("<html><b>Is experiment on current IDE:</b> $isExperimentOnCurrentIDE</html>"))
        }
    }

    override fun reset() {
        manualControlCb.isSelected = ManualExperimentControl.isOn
        manualSortingCb.isSelected = ManualMlSorting.isOn
    }

    override fun getHelpTopic(): String? = null

    override fun disposeUIResources() = Unit
    
}