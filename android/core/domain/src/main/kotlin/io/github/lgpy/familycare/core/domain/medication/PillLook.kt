package io.github.lgpy.familycare.core.domain.medication

/**
 * How a tablet, capsule or bottle is drawn in the app (D33, screens v2).
 *
 * **Why this type lives in the core, next to [io.github.lgpy.familycare.core.domain.circle.PatientId],
 * and not in the medications feature:** the picture is drawn by a shared
 * component in `core:ui`, and `core:ui` must not depend on a feature module
 * (D5). A pill look is shared vocabulary — the medication form writes it, the
 * dashboard, dose screen, lists and shopping list draw it — so the value type
 * belongs in the core. The medications module still owns the medication row and
 * maps its `pill_*` columns onto this type.
 *
 * The values are the database tokens of `meds.medications` (the
 * `pill_shape`, `pill_color` and `pill_size` columns) and the fixed palette the
 * mockup (`docs/ux/mockups/screens-v2.html`, `pill()`) was approved with. They
 * are deliberately text tokens, not a database enum, so a future server can add
 * a shape without a migration that older phones would trip over: an unknown
 * token becomes `null` here, never a crash.
 *
 * @property shape What the pill looks like. Required: this is what makes a look
 *   a look; a row with no shape has no drawn pill (a generic outline is shown).
 * @property color Main colour, or null when the row has no colour set. The UI
 *   draws null as the neutral white of the mockup.
 * @property color2 Second colour, only meaningful for [PillShape.CAPSULE]
 *   (two-tone capsules). It is ignored/dropped for every other shape.
 * @property score Whether the pill has a score line (a tablet that can be
 *   broken in half).
 * @property size Relative size on the fixed drawing box: small 0.8, medium 1,
 *   large 1.1.
 */
data class PillLook(
    val shape: PillShape,
    val color: PillColor? = null,
    val color2: PillColor? = null,
    val score: Boolean = false,
    val size: PillSize = PillSize.MEDIUM,
)

/** The `meds.medications.pill_shape` tokens. */
enum class PillShape(val serverValue: String) {
    ROUND("round"),
    OVAL("oval"),
    OBLONG("oblong"),
    CAPSULE("capsule"),
    DIAMOND("diamond"),
    TRIANGLE("triangle"),
    PENTAGON("pentagon"),
    DROPS("drops"),
    ;

    companion object {
        /** The shape with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): PillShape? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/** The fixed palette tokens accepted by `pill_color` / `pill_color2` (D33). */
enum class PillColor(val serverValue: String) {
    WHITE("white"),
    CREAM("cream"),
    YELLOW("yellow"),
    ORANGE("orange"),
    PINK("pink"),
    RED("red"),
    BLUE("blue"),
    NAVY("navy"),
    GREEN("green"),
    BROWN("brown"),
    PURPLE("purple"),
    GREY("grey"),
    ;

    companion object {
        /** The colour with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): PillColor? =
            entries.firstOrNull { it.serverValue == value }

        /** The exact token list the database check accepts, for documentation/tests. */
        val tokens: List<String> = entries.map { it.serverValue }
    }
}

/**
 * The `meds.medications.pill_size` tokens and the factor the drawing applies
 * inside its box. [MEDIUM] is the database default.
 */
enum class PillSize(val serverValue: String, val scale: Float) {
    SMALL("small", 0.8f),
    MEDIUM("medium", 1f),
    LARGE("large", 1.1f),
    ;

    companion object {
        /** The size with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): PillSize? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/** A field of a [PillLook] a rule rejected. */
enum class PillLookField {
    /** A second colour is only meaningful on a capsule. */
    PILL_LOOK_COLOR2,
}

/**
 * The rules of the drawn pill look, in one place.
 *
 * The only structural rule is the two-tone one: `pill_color2` is only meaningful
 * for a capsule, mirroring the `medications_pill_color2_only_capsule` database
 * check. A look with a second colour on a round tablet would show a picture the
 * box does not have, and the server would refuse the write, so the form is
 * refused locally first.
 */
object PillLookRules {

    /**
     * The look a server row describes, or null when it cannot be drawn.
     *
     * Unknown or missing tokens degrade instead of failing:
     * - an unknown or null `shape` means "no pill look" (null);
     * - an unknown `color`/`color2` becomes null (the drawing falls back to the
     *   neutral white / no second tone);
     * - an unknown or null `size` becomes [PillSize.MEDIUM];
     * - a second colour on a non-capsule is dropped.
     *
     * That is the "a newer server must never crash an older phone" rule the rest
     * of the domain follows for enum columns.
     */
    fun fromServer(
        shape: String?,
        color: String?,
        color2: String?,
        score: Boolean,
        size: String?,
    ): PillLook? {
        val parsedShape = PillShape.fromServer(shape) ?: return null
        val parsedColor = PillColor.fromServer(color)
        val parsedColor2 = PillColor.fromServer(color2)
            ?.takeIf { parsedShape == PillShape.CAPSULE }

        return PillLook(
            shape = parsedShape,
            color = parsedColor,
            color2 = parsedColor2,
            score = score,
            size = PillSize.fromServer(size) ?: PillSize.MEDIUM,
        )
    }

    /** The look as it should be stored: a second colour only survives on a capsule. */
    fun normalize(look: PillLook): PillLook =
        if (look.shape == PillShape.CAPSULE) look else look.copy(color2 = null)

    /** The rule violations in [look]; empty when it can be stored as it is. */
    fun validate(look: PillLook): Set<PillLookField> {
        val errors = mutableSetOf<PillLookField>()
        if (look.color2 != null && look.shape != PillShape.CAPSULE) {
            errors += PillLookField.PILL_LOOK_COLOR2
        }
        return errors
    }
}
