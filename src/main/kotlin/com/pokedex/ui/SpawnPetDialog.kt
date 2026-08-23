package com.pokedex.ui

import com.intellij.openapi.project.Project
import com.pokedex.PokedexBundle
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.pokedex.render.SpritePack
import com.pokedex.render.SpritePackRegistry
import com.pokedex.world.Personality
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Dialog for adding a pet: incremental search, group filter, display name and
 * personality.
 *
 * Group values are read from each pack's `gen` property rather than being
 * hard-coded, so importing a new collection populates the filter on its own.
 */
class SpawnPetDialog(project: Project) : DialogWrapper(project) {

    private val allPacks = SpritePackRegistry.all().sortedBy { it.displayName }

    private val searchField = JBTextField()
    private val groupCombo = ComboBox<String>()
    private val nameField = JBTextField()
    private val personalityCombo = ComboBox(Personality.entries.toTypedArray()).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>, value: Any?, index: Int,
                selected: Boolean, focused: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, selected, focused)
                if (value is Personality && c is javax.swing.JLabel) {
                    c.text = PokedexBundle.message("personality.${value.name}")
                }
                return c
            }
        }
    }

    private val listModel = DefaultListModel<SpritePack>()
    private val packList = JBList(listModel)

    /** Populated once the dialog is accepted. */
    var chosenPack: SpritePack? = null
        private set
    var chosenName: String = ""
        private set
    var chosenPersonality: Personality = Personality.CHEERFUL
        private set

    init {
        title = PokedexBundle.message("dialog.spawn.title")
        setOKButtonText(PokedexBundle.message("dialog.spawn.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        // Group filter
        val groups = allPacks.mapNotNull { it.group }.distinct().sorted()
        groupCombo.addItem(ALL_GROUPS)
        groups.forEach { groupCombo.addItem(it) }

        // Pack list
        packList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        packList.cellRenderer = PackRenderer()
        packList.visibleRowCount = 12

        refilter()
        if (listModel.size() > 0) packList.selectedIndex = 0

        packList.addListSelectionListener {
            // Prefill the name field until the user edits it themselves.
            if (!nameTouched) {
                nameField.text = selectedPack()?.displayName ?: ""
            }
        }

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })
        groupCombo.addActionListener { refilter() }

        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (nameField.hasFocus()) nameTouched = true
            }
        })

        // Arrow down moves focus from the search field into the list.
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN && listModel.size() > 0) {
                    packList.requestFocusInWindow()
                    packList.selectedIndex = 0
                }
            }
        })

        // Layout
        val filters = JPanel(java.awt.GridBagLayout()).apply {
            val c = java.awt.GridBagConstraints()
            c.insets = JBUI.insets(2)
            c.fill = java.awt.GridBagConstraints.HORIZONTAL

            c.gridx = 0; c.gridy = 0; c.weightx = 0.0
            add(JBLabel(PokedexBundle.message("dialog.spawn.search")), c)
            c.gridx = 1; c.weightx = 1.0
            add(searchField, c)

            c.gridx = 0; c.gridy = 1; c.weightx = 0.0
            add(JBLabel(PokedexBundle.message("dialog.spawn.group")), c)
            c.gridx = 1; c.weightx = 1.0
            add(groupCombo, c)
        }

        val bottom = JPanel(java.awt.GridBagLayout()).apply {
            val c = java.awt.GridBagConstraints()
            c.insets = JBUI.insets(2)
            c.fill = java.awt.GridBagConstraints.HORIZONTAL

            c.gridx = 0; c.gridy = 0; c.weightx = 0.0
            add(JBLabel(PokedexBundle.message("dialog.spawn.name")), c)
            c.gridx = 1; c.weightx = 1.0
            add(nameField, c)

            c.gridx = 0; c.gridy = 1; c.weightx = 0.0
            add(JBLabel(PokedexBundle.message("dialog.spawn.personality")), c)
            c.gridx = 1; c.weightx = 1.0
            add(personalityCombo, c)
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(380), JBUI.scale(420))
            add(filters, BorderLayout.NORTH)
            add(JBScrollPane(packList), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private var nameTouched = false

    override fun getPreferredFocusedComponent() = searchField

    private fun selectedPack(): SpritePack? = packList.selectedValue

    private fun refilter() {
        val q = searchField.text.trim().lowercase()
        val group = groupCombo.selectedItem as? String ?: ALL_GROUPS

        val previous = selectedPack()?.id
        listModel.clear()

        allPacks.asSequence()
            .filter { group == ALL_GROUPS || it.group == group }
            .filter { q.isEmpty() || it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
            .forEach { listModel.addElement(it) }

        if (listModel.size() == 0) return
        val idx = (0 until listModel.size()).firstOrNull { listModel.get(it).id == previous } ?: 0
        packList.selectedIndex = idx
        if (!nameTouched) nameField.text = listModel.get(idx).displayName
    }

    override fun doValidate(): ValidationInfo? {
        if (allPacks.isEmpty()) {
            return ValidationInfo(PokedexBundle.message("dialog.spawn.noPacks"))
        }
        if (selectedPack() == null) return ValidationInfo(PokedexBundle.message("dialog.spawn.pickOne"), packList)
        return null
    }

    override fun doOKAction() {
        chosenPack = selectedPack()
        chosenName = nameField.text.trim()
            .ifEmpty { chosenPack?.displayName ?: PokedexBundle.message("pet.defaultName") }
        chosenPersonality = personalityCombo.selectedItem as? Personality ?: Personality.random()
        super.doOKAction()
    }

    private class PackRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>, value: Any?, index: Int,
            selected: Boolean, focused: Boolean,
        ): java.awt.Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, focused)
            if (value is SpritePack && c is javax.swing.JLabel) {
                c.text = value.displayName
                c.icon = value.thumbnail()
                c.iconTextGap = JBUI.scale(8)
            }
            return c
        }
    }

    companion object {
        private val ALL_GROUPS: String get() = PokedexBundle.message("dialog.spawn.allGroups")
    }
}