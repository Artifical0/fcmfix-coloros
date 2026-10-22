package com.kooritea.fcmfix

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kooritea.fcmfix.ui.FcmFixApp
import com.kooritea.fcmfix.ui.theme.FcmFixTheme
import com.kooritea.fcmfix.util.IceboxUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestIceBoxPermission()
        setContent {
            FcmFixTheme {
                FcmFixApp(viewModel())
            }
        }
    }

    /** Ice Box's SDK permission, for "允许唤醒被冰箱冻结的应用"; ignored when Ice Box is not installed. */
    private fun requestIceBoxPermission() {
        try {
            if (checkSelfPermission(IceboxUtils.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(IceboxUtils.SDK_PERMISSION), IceboxUtils.REQUEST_CODE)
            }
        } catch (ignored: Throwable) {
        }
    }
}
