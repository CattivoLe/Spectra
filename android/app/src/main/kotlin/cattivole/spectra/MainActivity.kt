package cattivole.spectra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cattivole.spectra.ui.SpectraApp
import cattivole.spectra.ui.SpectraTheme

class MainActivity : ComponentActivity() {
    private val model: SpectraModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                model.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                model.onBackground()
            }
        })
        setContent {
            SpectraTheme {
                SpectraApp(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.usb.refresh()
    }
}
