package tw.kebbi.medreminder

import android.app.Application
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.robot.AndroidTtsVoice
import tw.kebbi.medreminder.robot.NuwaVoice
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.sync.CloudSync

class KebbiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        // app/libs/ 有 Nuwa SDK（凱比原廠語音）就用它，沒有就用 Android 內建語音
        Robot.voice = if (NuwaVoice.isAvailable()) NuwaVoice(this) else AndroidTtsVoice(this)
        CloudSync.start(this)
        Repo.tick()
    }
}
