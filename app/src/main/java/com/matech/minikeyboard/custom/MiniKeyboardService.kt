package com.matech.minikeyboard.custom

import android.annotation.SuppressLint
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.text.method.MetaKeyKeyListener
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import android.view.inputmethod.InputMethodManager
import androidx.constraintlayout.widget.ConstraintLayout
import com.matech.minikeyboard.R
import com.matech.minikeyboard.keyboard.Keyboard
import com.matech.minikeyboard.keyboard.KeyboardView

class MiniKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    companion object {
        const val PROCESS_HARD_KEYS = true
        const val TIMEOUT_CAPS_LOCK_DOUBLE_CLICK = 500L
    }

    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var wordSeparators: String

    private var inputView: LatinKeyboardView? = null
    private var qwertyKeyboard: LatinKeyboard? = null
    private var symbolsKeyboard: LatinKeyboard? = null
    private var symbolsShiftedKeyboard: LatinKeyboard? = null
    private var currentKeyboard: LatinKeyboard? = null
    private var lastDisplayWidth: Int = 0
    private var lastShiftTime: Long = 0L
    private var metaState = 0L
    private var isCapsLockOn = false
    private var isPredictionOn = false

    override fun onCreate() {
        super.onCreate()
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        wordSeparators = resources.getString(R.string.word_separators)
    }

    override fun onInitializeInterface() {
        val displayContext = getDisplayContext()
        if (qwertyKeyboard != null) {
            if (maxWidth == lastDisplayWidth) return
            lastDisplayWidth = maxWidth
        }
        qwertyKeyboard = LatinKeyboard(displayContext, R.xml.qwerty)
        symbolsKeyboard = LatinKeyboard(displayContext, R.xml.symbols)
        symbolsShiftedKeyboard = LatinKeyboard(displayContext, R.xml.symbols_shift)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        inputView = layoutInflater.inflate(R.layout.input, null) as LatinKeyboardView
        inputView?.setOnKeyboardActionListener(this)
        inputView?.keyboard = qwertyKeyboard
        return inputView as LatinKeyboardView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPredictionOn = false
        if (!restarting) metaState = 0L
        when (attribute?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_CLASS_PHONE -> {
                currentKeyboard = symbolsKeyboard
            }
            InputType.TYPE_CLASS_TEXT -> {
                currentKeyboard = qwertyKeyboard
                isPredictionOn = true
                val variation = attribute.inputType.and(InputType.TYPE_MASK_VARIATION)
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) {
                    isPredictionOn = false
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_URI
                    || variation == InputType.TYPE_TEXT_VARIATION_FILTER
                ) {
                    isPredictionOn = false
                }
                if (attribute.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) {
                    isPredictionOn = false
                }
                updateShiftKeyState(attribute)
            }
            else -> {
                currentKeyboard = qwertyKeyboard
                updateShiftKeyState(attribute)
            }
        }
        attribute?.imeOptions?.let { currentKeyboard?.setImeOptions(resources, it) }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentKeyboard = qwertyKeyboard
        inputView?.closing()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.apply {
            keyboard = currentKeyboard
            closing()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                inputView?.let {
                    if (event?.repeatCount == 0 && it.handleBack()) {
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_ENTER -> return false
            else -> {
                if (PROCESS_HARD_KEYS) {
                    if (keyCode == KeyEvent.KEYCODE_SPACE && (event?.metaState?.and(KeyEvent.META_ALT_ON) != 0)) {
                        currentInputConnection?.let {
                            it.clearMetaKeyStates(KeyEvent.META_ALT_ON)
                            keyDownUp(KeyEvent.KEYCODE_A)
                            keyDownUp(KeyEvent.KEYCODE_N)
                            keyDownUp(KeyEvent.KEYCODE_D)
                            keyDownUp(KeyEvent.KEYCODE_R)
                            keyDownUp(KeyEvent.KEYCODE_O)
                            keyDownUp(KeyEvent.KEYCODE_I)
                            keyDownUp(KeyEvent.KEYCODE_D)
                            return true
                        }
                    }
                    if (isPredictionOn && translateKeyDown(keyCode, event)) return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (PROCESS_HARD_KEYS) {
            if (isPredictionOn) {
                metaState = MetaKeyKeyListener.handleKeyUp(metaState, keyCode, event)
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPress(primaryCode: Int) {

    }

    override fun onRelease(primaryCode: Int) {

    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        when {
            isWordSeparator(primaryCode) -> {
                sendKey(primaryCode)
                updateShiftKeyState(currentInputEditorInfo)
            }
            primaryCode == Keyboard.KEYCODE_DELETE -> handleBackspace()
            primaryCode == Keyboard.KEYCODE_SHIFT -> handleShift()
            primaryCode == Keyboard.KEYCODE_CANCEL -> handleClose()
            primaryCode == Keyboard.KEYCODE_LANGUAGE_SWITCH -> handleSwitchLanguage()
            primaryCode == Keyboard.KEYCODE_OPTIONS -> handleOptions()
            primaryCode == Keyboard.KEYCODE_MODE_CHANGE -> {
                inputView?.let {
                    if (it.keyboard == symbolsKeyboard || it.keyboard == symbolsShiftedKeyboard) {
                        it.keyboard = qwertyKeyboard
                    } else {
                        it.keyboard = symbolsKeyboard
                        symbolsKeyboard?.isShifted = false
                    }
                }
            }
            primaryCode == Keyboard.KEYCODE_STICKERS -> handleStickers()
            else -> {
                handleCharacter(primaryCode, keyCodes)
            }
        }
    }

    override fun onText(text: CharSequence?) {
        currentInputConnection?.apply {
            beginBatchEdit()
            commitText(text, 0)
            endBatchEdit()
            updateShiftKeyState(currentInputEditorInfo)
        }
    }

    override fun swipeLeft() {
        handleBackspace()
    }

    override fun swipeRight() {

    }

    override fun swipeDown() {
        handleClose()
    }

    override fun swipeUp() {

    }

    private fun getDisplayContext(): Context? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.let { createDisplayContext(it) }
        } else {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createDisplayContext(windowManager.defaultDisplay)
        }
    }

    private fun updateShiftKeyState(attribute: EditorInfo?) {
        attribute?.let { attr ->
            inputView?.let { keyboardView ->
                if (qwertyKeyboard == keyboardView.keyboard) {
                    var caps = 0
                    if (currentInputEditorInfo.inputType != InputType.TYPE_NULL) {
                        caps = currentInputConnection.getCursorCapsMode(attr.inputType)
                    }
                    keyboardView.isShifted = isCapsLockOn || caps != 0
                }
            }
        }
    }

    private fun translateKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        metaState = MetaKeyKeyListener.handleKeyDown(metaState, keyCode, event)
        var unicodeChar = event?.getUnicodeChar(MetaKeyKeyListener.getMetaState(metaState)) ?: 0
        metaState = MetaKeyKeyListener.adjustMetaAfterKeypress(metaState)
        if (unicodeChar == 0 || currentInputConnection == null) return false
        if (unicodeChar.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
            unicodeChar = unicodeChar.and(KeyCharacterMap.COMBINING_ACCENT_MASK)
        }
        onKey(unicodeChar, null)
        return true
    }

    private fun keyDownUp(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun isWordSeparator(code: Int): Boolean {
        return wordSeparators.contains(code.toChar())
    }

    private fun sendKey(keyCode: Int) {
        keyCode.toChar().let {
            if (it == '\n') {
                keyDownUp(KeyEvent.KEYCODE_ENTER)
            } else {
                if (it in '0'..'9') {
                    keyDownUp(it - '0' + KeyEvent.KEYCODE_0)
                } else {
                    currentInputConnection?.commitText(it.toString(), 1)
                }
            }
        }
    }

    private fun handleBackspace() {
        keyDownUp(KeyEvent.KEYCODE_DEL)
        updateShiftKeyState(currentInputEditorInfo)
    }

    private fun handleShift() {
        inputView?.let {
            when {
                qwertyKeyboard == it.keyboard -> {
                    checkToggleCapsLock()
                    it.isShifted = isCapsLockOn || !it.isShifted
                }
                it.keyboard == symbolsKeyboard -> {
                    symbolsKeyboard?.isShifted = true
                    it.keyboard = symbolsShiftedKeyboard
                    symbolsShiftedKeyboard?.isShifted = true
                }
                it.keyboard == symbolsShiftedKeyboard -> {
                    symbolsShiftedKeyboard?.isShifted = false
                    it.keyboard = symbolsKeyboard
                    symbolsKeyboard?.isShifted = false
                }
            }
        }
    }

    private fun handleCharacter(primaryCode: Int, keyCodes: IntArray?) {
        var code = primaryCode
        if (isInputViewShown) {
            if (inputView?.isShifted == true) code = Character.toUpperCase(primaryCode)
        }
        if (Character.isLetter(code) && isPredictionOn) {
            sendKey(code)
            updateShiftKeyState(currentInputEditorInfo)
        } else {
            currentInputConnection?.commitText(code.toChar().toString(), 1)
        }
    }

    private fun handleClose() {
        requestHideSelf(InputMethodManager.RESULT_UNCHANGED_SHOWN)
        inputView?.closing()
    }

    private fun handleSwitchLanguage() {
        // TODO: 29/10/2020 Handle switch language if used
    }

    private fun handleOptions() {
        // TODO: 29/10/2020 Handle show menu or something if used
    }

    private fun handleStickers() {
        // TODO: 29/10/2020 Handle layout stickers
    }

    private fun checkToggleCapsLock() {
        val now = System.currentTimeMillis()
        if (lastShiftTime + TIMEOUT_CAPS_LOCK_DOUBLE_CLICK > now) {
            isCapsLockOn = !isCapsLockOn
            lastShiftTime = 0
        } else {
            lastShiftTime = now
        }
    }
}