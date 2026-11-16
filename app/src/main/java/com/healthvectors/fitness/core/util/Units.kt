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

    /**
     * Weight jump used for progression suggestions. An exercise's own [overrideKg] wins; otherwise it
     * follows the equipment: barbells 2.5 kg / 5 lb, machines and cables 5 kg / 10 lb, everything
     * else (dumbbells, bodyweight) 2 kg / 5 lb.
     */
    fun stepKg(unit: UnitSystem, equipment: String, overrideKg: Double? = null): Double {
        if (overrideKg != null && overrideKg > 0) return overrideKg
        val e = equipment.lowercase()
        val metric = unit == UnitSystem.METRIC
        return when {
            "barbell" in e || "ez bar" in e || "smith" in e -> if (metric) 2.5 else lbToKg(5.0)
            listOf("machine", "cable", "pec deck", "leg press").any { it in e } -> if (metric) 5.0 else lbToKg(10.0)
            else -> defaultStepKg(unit)
        }
    }

    /** Standard bar (20 kg / 45 lb) in display units. */
    fun defaultBar(unit: UnitSystem) = if (unit == UnitSystem.METRIC) 20.0 else 45.0

    /** Common plate sets in display units. */
    fun defaultPlates(unit: UnitSystem) =
        if (unit == UnitSystem.METRIC) listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25) else listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)

    /** Formats without trailing zeros (60.0 -> "60", 62.5 -> "62.5"). */
    fun fmt(value: Double): String =
        if (kotlin.math.abs(value - Math.round(value)) < 0.05) Math.round(value).toString()
        else String.format(Locale.US, "%.1f", value)

    fun fmtWeight(kg: Double, unit: UnitSystem) = fmt(weightToDisplay(kg, unit)) + " " + weightLabel(unit)
}
