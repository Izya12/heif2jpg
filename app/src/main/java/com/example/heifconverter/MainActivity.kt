package com.example.heifconverter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.heifconverter.BuildConfig
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val bSelect: Button by lazy { findViewById(R.id.select_img_button) }
    private val progressContainer: LinearLayout by lazy { findViewById(R.id.progress_container) }
    private val progressBar: ProgressBar by lazy { findViewById(R.id.progress_bar) }
    private val progressText: TextView by lazy { findViewById(R.id.progress_text) }

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            Toast.makeText(this, "Selected ${uris.size} images", Toast.LENGTH_SHORT).show()
            convertAll(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bSelect.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = getUriFromIntent(intent)
                uri?.let { convertAll(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                getUriListFromIntent(intent)
                    ?.take(MAX_IMAGES)
                    ?.let { convertAll(it) }
            }
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun getUriListFromIntent(intent: Intent): List<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun convertAll(uris: List<Uri>) {
        val images = uris.filter { uri ->
            val mimeType = contentResolver.getType(uri)
            if (mimeType == null || !mimeType.startsWith("image/")) {
                Log.d(TAG, "Skipping non-image URI: $uri (type=$mimeType)")
                false
            } else {
                true
            }
        }

        if (images.isEmpty()) return

        progressContainer.visibility = View.VISIBLE
        progressBar.max = images.size
        progressBar.progress = 0
        progressText.text = "Converting 0 / ${images.size}..."

        lifecycleScope.launch {
            var successCount = 0
            var errorCount = 0

            for ((index, uri) in images.withIndex()) {
                progressText.text = "Converting ${index + 1} / ${images.size}..."
                try {
                    withContext(Dispatchers.IO) {
                        convert(uri)
                    }
                    successCount++
                } catch (e: Exception) {
                    errorCount++
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Conversion failed: $e")
                    }
                }
                progressBar.progress = index + 1
                yield()
            }

            progressContainer.visibility = View.GONE

            val message = buildString {
                append("Done: $successCount converted")
                if (errorCount > 0) append(", $errorCount failed")
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun convert(uri: Uri): String {
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source)
        val displayName = sanitizeDisplayName(uri.lastPathSegment) ?: "converted.jpeg"
        try {
            saveBitmap(this, bitmap, Bitmap.CompressFormat.JPEG, "image/jpeg", displayName)
        } finally {
            bitmap.recycle()
        }
        return displayName
    }

    @Throws(IOException::class)
    private fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        mimeType: String,
        displayName: String
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
        }

        var uri: Uri? = null

        return runCatching {
            with(context.contentResolver) {
                insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also {
                    uri = it

                    openOutputStream(it)?.use { stream ->
                        if (!bitmap.compress(format, 95, stream)) {
                            throw IOException("Failed to save bitmap.")
                        }
                    } ?: throw IOException("Failed to open output stream.")
                } ?: throw IOException("Failed to create new MediaStore record.")
            }
        }.getOrElse {
            uri?.let { orphanUri ->
                context.contentResolver.delete(orphanUri, null, null)
            }
            throw it
        }
    }

    private fun sanitizeDisplayName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val name = raw.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
        if (name.isBlank()) return null
        val baseName = name.substringBeforeLast('.', name)
        val timestamp = System.currentTimeMillis()
        return "${baseName}_${timestamp}.jpeg"
    }

    companion object {
        private const val TAG = "heif2jpg"
        private const val MAX_IMAGES = 100
    }
}
