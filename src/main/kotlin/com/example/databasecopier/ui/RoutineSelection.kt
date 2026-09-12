package com.example.databasecopier.ui

import com.example.databasecopier.adapter.RoutineKind
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty

class RoutineSelection(name: String, val kind: RoutineKind, selected: Boolean) {
    val nameProperty = SimpleStringProperty(name)
    val kindProperty = SimpleStringProperty(if (kind == RoutineKind.FUNCTION) "Функция" else "Процедура")
    val selectedProperty = SimpleBooleanProperty(selected)

    val name: String get() = nameProperty.get()
    val isSelected: Boolean get() = selectedProperty.get()
}
