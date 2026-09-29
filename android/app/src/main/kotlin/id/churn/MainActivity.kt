package id.churn

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Placeholder screen until the real app exists. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.placeholder)
                gravity = Gravity.CENTER
                textSize = 18f
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
