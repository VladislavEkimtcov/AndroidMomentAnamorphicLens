package io.github.vladislavekimtcov.anamorphic

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.vladislavekimtcov.anamorphic.core.SqueezeFactor
import io.github.vladislavekimtcov.anamorphic.databinding.ActivityMainBinding

/** The only real screen: what the app does, and its three settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updatePermissionViews() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.factorGroup.check(if (settings.factor == SqueezeFactor.X155) R.id.factor155 else R.id.factor133)
        binding.factorGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) settings.factor = if (checkedId == R.id.factor155) SqueezeFactor.X155 else SqueezeFactor.X133
        }

        binding.rotate.isChecked = settings.rotated
        binding.rotate.setOnCheckedChangeListener { _, checked -> settings.rotated = checked }

        binding.replaceOriginal.isChecked = settings.replaceOriginal
        binding.replaceOriginal.setOnCheckedChangeListener { _, checked ->
            settings.replaceOriginal = checked
            if (checked && !MediaPermissions.allGranted(this)) requestPermissions()
            updatePermissionViews()
        }

        binding.grantAccess.setOnClickListener { requestPermissions() }
        binding.manageMedia.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
            }
        }
        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionViews()
    }

    private fun requestPermissions() {
        val permanentlyDenied = settings.askedForPermission && MediaPermissions.all.none {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (permanentlyDenied && !MediaPermissions.allGranted(this)) {
            // Android stops showing the dialog after two denials; send the user to app settings.
            startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            settings.askedForPermission = true
            permissionLauncher.launch(MediaPermissions.all)
        }
    }

    private fun updatePermissionViews() {
        val granted = MediaPermissions.allGranted(this)
        binding.permissionStatus.setText(if (granted) R.string.permission_granted else R.string.permission_missing)
        binding.grantAccess.visibility = if (granted) View.GONE else View.VISIBLE
        val canOfferManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaPermissions.canManageMedia(this)
        binding.manageMedia.visibility = if (canOfferManageMedia && settings.replaceOriginal) View.VISIBLE else View.GONE
    }
}
