package com.matech.minikeyboard

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.inputmethod.EditorInfo
import androidx.annotation.XmlRes
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.*
import kotlin.math.roundToInt

class Keyboard(context: Context, @XmlRes xmlLayoutResId: Int, modeId: Int = 0) {

    companion object {
        const val KEYCODE_SHIFT = -1
        const val KEYCODE_MODE_CHANGE = -2
        const val KEYCODE_CANCEL = -3
        const val KEYCODE_DONE = -4
        const val KEYCODE_DELETE = -5
        const val KEYCODE_ALT = -6
        const val KEYCODE_LANGUAGE_SWITCH = -101
        const val KEYCODE_OPTIONS = -100

        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08

        private const val TAG = "Keyboard"
        private const val TAG_KEYBOARD = "Keyboard"
        private const val TAG_ROW = "Row"
        private const val TAG_KEY = "Key"

        private const val SEARCH_DISTANCE = 1.8f

        // Variables for pre-computing nearest keys.
        private const val GRID_WIDTH = 10
        private const val GRID_HEIGHT = 5
        private const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT

    }

    /**
     * Width of the screen available to fit the keyboard
     */
    private var displayWidth: Int = 0

    /**
     * Height of the screen
     */
    private var displayHeight: Int = 0

    /**
     * Default key width
     */
    private var defaultWidth: Int = 0

    /**
     * Default key height
     */
    private var defaultHeight: Int = 0

    /**
     * Horizontal gap default for all rows
     */
    private var defaultHorizontalGap: Int = 0

    /**
     * Default gap between rows
     */
    private var defaultVerticalGap: Int = 0

    /**
     * Total width of the keyboard, including left side gaps and keys, but not any gaps on the
     * right side.
     */
    var totalWidth: Int = 0

    /**
     * Total height of the keyboard, including the padding and keys
     */
    var totalHeight = 0

    /**
     * List of keys in this keyboard
     */
    var keys = arrayListOf<Key>()

    /**
     * List of modifier keys such as Shift & Alt, if any
     */
    private var modifierKeys = arrayListOf<Key>()

    /**
     * List of rows in this keyboard
     */
    private var rows = arrayListOf<Row>()

    /**
     * Keyboard mode, or zero, if none.
     */
    private var keyboardMode: Int = 0

    /**
     * Key instance for the shift key, if present
     */
    private var shiftKeys = arrayOfNulls<Key>(2)

    /**
     * Key index for the shift key, if present
     */
    private var shiftKeyIndices = arrayOf(-1, -1)

    /**
     * Is the keyboard in the shifted state
     */
    var isShifted = false

    private var enterKey: Key? = null

    private var proximityThreshold: Int = 0
    private var cellWidth: Int = 0
    private var cellHeight: Int = 0
    private var gridNeighbors: Array<IntArray>? = null

    init {
        val displayMetrics = context.resources.displayMetrics
        displayWidth = displayMetrics.widthPixels
        displayHeight = displayMetrics.heightPixels
        defaultWidth = displayWidth / 2
        defaultHeight = defaultWidth
        keyboardMode = modeId
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
    }

    constructor(
        context: Context,
        @XmlRes xmlLayoutResId: Int,
        modeId: Int,
        width: Int,
        height: Int
    ) {
        displayWidth = width
        displayHeight = height
        defaultWidth = displayWidth / 2
        defaultHeight = defaultWidth
        keyboardMode = modeId
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
    }

    constructor(
        context: Context,
        layoutTemplateResId: Int,
        characters: CharSequence,
        columns: Int,
        horizontalPadding: Int
    ) : this(context, layoutTemplateResId) {
        var x = 0
        var y = 0
        var column = 0
        val row = Row(this).apply {
            defaultHeight = this@Keyboard.defaultHeight
            defaultWidth = this@Keyboard.defaultWidth
            defaultHorizontalGap = this@Keyboard.defaultHorizontalGap
            verticalGap = this@Keyboard.defaultVerticalGap
            rowEdgeFlags = EDGE_TOP or EDGE_BOTTOM
        }
        val maxColumn = if (columns == -1) Int.MAX_VALUE else columns
        for (element in characters) {
            if (column >= maxColumn || x + defaultWidth + horizontalPadding > displayWidth) {
                x = 0
                y += defaultVerticalGap + defaultHeight
                column = 0
            }
            val key = Key(row).apply {
                this.x = x
                this.y = y
                label = element.toString()
                codes = intArrayOf(element.toInt())
            }
            column++
            x += key.width + key.gap
            keys.add(key)
            row.keys.add(key)
            if (x > totalWidth) {
                totalWidth = x
            }
        }
        totalHeight = y + defaultHeight
        rows.add(row)
    }

