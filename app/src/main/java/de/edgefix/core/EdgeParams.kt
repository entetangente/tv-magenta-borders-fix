package de.edgefix.core

/** Form des Verlaufs von der Kante (t = 1) zur Innenkante des Randbereichs (t = 0). */
enum class Curve(val label: String) {
    LINEAR("linear"),
    SMOOTHSTEP("weich (smoothstep)"),
    QUADRATIC("quadratisch");

    fun apply(t: Float): Float = when (this) {
        LINEAR -> t
        SMOOTHSTEP -> t * t * (3f - 2f * t)
        QUADRATIC -> t * t
    }
}

/** Ein Wert je Bildschirmkante. */
data class Edges(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
) {
    fun map(f: (Float) -> Float) = Edges(f(top), f(bottom), f(left), f(right))

    companion object {
        fun all(v: Float) = Edges(v, v, v, v)
    }
}

enum class Edge(val label: String) {
    TOP("oben"), BOTTOM("unten"), LEFT("links"), RIGHT("rechts");
}

fun Edges.get(e: Edge): Float = when (e) {
    Edge.TOP -> top
    Edge.BOTTOM -> bottom
    Edge.LEFT -> left
    Edge.RIGHT -> right
}

fun Edges.with(e: Edge, v: Float): Edges = when (e) {
    Edge.TOP -> copy(top = v)
    Edge.BOTTOM -> copy(bottom = v)
    Edge.LEFT -> copy(left = v)
    Edge.RIGHT -> copy(right = v)
}

/**
 * Parameter der parametrischen Randmaske (siehe Spezifikation, Abschnitt
 * „Rendering der Randmaske“).
 */
data class EdgeParams(
    /** Gesamtstärke: maximale Deckkraft am Rand, 0–0.40. */
    val aMax: Float = 0.12f,
    /** Farbanteil: 0 = nur abdunkeln, 1 = volle Gegenfarbe. */
    val k: Float = 0.5f,
    /** Farbton der Gegenfarbe in Grad (75° = Gelbgrün). */
    val hueDeg: Float = 75f,
    /** Breite des Verlaufs je Kante als Anteil der Bildhöhe, 0–0.20. */
    val width: Edges = Edges.all(0.06f),
    /** Stärke je Kante, 0–1.5. */
    val strength: Edges = Edges.all(1.0f),
    val curve: Curve = Curve.SMOOTHSTEP,
) {
    fun clamped(): EdgeParams = copy(
        aMax = aMax.coerceIn(0f, MAX_A),
        k = k.coerceIn(0f, 1f),
        hueDeg = ((hueDeg % 360f) + 360f) % 360f,
        width = width.map { it.coerceIn(0f, MAX_WIDTH) },
        strength = strength.map { it.coerceIn(0f, MAX_STRENGTH) },
    )

    companion object {
        const val MAX_A = 0.40f
        const val MAX_WIDTH = 0.20f
        const val MAX_STRENGTH = 1.5f
    }
}
