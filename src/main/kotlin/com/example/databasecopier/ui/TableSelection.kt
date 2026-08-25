package com.example.databasecopier.ui

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty

class TableSelection(name: String, selected: Boolean) {
    val nameProperty = SimpleStringProperty(name)
    val selectedProperty = SimpleBooleanProperty(selected)

    val name: String get() = nameProperty.get()
    val isSelected: Boolean get() = selectedProperty.get()
}
