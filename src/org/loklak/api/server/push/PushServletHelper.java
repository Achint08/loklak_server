package org.loklak.api.server.push;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.loklak.api.server.RemoteAccess;
import org.loklak.data.DAO;
import org.loklak.data.ImportProfileEntry;
import org.loklak.data.MessageEntry;
import org.loklak.data.UserEntry;
import org.loklak.harvester.HarvestingFrequency;
import org.loklak.harvester.SourceType;

import java.io.IOException;
import java.util.*;

public class PushServletHelper {

    public static PushReport saveMessagesAndImportProfile(List<Map<String, Object>> messages, int fileHash, RemoteAccess.Post post, SourceType sourceType) throws IOException {
        PushReport report = new PushReport();
        List<String> importedMsgIds = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            Map<String, Object> user = (Map<String, Object>) message.remove("user");
            MessageEntry messageEntry = new MessageEntry(message);
            UserEntry userEntry = new UserEntry((user != null && user.get("screen_name") != null) ? user : new HashMap<String, Object>());
            boolean successful;
            report.incrementRecordCount();
            try {
                successful = DAO.writeMessage(messageEntry, userEntry, true, false);
            } catch (Exception e) {
                e.printStackTrace();
                report.incrementErrorCount();
                continue;
            }
            if (successful) {
                report.incrementNewCount();
                importedMsgIds.add((String) message.get("id_str"));
            } else {
                report.incrementKnownCount();
            }
        }

        if (report.getNewCount() > 0 ) {
            ImportProfileEntry importProfileEntry = saveImportProfile(fileHash, post, sourceType, importedMsgIds);
            report.setImportProfile(importProfileEntry);
        }

        return report;
    }

    protected static ImportProfileEntry saveImportProfile(int fileHash, RemoteAccess.Post post, SourceType sourceType, List<String> importedMsgIds) throws IOException {
        ImportProfileEntry importProfileEntry ;
        Map<String, Object> profile = new HashMap<>();
        profile.put("client_host", post.getClientHost());
        profile.put("imported", importedMsgIds);

        String screen_name = post.get("screen_name", "");
        if (!"".equals(screen_name)) {
            profile.put("screen_name", screen_name);
        }
        String harvesting_freq = post.get("harvesting_freq", "");
        if (!"".equals(harvesting_freq)) {
            try {
                profile.put("harvesting_freq", HarvestingFrequency.valueOf(harvesting_freq).getFrequency());
            } catch (IllegalArgumentException e) {
                throw new IOException("Unsupported 'harvesting_freq' parameter value : " + harvesting_freq);
            }
        } else {
            profile.put("harvesting_freq", HarvestingFrequency.NEVER.getFrequency());
        }
        String lifetime_str = post.get("lifetime", "");
        if (!"".equals(lifetime_str)) {
            int lifetime;
            try {
                lifetime = Integer.parseInt(lifetime_str);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid lifetime parameter (must be an integer) : " + lifetime_str);
            }
            profile.put("lifetime", lifetime);
        } else {
            profile.put("lifetime", Integer.MAX_VALUE);
        }
        profile.put("source_url", post.get("url", ""));
        profile.put("source_type", sourceType.name());
        profile.put("source_hash", fileHash);
        profile.put("id_str", computeImportProfileId(profile, fileHash));
        Date currentDate = new Date();
        profile.put("created_at" , currentDate);
        profile.put("last_modified", currentDate);
        profile.put("last_harvested", currentDate);
        try {
            importProfileEntry = new ImportProfileEntry(profile);
        } catch (Exception e) {
            throw new IOException("Unable to create import profile : " + e.getMessage());
        }
        boolean success = DAO.writeImportProfile(importProfileEntry, true);
        if (!success) {
            DAO.log("Error saving import profile from " + post.getClientHost());
            throw new IOException("Unable to save import profile : " + importProfileEntry);
        }
        return importProfileEntry;
    }

    public static String buildJSONResponse(String callback, PushReport pushReport) throws IOException {

        // generate json
        XContentBuilder json = XContentFactory.jsonBuilder().prettyPrint().lfAtEnd();
        json.startObject();
        json.field("status", "ok");
        json.field("records", pushReport.getRecordCount());
        json.field("new", pushReport.getNewCount());
        json.field("known", pushReport.getKnownCount());
        json.field("error", pushReport.getErrorCount());
        ImportProfileEntry importProfile = pushReport.getImportProfile();
        if (importProfile != null)
            json.field("importProfile", importProfile.toMap());
        json.field("message", "pushed");
        json.endObject();


        // build result
        String result = "";
        boolean jsonp = callback != null && callback.length() > 0;
        if (jsonp) result += callback + "(";
        result += json.string();
        if (jsonp) result += ");";

        return result;
    }

    private static String computeImportProfileId(Map<String, Object> importProfile, int fileHash) {
        String screen_name = (String) importProfile.get("screen_name");
        String source_url = (String) importProfile.get("source_url");
        if (screen_name != null && !"".equals(screen_name)) {
            return source_url + "_" + screen_name + "_" + fileHash;
        }
        String client_host = (String) importProfile.get("client_host");
        return source_url + "_" + client_host + "_" + fileHash;
    }

    public static String computeMessageId(Map<String, Object> message, Object initialId, SourceType sourceType) throws Exception {
        List<Object> location = (List<Object>) message.get("location_point");
        if (location == null) {
            throw new Exception("location_point not found");
        }

        String longitude, latitude;
        try {
            Object rawLon = location.get(1);
            longitude = rawLon instanceof Integer ? Integer.toString((Integer) rawLon)
                    : (rawLon instanceof Double ? Double.toString((Double) rawLon) : (String) rawLon);
            Object rawLat = location.get(0);
            latitude = rawLat instanceof Integer ? Integer.toString((Integer) rawLat)
                    : (rawLat instanceof Double ? Double.toString((Double) rawLat) : (String) rawLat);
        } catch (ClassCastException e) {
            throw new ClassCastException("Unable to extract lat, lon from location_point " + e.getMessage());
        }
        // Modification time = 'mtime' value. If not found, take current time
        Object mtime = message.get("mtime");
        if (mtime == null) {
            mtime = Long.toString(System.currentTimeMillis());
            message.put("mtime", mtime);
        }

        // If initialId found, append it in the id. The new id has this format
        // <source_type>_<id>_<lat>_<lon>_<mtime>
        // otherwise, the new id is <source_type>_<lat>_<lon>_<mtime>
        boolean hasInitialId = initialId != null && !"".equals(initialId.toString());
        if (hasInitialId) {
            return sourceType.name() + "_" + initialId + "_" + latitude + "_" + longitude + "_" + mtime;
        } else {
            return sourceType.name() + "_" + latitude + "_" + longitude + "_" + mtime;
        }
    }

}