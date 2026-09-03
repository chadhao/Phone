package dev.chadhao.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.chadhao.phone.extensions.hideTimerNotification
import dev.chadhao.phone.extensions.timerHelper
import dev.chadhao.phone.helpers.INVALID_TIMER_ID
import dev.chadhao.phone.helpers.TIMER_HIDE
import dev.chadhao.phone.helpers.TIMER_ID
import dev.chadhao.phone.helpers.TIMER_RESTART
import dev.chadhao.phone.models.TimerEvent
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)

        when (intent.action) {
            TIMER_HIDE -> {
                context.hideTimerNotification(timerId)
                EventBus.getDefault().post(TimerEvent.Reset(timerId))
            }
            TIMER_RESTART -> {
                EventBus.getDefault().post(TimerEvent.Restart(timerId))
            }
            else -> { // Start a new
                context.hideTimerNotification(timerId)
                EventBus.getDefault().post(TimerEvent.Reset(timerId))
                context.timerHelper.getTimer(timerId) { timer ->
                    EventBus.getDefault().post(TimerEvent.Start(timer.id!!, TimeUnit.SECONDS.toMillis(timer.seconds.toLong())))
                }
            }
        }
    }
}
