package ch.fbc.krakenbridge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Black screen covering the camera while it is parked; see [CameraController.parkIfIdle]. */
class KrakenStandbyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setText(R.string.standby_message)
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                textSize = 22f
                setOnClickListener {
                    KrakenBleService.instance?.leaveStandby()
                    startActivity(Intent(context, MainActivity::class.java))
                    finish()
                }
            }
        )
        lifecycleScope.launch {
            KrakenBleService.state.first { !it.isCameraParked }
            finish()
        }
    }
}
