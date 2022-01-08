package nl.bassages.np30.service;

import nl.bassages.np30.domain.Item;
import nl.bassages.np30.domain.Np30ControllerUncheckedException;
import nl.bassages.np30.domain.PlayBackDetails;
import nl.bassages.np30.repository.ItemRepo;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.upnp.schemas.metadata_1_0.didl_lite.ContainerType;
import org.upnp.schemas.metadata_1_0.didl_lite.ItemType;
import org.upnp.schemas.metadata_1_0.didl_lite.RootType;
import org.w3c.dom.Document;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

// host name probably is "np30"
// http://np30:8050/e68f7d3a-302b-4bf2-98b9-15c5ad390f0b/description.xml

// Fritzbox: http://fritz.box/html/capture.html capture "wl1"
// Wireshark filter: ip.addr == 192.168.178.29 && http

@Service
public class MusicService {
    private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);

    public static final String TOP_ID = "0";

    private static final String SKIP_NEXT = "SKIP_NEXT";
    private static final String SKIP_PREVIOUS = "SKIP_PREVIOUS";
    private static final String PLAY_PAUSE = "PLAY_PAUSE";

    private String navigatorId;

    @Value("${np30.api.server-udn}")
    private String np30ServerUdn;

    @Value("#{'${np30.library.excluded-containternames}'.split(',')}")
    private List<String> excludedContainerNames;

    private final Random randomGenerator = new Random();

    private final List<Item> refreshCacheStatusStack = new CopyOnWriteArrayList<>();

    private final ItemRepo itemRepo;
    private final NavigatorService navigatorService;
    private final CacheManager cacheManager;
    private final SoapActionExcecutor soapActionExcecutor;

    public MusicService(ItemRepo itemRepo, NavigatorService navigatorService, CacheManager cacheManager, SoapActionExcecutor soapActionExcecutor) {
        this.itemRepo = itemRepo;
        this.navigatorService = navigatorService;
        this.cacheManager = cacheManager;
        this.soapActionExcecutor = soapActionExcecutor;
    }

    private enum PlaylistAction {
        PLAY_NOW,
        REPLACE
    }

    private static final String PLAY_FOLDER_NOW_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:QueueFolder xmlns:u="urn:UuVol-com:service:UuVolControl:5">
                        <DIDL>&lt;DIDL-Lite xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"&gt;&lt;container id="%s" parentID="%s"&lt;upnp:class&gt;object.container.album.musicAlbum&lt;/upnp:class&gt;&lt;/container&gt;&lt;/DIDL-Lite&gt;</DIDL>
                        <ServerUDN>%s</ServerUDN>
                        <Action>%s</Action>
                        <NavigatorId>%s</NavigatorId>
                        <ExtraInfo></ExtraInfo>
                    </u:QueueFolder>
                </s:Body>
            </s:Envelope>""";

    private static final String BROWSE_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                        <ObjectID>%s</ObjectID>
                        <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                        <Filter>*</Filter>
                        <StartingIndex>0</StartingIndex>
                        <RequestedCount>1500</RequestedCount>
                        <SortCriteria></SortCriteria>
                    </u:Browse>
                </s:Body>
            </s:Envelope>""";

    private static final String KEY_PRESSED_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:KeyPressed xmlns:u="urn:UuVol-com:service:UuVolSimpleRemote:1">
                        <Key>%s</Key>
                        <Duration>SHORT</Duration>
                    </u:KeyPressed>
                </s:Body>
            </s:Envelope>""";

    private static final String GET_PLAYBACK_DETAILS_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:GetPlaybackDetails xmlns:u="urn:UuVol-com:service:UuVolControl:5">
                    <NavigatorId>%s</NavigatorId></u:GetPlaybackDetails>
                </s:Body>
            </s:Envelope>""";

    public String playRandomFolderNow(String folderId) {
        this.navigatorId = navigatorService.registerNavigatorWhenNotAlreadyDone();

        List<Item> allPlayableItems = itemRepo.findByIsContainerFalse()
                .stream()
                .filter(playableItem -> playableItem.getId().startsWith(folderId))
                .toList();

        if (!allPlayableItems.isEmpty()) {
            var randomIndex = randomGenerator.nextInt(allPlayableItems.size());
            var randomItem = allPlayableItems.get(randomIndex);
            Item randomContainer = getParent(randomItem)
                    .orElseThrow(() -> new Np30ControllerUncheckedException("Could not find parent of randomItem with id " + randomItem.getId()));

            playFolderNow(randomContainer);

            return getPathString(randomContainer);
        }
        return "Failed to select random item";
    }

    public void playFolderNow(Item folder) {
        this.navigatorId = navigatorService.registerNavigatorWhenNotAlreadyDone();

        List<Item> path = getPathTo(new ArrayList<>(), folder);
        Collections.reverse(path);
        path.forEach(pathItem -> browse(pathItem.getId()));

        queueFolder(folder, PlaylistAction.REPLACE, navigatorId);
    }

    private List<Item> getPathTo(List<Item> path, Item item) {
        if (item != null) {
            path.add(item);
            final Optional<Item> parent = getParent(item);
            parent.ifPresent(value -> getPathTo(path, value));
        }
        return path;
    }

    private Optional<Item> getParent(Item item) {
        return itemRepo.findById(item.getParentId());
    }

    @Async
    public void refreshCache() throws Exception {
        LOG.info("RefreshCache");
        long start = System.currentTimeMillis();
        this.navigatorId = navigatorService.registerNavigatorWhenNotAlreadyDone();
        itemRepo.deleteAll();
        var item = new Item();
        item.setId(TOP_ID);
        refresh(item); // Always start from top, otherwise an error will be returned
        String msg = "Update cache finished at " + new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date()) + ". Processing time: " + DurationFormatUtils.formatDurationHMS(System.currentTimeMillis() - start);
        refreshCacheStatusStack.clear();
        LOG.info(msg);
    }

    public String getRefreshCacheProgressStatus() {
        StringBuilder result = new StringBuilder();

        for (int i=1; i<refreshCacheStatusStack.size(); i++) {
            Item pathItem = refreshCacheStatusStack.get(i);
            if (i > 1) {
                result.append(" -> ");
            }
            result.append(pathItem.getTitle());
        }
        return result.toString();
    }

    private String getPathString(Item item) {
        List<Item> path = getPathTo(new ArrayList<>(), item);

        Collections.reverse(path);

        StringBuilder randomItemPathString = new StringBuilder();
        for (var i=0; i<path.size(); i++) {
            var pathItem = path.get(i);
            if (i > 0) {
                randomItemPathString.append(" -> ");
            }
            browse(pathItem.getId());

            randomItemPathString.append(pathItem.getTitle());
        }

        queueFolder(item, PlaylistAction.REPLACE, navigatorId);
        return randomItemPathString.toString();
    }

    private void refresh(Item item) throws Exception {
        clearAllCaches();
        refreshCacheStatusStack.add(item);

        var responseString = browse(item.getId());

        String resultXml = XmlUtil.getElementContent(responseString, "Result")
                .orElseThrow(() -> new Np30ControllerUncheckedException("Failed to find element \"Result\" in response [" + responseString + "]"));

        LOG.debug(resultXml);

        var jaxbContext = JAXBContext.newInstance(RootType.class);
        var unmarshaller = jaxbContext.createUnmarshaller();

        var reader = new StringReader(resultXml);
        var rootType = ((JAXBElement<RootType>) unmarshaller.unmarshal(reader)).getValue();

        for (Object object : rootType.getAllowedUnderDIDLLite()) {
            if (object instanceof ContainerType container) {
                String title = container.getTitle().getValue();

                if (excludedContainerNames.stream().noneMatch(title::equalsIgnoreCase)) {
                    var isContainer = true;
                    Item savedContainer = save(container.getId(), container.getParentID(), null, title, null, isContainer);
                    refresh(savedContainer);
                }

            } else if (object instanceof ItemType itemType) {
                var isContainer = false;
                String duration = getDuration(itemType);
                save(itemType.getId(), itemType.getParentID(), itemType.getOriginalTrackNumber(), itemType.getTitle().getValue(), duration, isContainer);
            }
        }
        refreshCacheStatusStack.remove(item);
        clearAllCaches();
    }

    private String getDuration(ItemType itemType) {
        if (itemType.getRes() == null) {
            return null;
        }
        // delete millis part (because it's always ".000")
        String duration = itemType.getRes().getDuration().replace(".000", "");
        // Only keep hours when it's > 0
        final String[] split = duration.split(":");
        if (split[0].equals("0")) {
            duration = split[1] + ":" + split[2];
        } else {
            duration = split[0] + ":" + split[1] + ":" + split[2];
        }
        return duration;
    }

    private String browse(String id) {
        final String response = soapActionExcecutor.execute("/ContentDirectory/invoke.xml", "urn:schemas-upnp-org:service:ContentDirectory:1#Browse",
                format(BROWSE_TEMPLATE, id));
        if (response.contains("Fault") && response.contains("faultstring")) {
            throw new Np30ControllerUncheckedException("Failed to browse id=" + id);
        }
        return response;
    }

    private void queueFolder(Item folder, PlaylistAction playlistAction, String navigatorId) {
        final String response = soapActionExcecutor.execute("/RecivaRadio/invoke.xml", "urn:UuVol-com:service:UuVolControl:5#QueueFolder",
                format(PLAY_FOLDER_NOW_TEMPLATE, folder.getId(), folder.getParentId(), np30ServerUdn, playlistAction.name(), navigatorId));
        final Optional<String> result = XmlUtil.getElementContent(response, "Result");
        if (result.isEmpty() || !result.get().equals("OK")) {
            throw new Np30ControllerUncheckedException("Failed to queueFolder. Response: " + response);
        }
    }

    public PlayBackDetails getPlaybackDetails() throws Exception {
        final String soapActionResult = soapActionExcecutor.execute("/RecivaRadio/invoke.xml", "urn:UuVol-com:service:UuVolControl:5#GetPlaybackDetails",
                format(GET_PLAYBACK_DETAILS_TEMPLATE, navigatorId));

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final Document soapActionResultDoc = builder.parse(IOUtils.toInputStream(soapActionResult, UTF_8));
        final String reciva = soapActionResultDoc.getElementsByTagName("RetPlaybackXML").item(0).getTextContent();

        final Document recivaDoc = builder.parse(IOUtils.toInputStream(reciva, UTF_8));
        final String state = recivaDoc.getElementsByTagName("state").item(0).getTextContent().toUpperCase();

        if ("PLAYING".equals(state)) {
            final String artist = recivaDoc.getElementsByTagName("artist").item(0).getTextContent();
            final String album = recivaDoc.getElementsByTagName("album").item(0).getTextContent();
            final String title = recivaDoc.getElementsByTagName("title").item(0).getTextContent();
            final String file = URLDecoder.decode(recivaDoc.getElementsByTagName("url").item(0).getTextContent(), UTF_8).replace("file:///tmp/usm/1/", "");
            return new PlayBackDetails(artist, title, album, file, state);
        } else {
            return new PlayBackDetails(state);
        }
    }

    public String skip(final String direction) {
        return soapActionExcecutor.execute("/RecivaSimpleRemote/invoke.xml", "urn:UuVol-com:service:UuVolSimpleRemote:1#KeyPressed",
                format(KEY_PRESSED_TEMPLATE, direction));
    }

    public String playPause() {
        return soapActionExcecutor.execute("/RecivaSimpleRemote/invoke.xml", "urn:UuVol-com:service:UuVolSimpleRemote:1#KeyPressed",
                format(KEY_PRESSED_TEMPLATE, PLAY_PAUSE));
    }

    public String skipNext() {
        return skip(SKIP_NEXT);
    }

    public String skipPrevious() {
        return skip(SKIP_PREVIOUS);
    }

    private Item save(String id, String parentId, Integer originalTrackNumber, String title, String duration, boolean isContainer) {
        var item = new Item();
        item.setId(id);
        item.setOriginalTrackNumber(originalTrackNumber);
        item.setTitle(title);
        item.setParentId(parentId);
        item.setIsContainer(isContainer);
        item.setDuration(duration);
        return itemRepo.saveAndFlush(item);
    }

    private void clearAllCaches() {
        cacheManager.getCacheNames().forEach(s -> {
            final Cache cache = cacheManager.getCache(s);
            if (cache != null) {
                cache.clear();
            }
        });
    }
}
