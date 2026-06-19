import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

fun main() {
    val context: android.content.Context? = null
    val ts = DefaultTrackSelector(context!!)
    // Check if it accepts Builder
    ts.setParameters(ts.parameters.buildUpon().setMaxVideoBitrate(100))
}
