import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.upstream.ParsingLoadable

class MyParserFactory : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return HlsPlaylistParser()
    }
    
    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
    }
}
