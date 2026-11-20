package de.edgefix.core

/**
 * Detailform der Randmaske für den Abgleich per Auge: je Kante 16 Stützpunkte
 * mit eigener Breite und Stärke (dazwischen Catmull-Rom-Spline) und 8 Stellen
 * mit eigenem Farbton (4 Ecken, 4 Kantenmitten, dazwischen linear).
 *
 * Laufrichtung t ∈ [0, 1] je Kante: oben und unten von links nach rechts,
 * links und rechts von oben nach unten.
 */
data class EdgeShape(
    /** Breite je Stützpunkt, Anteil der Bildhöhe */
    val width: Map<Edge, List<Float>>,
    /** Stärke je Stützpunkt (wie Edges.strength) */
    val strength: Map<Edge, List<Float>>,
    /**
     * Farbton der Gegenfarbe an 8 Stellen in Grad, Reihenfolge [Spot], oder null
     * = überall der globale Farbton.
     */
    val spotHue: List<Float>? = null,
    /** Verstärkung an den 8 Stellen (aus dem Farbabgleich), 1 = neutral */
    val spotGain: List<Float>? = null,
) {
    fun clamped() = copy(
        width = width.mapValues { (_, v) -> v.map { it.coerceIn(0f, EdgeParams.MAX_WIDTH) } },
        strength = strength.mapValues { (_, v) -> v.map { it.coerceIn(0f, EdgeParams.MAX_STRENGTH) } },
        spotHue = spotHue?.map { ((it % 360f) + 360f) % 360f },
        spotGain = spotGain?.map { it.coerceIn(0f, 3f) },
    )

    fun withPoint(e: Edge, i: Int, w: Float? = null, s: Float? = null) = copy(
        width = if (w == null) width else width + (e to width.getValue(e).toMutableList().also { it[i] = w }),
        strength = if (s == null) strength else strength + (e to strength.getValue(e).toMutableList().also { it[i] = s }),
    ).clamped()

    companion object {
        const val POINTS = 16

        /** Startform aus den einfachen Werten je Kante. */
        fun from(p: EdgeParams) = EdgeShape(
            width = Edge.entries.associateWith { e -> List(POINTS) { p.width.get(e) } },
            strength = Edge.entries.associateWith { e -> List(POINTS) { p.strength.get(e) } },
        )

        /** Catmull-Rom-Interpolation der Stützpunkte an t ∈ [0, 1]. */
        fun spline(points: List<Float>, t: Float): Float {
            val n = points.size
            if (n == 1) return points[0]
            val x = t.coerceIn(0f, 1f) * (n - 1)
            val i = x.toInt().coerceAtMost(n - 2)
            val u = x - i
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(n - 1)]
            val v = 0.5f * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u)
            // Überschwinger begrenzen: nie außerhalb der beiden Nachbarpunkte
            return v.coerceIn(minOf(p1, p2), maxOf(p1, p2))
        }
    }
}

/** Die 8 Stellen für den Farbabgleich, im Uhrzeigersinn ab oben links. */
enum class Spot(val label: String, val x: Float, val y: Float) {
    TOP_LEFT("Ecke oben links", 0f, 0f),
    TOP("Mitte oben", 0.5f, 0f),
    TOP_RIGHT("Ecke oben rechts", 1f, 0f),
    RIGHT("Mitte rechts", 1f, 0.5f),
    BOTTOM_RIGHT("Ecke unten rechts", 1f, 1f),
    BOTTOM("Mitte unten", 0.5f, 1f),
    BOTTOM_LEFT("Ecke unten links", 0f, 1f),
    LEFT("Mitte links", 0f, 0.5f);

    companion object {
        /** Die drei Stellen entlang einer Kante (Anfang, Mitte, Ende in Laufrichtung). */
        fun along(e: Edge): Triple<Spot, Spot, Spot> = when (e) {
            Edge.TOP -> Triple(TOP_LEFT, TOP, TOP_RIGHT)
            Edge.BOTTOM -> Triple(BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT)
            Edge.LEFT -> Triple(TOP_LEFT, LEFT, BOTTOM_LEFT)
            Edge.RIGHT -> Triple(TOP_RIGHT, RIGHT, BOTTOM_RIGHT)
        }
    }
}

/** Kreisförmige Interpolation zweier Farbtöne (kürzester Weg). */
fun lerpHue(a: Float, b: Float, t: Float): Float {
    var d = (b - a) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return ((a + d * t) % 360f + 360f) % 360f
}
