package amirz.dngprocessor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.preference.Preference
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import amirz.dngprocessor.scheduler.DngParseWorker
import amirz.dngprocessor.scheduler.DngScanJob
import amirz.dngprocessor.util.NotifHandler
import amirz.dngprocessor.util.Path
import amirz.dngprocessor.util.Utilities

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            // Run preference migration before loading preferences
            Preferences.global().migratePreferences(this)
            
            NotifHandler.createChannel(this)
            requestNotificationPermission()
            tryLoad()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun tryLoad() {
        if (Environment.isExternalStorageManager()) {
            DngScanJob.scheduleJob(this)
            fragmentManager.beginTransaction()
                .replace(android.R.id.content, Preferences.Fragment())
                .commit()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                ("package:" + BuildConfig.APPLICATION_ID).toUri()
            )
            startActivityForResult(intent, REQUEST_STORAGE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_PERMISSIONS -> if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tryLoad()
            }
        }
    }

    fun requestImage(preference: Preference?): Boolean {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
        picker.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        picker.setType(Path.MIME_RAW)
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(picker, REQUEST_IMAGE)

        return false
    }

    fun requestLutFile(preference: Preference?): Boolean {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
        picker.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        picker.setType("*/*")
        picker.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "text/plain"))
        // Filter for .cube files
        picker.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        startActivityForResult(picker, REQUEST_LUT_FILE)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_STORAGE_PERMISSION -> {
                // User returned from storage permission settings
                // Check again if permission was granted and load content
                tryLoad()
            }
            REQUEST_IMAGE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val flags = data.flags
                    val cd = data.clipData
                    if (cd == null) {
                        process(data.data!!, flags)
                    } else {
                        for (i in 0..<cd.itemCount) {
                            process(cd.getItemAt(i).uri, flags)
                        }
                    }
                }
            }
            REQUEST_LUT_FILE -> {
                if (resultCode == RESULT_OK && data != null && data.data != null) {
                    val uri = data.data!!
                    val flags = data.flags
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    // Store URI as string (works for both file paths and document URIs)
                    val uriString = uri.toString()
                    // Get display name for summary using ContentResolver
                    var fileName: String? = null
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) {
                                    fileName = cursor.getString(nameIndex)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Failed to get filename from URI", e)
                    }
                    
                    // Fallback to Path.getFileFromUri if ContentResolver query failed
                    if (fileName == null || fileName!!.isEmpty()) {
                        fileName = Path.getFileFromUri(this, uri)
                    }
                    
                    // Save to preferences
                    val prefs = Utilities.prefs(this)
                    prefs.edit()
                        .putString(getString(amirz.dngprocessor.R.string.pref_external_lut_path), uriString)
                        .apply()
                    
                    // Update the preference summary directly - simple and direct approach
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val fragment = fragmentManager.findFragmentById(android.R.id.content)
                            if (fragment is Preferences.Fragment && fragment.isAdded) {
                                val prefKey = getString(amirz.dngprocessor.R.string.pref_external_lut_path)
                                val pref = fragment.findPreference(prefKey)
                                if (pref != null) {
                                    val displayName = fileName ?: "LUT file"
                                    pref.summary = displayName
                                    android.util.Log.d("MainActivity", "Updated LUT summary to: $displayName")
                                } else {
                                    android.util.Log.w("MainActivity", "Preference not found with key: $prefKey")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to update LUT summary", e)
                        }
                    }
                }
            }
        }
    }

    override fun recreate() {
        finish()
        startActivity(intent)
    }

    private fun process(uri: Uri, flags: Int) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        DngParseWorker.enqueueWork(this, uri)
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_IMAGE = 2
        private const val REQUEST_NOTIFICATION_PERMISSION = 3
        private const val REQUEST_STORAGE_PERMISSION = 4
        private const val REQUEST_LUT_FILE = 5
    }
}
