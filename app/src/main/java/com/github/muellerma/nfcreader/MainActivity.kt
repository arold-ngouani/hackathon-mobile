package com.github.muellerma.nfcreader

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {


    private lateinit var scanBadgeTextView: TextView
    private lateinit var codeTextView: TextView
    private lateinit var codeValueTextView: TextView

    private var tagList: LinearLayout? = null
    private var nfcAdapter: NfcAdapter? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanBadgeTextView = findViewById(R.id.scan_badge_text)
        codeTextView = findViewById(R.id.auth_code_text)
        codeValueTextView = findViewById(R.id.code_text)



        tagList = findViewById<View>(R.id.list) as LinearLayout

        resolveIntent(intent)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showNoNfcDialog()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter?.isEnabled == false) {
            openNfcSettings()
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent_Mutable
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveIntent(intent)
    }

    private fun showNoNfcDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.no_nfc)
            .setNeutralButton(R.string.close_app) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openNfcSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_NFC)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        startActivity(intent)
    }
    private fun addSpaceBetweenChars(input: String?): String {
        val safeInput = input ?: ""
        return safeInput.toCharArray().joinToString("  ")
    }
    private fun resolveIntent(intent: Intent) {
        val validActions = listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (intent.action in validActions) {
            // TODO
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            val messages = mutableListOf<NdefMessage>()
            if (rawMsgs != null) {
                rawMsgs.forEach {
                    messages.add(it as NdefMessage)
                }
            } else {
                // Unknown tag type
                val empty = ByteArray(0)
                val id = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
                val tag = intent.parcelable<Tag>(NfcAdapter.EXTRA_TAG) ?: return
                val payload = dumpTagData(tag).toByteArray()
                val record = NdefRecord(NdefRecord.TNF_UNKNOWN, empty, id, payload)
                val msg = NdefMessage(arrayOf(record))
                messages.add(msg)
            }
            // Setup the views


            // Extract the token
            val token = extractTokenFromNdefMessages(messages)
            if (token != null) {
                val client = OkHttpClient()
                // Create JSON object

                codeTextView.text = "Scanne en cours ..."

                val jsonObject = JSONObject().apply {
                    put("token", token.toString())
                }

                // Create RequestBody
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonObject.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://hackathon-api-3cw7.onrender.com/auth/read_nfc")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            codeValueTextView.text = "Request failed: ${e.message}"
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            val responseText = response.body?.string()
                            runOnUiThread {
                                codeValueTextView.text = addSpaceBetweenChars(responseText)
                                codeTextView.text = "Code d'authentification"
                            }
                        } else {
                            runOnUiThread {
                                codeValueTextView.text = "Request failed yo: ${response.message}"
                            }
                        }
                    }
                })
                // Here you can use the token, for example, send it to your server
            }else{
                codeTextView.text = "Impossible de récuperer le token"
                codeValueTextView.text = ""
            }
        }
    }
    private fun extractTokenFromNdefMessages(messages: List<NdefMessage>): String? {

        for (message in messages) {
            for (record in message.records) {
                val payload = record.payload
                val payloadString = String(payload, Charsets.UTF_8).drop(3)

                Log.d("NFC", "Payload: $payloadString")  // Ajoutez ce journal pour inspecter le contenu de la charge utile

                if (isTokenFormat(payloadString)) {
                    Log.d("NFC", "Token found: $payloadString")  // Journal supplémentaire pour confirmer la détection du token
                    return payloadString
                }
            }
        }
        return null
    }

    private fun isTokenFormat(payload: String): Boolean {
        // A simple check to see if the payload matches the expected token format
        val parts = payload.split('.')
        return parts.size == 3 && parts.all { it.length > 0 }
    }
    private fun dumpTagData(tag: Tag): String {
        val sb = StringBuilder()
        val id = tag.id
        sb.append("ID (hex): ").append(toHex(id)).append('\n')
        sb.append("ID (reversed hex): ").append(toReversedHex(id)).append('\n')
        sb.append("ID (dec): ").append(toDec(id)).append('\n')
        sb.append("ID (reversed dec): ").append(toReversedDec(id)).append('\n')
        val prefix = "android.nfc.tech."
        sb.append("Technologies: ")
        for (tech in tag.techList) {
            sb.append(tech.substring(prefix.length))
            sb.append(", ")
        }
        sb.delete(sb.length - 2, sb.length)
        for (tech in tag.techList) {
            if (tech == MifareClassic::class.java.name) {
                sb.append('\n')
                var type = "Unknown"
                try {
                    val mifareTag = MifareClassic.get(tag)

                    when (mifareTag.type) {
                        MifareClassic.TYPE_CLASSIC -> type = "Classic"
                        MifareClassic.TYPE_PLUS -> type = "Plus"
                        MifareClassic.TYPE_PRO -> type = "Pro"
                    }
                    sb.appendLine("Mifare Classic type: $type")
                    sb.appendLine("Mifare size: ${mifareTag.size} bytes")
                    sb.appendLine("Mifare sectors: ${mifareTag.sectorCount}")
                    sb.appendLine("Mifare blocks: ${mifareTag.blockCount}")
                } catch (e: Exception) {
                    sb.appendLine("Mifare classic error: ${e.message}")
                }
            }
            if (tech == MifareUltralight::class.java.name) {
                sb.append('\n')
                val mifareUlTag = MifareUltralight.get(tag)
                var type = "Unknown"
                when (mifareUlTag.type) {
                    MifareUltralight.TYPE_ULTRALIGHT -> type = "Ultralight"
                    MifareUltralight.TYPE_ULTRALIGHT_C -> type = "Ultralight C"
                }
                sb.append("Mifare Ultralight type: ")
                sb.append(type)
            }
        }
        return sb.toString()
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices.reversed()) {
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
            if (i > 0) {
                sb.append(" ")
            }
        }
        return sb.toString()
    }

    private fun toReversedHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            if (i > 0) {
                sb.append(" ")
            }
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
        }
        return sb.toString()
    }

    private fun toDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    private fun toReversedDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices.reversed()) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }





    companion object {
        private val TIME_FORMAT = SimpleDateFormat.getDateTimeInstance()
    }
}