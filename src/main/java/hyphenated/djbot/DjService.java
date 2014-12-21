package hyphenated.djbot;

import com.dropbox.core.*;
import hyphenated.djbot.json.SongEntry;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//the main brains of the djbot, handles the queues and whatnot
public class DjService {
    public Logger logger = LoggerFactory.getLogger("hyphenated.djbot");

    private final String queueHistoryFilePath = "queue.json";
    private final String unplayedSongsFilePath = "unplayedSongs.json";
    private final String dboxFilePath = "/Public/songlist.txt";

    private final DjConfiguration conf;
    private final DjIrcBot irc;

    private volatile SongEntry currentSong;
    private volatile ArrayList<SongEntry> songList = new ArrayList<>();
    private volatile ArrayList<SongEntry> secondarySongList = new ArrayList<>();
    private volatile ArrayList<SongEntry> songHistory = new ArrayList<>();

    private volatile ArrayList<SongEntry> lastPlayedSongs = new ArrayList<>();

    private volatile int volume;
    private volatile int nextRequestId;

    //the following things should not change after initialization
    private final String streamer;
    private final String dropboxLink;
    private Set<String> blacklistedYoutubeIds; //immutable after creation


    public DjService(DjConfiguration newConf, DjIrcBot irc) {
        this.conf = newConf;
        this.streamer = conf.getChannel();
        this.volume = conf.getDefaultVolume();
        this.irc = irc;


        List<String> history = null;
        try {
            history = IOUtils.readLines(new FileInputStream(queueHistoryFilePath), "utf-8");
        } catch (IOException e) {
            throw new RuntimeException("Couldn't find file at " + queueHistoryFilePath, e);
        }

        String unplayedSongsStr = null;
        try {
            unplayedSongsStr = IOUtils.toString(new FileInputStream(unplayedSongsFilePath), "utf-8");
        } catch (IOException e) {
            throw new RuntimeException("Couldn't find file at " + unplayedSongsFilePath, e);
        }

        ObjectMapper mapper = new ObjectMapper();
        ArrayList<Integer> unplayedSongsList = null;
        try {
            unplayedSongsList = mapper.readValue(unplayedSongsStr, ArrayList.class);
        } catch (IOException e) {
            throw new RuntimeException("File at " + unplayedSongsFilePath + " probably has invalid format (it should be json with just an array of integers)", e);
        }
        HashSet<Integer> unplayedSongs = new HashSet<>();
        unplayedSongs.addAll(unplayedSongsList);

        int lastRequestId = 0;
        int lineNumber = 1;
        for(String historyElement : history) {
            SongEntry entry = null;
            try {
                entry = mapper.readValue(historyElement, SongEntry.class);
            } catch (IOException e) {
                throw new RuntimeException("File at " + queueHistoryFilePath + " has a problem at line " + lineNumber + ": the line won't parse to a json object with the right format.", e);
            }

            if(unplayedSongs.contains(entry.getRequestId())) {
                if(entry.isBackup()) {
                    secondarySongList.add(entry);
                } else {
                    songList.add(entry);
                }
            }
            //todo: configure 2 weeks here. or maybe have no cutoff? the point is so when the file gets huge we don't keep operating on the huge thing all day.
            long historyCutoff = DateTime.now().minusWeeks(2).toDate().getTime();
            if(entry.getRequestTime() > historyCutoff ) {
                songHistory.add(entry);
            }

            lastRequestId = Math.max(lastRequestId, entry.getRequestId());
            ++lineNumber;
        }

        this.nextRequestId = lastRequestId + 1;

        dropboxLink = determineDropboxLink(conf);

        HashSet<String> blacklist = new HashSet<>();
        if(conf.getBlacklistedYoutubeIds() != null) {
            blacklist.addAll(conf.getBlacklistedYoutubeIds());
        }
        this.blacklistedYoutubeIds = Collections.unmodifiableSet(blacklist);

        //create the scrollable console gui window
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                GuiWindow.createAndShowGUI(conf.getMaxConsoleLines());
            }
        });

    }

    private String determineDropboxLink(DjConfiguration conf) {
        DbxClient client = getDbxClient();
        try {
            String url = client.createShareableUrl(dboxFilePath);
            return url.replace("?dl=0", "?raw=1");
        } catch (DbxException e) {
            logger.error("Can't create dropbox link", e);
            return null;
        }
    }

    private DbxClient getDbxClient() {
        DbxRequestConfig config = new DbxRequestConfig("djbot/1.0", Locale.getDefault().toString());
        return new DbxClient(config, conf.getDropboxAccessToken());
    }

    public synchronized void irc_songlist( String sender) {
        if(StringUtils.isEmpty(dropboxLink)) {
            irc.message("Sorry " + sender + ", songlist isn't set up");
        } else {
            irc.message(sender + ": see the song list at " + dropboxLink);
        }
    }

    public synchronized void irc_removesong( String sender, String skipIdStr) {
        if(!irc.isMod(sender)) {
            irc.message("removing or skipping songs is for mods only");
            return;
        }

        int skipId;
        try {
            skipId = Integer.parseInt(skipIdStr);
        } catch (NumberFormatException e) {
            irc.message(sender + ": you must specify the song id to remove or skip (a number)");
            return;
        }

        if (currentSong.getRequestId() == skipId) {
            nextSong();
        }
        removeSongFromList(songList, skipId, sender);
        removeSongFromList(secondarySongList, skipId, sender);

        try {
            updatePlayedSongsFile();
        } catch (IOException e) {
            logger.error("problem updating playedSongs", e);
        }


    }

    private void removeSongFromList(List<SongEntry> listOfSongs, int skipId, String sender) {
        Iterator<SongEntry> entryIterator = listOfSongs.iterator();
        while (entryIterator.hasNext()) {
            SongEntry curEntry = entryIterator.next();
            if (curEntry.getRequestId() == skipId) {
                irc.message(sender + ": removed song \"" + curEntry.getTitle() + "\"");
                entryIterator.remove();
            }
        }
    }


    public void irc_volume(String sender, String newVolStr) {
        if(StringUtils.isEmpty(newVolStr)) {
            irc.message(sender + ": current volume is " + volume);
            return;
        }

        if(!irc.isMod(sender)) {
            irc.message(sender + ": changing volume is for mods only");
            return;
        }

        int newVolume;
        if("up".equalsIgnoreCase(newVolStr)) {
            newVolume = volume + 10;
        } else if ("down".equalsIgnoreCase(newVolStr)) {
            newVolume = volume - 10;
        } else {
            try {
                newVolume = Integer.parseInt(newVolStr);
            } catch (NumberFormatException e) {
                irc.message( sender + ": volume must be 'up', 'down', or a number between 1 and 100");
                return;
            }
        }

        if (newVolume < 1) {
            newVolume = 1;
        } else if (newVolume > 100) {
            newVolume = 100;
        }
        irc.message(sender + ": volume changed from " + volume + " to " + newVolume);
        volume = newVolume;

    }

    public void irc_songs(String sender) {
        irc.message( sender + ": use \"!songrequest youtubeURL\" to request a song");
    }


    public synchronized void irc_currentsong(String sender) {
        if(currentSong == null) {
            irc.message( sender + ": no current song (or the server just restarted)");
        } else {
            irc.message( sender + ": Current song: \"" + currentSong.getTitle() + "\", url: " + currentSong.buildYoutubeUrl() + ", id: " + currentSong.getRequestId());
        }
    }

    public synchronized void irc_lastsong(String sender) {
        if(lastPlayedSongs.size() == 0) {
            irc.message(sender + ": no song has finished playing yet");
            return;
        }
        SongEntry lastSong = lastPlayedSongs.get(lastPlayedSongs.size()-1);
        irc.message( sender + ": Last song: \"" + currentSong.getTitle() + "\", url: " + currentSong.buildYoutubeUrl());
    }


    Pattern idPattern = Pattern.compile("[a-zA-Z0-9_-]{11}");

    //given a string that a user songrequested, try to figure out what it is a link to and do the work to handle it
    public synchronized void irc_songRequest(String sender, String requestStr) {

        int startSeconds = extractStartSecondsFromTimeParam(requestStr);

        String possibleYoutubeId = requestStr.substring(0, 11);
        Matcher m = idPattern.matcher(possibleYoutubeId);
        //we support !songrequest <youtubeid>
        if (m.matches()) {
            doYoutubeRequest(sender, possibleYoutubeId, startSeconds);
            return;
        }

        //we support youtu.be/<youtubeid>
        String youtuBeId = findYoutubeIdAfterMarker(requestStr, "youtu.be/");
        if(youtuBeId != null) {
            doYoutubeRequest(sender, youtuBeId, startSeconds);
            return;
        }

        //we support standard youtube links like https://www.youtube.com/watch?v=<youtubeid>
        String vParamId = findYoutubeIdAfterMarker(requestStr, "v=");
        if(vParamId != null) {
            doYoutubeRequest(sender, vParamId, startSeconds);
            return;
        }

        //we support youtube links like https://www.youtube.com/v/<youtubeid>
        String vPathId = findYoutubeIdAfterMarker(requestStr, "/v/");
        if(requestStr.contains("youtube.com") && vPathId != null) {
            doYoutubeRequest(sender, vPathId, startSeconds);
            return;
        }

        //we support PLAYLISTS like https://www.youtube.com/playlist?list=<playlistid>
        String listPathVar = "?list=";
        int listPathIndex = requestStr.lastIndexOf(listPathVar);
        if(listPathIndex > -1) {
            String listPathId = requestStr.substring(listPathIndex + listPathVar.length());
            doYoutubeListRequest(sender, listPathId);
            return;
        }
        irc.message(sender + ": couldn't find a youtube video id in your request");

    }


    private static final Pattern timePattern = Pattern.compile("(((\\d)+)m)?(((\\d)+)s)");

    //given a request string, look for something like &t=1m22s in there, parse the time value, and return the number of seconds it represents
    private int extractStartSecondsFromTimeParam(String requestStr) {
        int paramIdx = requestStr.lastIndexOf("&t=");
        if(paramIdx < 0) {
            paramIdx = requestStr.lastIndexOf("#t=");
        }
        if(paramIdx < 0) {
            return 0;
        }
        int startOfTimeIdx = paramIdx + 3;
        int endOfTimeIdx;
        for(endOfTimeIdx = startOfTimeIdx; endOfTimeIdx < requestStr.length(); ++endOfTimeIdx) {
            char c = requestStr.charAt(endOfTimeIdx);
            if(c != 'm' && c != 's' && !Character.isDigit(c)) {
                break;
            }
        }

        String timeStr = requestStr.substring(startOfTimeIdx, endOfTimeIdx);
        logger.info("trying to parse " + timeStr + " as a time string");

        Matcher matcher = timePattern.matcher(timeStr);
        if(!matcher.matches()) {
            return 0;
        }
        //minutes and seconds
        String mStr = matcher.group(2);
        String sStr = matcher.group(5);
        int m, s;
        try {
            m = Integer.parseInt(mStr);
        } catch (NumberFormatException e) {
            m = 0;
        }

        try {
            s = Integer.parseInt(sStr);
        } catch (NumberFormatException e) {
            s = 0;
        }

        return m * 60 + s;
    }

    //given a request string, look for a youtube id that directly follows the given marker at some point in the string
    private String findYoutubeIdAfterMarker(String requestStr, String marker) {
        int youtuBeIdx = requestStr.indexOf(marker);
        if(youtuBeIdx > 0) {
            return findYoutubeIdPrefix(requestStr.substring(youtuBeIdx + marker.length()));
        } else {
            return null;
        }
    }

    //given a string that might begin with a youtube id, return the id if it does begin with one
    private String findYoutubeIdPrefix(String strWithPossibleIdPrefix) {
        int idStart = 0;
        int idEnd = 11;
        if (strWithPossibleIdPrefix.length() >= idEnd) {
            String potentialId = strWithPossibleIdPrefix.substring(idStart, idEnd);
            Matcher m = idPattern.matcher(potentialId);
            if(m.matches()) {
                return potentialId;
            }
        }
        return null;
    }

    private void updateQueuesForLeavers() {
        if(conf.getSecondaryQueueCountdownSeconds() == 0) {
            return;
        }
        try {
            //any song requested by someone who has been gone for long enough should move from the primary list to the secondary list

            ArrayList<SongEntry> newSongList = new ArrayList<>();
            for (SongEntry song : songList) {
                DateTime leaveTime = irc.leaveTimeByUser.get(song.getUser());

                if (leaveTime != null && leaveTime.plusSeconds(conf.getSecondaryQueueCountdownSeconds()).isBeforeNow()) {
                    logger.info("Bumping songid " + song.getRequestId() + " to secondary queue because its requester (" + song.getUser() + ") has been gone for " + conf.getSecondaryQueueCountdownSeconds() + " seconds");
                    secondarySongList.add(song);
                } else {
                    newSongList.add(song);
                }
            }
            songList = newSongList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void updatePlayedSongsFile() throws IOException {
        //update the list of what songs have been played. anything currently playing or in a queue has not "been played"
        ArrayList<Integer> unplayedSongs = new ArrayList<>();
        if(currentSong != null) {
            unplayedSongs.add(currentSong.getRequestId());
        }
        for (SongEntry song : songList) {
            unplayedSongs.add(song.getRequestId());
        }
        for (SongEntry song : secondarySongList) {
            unplayedSongs.add(song.getRequestId());
        }

        ObjectMapper mapper = new ObjectMapper();
        String unplayedSongsJson = mapper.writeValueAsString(unplayedSongs);
        FileUtils.writeStringToFile(new File(this.unplayedSongsFilePath), unplayedSongsJson, "utf-8");


        //send file to dropbox
        DbxClient client = getDbxClient();
        try {
            String dboxContents = buildReportString();
            byte[] contentBytes = dboxContents.getBytes("utf-8");
            DbxEntry.File uploadedFile = client.uploadFile(dboxFilePath,
                    DbxWriteMode.force(), contentBytes.length, new ByteArrayInputStream(contentBytes));
        } catch (DbxException e) {
            logger.error("Problem talking to dropbox to update the songlist", e);
        }
    }

    private String buildReportJSON() {
        int runningSeconds = 0;

        ObjectNode reportJs = new ObjectNode(JsonNodeFactory.instance);
        if(currentSong != null) {
            runningSeconds += currentSong.getDurationSeconds();
            reportJs.put("nowPlaying", songEntryToReportJSON(currentSong, -1));
        }

        if(songList.size() > 0) {
            ArrayNode mainList = new ArrayNode(JsonNodeFactory.instance);
            for(SongEntry song : songList) {
                mainList.add(songEntryToReportJSON(song, runningSeconds));
                runningSeconds += song.getDurationSeconds();
            }
            reportJs.put("mainList", mainList);
        }


        if(secondarySongList.size() > 0) {
            ArrayNode secondaryList = new ArrayNode(JsonNodeFactory.instance);
            for(SongEntry song : songList) {
                secondaryList.add(songEntryToReportJSON(song, runningSeconds));
                runningSeconds += song.getDurationSeconds();
            }
            reportJs.put("secondaryList", secondaryList);
        }

        runningSeconds = 0;
        if(lastPlayedSongs.size() > 0) {
            ArrayNode previouslyPlayed = new ArrayNode(JsonNodeFactory.instance);
            for(int i = lastPlayedSongs.size()-1; i >=0; --i) {
                SongEntry song = lastPlayedSongs.get(i);
                previouslyPlayed.add(songEntryToReportJSON(song, runningSeconds));
                runningSeconds += song.getDurationSeconds();
            }
            reportJs.put("previouslyPlayed", previouslyPlayed);
        }


        return reportJs.toString();

    }

    private ObjectNode songEntryToReportJSON(SongEntry song, int secondsFromNow) {

        ObjectMapper mapper = new ObjectMapper();
        JsonNode js = mapper.valueToTree(song);
        ObjectNode obJs = (ObjectNode) js;

        obJs.put("time", "about " +  secondsFromNow / 60 + " minutes");
        return obJs;
    }


    private String buildReportString() {
        StringBuilder sb = new StringBuilder();
        int runningSeconds = 0;

        if(currentSong != null) {
            runningSeconds += currentSong.getDurationSeconds();
            sb.append("Now playing:\n");
            appendSongReportEntry(sb, currentSong);
            sb.append("\n");
        }
        sb.append("============\n");
        if(songList.size() > 0) {
            sb.append("Main list:\n============\n");
            for(SongEntry song : songList) {
                appendSongReportEntry(sb, song);
                appendPlaysNextInfo(sb, runningSeconds);
                sb.append("\n\n" );
                runningSeconds += song.getDurationSeconds();
            }
        }
        if(secondarySongList.size() > 0) {
            sb.append("\n\nSecondary list:\n============\n");
            for(SongEntry song : secondarySongList) {
                appendSongReportEntry(sb, song);
                appendPlaysNextInfo(sb, runningSeconds);
                sb.append(" (if nothing is requested)\n\n" );
                runningSeconds += song.getDurationSeconds();
            }
        }

        runningSeconds = 0;
        if(lastPlayedSongs.size() > 0) {
            sb.append("\n\nPreviously played songs:\n============\n");
            for(int i = lastPlayedSongs.size()-1; i >=0; --i) {
                SongEntry song = lastPlayedSongs.get(i);
                appendSongReportEntry(sb, song);
                appendPlaysNextInfo(sb, runningSeconds);
                sb.append(" ago\n\n" );
                runningSeconds += song.getDurationSeconds();
            }
        }


        return sb.toString();
    }

    private void appendSongReportEntry(StringBuilder sb, SongEntry song) {
        sb.append(song.buildYoutubeUrl()).append(" \"")
                .append(song.getTitle())
                .append("\", requested by ").append(song.getUser())
                .append(", id: ").append(song.getRequestId());

    }

    private void appendPlaysNextInfo(StringBuilder sb, int runningSeconds) {
        sb.append(", about ").append(runningSeconds / 60).append(" minutes");
    }

    private void doYoutubeRequest(String sender, String youtubeId, int startSeconds) {
        updateQueuesForLeavers();

        if(blacklistedYoutubeIds.contains(youtubeId)) {
            denySong(sender, "that song is blacklisted by the streamer");
            return;
        }

        String infoUrl = "http://gdata.youtube.com/feeds/api/videos/" + youtubeId + "?v=2&alt=jsonc&restriction=" + conf.getUserCountryCode();
        GetMethod get = new GetMethod(infoUrl);
        HttpClient client = new HttpClient();
        try {
            int errcode = client.executeMethod(get);
            if(errcode != 200) {
                logger.info("Song request error: got code " + errcode + " from " + infoUrl);
                denySong(sender, "I couldn't find info about that video on youtube");

                return;
            }
        } catch (IOException e) {
            logger.warn("Couldn't get info from youtube api", e);
            denySong(sender, "I couldn't find info about that video on youtube");
            return;
        }

        try {
            String resp = IOUtils.toString(get.getResponseBodyAsStream(), "utf-8");
            if(resp == null) {
                logger.info("Couldn't get detail at " + infoUrl);
                denySong(sender, "I couldn't find info about that video on youtube");

                return;
            }
            JSONObject obj = new JSONObject(resp);
            JSONObject data = obj.getJSONObject("data");
            String title = data.getString("title");
            int durationSeconds = data.getInt("duration");
            JSONObject accessControl = data.getJSONObject("accessControl");
            String embedAllowed = accessControl.getString("embed");
            JSONObject status = data.optJSONObject("status");
            String restrictionReason = null;
            if(status != null) {
                restrictionReason = status.optString("reason");
            }

            if("requesterRegion".equals(restrictionReason)) {
                denySong(sender, "that video can't be played in the streamer's country");
                return;
            }

            if("private".equals(restrictionReason)) {
                denySong(sender, "that video is private");
                return;
            }

            if(!("allowed".equals(embedAllowed))) {
                denySong(sender, "that video is not allowed to be embedded");
                return;
            }

            if(!sender.equals(streamer) && durationSeconds / 60.0 > songLengthAllowedMinutes()) {
                denySong(sender, "the song is over " + songLengthAllowedMinutes() + " minutes");
                return;
            }

            if(!sender.equals(streamer) && conf.getQueueSize() > 0 && songList.size() >= conf.getQueueSize()) {
                denySong(sender, "the queue is full at " + conf.getQueueSize());
                return;
            }

            if(currentSong != null && youtubeId.equals(currentSong.getVideoId())) {
                denySong(sender, "the song \"" + title + "\" is currently playing");
                return;
            }

            if(idCountInMainList(youtubeId) > 0) {
                denySong(sender, "the song \"" + title + "\" is already in the queue");
                return;
            }

            if(!sender.equals(streamer) && idInRecentHistory(youtubeId)) {
                denySong(sender, "the song \"" + title + "\" has been played in the last " + conf.getRecencyDays() + " days");
                return;
            }

            if(!sender.equals(streamer) && conf.getMaxSongsPerUser() > 0 && senderCount(sender) >= conf.getMaxSongsPerUser()) {
                denySong(sender, "you have " + conf.getMaxSongsPerUser() + " songs in the queue already");
                return;
            }

            if(!sender.equals(streamer) && moveToPrimaryIfSongInSecondary(youtubeId)) {
                irc.message(sender + ": bumping \"" + title + "\" to main queue" );
                return;
            }

            if(! (currentSong == null && conf.isShowUpNextMessages())) {
                //don't show this message when there's no song playing, because we're immediately going to show an "up next" message and it makes this one redundant
                irc.message(sender + ": added \"" + title + "\" to queue. id: " + nextRequestId);
            }


            SongEntry newSong = new SongEntry(title, youtubeId, nextRequestId, sender, new Date().getTime(), durationSeconds, false, startSeconds);
            addSongToQueue(newSong);

        } catch (IOException e) {
            logger.error("Problem with youtube request \"" + youtubeId + "\"", e);
            return;
        }
    }

    private void addSongToQueue(SongEntry newSong) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ++nextRequestId;
        if(newSong.isBackup()) {
            secondarySongList.add(newSong);
        } else {
            songList.add(newSong);
        }


        String songJson = mapper.writeValueAsString(newSong);
        FileUtils.writeStringToFile(new File(this.queueHistoryFilePath), songJson + "\n", "utf-8", true);

        updatePlayedSongsFile();
    }

    private void doYoutubeListRequest(String sender, String listPathId) {
        if(!sender.equals(streamer)) {
            denySong(sender, "youtube playlists are only for the streamer to request");
            return;
        }

        String infoUrl = "http://gdata.youtube.com/feeds/api/playlists/" + listPathId + "?v=2&alt=jsonc";
        GetMethod get = new GetMethod(infoUrl);
        HttpClient client = new HttpClient();
        try {
            int errcode = client.executeMethod(get);
            if(errcode != 200) {
                logger.info("Song request error: got code " + errcode + " from " + infoUrl);
                denySong(sender, "I couldn't find info about that playlist on youtube");

                return;
            }
        } catch (IOException e) {
            logger.warn("Couldn't get info from youtube api", e);
            denySong(sender, "I couldn't find info about that playlist on youtube");
            return;
        }

        try {
            String resp = IOUtils.toString(get.getResponseBodyAsStream(), "utf-8");
            if(resp == null) {
                logger.info("Couldn't get detail at " + infoUrl);
                denySong(sender, "I couldn't find info about that playlist on youtube");

                return;
            }
            JSONObject obj = new JSONObject(resp);
            JSONObject data = obj.getJSONObject("data");
            JSONArray items = data.getJSONArray("items");
            int songsAdded = 0;
            irc.message("Adding playlist...");
            for(int i = 0; i < items.length(); ++i) {
                JSONObject item = items.getJSONObject(i);
                JSONObject video = item.getJSONObject("video");
                String videoId = video.getString("id");
                songsAdded += addSonglistSong(sender, videoId);
            }
            irc.message("Added " + songsAdded + " songs to secondary queue");

        } catch (IOException e) {
            logger.error("Problem with youtube playlist request \"" + listPathId + "\"", e);
            return;
        }

    }

    //returns the number of songs added: 0 or 1
    //TODO: refactor this so there isn't so much duplication with doYoutubeRequest
    private int addSonglistSong(String sender, String videoId) {
        String infoUrl = "http://gdata.youtube.com/feeds/api/videos/" + videoId + "?v=2&alt=jsonc&restriction=" + conf.getUserCountryCode();
        GetMethod get = new GetMethod(infoUrl);
        HttpClient client = new HttpClient();
        try {
            int errcode = client.executeMethod(get);
            if(errcode != 200) {
                logger.info("Song request error: got code " + errcode + " from " + infoUrl);
                return 0;
            }
        } catch (IOException e) {
            logger.warn("Couldn't get info from youtube api", e);
            return 0;
        }

        try {
            String resp = IOUtils.toString(get.getResponseBodyAsStream(), "utf-8");
            if(resp == null) {
                logger.info("Couldn't get detail at " + infoUrl);

                return 0;
            }
            JSONObject obj = new JSONObject(resp);
            JSONObject data = obj.getJSONObject("data");
            String title = data.getString("title");
            int durationSeconds = data.getInt("duration");
            JSONObject accessControl = data.getJSONObject("accessControl");
            String embedAllowed = accessControl.getString("embed");
            JSONObject status = data.optJSONObject("status");
            String restrictionReason = null;
            if(status != null) {
                restrictionReason = status.optString("reason");
            }

            if("requesterRegion".equals(restrictionReason)) {
                logger.info("Video " + videoId + " can't be played in streamer's country");
                return 0;
            }

            if("private".equals(restrictionReason)) {
                logger.info("Video " + videoId + " is private");
                return 0;
            }

            if(!("allowed".equals(embedAllowed))) {
                logger.info("Video " + videoId + " can't be embedded");
                return 0;
            }

            boolean isBackupSong = true;
            SongEntry newSong = new SongEntry(title, videoId, nextRequestId, sender, new Date().getTime(), durationSeconds, isBackupSong, 0);
            addSongToQueue(newSong);
            return 1;

        } catch (IOException e) {
            logger.info("Problem getting info for video " + videoId);
            return 0;
        }
    }

    public void setVolume(String trim) {
        try {
            int vol = Integer.parseInt(trim);
            if(vol >= 0 && vol <= 100) {
                volume = vol;
            }
        } catch (NumberFormatException e) {
            logger.error("Not a number: \""+ trim +"\"");
        }
    }

    private boolean moveToPrimaryIfSongInSecondary(String id) {
        for (SongEntry entry : secondarySongList) {
            if (entry.getVideoId().equals(id)) {
                songList.add(entry);
                secondarySongList.remove(entry);
                return true;
            }
        }
        return false;
    }

    private float songLengthAllowedMinutes() {
        if(songList.size() == 0) {
            return conf.getMaxSongLengthWhenQueueEmpty();
        } else {
            return conf.getMaxSongLength();
        }
    }

    private void denySong(String sender, String reason) {
        irc.message("Sorry " + sender + ", " + reason);
    }

    private int idCountInMainList(String id) {
        int count = 0;
        for(SongEntry entry : songList) {
            if(entry.getVideoId().equals(id)) {
                ++count;
            }
        }
        return count;
    }

    private int senderCount(String sender) {
        int count = 0;
        for(SongEntry entry : songList) {
            if(entry.getUser().equals(sender)) {
                ++count;
            }
        }
        return count;
    }

    private boolean idInRecentHistory(String id) {
        int days = conf.getRecencyDays();
        if(days == 0) {
            //feature turned off
            return false;
        }
        long recencyCutoff = DateTime.now().minusDays(conf.getRecencyDays()).toDate().getTime();

        for(SongEntry entry : songHistory) {
            if(entry.getVideoId().equals(id) && entry.getRequestTime() > recencyCutoff ) {
                return true;
            }
        }
        return false;
    }

    public boolean noMoreSongs() {
        return songList.size() == 0 && secondarySongList.size() == 0;
    }

    public synchronized SongEntry startCurrentSong() {
        if(currentSong != null) {
            logger.info("I was asked for the current song; responding that it is id: " + currentSong.getRequestId() + " (no changes)");
            return currentSong;
        }
        return nextSong();
    }

    @Nullable
    public synchronized SongEntry nextSong() {

        if(currentSong != null) {
            lastPlayedSongs.add(currentSong);
        }

        if(lastPlayedSongs.size() > conf.getSonglistHistoryLength()) {
            lastPlayedSongs.remove(0);
        }

        updateQueuesForLeavers();
        SongEntry song;
        String secondaryReport = "";
        //if the main queue is empty, pull from secondary
        if(songList.size() == 0) {
            if(secondarySongList.size() == 0) {
                currentSong = null;
                return null;
            }
            song = secondarySongList.remove(0);
            secondaryReport = " (from secondary queue)";
        } else {
            song = songList.remove(0);
        }

        if(conf.isShowUpNextMessages()) {
            irc.message("Up next: " + song.getTitle() + ", requested by: " + song.getUser() + ", duration " + song.buildDurationStr() + ", id: " + song.getRequestId() + secondaryReport);
        }

        currentSong = song;

        try {
            updatePlayedSongsFile();
        } catch (IOException e) {
            logger.error("Couldn't update playedSongs file", e);
        }

        return song;
    }

    public SongEntry getCurrentSong() {
        return currentSong;
    }

    public String getStreamer() {
        return streamer;
    }

    public int getVolume() {
        return volume;
    }

    public synchronized DjState getStateRepresentation() {
        DjState state = new DjState(songList, secondarySongList, songHistory, currentSong, volume, nextRequestId, dropboxLink, irc.opUsernames);
        return state;
    }
}