    fun setShiftState(shiftState: Boolean): Boolean {
        for (shiftKey in shiftKeys) {
            if (shiftKey != null) {
                shiftKey.on = shiftState
            }
        }
        if (isShifted != shiftState) {
            isShifted = shiftState
            return true
        }
        return false
    }

    private fun computeNearestNeighbors() {
        // Round-up so we don't have any pixels outside the grid
        cellWidth =
            (totalWidth + GRID_WIDTH - 1) / GRID_WIDTH
        cellHeight =
            (totalHeight + GRID_HEIGHT - 1) / GRID_HEIGHT
        gridNeighbors = Array(GRID_SIZE) { intArrayOf() }
        val indices = IntArray(keys.size)
        val gridWidth: Int = GRID_WIDTH * cellWidth
        val gridHeight: Int = GRID_HEIGHT * cellHeight
        var x = 0
        while (x < gridWidth) {
            var y = 0
            while (y < gridHeight) {
                var count = 0
                for (i in keys.indices) {
                    val key: Key = keys[i]
                    if (key.squaredDistanceFrom(x, y) < proximityThreshold
                        || key.squaredDistanceFrom(x + cellWidth - 1, y) < proximityThreshold
                        || (key.squaredDistanceFrom(
                            x + cellWidth - 1,
                            y + cellHeight - 1
                        ) < proximityThreshold)
                        || key.squaredDistanceFrom(x, y + cellHeight - 1) < proximityThreshold
                    ) {
                        indices[count++] = i
                    }
                }
                val cell = IntArray(count)
                System.arraycopy(indices, 0, cell, 0, count)
                gridNeighbors?.set(y / cellHeight * GRID_WIDTH + x / cellWidth, cell)
                y += cellHeight
            }
            x += cellWidth
        }
    }

    /**
     * Returns the indices of the keys that are closest to the given point.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the array of integer indices for the nearest keys to the given point. If the given
     * point is out of range, then an array of size zero is returned.
     */
    fun getNearestKeys(x: Int, y: Int): IntArray? {
        if (gridNeighbors == null) computeNearestNeighbors()
        if (x in 0 until totalWidth && y >= 0 && y < totalHeight) {
            val index: Int =
                y / cellHeight * GRID_WIDTH + x / cellWidth
            if (index < GRID_SIZE) {
                return gridNeighbors?.get(index)
            }
        }
        return IntArray(0)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        val numRows = rows.size
        for (rowIndex in 0 until numRows) {
            val row: Row = rows[rowIndex]
            val numKeys = row.keys.size
            var totalGap = 0
            var totalWidth = 0
            for (keyIndex in 0 until numKeys) {
                val key = row.keys[keyIndex]
                if (keyIndex > 0) {
                    totalGap += key.gap
                }
                totalWidth += key.width
            }
            if (totalGap + totalWidth > newWidth) {
                var x = 0
                val scaleFactor = (newWidth - totalGap).toFloat() / totalWidth
                for (keyIndex in 0 until numKeys) {
                    val key = row.keys[keyIndex]
                    key.width *= scaleFactor.toInt()
                    key.x = x
                    x += key.width + key.gap
                }
            }
        }
        totalWidth = newWidth
    }

