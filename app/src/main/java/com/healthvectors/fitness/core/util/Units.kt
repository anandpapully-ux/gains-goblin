package com.healthvectors.fitness.core.util

import com.healthvectors.fitness.domain.model.UnitSystem
import java.util.Locale

/** Unit conversion helpers. Storage is always kg/cm; these convert for display and input only. */
object Units {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_IN = 2.54

    fun kgToLb(kg: Double) = kg / KG_PER_LB
    fun lbToKg(lb: Double) = lb * KG_PER_LB

    fun weightToDisplay(kg: Double, unit: UnitSystem) =
        if (unit == UnitSystem.METRIC) kg else kgToLb(kg)

    fun weightFromDisplay(value: Double, unit: UnitSystem) =
        if (unit == UnitSystem.METRIC) value else lbToKg(value)

    fun weightLabel(unit: UnitSystem) = if (unit == UnitSystem.METRIC) "kg" else "lb"

    /** Default smallest dumbbell jump: 2 kg or 5 lb. */
    fun defaultStepKg(unit: UnitSystem) = if (unit == UnitSystem.METRIC) 2.0 else lbToKg(5.0)

    /** Formats without trailing zeros (60.0 -> "60", 62.5 -> "62.5"). */
    fun fmt(value: Double): String =
        if (kotlin.math.abs(value - Math.round(value)) < 0.05) Math.round(value).toString()
        else String.format(Locale.US, "%.1f", value)

    fun fmtWeight(kg: Double, unit: UnitSystem) = fmt(weightToDisplay(kg, unit)) + " " + weightLabel(unit)
}
