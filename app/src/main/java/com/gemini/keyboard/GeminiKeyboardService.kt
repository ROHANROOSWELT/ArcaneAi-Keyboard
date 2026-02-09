package com.gemini.keyboard

import android.app.Service
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gemini.keyboard.settings.SettingsActivity
import com.gemini.keyboard.data.GeminiRepository
import com.gemini.keyboard.ui.SuggestionsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log

class GeminiKeyboardService : InputMethodService() {

    private lateinit var keyboardLayout: LinearLayout
    private lateinit var layoutClipboardActions: LinearLayout
    private lateinit var tvClipboardPreview: TextView
    private lateinit var btnSmartReply: Button
    private lateinit var btnTranslate: Button
    private lateinit var btnCloseClipboard: android.view.View
    private lateinit var svGeminiLogs: ScrollView
    private lateinit var tvGeminiLogs: TextView
    private lateinit var tvToolbarTranslation: TextView
    private lateinit var btnToolbarReply: Button

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private val repository = GeminiRepository()
    private lateinit var suggestionsAdapter: SuggestionsAdapter

    private var speechManager: com.gemini.keyboard.input.SpeechInputManager? = null
    
    private var isCaps = false
    private var isSymbols = false
    private var clipboardText: String? = null

    override fun onCreate() {
        try {
            super.onCreate()
             speechManager = com.gemini.keyboard.input.SpeechInputManager(this) { result ->
                 // Simple speech flow: just insert text or normalize
                 commitText(result) 
            }
        } catch (e: Exception) {
            Log.e("GeminiKeyboard", "Error in onCreate", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechManager?.destroy()
        serviceJob.cancel()
    }

    override fun onCreateInputView(): View {
        try {
            keyboardLayout = layoutInflater.inflate(R.layout.layout_keyboard, null) as LinearLayout
            
            // Bind Views
            layoutClipboardActions = keyboardLayout.findViewById(R.id.layoutClipboardActions)
            tvClipboardPreview = keyboardLayout.findViewById(R.id.tvClipboardPreview)
            btnSmartReply = keyboardLayout.findViewById(R.id.btnSmartReply)
            btnTranslate = keyboardLayout.findViewById(R.id.btnTranslate)
            btnCloseClipboard = keyboardLayout.findViewById(R.id.btnCloseClipboard)
            
            // Log Views
            svGeminiLogs = keyboardLayout.findViewById(R.id.svGeminiLogs)
            tvGeminiLogs = keyboardLayout.findViewById(R.id.tvGeminiLogs)

            // Toolbar Views
            tvToolbarTranslation = keyboardLayout.findViewById(R.id.tvToolbarTranslation)
            btnToolbarReply = keyboardLayout.findViewById(R.id.btnToolbarReply)

            setupSuggestions()
            setupInteractions()
            setupKeyboard() // Initial setup
            return keyboardLayout
        } catch (e: Exception) {
            Log.e("GeminiKeyboard", "Error in onCreateInputView", e)
            return View(this)
        }
    }

    private fun setupSuggestions() {
        val rvSuggestions = keyboardLayout.findViewById<RecyclerView>(R.id.rvSuggestions)
        suggestionsAdapter = SuggestionsAdapter { text ->
             // Smart Reply / Translation Logic
            if (clipboardText != null && rvSuggestions.visibility == View.VISIBLE) {
                // If suggestions are visible and we have clipboard context, it's a Smart Reply pick.
                // Translate it to target language and clear.
                serviceScope.launch {
                    val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
                    val tokenToUse = "AIzaSyDzST1diPdDi4PhI_zoqPLdJB38TA-oQFQ"
                    
                    if (tokenToUse.isEmpty()) return@launch
                    
                    val targetLang = prefs.getString("target_lang", "English") ?: "English"
                    
                    // Show logs briefly to indicate translation
                    showLogs()
                    appendLog("Translating to $targetLang...")
                    
                    val translatedReply = repository.translateText(text, tokenToUse, targetLang) { logMsg ->
                        appendLog(logMsg)
                    }
                    
                    commitText(translatedReply ?: text)
                    hideClipboardActions() // This clears everything top-side
                }
            } else {
                // Normal suggestion pick (or fallback)
                commitText(text)
                // If it was some other dynamic suggestion, we might still want to clear? 
                // But usually standard suggestions stay or update. 
                // However, user said "clear the area once ... translated reply is pasted".
                if (rvSuggestions.visibility == View.VISIBLE) {
                    hideClipboardActions()
                }
            }
        }
        rvSuggestions.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSuggestions.adapter = suggestionsAdapter
    }

    private fun updateSuggestions(list: List<String>) {
        if (list.isNotEmpty()) {
            hideLogs()
            keyboardLayout.findViewById<View>(R.id.rvSuggestions).visibility = View.VISIBLE
        } else {
            keyboardLayout.findViewById<View>(R.id.rvSuggestions).visibility = View.GONE
        }
        suggestionsAdapter.submitList(list)
    }

    private fun setupInteractions() {
        val btnMic = keyboardLayout.findViewById<View>(R.id.btnMic)
        val btnSettings = keyboardLayout.findViewById<View>(R.id.btnSettings)

        btnMic.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                speechManager?.startListening()
            } else {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }

        btnSettings.setOnClickListener {
             val intent = Intent(this, SettingsActivity::class.java)
             intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
             startActivity(intent)
        }
        
        // Clipboard logic
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.addPrimaryClipChangedListener {
             if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                 val item = clipboard.primaryClip?.getItemAt(0)
                 val text = item?.text?.toString()
                 if (!text.isNullOrEmpty()) {
                     clipboardText = text
                     showClipboardActions(text)
                 }
             }
        }
        

        
        btnTranslate.setOnClickListener {
            clipboardText?.let { text ->
             serviceScope.launch {
                    val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
                    val tokenToUse = "AIzaSyDzST1diPdDi4PhI_zoqPLdJB38TA-oQFQ"

                    if (tokenToUse.isEmpty()) return@launch
                    
                    val nativeLang = prefs.getString("native_lang", "Tamil") ?: "Tamil"
                    
                    showLogs()
                    appendLog("Translating to $nativeLang...")
                    
                    val translatedNative = repository.translateText(text, tokenToUse, nativeLang) { logMsg ->
                        appendLog(logMsg)
                    }
                    
                    if (translatedNative != null) {
                        // 1. Show native translation and hide logs immediately
                        tvToolbarTranslation.text = translatedNative
                        hideLogs()
                        btnToolbarReply.visibility = View.VISIBLE
                        
                        // 2. Automatically generate replies in the background
                        appendLog("Generating replies...")
                        val replies = repository.generateReplies(translatedNative, tokenToUse, nativeLang) { logMsg ->
                             // Option: Don't show logs here to avoid "interrupting" the toolbar text
                        }
                        
                        if (replies != null) {
                            updateSuggestions(replies)
                        }
                    } else {
                         appendLog("Translation failed.")
                    }
                 }
            }
        }

        btnToolbarReply.setOnClickListener {
            val textToReplyTo = tvToolbarTranslation.text.toString()
            if (textToReplyTo.isNotEmpty()) {
                serviceScope.launch {
                    val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
                    val tokenToUse = "AIzaSyDzST1diPdDi4PhI_zoqPLdJB38TA-oQFQ"
                    val nativeLang = prefs.getString("native_lang", "Tamil") ?: "Tamil"
                    
                    showLogs()
                    val replies = repository.generateReplies(textToReplyTo, tokenToUse, nativeLang) { logMsg ->
                        appendLog(logMsg)
                    }
                    if (replies != null) updateSuggestions(replies)
                }
            }
        }
        
        btnSmartReply.setOnClickListener {
            clipboardText?.let { text ->
                serviceScope.launch {
                     // val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
                    // val authToken = prefs.getString("google_auth_token", "") ?: ""
                    // val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                    
                    // val tokenToUse = if (authToken.isNotEmpty()) authToken else apiKey
                     val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
                    // val authToken = prefs.getString("google_auth_token", "") ?: ""
                    // val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                    
                    // val tokenToUse = if (authToken.isNotEmpty()) authToken else apiKey
                    val tokenToUse = "AIzaSyDmp4NlTtRXS82K9w8DzA0gYQd0TS0VLOw"

                    if (tokenToUse.isEmpty()) {
                        Log.e("GeminiKeyboard", "No Auth Token or API Key found")
                        return@launch
                    }
                     val nativeLang = prefs.getString("native_lang", "Tamil") ?: "Tamil"
                     
                     showLogs()
                     val replies = repository.generateReplies(text, tokenToUse, nativeLang) { logMsg ->
                         appendLog(logMsg)
                     }
                    if (replies != null) {
                        updateSuggestions(replies)
                        // Keep logs visible or hide? User wants to see work.
                        // We will keep logs visible until user interacts or closes?
                        // Actually, let's hide logs when suggestions appear so user can pick them?
                        // Or maybe show suggestions AND logs?
                        // The prompt says "show the work it does ... allocaie the same space above"
                        // I'll keep it open.
                    }
                }
            }
        }
        
        btnCloseClipboard.setOnClickListener {
            hideClipboardActions()
        }
    }
    
