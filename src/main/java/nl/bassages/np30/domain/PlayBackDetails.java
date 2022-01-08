package nl.bassages.np30.domain;

public class PlayBackDetails {
    private final String artist;
    private final String title;
    private final String album;
    private final String file;
    private final String state;

    public PlayBackDetails(String state) {
        this.artist = null;
        this.title = null;
        this.album = null;
        this.file = null;
        this.state = state;
    }

    public PlayBackDetails(final String artist, final String title, final String album, String file, String state) {
        this.artist = artist;
        this.title = title;
        this.album = album;
        this.file = file;
        this.state = state;
    }

    public String getArtist() {
        return artist;
    }

    public String getTitle() {
        return title;
    }

    public String getAlbum() {
        return album;
    }

    public String getFile() {
        return file;
    }

    public String getState() {
        return state;
    }
}