    private fun loadKeyboard(context: Context, parser: XmlResourceParser) {
        var inKey = false
        var inRow = false
        var leftMostKey = false
        var row = 0
        var x = 0
        var y = 0
        var currentKey: Key? = null
        var currentRow: Row? = null
        val res = context.resources
        var skipRow = false

        fun startParseRow() {
            inRow = true
            x = 0
            currentRow = createRowFromXml(res, parser)
            currentRow?.let { rows.add(it) }
            skipRow = currentRow?.mode != 0 && currentRow?.mode != this@Keyboard.keyboardMode
            if (skipRow) {
                skipToEndOfRow(parser)
                inRow = false
            }
        }

        fun startParseKey() {
            inKey = true
            currentKey = currentRow?.let { createKeyFromXml(res, it, x, y, parser) }
            currentKey?.let { key ->
                this@Keyboard.keys.add(key)
                if (key.codes[0] == KEYCODE_SHIFT) {
                    for (index in this@Keyboard.shiftKeys.indices) {
                        if (this@Keyboard.shiftKeys[index] == null) {
                            this@Keyboard.shiftKeys[index] = key
                            this@Keyboard.shiftKeyIndices[index] = this@Keyboard.keys.size - 1
                            break
                        }
                    }
                    this@Keyboard.modifierKeys.add(key)
                } else if (key.codes[0] == KEYCODE_ALT) {
                    this@Keyboard.modifierKeys.add(key)
                }
                currentRow?.keys?.add(key)
            }
        }

        fun parseKeyboard() {
            val attrs = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)

            this@Keyboard.defaultWidth = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_keyWidth,
                this@Keyboard.displayWidth, this@Keyboard.displayWidth / 10
            )
            this@Keyboard.defaultHeight = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_keyHeight,
                this@Keyboard.displayHeight, 50
            )
            this@Keyboard.defaultHorizontalGap = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_horizontalGap,
                this@Keyboard.displayWidth, 0
            )
            this@Keyboard.defaultVerticalGap = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_verticalGap,
                this@Keyboard.displayHeight, 0
            )
            this@Keyboard.proximityThreshold =
                (this@Keyboard.defaultWidth * SEARCH_DISTANCE).toInt()
            this@Keyboard.proximityThreshold *= this@Keyboard.proximityThreshold
            attrs.recycle()
        }

        try {
            var event: Int
            while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    when (parser.name) {
                        TAG_ROW -> startParseRow()
                        TAG_KEY -> startParseKey()
                        TAG_KEYBOARD -> parseKeyboard()
                    }
                } else if (event == XmlResourceParser.END_TAG) {
                    when {
                        inKey -> {
                            currentKey?.apply {
                                inKey = false
                                x += gap + width
                                if (x > this@Keyboard.totalWidth) {
                                    this@Keyboard.totalWidth = x
                                }
                            }
                            break
                        }

                        inRow -> {
                            currentRow?.apply {
                                inRow = false
                                y += verticalGap + defaultHeight
                                row++
                            }
                        }
                        else -> {
                            // TODO: 04/11/2020 Throw an error or todo something
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "loadKeyboard: Parse error: ", ex)
            ex.printStackTrace()
        }
        this@Keyboard.totalHeight = y - this@Keyboard.defaultVerticalGap
    }

    private fun createRowFromXml(res: Resources, parser: XmlResourceParser): Row {
        return Row(this, res, parser)
    }

    private fun createKeyFromXml(
        res: Resources,
        currentRow: Row,
        x: Int,
        y: Int,
        parser: XmlResourceParser
    ): Key {
        val key = Key(res, currentRow, x, y, parser)
        if (key.codes[0] == 10) enterKey = key
        return key
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun skipToEndOfRow(parser: XmlResourceParser) {
        var event: Int
        while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
            if (event == XmlResourceParser.END_TAG && parser.name == TAG_ROW) break
        }
    }

    fun getDimensionOrFraction(attrs: TypedArray, index: Int, base: Int, defValue: Int): Int {
        val value = attrs.peekValue(index) ?: return defValue
        if (value.type == TypedValue.TYPE_DIMENSION) {
            return attrs.getDimensionPixelOffset(index, defValue)
        } else if (value.type == TypedValue.TYPE_FRACTION) {
            return attrs.getFraction(index, base, base, defValue.toFloat()).roundToInt()
        }
        return defValue
    }

    /**
     * This looks at the ime options given by the current editor, to set the
     * appropriate label on the keyboard's enter key (if it has one).
     */
    fun setImeOptions(res: Resources, options: Int) {
        enterKey?.apply {
            when (options and (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                EditorInfo.IME_ACTION_GO -> {
                    iconPreview = null
                    icon = null
                    label = res.getText(R.string.label_go_key)
                }
                EditorInfo.IME_ACTION_NEXT -> {
                    iconPreview = null
                    icon = null
                    label = res.getText(R.string.label_next_key)
                }
                EditorInfo.IME_ACTION_SEARCH -> {
                    icon = ResourcesCompat.getDrawable(res, R.drawable.ic_key_search, null)
                    label = null
                }
                EditorInfo.IME_ACTION_SEND -> {
                    iconPreview = null
                    icon = null
                    label = res.getText(R.string.label_send_key)
                }
                else -> {
                    icon = ResourcesCompat.getDrawable(res, R.drawable.ic_key_enter, null)
                    label = null
                }
            }
        }
    }

    inner class Row(val keyboard: Keyboard) {

        /**
         * Default width of a key in this row.
         */
        var defaultWidth: Int = 0

        /**
         * Default height of a key in this row.
         */
        var defaultHeight: Int = 0

        /**
         * Default horizontal gap between keys in this row.
         */
        var defaultHorizontalGap = 0

        /**
         * Vertical gap following this row.
         */
        var verticalGap: Int = 0

        /**
         * List of keys in this row
         */
        var keys = arrayListOf<Key>()

        /**
         * Edge flags for this row of keys. Possible values that can be assigned are
         * {@link Keyboard#EDGE_TOP EDGE_TOP} and {@link Keyboard#EDGE_BOTTOM EDGE_BOTTOM}
         */
        var rowEdgeFlags = 0

        var mode: Int = 0

        constructor(keyboard: Keyboard, res: Resources, parser: XmlResourceParser) {
            var attr: TypedArray = res.obtainAttributes(
                Xml.asAttributeSet(parser),
                R.styleable.Keyboard
            )
            defaultWidth = getDimensionOrFraction(
                attr,
                R.styleable.Keyboard_keyWidth,
                keyboard.displayWidth,
                keyboard.defaultWidth
            )
            defaultHeight = getDimensionOrFraction(
                attr,
                R.styleable.Keyboard_keyHeight,
                keyboard.displayHeight,
                keyboard.defaultHeight
            )
            defaultHorizontalGap = getDimensionOrFraction(
                attr,
                R.styleable.Keyboard_horizontalGap,
                keyboard.displayWidth,
                keyboard.defaultHorizontalGap
            )
            verticalGap = getDimensionOrFraction(
                attr,
                R.styleable.Keyboard_verticalGap,
                keyboard.displayHeight,
                keyboard.defaultVerticalGap
            )
            attr = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Row)
            rowEdgeFlags = attr.getInt(R.styleable.Keyboard_Row_rowEdgeFlags, 0)
            mode = attr.getResourceId(R.styleable.Keyboard_Row_keyboardMode, 0)
            attr.recycle()
        }
    }

    inner class Key(parent: Row) {

        private val KEY_STATE_NORMAL_ON = intArrayOf(
            android.R.attr.state_checkable,
            android.R.attr.state_checked
        )

        private val KEY_STATE_PRESSED_ON = intArrayOf(
            android.R.attr.state_pressed,
            android.R.attr.state_checkable,
            android.R.attr.state_checked
        )

        private val KEY_STATE_NORMAL_OFF = intArrayOf(
            android.R.attr.state_checkable
        )

        private val KEY_STATE_PRESSED_OFF = intArrayOf(
            android.R.attr.state_pressed,
            android.R.attr.state_checkable
        )

        private val KEY_STATE_NORMAL = intArrayOf()

        private val KEY_STATE_PRESSED = intArrayOf(
            android.R.attr.state_pressed
        )

        /**
         * All the key codes (unicode or custom code) that this key could generate, zero'th
         * being the most important.
         */
        var codes: IntArray = intArrayOf()

        /**
         * Label to display
         */
        var label: CharSequence? = null

        /**
         * Icon to display instead of a label. Icon takes precedence over a label
         */
        var icon: Drawable? = null

        /**
         * Preview version of the icon, for the preview popup
         */
        var iconPreview: Drawable? = null

        /**
         * Width of the key, not including the gap
         */
        var width = parent.defaultWidth

        /**
         * Height of the key, not including the gap
         */
        var height = parent.defaultHeight

        /**
         * The horizontal gap before this key
         */
        var gap = parent.defaultHorizontalGap

        /**
         * Whether this key is sticky, i.e., a toggle key
         */
        var sticky = false

        /**
         * X coordinate of the key in the keyboard layout
         */
        var x = 0

        /**
         * Y coordinate of the key in the keyboard layout
         */
        var y = 0

        /**
         * The current pressed state of this key
         */
        var pressed = false

        /**
         * If this is a sticky key, is it on?
         */
        var on = false

        /**
         * Text to output when pressed. This can be multiple characters, like ".com"
         */
        var text: CharSequence? = null

        /**
         * Popup characters
         */
        var popupCharacters: CharSequence? = null

        /**
         * Flags that specify the anchoring to edges of the keyboard for detecting touch events
         * that are just out of the boundary of the key. This is a bit mask of
         * [Keyboard.EDGE_LEFT], [Keyboard.EDGE_RIGHT], [Keyboard.EDGE_TOP] and
         * [Keyboard.EDGE_BOTTOM].
         */
        var edgeFlags = parent.rowEdgeFlags

        /**
         * Whether this is a modifier key, such as Shift or Alt
         */
        var modifier = false

        /**
         * The keyboard that this key belongs to
         */
        private val keyboard: Keyboard = parent.keyboard

        /**
         * If this key pops up a mini keyboard, this is the resource id for the XML layout for that
         * keyboard.
         */
        var popupResId = 0

        /**
         * Whether this key repeats itself when held down
         */
        var repeatable = false

        /**
         * Create a key with the given top-left coordinate and extract its attributes from
         * the XML parser.
         *
         * @param res    resources associated with the caller's context
         * @param parent the row that this key belongs to. The row must already be attached to
         *               a {@link Keyboard}.
         * @param x      the x coordinate of the top-left
         * @param y      the y coordinate of the top-left
         * @param parser the XML parser containing the attributes for this key
         */
        constructor(res: Resources, parent: Row, x: Int, y: Int, parser: XmlResourceParser) : this(
            parent
        ) {
            this.x = x
            this.y = y
            var attrs = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)
            width = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_keyWidth,
                keyboard.displayWidth, parent.defaultWidth
            )
            height = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_keyHeight,
                keyboard.displayHeight, parent.defaultHeight
            )
            gap = getDimensionOrFraction(
                attrs,
                R.styleable.Keyboard_horizontalGap,
                keyboard.displayWidth, parent.defaultHorizontalGap
            )
            attrs = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Key)
            this.x += gap
            val codeValue = TypedValue()
            attrs.getValue(R.styleable.Keyboard_Key_codes, codeValue)
            if (codeValue.type == TypedValue.TYPE_INT_DEC || codeValue.type == TypedValue.TYPE_INT_HEX) {
                codes = intArrayOf(codeValue.data)
            } else if (codeValue.type == TypedValue.TYPE_STRING) {
                codes = parseCSV(codeValue.string.toString())
            }

            iconPreview = attrs.getDrawable(R.styleable.Keyboard_Key_iconPreview)
            iconPreview?.let {
                it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
            }
            popupCharacters = attrs.getText(
                R.styleable.Keyboard_Key_popupCharacters
            )
            popupResId = attrs.getResourceId(
                R.styleable.Keyboard_Key_popupKeyboard, 0
            )
            repeatable = attrs.getBoolean(
                R.styleable.Keyboard_Key_isRepeatable, false
            )
            modifier = attrs.getBoolean(
                R.styleable.Keyboard_Key_isModifier, false
            )
            sticky = attrs.getBoolean(
                R.styleable.Keyboard_Key_isSticky, false
            )
            edgeFlags = attrs.getInt(R.styleable.Keyboard_Key_keyEdgeFlags, 0)
            edgeFlags = edgeFlags or parent.rowEdgeFlags

            icon = attrs.getDrawable(
                R.styleable.Keyboard_Key_keyIcon
            )
            if (icon != null) {
                icon!!.setBounds(0, 0, icon!!.intrinsicWidth, icon!!.intrinsicHeight)
            }
            label = attrs.getText(R.styleable.Keyboard_Key_keyLabel)
            text = attrs.getText(R.styleable.Keyboard_Key_keyOutputText)

            label?.let {
                if (codes.isEmpty() && !TextUtils.isEmpty(it)) {
                    codes = intArrayOf(it[0].toInt())
                }
            }
            attrs.recycle()
        }

        /**
         * Informs the key that it has been pressed, in case it needs to change its appearance or
         * state.
         *
         * @see onReleased
         */
        fun onPressed() {
            pressed = !pressed
        }

        /**
         * Changes the pressed state of the key.
         *
         *
         * Toggled state of the key will be flipped when all the following conditions are
         * fulfilled:
         *
         *
         *  * This is a sticky key, that is, {@link #sticky} is {@code true}.
         *  * The parameter {@code inside} is {@code true}.
         *  * [android.os.Build.VERSION.SDK_INT] is greater than
         * [android.os.Build.VERSION_CODES.LOLLIPOP_MR1].
         *
         *
         * @param inside whether the finger was released inside the key. Works only on Android M and
         * later. See the method document for details.
         * @see onPressed
         */
        fun onReleased(inside: Boolean) {
            pressed = !pressed
            if (sticky && inside) {
                on = !on
            }
        }

        private fun parseCSV(value: String): IntArray {
            var count = 0
            var lastIndex = 0
            if (value.isNotEmpty()) {
                count++
                while (value.indexOf(",", lastIndex + 1).also { lastIndex = it } > 0) {
                    count++
                }
            }
            val values = IntArray(count)
            count = 0
            val st = StringTokenizer(value, ",")
            while (st.hasMoreTokens()) {
                try {
                    values[count++] = st.nextToken().toInt()
                } catch (nfe: NumberFormatException) {
                    Log.e(TAG, "Error parsing key codes $value", nfe)
                }
            }
            return values
        }

        /**
         * Detects if a point falls inside this key.
         *
         * @param x the x-coordinate of the point
         * @param y the y-coordinate of the point
         * @return whether or not the point falls inside the key. If the key is attached to an edge,
         * it will assume that all points between the key and the edge are considered to be inside
         * the key.
         */
        fun isInside(x: Int, y: Int): Boolean {
            val leftEdge = edgeFlags and EDGE_LEFT > 0
            val rightEdge = edgeFlags and EDGE_RIGHT > 0
            val topEdge = edgeFlags and EDGE_TOP > 0
            val bottomEdge = edgeFlags and EDGE_BOTTOM > 0
            return ((x >= this.x || leftEdge && x <= this.x + width)
                    && (x < this.x + width || rightEdge && x >= this.x)
                    && (y >= this.y || topEdge && y <= this.y + height)
                    && (y < this.y + height || bottomEdge && y >= this.y))
        }

        /**
         * Returns the square of the distance between the center of the key and the given point.
         *
         * @param x the x-coordinate of the point
         * @param y the y-coordinate of the point
         * @return the square of the distance of the point from the center of the key
         */
        fun squaredDistanceFrom(x: Int, y: Int): Int {
            val xDist = this.x + width / 2 - x
            val yDist = this.y + height / 2 - y
            return xDist * xDist + yDist * yDist
        }

        /**
         * Returns the drawable state for the key, based on the current state and type of the key.
         *
         * @return the drawable state of the key.
         * @see android.graphics.drawable.StateListDrawable.setState
         */
        fun getCurrentDrawableState(): IntArray? {
            var states = KEY_STATE_NORMAL
            if (on) {
                states = if (pressed) KEY_STATE_PRESSED_ON else KEY_STATE_NORMAL_ON
            } else {
                if (sticky) {
                    states = if (pressed) KEY_STATE_PRESSED_OFF else KEY_STATE_NORMAL_OFF
                } else {
                    if (pressed) states = KEY_STATE_PRESSED
                }
            }
            return states
        }
    }
}