    private fun showClipboardActions(text: String) {
        layoutClipboardActions.visibility = View.VISIBLE
        tvClipboardPreview.text = "Copied: $text"
        // Hide standard suggestions? Or keep them? 
        // Let's keep recycler view visible as it shares the frame.
        // Wait, LayoutFrame stacks them? No, we need to toggle.
        // In my logic, they share a FrameLayout.
        // Actually, let's keep Recycler VISIBLE, but overlay or shift?
        // Ah, the XML has them in FrameLayout together. If Actions is VISIBLE (and opaque bg), it covers RecyclerView?
        // Yes, background is @color/suggestion_bg which is opaque.
        // BUT, we want to show SUGGESTIONS (Replies) into the RecyclerView once we click Reply!
        // So, once Reply is clicked, valid suggestions arrive, we might want to HIDE the Clipboard Actions part 
        // OR better: The "Actions" view is for the "Reply" button. 
        // Flow:
        // 1. Copy -> Actions View SHOWS (Covering defaults). 
        // 2. Click Reply -> Actions View SHOWS (Still? Or Hides to show suggestions?)
        //    If we hide it, we lose the context. 
        //    Let's HIDE Actions View when suggestions arrive so user can pick them.
        //    But how do we know internal state? 
        //    Let's just hide it inside btnSmartReply listener after fetching.
    }
    
