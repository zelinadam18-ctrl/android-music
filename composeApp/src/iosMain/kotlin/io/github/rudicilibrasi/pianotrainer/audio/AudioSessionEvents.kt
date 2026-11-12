package io.github.rudicilibrasi.pianotrainer.audio

import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * Calls [onStop] when iOS interrupts audio (a phone call, Siri, an alarm) or when headphones are
 * unplugged, so playback doesn't suddenly blast from the speaker and a recording doesn't hang
 * (#23). Observers live as long as the app, like the players that use them.
 */
internal object AudioSessionEvents {
    fun observe(onStop: () -> Unit) {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVAudioSessionInterruptionNotification, `object` = null, queue = NSOperationQueue.mainQueue) { notification ->
            val type = (notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) onStop()
        }
        center.addObserverForName(AVAudioSessionRouteChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue) { notification ->
            val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onStop()
        }
    }
}
