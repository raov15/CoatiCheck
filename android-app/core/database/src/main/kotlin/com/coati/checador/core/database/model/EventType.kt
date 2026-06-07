package com.coati.checador.core.database.model

object EventType {
    const val CLOCK_IN = "CLOCK_IN"
    const val CLOCK_OUT = "CLOCK_OUT"
    const val MEAL_START = "MEAL_START"
    const val MEAL_END = "MEAL_END"

    fun isValid(value: String) = value in setOf(CLOCK_IN, CLOCK_OUT, MEAL_START, MEAL_END)
}
