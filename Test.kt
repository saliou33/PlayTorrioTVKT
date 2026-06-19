import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

fun main() {
    val builder = DefaultTrackSelector.Parameters.Builder(null)
    builder.setExceedRendererCapabilitiesIfNecessary(true)
    builder.setExceedVideoConstraintsIfNecessary(true)
    builder.clearViewportSizeConstraints()
}
