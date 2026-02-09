package com.gemini.keyboard.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gemini.keyboard.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.google.android.gms.auth.GoogleAuthUtil
import android.accounts.Account
import android.content.Intent

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

        val prefs = getSharedPreferences("gemini_prefs", MODE_PRIVATE)
        val spNative = findViewById<Spinner>(R.id.spNativeLang)
        val spTarget = findViewById<Spinner>(R.id.spTargetLang)
        val etApiKey = findViewById<android.widget.EditText>(R.id.etApiKey)
        val btnSave = findViewById<android.widget.Button>(R.id.btnSave)

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.languages_array,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spNative.adapter = adapter
        spTarget.adapter = adapter

        val nativeLang = prefs.getString("native_lang", "Tamil") ?: "Tamil"
        val targetLang = prefs.getString("target_lang", "English") ?: "English"

        val languages = resources.getStringArray(R.array.languages_array)
        spNative.setSelection(languages.indexOf(nativeLang).coerceAtLeast(0))
        spTarget.setSelection(languages.indexOf(targetLang).coerceAtLeast(0))

        etApiKey.setText(prefs.getString("gemini_api_key", ""))

        val btnGoogleSignIn = findViewById<com.google.android.gms.common.SignInButton>(R.id.btnGoogleSignIn)
        val tvSignedInAccount = findViewById<android.widget.TextView>(R.id.tvSignedInAccount)

        updateUI(tvSignedInAccount)

        // Configure sign-in to request the user's ID, email address, and basic
        // profile. ID and basic profile are included in DEFAULT_SIGN_IN.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
            
        val mGoogleSignInClient = GoogleSignIn.getClient(this, gso)

        val signInLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    // Signed in successfully, show authenticated UI.
                    updateUI(tvSignedInAccount)
                    fetchAuthToken(account.account)
                } catch (e: ApiException) {
                    // The ApiException status code indicates the detailed failure reason.
                    Log.w("SettingsActivity", "signInResult:failed code=" + e.statusCode)
                    updateUI(tvSignedInAccount)
                }
            }
        }

        btnGoogleSignIn.setOnClickListener {
             val signInIntent = mGoogleSignInClient.signInIntent
             signInLauncher.launch(signInIntent)
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("native_lang", spNative.selectedItem.toString())
                putString("target_lang", spTarget.selectedItem.toString())
                putString("gemini_api_key", etApiKey.text.toString())
                apply()
            }
            finish()
        }
    }
    
    private fun updateUI(tvAccount: android.widget.TextView) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            tvAccount.text = "Signed in as: ${account.email}"
        } else {
            tvAccount.text = "Not signed in"
        }
    }
    
    private fun fetchAuthToken(account: Account?) {
        if (account == null) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Scope for Cloud Platform or specifically Generative Language if available
                val scope = "oauth2:https://www.googleapis.com/auth/cloud-platform"
                val token = GoogleAuthUtil.getToken(this@SettingsActivity, account, scope)
                
                withContext(Dispatchers.Main) {
                   Toast.makeText(this@SettingsActivity, "Token retrieved!", Toast.LENGTH_SHORT).show()
                   Log.d("SettingsActivity", "Token: $token")
                   getSharedPreferences("gemini_prefs", MODE_PRIVATE).edit()
                       .putString("google_auth_token", token)
                       .putLong("token_expiry", System.currentTimeMillis() + 3000 * 1000) // Rough expiry
                       .apply()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error getting token", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Failed to get token: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
