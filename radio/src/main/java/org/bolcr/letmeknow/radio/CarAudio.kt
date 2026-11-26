package org.bolcr.letmeknow.radio

import android.content.Context
import android.content.Intent

/**
 * The car's "BT Música": the radio plays the phone's sound only while it is open. Opening a voice message on the
 * phone opens it too, so the message is heard in the car.
 */
object CarAudio {
    private val names = Regex("(?i)^(bt\\s*m[uú]sic|bluetooth\\s*m[uú]sic|m[uú]sica\\s*bt|a2dp)")

    fun open(c: Context) {
        try {
            val pm = c.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val app = pm.queryIntentActivities(launcher, 0).firstOrNull { names.containsMatchIn(it.loadLabel(pm).toString().trim()) }
                ?: return
            val open = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            c.startActivity(open)
        } catch (_: Exception) {
        }
    }
}