    private fun hideClipboardActions() {
        clipboardText = null
        layoutClipboardActions.visibility = View.GONE
        keyboardLayout.findViewById<View>(R.id.rvSuggestions).visibility = View.GONE
        tvToolbarTranslation.text = ""
        btnToolbarReply.visibility = View.GONE
        hideLogs()
        updateSuggestions(emptyList())
    }
    
    private fun showLogs() {
        if (::svGeminiLogs.isInitialized) {
            keyboardLayout.findViewById<View>(R.id.rvSuggestions).visibility = View.GONE
            layoutClipboardActions.visibility = View.GONE
            // tvToolbarTranslation.text = "" // USER REQUEST: Don't clear preview during log updates
            svGeminiLogs.visibility = View.VISIBLE
            tvGeminiLogs.text = "> Initializing Gemini...\n"
        }
    }
    
    private fun hideLogs() {
        if (::svGeminiLogs.isInitialized) {
            svGeminiLogs.visibility = View.GONE
        }
    }

    private fun appendLog(msg: String) {
        serviceScope.launch(Dispatchers.Main) {
            if (::tvGeminiLogs.isInitialized) {
                tvGeminiLogs.append("> $msg\n")
                svGeminiLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun setupKeyboard() {
        if (isSymbols) {
            setupSymbolRows()
        } else {
            setupQwertyRows()
        }
    }

    // Helper to commit text to the app
    fun commitText(text: String) {
        val ic: InputConnection? = currentInputConnection
        ic?.commitText(text, 1)
        
        // Reset caps after single letter if simple caps (not doing lock for now for simplicity, or we can)
        if (isCaps) {
            isCaps = false
            setupKeyboard()
        }
    }

    // Helper to delete text (Backspace)
    fun handleBackspace() {
        val ic: InputConnection? = currentInputConnection
        ic?.deleteSurroundingText(1, 0)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setupKeyboard()
    }

    private fun setupQwertyRows() {
        if (!::keyboardLayout.isInitialized) return
        val rowsContainer = keyboardLayout.findViewById<LinearLayout>(R.id.keyboardRows) ?: return
        rowsContainer.removeAllViews()

        val keys = listOf(
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM"
        )
        
        val keyHeight = (50 * resources.displayMetrics.density).toInt()

        keys.forEachIndexed { index, rowKeys ->
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
                }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }

            if (index == 2) { 
                // Shift Key
                val shiftLabel = if (isCaps) "⬆" else "⇧"
                addFunctionKey(rowLayout, shiftLabel, keyHeight) {
                    isCaps = !isCaps
                    setupKeyboard()
                }
            }

            for (char in rowKeys) {
                val btn = android.widget.Button(this).apply {
                    val displayChar = if (isCaps) char.uppercase() else char.lowercase()
                    text = displayChar
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, keyHeight).apply {
                        weight = 1f
                        val margin = (2 * resources.displayMetrics.density).toInt()
                        setMargins(margin, 0, margin, 0) 
                    }
                    setOnClickListener {
                        commitText(displayChar)
                    }
                    setBackgroundResource(R.drawable.bg_key_selector)
                    setTextColor(resources.getColor(R.color.key_text, null))
                    textSize = 20f
                    stateListAnimator = null
                    elevation = 2f
                }
                rowLayout.addView(btn)
            }

            if (index == 2) { 
                addFunctionKey(rowLayout, "⌫", keyHeight) { handleBackspace() }
            }

            rowsContainer.addView(rowLayout)
        }
        
        addSpacebarRow(rowsContainer, keyHeight)
    }
    
