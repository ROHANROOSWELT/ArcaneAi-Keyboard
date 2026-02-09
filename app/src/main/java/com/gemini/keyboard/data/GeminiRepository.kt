package com.gemini.keyboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GeminiRepository {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val MODEL = "gemini-2.5-flash"
    private val BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun generateContent(
        prompt: String,
        apiKey: String,
        onLog: (String) -> Unit = {}
    ): String? {

        onLog("Preparing Gemini request...")

        return withContext(Dispatchers.IO) {

            val bodyJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                    )
                )
            }

            val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
            onLog("URL: $url")

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody(JSON))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onLog("Error ${response.code}: ${response.body?.string()}")
                        return@use null
                    }

                    val responseBody = response.body?.string() ?: return@use null
                    onLog("Response received")

                    val json = JSONObject(responseBody)
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                }
            } catch (e: IOException) {
                onLog("Network error: ${e.message}")
                null
            }
        }
    }

    // -----------------------
    // Helper use-cases
    // -----------------------

    suspend fun normalizeHybridText(
        text: String,
        apiKey: String,
        targetLang: String = "English",
        onLog: (String) -> Unit = {}
    ): String? {

        val prompt = """
            You are a multilingual communication assistant.
            The user is speaking in a hybrid mix (Tamil/Hindi + English).

            Task:
            1. Decode intent
            2. Respond clearly in $targetLang
            3. Output ONLY the reply text

            Input: "$text"
        """.trimIndent()

        return generateContent(prompt, apiKey, onLog)
    }

    suspend fun generateReplies(
        contextText: String,
        apiKey: String,
        nativeLang: String,
        onLog: (String) -> Unit = {}
    ): List<String>? {

        val prompt = """
            Context: "$contextText"
            Task: Suggest 5 short, natural replies STRICTLY in $nativeLang.
            Output format: Just the 5 replies separated by '|'. No numbering.
            Example (if native is Tamil): Saptiya? | Ama | Illai | Busy | Call panren
        """.trimIndent()

        onLog("Generating 5 $nativeLang replies...")
        val result = generateContent(prompt, apiKey, onLog)
        return result?.split("|")?.map { it.trim() }
    }

    suspend fun translateText(
        text: String,
        apiKey: String,
        targetLang: String,
        onLog: (String) -> Unit = {}
    ): String? {

        val prompt = """
            Act as a professional translator.
            Input text: "$text"
            Task: 
            1. Detect the language (it could be hybrid/mixed like Tanglish, Hinglish, or standard).
            2. Translate the meaning accurately and professionally to $targetLang.
            3. Output ONLY the translated text. No explanations.
        """.trimIndent()

        onLog("Translating to $targetLang...")
        return generateContent(prompt, apiKey, onLog)
    }
}
