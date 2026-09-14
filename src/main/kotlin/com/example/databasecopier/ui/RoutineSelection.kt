package com.example.databasecopier.ui

import com.example.databasecopier.adapter.RoutineKind
import com.example.databasecopier.i18n.Messages
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty

class RoutineSelection(name: String, val kind: RoutineKind, selected: Boolean) {
    val nameProperty = SimpleStringProperty(name)
    val kindProperty = SimpleStringProperty(
        Messages.get(if (kind == RoutineKind.FUNCTION) "source.routines.kind.function" else "source.routines.kind.procedure")
    )
    val selectedProperty = SimpleBooleanProperty(selected)

    val name: String get() = nameProperty.get()
    val isSelected: Boolean get() = selectedProperty.get()
}