    private fun setupSymbolRows() {
         if (!::keyboardLayout.isInitialized) return
         val rowsContainer = keyboardLayout.findViewById<LinearLayout>(R.id.keyboardRows) ?: return
        rowsContainer.removeAllViews()

        val keys = listOf(
            "1234567890",
            "@#$%&-+()", // Simplified symbols
            "*\"':;!?"
        )
        
        val keyHeight = (50 * resources.displayMetrics.density).toInt()

        keys.forEachIndexed { index, rowKeys ->
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
                }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            
            // Padding logic similar to QWERTY if needed
             if (index == 2) { 
                 // Placeholder for alignment
                 addFunctionKey(rowLayout, "", keyHeight) { }
            }

            for (char in rowKeys) {
                 val btn = android.widget.Button(this).apply {
                    text = char.toString()
                    layoutParams = LinearLayout.LayoutParams(0, keyHeight).apply {
                        weight = 1f
                        val margin = (2 * resources.displayMetrics.density).toInt()
                        setMargins(margin, 0, margin, 0) 
                    }
                    setOnClickListener {
                        commitText(char.toString())
                    }
                    setBackgroundResource(R.drawable.bg_key_selector)
                    setTextColor(resources.getColor(R.color.key_text, null))
                    textSize = 20f
                    stateListAnimator = null
                    elevation = 2f
                }
                rowLayout.addView(btn)
            }
            
            if (index == 2) { 
                addFunctionKey(rowLayout, "⌫", keyHeight) { handleBackspace() }
            }
            
            rowsContainer.addView(rowLayout)
        }
        
        addSpacebarRow(rowsContainer, keyHeight)
    }

    private fun addFunctionKey(parent: LinearLayout, label: String, heightPx: Int, onClick: () -> Unit = {}) {
         val btn = android.widget.Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, heightPx).apply {
                weight = 1.3f 
                val margin = (2 * resources.displayMetrics.density).toInt()
                setMargins(margin, 0, margin, 0)
            }
            setOnClickListener { onClick() }
            setBackgroundResource(R.drawable.bg_key_selector) 
            setTextColor(resources.getColor(R.color.key_text, null))
            textSize = 16f
            stateListAnimator = null
            elevation = 2f
        }
        parent.addView(btn)
    }

    private fun addSpacebarRow(parent: LinearLayout, heightPx: Int) {
        val rowLayout = LinearLayout(this).apply {
             layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        
        val margin = (2 * resources.displayMetrics.density).toInt()
        
        // Mode Switch Key (?123 / ABC)
        val modeLabel = if (isSymbols) "ABC" else "?123"
        addFunctionKey(rowLayout, modeLabel, heightPx) {
            isSymbols = !isSymbols
            setupKeyboard()
        }
        
        android.widget.Button(this).apply {
            text = ","
             layoutParams = LinearLayout.LayoutParams(0, heightPx).apply {
                weight = 1f
                setMargins(margin, 0, margin, 0)
            }
            setBackgroundResource(R.drawable.bg_key_selector)
            setTextColor(resources.getColor(R.color.key_text, null))
            textSize = 20f
            stateListAnimator = null
            elevation = 2f
            setOnClickListener { commitText(",") }
        }.also { rowLayout.addView(it) }

        android.widget.Button(this).apply {
            text = "English" 
            isAllCaps = false 
            layoutParams = LinearLayout.LayoutParams(0, heightPx).apply {
                weight = 4f
                setMargins(margin, 0, margin, 0)
            }
            setBackgroundResource(R.drawable.bg_key_selector)
            setTextColor(resources.getColor(R.color.key_text, null))
            textSize = 14f
            stateListAnimator = null
            elevation = 2f
            setOnClickListener { commitText(" ") }
        }.also { rowLayout.addView(it) }

         android.widget.Button(this).apply {
            text = "."
             layoutParams = LinearLayout.LayoutParams(0, heightPx).apply {
                weight = 1f
                setMargins(margin, 0, margin, 0)
            }
            setBackgroundResource(R.drawable.bg_key_selector)
            setTextColor(resources.getColor(R.color.key_text, null))
            textSize = 20f
            stateListAnimator = null
            elevation = 2f
             setOnClickListener { commitText(".") }
        }.also { rowLayout.addView(it) }
        
        val btnEnter = android.widget.Button(this).apply {
            text = "↵"
            layoutParams = LinearLayout.LayoutParams(0, heightPx).apply {
                weight = 1.3f
                setMargins(margin, 0, margin, 0)
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_blue, null))
            setBackgroundResource(R.drawable.bg_key_selector)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            stateListAnimator = null
            elevation = 2f
            setOnClickListener { sendKeyChar('\n') }
        }
        rowLayout.addView(btnEnter)

        parent.addView(rowLayout)
    }
}
