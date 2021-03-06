package nl.bassages.np30.service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import nl.bassages.np30.domain.Item;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.upnp.schemas.metadata_1_0.didl_lite.ContainerType;
import org.upnp.schemas.metadata_1_0.didl_lite.ItemType;
import org.upnp.schemas.metadata_1_0.didl_lite.RootType;

import nl.bassages.np30.repository.ItemRepo;
import org.w3c.dom.Document;

// Please check http://<ip or hostname of NP30>:8050/e68f7d3a-302b-4bf2-98b9-15c5ad390f0b/description.xml
// Example, see src/test/resources/description.xml

@Service
public class MusicService {

    private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);

    public static final String TOP_ID = "0:0";
    private static final String NAVIGATOR_NAME = "eae7dc9f-ce35-4940-bcd9-f495e4867cf5_NP";

    private static final String SKIP_NEXT = "SKIP_NEXT";
    private static final String SKIP_PREVIOUS = "SKIP_PREVIOUS";

    private String navigatorId;

    @Value("${np30.api.server-udn}")
    private String np30ServerUdn;

    @Value("${np30.api.url}")
    private String np30BaseUrl;

    @Value("#{'${np30.library.excluded-containternames}'.split(',')}")
    private List<String> excludedContainernames;

    private final Random randomGenerator = new Random();

    private final List<Item> refreshCacheStatusStack = new CopyOnWriteArrayList();

    private final ItemRepo itemRepo;

    private final CacheManager cacheManager;

    @Autowired
    public MusicService(ItemRepo itemRepo, CacheManager cacheManager) {
        this.itemRepo = itemRepo;
        this.cacheManager = cacheManager;
    }

    private enum PlaylistAction {
        PLAY_NOW,
        REPLACE
    }

    private final String isRegisteredNavigatorNameTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body>" +
                    "<u:IsRegisteredNavigatorName xmlns:u=\"urn:UuVol-com:service:UuVolControl:5\">" +
                        "<NavigatorName>" + NAVIGATOR_NAME + "</NavigatorName>" +
                    "</u:IsRegisteredNavigatorName>" +
                "</s:Body>" +
            "</s:Envelope>";

    private final String registerNavigatorNameTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body>" +
                    "<u:RegisterNamedNavigator xmlns:u=\"urn:UuVol-com:service:UuVolControl:5\">" +
                        "<NewNavigatorName>" + NAVIGATOR_NAME + "</NewNavigatorName>" +
                    "</u:RegisterNamedNavigator>" +
                "</s:Body>" +
            "</s:Envelope>";

    private final String playFolderNowTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body>" +
                    "<u:QueueFolder xmlns:u=\"urn:UuVol-com:service:UuVolControl:5\">" +
                        "<DIDL>&lt;DIDL-Lite xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"&gt;&lt;container id=\"%s\" parentID=\"%s\"&lt;upnp:class&gt;object.container.album.musicAlbum&lt;/upnp:class&gt;&lt;/container&gt;&lt;/DIDL-Lite&gt;</DIDL>" +
                        "<ServerUDN>%s</ServerUDN>" +
                        "<Action>%s</Action>" +
                        "<NavigatorId>%s</NavigatorId>" +
                        "<ExtraInfo></ExtraInfo>" +
                    "</u:QueueFolder>" +
                "</s:Body>" +
            "</s:Envelope>";

    private final String browseRequestTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body>" +
                    "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                        "<ObjectID>%s</ObjectID>" +
                        "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                        "<Filter>*</Filter>" +
                        "<StartingIndex>0</StartingIndex>" +
                        "<RequestedCount>1500</RequestedCount>" +
                        "<SortCriteria></SortCriteria>" +
                    "</u:Browse>" +
                "</s:Body>" +
            "</s:Envelope>";

    private final String skipTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body><u:KeyPressed xmlns:u=\"urn:UuVol-com:service:UuVolSimpleRemote:1\">" +
                        "<Key>%s</Key>" +
                        "<Duration>SHORT</Duration>" +
                    "</u:KeyPressed>" +
                "</s:Body>" +
            "</s:Envelope>";

    private final String playbackDetailsTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body>" +
                    "<u:GetPlaybackDetails xmlns:u=\"urn:UuVol-com:service:UuVolControl:5\">" +
                        "<NavigatorId>%s</NavigatorId>" +
                    "</u:GetPlaybackDetails>" +
                "</s:Body>" +
            "</s:Envelope>";

    public String playRandomFolderNow() throws IOException {
        registerNavigatorWhenNotAlreadyDone();

        List<Item> items = itemRepo.findByIsContainerFalse();

        if (!items.isEmpty()) {
            var randomIndex = randomGenerator.nextInt(items.size());
            var randomItem = items.get(randomIndex);
            Item randomContainer = getParent(randomItem);

            playFolderNow(randomContainer);

            return getPathString(randomContainer);
        }
        return "Failed to select random item";
    }

    public void playFolderNow(Item folder) throws IOException {
        registerNavigatorWhenNotAlreadyDone();

        List<Item> path = getPathTo(new ArrayList<>(), folder);

        Collections.reverse(path);

        for (Item pathItem : path) {
            browse(pathItem.getId());
        }

        String response = queueFolder(folder, PlaylistAction.REPLACE, navigatorId);
        if (!getElementContentXml(response, "Result").equals("OK")) {
            throw new RuntimeException("Failed to queueFolder. Response: " + response);
        }
    }

    private List<Item> getPathTo(List<Item> path, Item item) {
        if (item != null) {
            path.add(item);
            if (!item.getId().equals(TOP_ID)) {
                getPathTo(path, getParent(item));
            }
        }
        return path;
    }

    private Item getParent(Item item) {
        final Optional<Item> itemById = itemRepo.findById(item.getParentId());
        return itemById.get();
    }

    private void registerNavigatorWhenNotAlreadyDone() throws IOException {
        String isRegisteredNavigatorNameResponse = isRegisteredNavigatorName();
        navigatorId = getElementContentXml(isRegisteredNavigatorNameResponse, "RetNavigatorId");

        if (navigatorId == null) {
            String registerNavigatorNameResponse = registerNavigatorName();
            navigatorId = getElementContentXml(registerNavigatorNameResponse, "RetNavigatorId");

            if (navigatorId == null) {
                throw new RuntimeException("Failed to determine navigatorId. SoapResponse: " + isRegisteredNavigatorNameResponse);
            }
        }
    }

    @Async
    public void refreshCache() throws Exception {
        long start = System.currentTimeMillis();

        LOG.info("RefreshCache");

        registerNavigatorWhenNotAlreadyDone();

        itemRepo.deleteAll();

        var item = new Item();
        item.setId(TOP_ID);

        refresh(item, 0); // Always start from top, otherwise an error will be returned

        String msg = "Update cache finished at " + new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date()) + ". Processing time: " + DurationFormatUtils.formatDurationHMS(System.currentTimeMillis() - start);

        refreshCacheStatusStack.clear();

        LOG.info(msg);
    }

    public String getRefreshCacheProgressStatus() {
        String result = "";

        for (int i=1; i<refreshCacheStatusStack.size(); i++) {
            Item pathItem = refreshCacheStatusStack.get(i);
            if (i > 1) {
                result += " -> ";
            }
            result += pathItem.getTitle();
        }
        return result;
    }

    private String getPathString(Item item) throws IOException {
        List<Item> path = getPathTo(new ArrayList<>(), item);

        Collections.reverse(path);

        var randomItemPathString = "";
        for (var i=0; i<path.size(); i++) {
            var pathItem = path.get(i);
            if (i > 0) {
                randomItemPathString += " -> ";
            }
            browse(pathItem.getId());

            randomItemPathString += pathItem.getTitle();
        }

        String response = queueFolder(item, PlaylistAction.REPLACE, navigatorId);
        if (!getElementContentXml(response, "Result").equals("OK")) {
            throw new RuntimeException("Failed to queueFolder. Response: " + response);
        }
        return randomItemPathString;
    }

    private void refresh(Item item, int level) throws Exception {
        clearAllCaches();
        refreshCacheStatusStack.add(item);

        var responseString = browse(item.getId());

        if (responseString.contains("Fault") && responseString.contains("faultstring")) {
            LOG.error(responseString);
        }

        String resultXml = getElementContentXml(responseString, "Result");

        var jaxbContext = JAXBContext.newInstance(RootType.class);
        var unmarshaller = jaxbContext.createUnmarshaller();

        var reader = new StringReader(resultXml);
        var rootType = ((JAXBElement<RootType>) unmarshaller.unmarshal(reader)).getValue();

        for(Object object : rootType.getAllowedUnderDIDLLite()) {

            if (object instanceof ContainerType) {
                ContainerType container = (ContainerType) object;

                String title = container.getTitle().getValue();

                if (excludedContainernames.stream().noneMatch(title::equalsIgnoreCase)) {
                    var isContainer = true;
                    Item savedContainer = save(container.getId(), container.getParentID(), title, level, isContainer);
                    refresh(savedContainer, level + 1);
                }

            } else if (object instanceof ItemType) {
                var itemType = (ItemType) object;
                var isContainer = false;
                save(itemType.getId(), itemType.getParentID(), itemType.getTitle().getValue(), level, isContainer);
            }
        }
        refreshCacheStatusStack.remove(item);
        clearAllCaches();
    }

    private String getElementContentXml(String soapResponse, String elementName) {
        String result = null;

        var pattern = Pattern.compile(".+<" + elementName + ">(.+)</" + elementName + ">.+", Pattern.DOTALL);
        var matcher = pattern.matcher(soapResponse);
        if (matcher.matches()) {
            result = StringEscapeUtils.unescapeXml(matcher.group(1));
        }
        return result;
    }

    private String isRegisteredNavigatorName() throws IOException {
        return executeSoapAction("/RecivaRadio/invoke", "\"urn:UuVol-com:service:UuVolControl:5#IsRegisteredNavigatorName\"", isRegisteredNavigatorNameTemplate);
    }

    private String registerNavigatorName() throws IOException {
        return executeSoapAction("/RecivaRadio/invoke", "\"urn:UuVol-com:service:UuVolControl:5#RegisterNamedNavigator\"", registerNavigatorNameTemplate);
    }

    private String browse(String id) throws IOException {
        return executeSoapAction("/ContentDirectory/invoke", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"", String.format(browseRequestTemplate, id));
    }

    private String queueFolder(Item folder, PlaylistAction playlistAction, String navigatorId) throws IOException {
        return executeSoapAction("/RecivaRadio/invoke", "\"urn:UuVol-com:service:UuVolControl:5#QueueFolder\"", String.format(playFolderNowTemplate, folder.getId(), folder.getParentId(), np30ServerUdn, playlistAction.name(), navigatorId));
    }

    public PlayBackDetails getPlaybackDetails() throws Exception {
        final String soapActionResult = executeSoapAction("/RecivaRadio/invoke", "\"urn:UuVol-com:service:UuVolControl:5#GetPlaybackDetails\"", String.format(playbackDetailsTemplate, navigatorId));

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final Document soapActionResultDoc = builder.parse(IOUtils.toInputStream(soapActionResult, StandardCharsets.UTF_8));
        final String reciva = soapActionResultDoc.getElementsByTagName("RetPlaybackXML").item(0).getTextContent();

        final Document recivaDoc = builder.parse(IOUtils.toInputStream(reciva, StandardCharsets.UTF_8));
        final String artist = recivaDoc.getElementsByTagName("artist").item(0).getTextContent();
        final String album = recivaDoc.getElementsByTagName("album").item(0).getTextContent();
        final String title = recivaDoc.getElementsByTagName("title").item(0).getTextContent();

        return new PlayBackDetails(artist, title, album);
    }

    public String skip(final String direction) throws IOException {
        return executeSoapAction("/RecivaSimpleRemote/invoke", "\"urn:UuVol-com:service:UuVolSimpleRemote:1#KeyPressed\"", String.format(skipTemplate, direction));
    }

    public String skipNext() throws IOException {
        return skip(SKIP_NEXT);
    }

    public String skipPrevious() throws IOException {
        return skip(SKIP_PREVIOUS);
    }

    private String executeSoapAction(String port, String soapAction, String body) throws IOException {
        LOG.debug("Request body: {}", body);

        CloseableHttpClient httpclient = HttpClients.createDefault();
        var httpPost = new HttpPost(np30BaseUrl + port);
        httpPost.setHeader("Content-Type", "text/xml; charset=\"utf-8\"");
        httpPost.setHeader("SOAPAction", soapAction);
        httpPost.setEntity(new StringEntity(body));

        String responseString;
        try (CloseableHttpResponse response = httpclient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            responseString = EntityUtils.toString(entity);
        }
        return responseString;
    }

    private Item save(String id, String parentId, String title, int level, boolean isContainer) {
        var item = new Item();
        item.setId(id);
        item.setTitle(title);
        item.setParentId(parentId);
        item.setIsContainer(isContainer);
        return itemRepo.saveAndFlush(item);
    }

    private void clearAllCaches() {
        cacheManager.getCacheNames().forEach(s -> cacheManager.getCache(s).clear());
    }
}
