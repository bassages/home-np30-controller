package nl.bassages.np30.api.dto;

import java.util.List;

public class ItemDto {

    private String id;
    private String title;
    private String duration;
    private Integer originalTrackNumber;
    private List<ItemDto> children;
    private boolean isContainer;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<ItemDto> getChildren() {
        return children;
    }

    public void setChildren(List<ItemDto> children) {
        this.children = children;
    }

    public boolean isContainer() {
        return isContainer;
    }

    public void setIsContainer(boolean isContainer) {
        this.isContainer = isContainer;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public Integer getOriginalTrackNumber() {
        return originalTrackNumber;
    }

    public void setOriginalTrackNumber(Integer originalTrackNumber) {
        this.originalTrackNumber = originalTrackNumber;
    }
}
