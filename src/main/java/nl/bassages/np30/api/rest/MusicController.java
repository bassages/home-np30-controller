package nl.bassages.np30.api.rest;

import nl.bassages.np30.api.dto.ItemDto;
import nl.bassages.np30.api.dto.Message;
import nl.bassages.np30.domain.Item;
import nl.bassages.np30.domain.PlayBackDetails;
import nl.bassages.np30.repository.ItemRepo;
import nl.bassages.np30.service.MusicService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MusicController {

    private final MusicService musicService;
    private final ItemRepo itemRepo;

    public MusicController(MusicService musicService, ItemRepo itemRepo) {
        this.musicService = musicService;
        this.itemRepo = itemRepo;
    }

    @PostMapping("/play-folder/{folder-id}")
    public void playFolderNow(@PathVariable("folder-id") String folderId) {
        itemRepo.findById(folderId)
                .ifPresent(musicService::playFolderNow);
    }

    @GetMapping( "/folder/{folder-id}")
    public ItemDto getFolder(@PathVariable("folder-id") String folderId) {
        return itemRepo.findById(folderId)
                .map(folder -> {
                    List<ItemDto> childrenOfFolder = new ArrayList<>();
                    List<Item> itemsInFolder = itemRepo.findByParentId(folderId);
                    for (Item child : itemsInFolder) {
                        ItemDto itemDto = map(child);
                        childrenOfFolder.add(itemDto);
                    }
                    ItemDto result = map(folder);
                    result.setChildren(childrenOfFolder);
                    return result;
                }).orElse(null);
    }

    @PostMapping("/play-random-folder/{folder-id}")
    public Message randomFolder(@PathVariable("folder-id") String folderId) {
        Message result = new Message();
        result.setMessage(musicService.playRandomFolderNow(folderId));
        return result;
    }

    @PostMapping( "/refresh-cache")
    public Message updateLocalDb() throws Exception {
        Message result = new Message();
        musicService.refreshCache();
        result.setMessage("Update started");
        return result;
    }

    @GetMapping("/refresh-cache")
    public Message updateCacheStatus() {
        Message result = new Message();
        result.setMessage(musicService.getRefreshCacheProgressStatus());
        return result;
    }

    @PostMapping("/skip-next")
    public Message playNext() {
        Message result = new Message();
        result.setMessage(musicService.skipNext());
        return result;
    }

    @PostMapping("/skip-previous")
    public Message skipPrevious() {
        Message result = new Message();
        result.setMessage(musicService.skipPrevious());
        return result;
    }

    @PostMapping("/play-pause")
    public Message pause() {
        Message result = new Message();
        result.setMessage(musicService.playPause());
        return result;
    }


    @GetMapping("/playback-details")
    public PlayBackDetails info() throws Exception {
        return musicService.getPlaybackDetails();
    }

    private ItemDto map(Item item) {
        ItemDto result = new ItemDto();
        result.setId(item.getId());
        result.setIsContainer(item.isContainer());
        result.setOriginalTrackNumber(item.getOriginalTrackNumber());
        result.setTitle(item.getTitle());
        result.setDuration(item.getDuration());
        return result;
    }
}
